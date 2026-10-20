#!/usr/bin/env python3
"""S12 QA-3 static/CI checks (run from the repo root: python3 -m unittest scripts/ci/test_s12qa3.py).
OPS-013 AC5: the 3-nights purge alarm evaluated against simulated nights (CloudWatch semantics of its own parameters).
OPS-015 AC2/AC3: the events gate on the repo, size boundary 2047/2048 bytes, denylist case/substring, CI wiring.
PLT-019 AC5: the tenantId-parameter metric, its --zero gate on a fixture, and that CI actually reports it."""
import glob, importlib.util, json, os, re, shutil, subprocess, sys, tempfile, unittest

TF = "infra/terraform/modules/cell/aws/monitoring.tf"
CI = ".github/workflows/ci.yml"


def alarm_block():
    s = open(TF).read()
    m = re.search(r'resource "aws_cloudwatch_metric_alarm" "purge_job_failing" \{(.*?)\n\}', s, re.S)
    assert m, "purge_job_failing alarm missing"
    b = m.group(1)
    num = lambda k: float(re.search(rf"\b{k}\s*=\s*([\d.]+)", b).group(1))
    jobs = re.findall(r'"([\w-]+-purge)"', re.search(r"purge_jobs\s*=\s*\[([^\]]*)\]", s).group(1))
    return {"period": num("period"), "evaluation_periods": int(num("evaluation_periods")), "datapoints_to_alarm": int(num("datapoints_to_alarm")),
            "threshold": num("threshold"), "op": re.search(r'comparison_operator\s*=\s*"(\w+)"', b).group(1),
            "missing": re.search(r'treat_missing_data\s*=\s*"(\w+)"', b).group(1), "stat": re.search(r'statistic\s*=\s*"(\w+)"', b).group(1),
            "metric": re.search(r'metric_name\s*=\s*"(\w+)"', b).group(1), "actions": re.search(r"alarm_actions\s*=\s*\[([^\]]*)\]", b).group(1),
            "jobs": jobs}


def alarms(a, nights):
    """nights: failures per day (None = no datapoint). CloudWatch M-of-N on the last N periods."""
    window = nights[-a["evaluation_periods"]:]
    breaching = 0
    for v in window:
        if v is None:
            breaching += 1 if a["missing"] == "breaching" else 0
        elif a["op"] == "GreaterThanThreshold" and v > a["threshold"]:
            breaching += 1
        elif a["op"] == "GreaterThanOrEqualToThreshold" and v >= a["threshold"]:
            breaching += 1
    return len(window) >= a["evaluation_periods"] and breaching >= a["datapoints_to_alarm"]


class Ops013Alarm(unittest.TestCase):
    def setUp(self):
        self.a = alarm_block()

    def test_shape(self):
        self.assertEqual(self.a["metric"], "job_failures_total")
        self.assertEqual(self.a["stat"], "Sum")
        self.assertEqual(self.a["period"], 86400)
        self.assertIn("aws_sns_topic.alerts.arn", self.a["actions"])

    def test_three_failed_nights_page(self):
        self.assertTrue(alarms(self.a, [0, 1, 1, 1]))

    def test_two_failed_nights_do_not_page(self):
        self.assertFalse(alarms(self.a, [1, 1, 0]))
        self.assertFalse(alarms(self.a, [0, 1, 1]))

    def test_a_good_night_in_between_resets(self):
        self.assertFalse(alarms(self.a, [1, 0, 1, 1]))

    def test_no_data_is_not_a_failure(self):
        self.assertFalse(alarms(self.a, [None, None, None]))
        self.assertFalse(alarms(self.a, [1, None, 1]))

    def test_every_purge_lock_name_in_java_is_alarmed(self):
        java = " ".join(open(f).read() for f in glob.glob("backend/src/main/java/**/*Purge*.java", recursive=True))
        purges = set(re.findall(r'@SchedulerLock\(name = "([\w-]+-purge)"', java))
        purges.discard("idempotency-purge")  # not a growth-table purge of this story
        purges.discard("otp-counter-purge")
        self.assertTrue({"sessions-purge", "login-attempts-purge", "messages-purge", "outbox-purge"} <= purges, purges)
        self.assertEqual(set(self.a["jobs"]), {"sessions-purge", "login-attempts-purge", "messages-purge", "outbox-purge"})


def events_module():
    spec = importlib.util.spec_from_file_location("events_gate", "scripts/ci/events.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class Ops015Gate(unittest.TestCase):
    T = "visit.queue_entry.created.v1"

    def setUp(self):
        self.ev = events_module()
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir)

    def env(self, **extra):
        ex = json.load(open(f"api/events/{self.T}.schema.json"))["examples"][0]
        return self.ev.sample_envelope(self.T, {**ex, **extra})

    def problems(self, *envs):
        for i, e in enumerate(envs):
            json.dump(e, open(f"{self.dir}/s{i}.json", "w"), separators=(",", ":"))
        return self.ev.check(".", self.dir)[1]

    def test_repo_catalogue_has_19_types_and_no_problem(self):
        cat, probs = self.ev.check(".", None)
        self.assertEqual(len(cat), 19)
        self.assertEqual(probs, [])

    def test_documented_example_envelope_passes(self):
        self.assertEqual(self.problems(self.env()), [])

    def test_size_boundary_2047_ok_2048_refused(self):
        base = self.ev.size(self.env(pad=""))
        e47, e48 = self.env(pad="x" * (2047 - base)), self.env(pad="x" * (2048 - base))
        self.assertEqual(self.ev.size(e47), 2047)
        self.assertEqual(self.ev.size(e48), 2048)
        size_msgs = lambda ps: [p for p in ps if "bytes (limit" in p]
        self.assertEqual(size_msgs(self.problems(e47)), [])
        self.assertEqual(len(size_msgs(self.problems(e48))), 1)

    def test_denylist_is_case_insensitive_and_substring(self):
        ps = self.problems(self.env(patientName="a", PhoneHash="b", guardianMobile="c", DOB="d"))
        flagged = {re.search(r"field '(\w+)'", p).group(1) for p in ps if "PHI denylist" in p}
        self.assertEqual(flagged, {"patientName", "PhoneHash", "guardianMobile", "DOB"})

    def test_extra_or_nested_fields_are_refused_by_the_schema(self):
        ps = self.problems(self.env(meta={"ref": "x"}))
        self.assertTrue(any("Additional properties" in p for p in ps), ps)

    def test_ci_runs_the_gate_in_stage1_and_the_samples_in_stage3(self):
        ci = open(CI).read()
        self.assertIn("python3 scripts/ci/events.py check && python3 scripts/ci/test_events.py", ci)
        self.assertIn("events.py check --samples backend/target/event-samples", ci)

    def test_qa_samples_from_the_backend_run_validate(self):
        d = "backend/target/s12qa3-event-samples"
        if not os.path.isdir(d):
            self.skipTest("run S12QaOps015EventsTest first")
        n = len(glob.glob(f"{d}/*.json"))
        self.assertGreater(n, 20)
        self.assertEqual(self.ev.check(".", d)[1], [])


class Plt019Metric(unittest.TestCase):
    def run_metric(self, *args):
        return subprocess.run([sys.executable, "scripts/ci/tenant_params.py", *args], capture_output=True, text=True)

    def test_migrated_module_is_at_zero(self):
        r = self.run_metric("--zero", "messaging")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(json.loads(r.stdout)["by_module"]["messaging"], 0)

    def test_zero_gate_fails_when_a_migrated_module_regains_a_tenant_param(self):
        d = tempfile.mkdtemp()
        try:
            os.makedirs(f"{d}/messaging")
            open(f"{d}/messaging/FooService.java", "w").write("class FooService {\n    public void send(UUID tenantId, String x) {\n    }\n}\n")
            r = self.run_metric(d, "--zero", "messaging")
            self.assertEqual(r.returncode, 1)
            self.assertEqual(json.loads(r.stdout)["by_module"]["messaging"], 1)
        finally:
            shutil.rmtree(d)

    def test_ci_reports_the_metric(self):
        wf = " ".join(open(f).read() for f in glob.glob(".github/workflows/*.yml"))
        self.assertIn("tenant_params.py", wf, "AC5: 'CI metric reported per sprint' - no workflow runs scripts/ci/tenant_params.py")


if __name__ == "__main__":
    unittest.main()
