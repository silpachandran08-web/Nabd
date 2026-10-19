#!/usr/bin/env python3
"""OPS-048 checks: python3 scripts/ci/test_phase0_gate.py (run from repo root)."""
import contextlib, hashlib, io, json, os, pathlib, shutil, sys, tempfile, unittest
sys.path.insert(0, "scripts/ci")
import phase0_gate as pg
import safety_report as sr
import yaml

GOOD_EVIDENCE = {"defects": {"open_s1": 0, "open_security_s2": 0, "link": "https://example.invalid/defects"},
                 "prod_config": {"conclusion": "success", "link": "https://example.invalid/run/1"},
                 "two_replicas": {"conclusion": "success", "link": "https://example.invalid/run/2"},
                 "k6": {"registry_p95_ms": 210, "checkin_p95_ms": 180, "link": "https://example.invalid/k6"}}


def call(*args):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = pg.main([str(a) for a in args])
    return rc, buf.getvalue()


class Repo:
    """Throwaway repo: N controls, the first `tested` mapped to passing tests T<i>, plus hazard log and sign-off."""

    def __init__(self, n=67, tested=67, signed=True, extra_passing=("ClinicalReadScopeTest", "PhiReadAuditTest")):
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.junit = self.root / "surefire"
        (self.root / "backend/src/test/java/fx").mkdir(parents=True)
        self.junit.mkdir()
        ctl = []
        for i in range(1, n + 1):
            c = {"id": f"SC-{i:02d}", "control": f"control {i}", "owner": f"@team{i % 3}", "tests": [], "evidence": []}
            if i <= tested:
                c["tests"] = [f"T{i}"]
            else:
                c["evidence"] = [{"link": f"https://example.invalid/ev/{i}", "accepted_by": None, "accepted_on": None}]
            ctl.append(c)
        self.write_controls(ctl)
        names = [f"T{i}" for i in range(1, tested + 1)] + list(extra_passing)
        for t in names:
            (self.root / f"backend/src/test/java/fx/{t}.java").write_text(f"package fx;\nclass {t} {{}}\n")
        self.result(names)
        (self.root / "docs/clinical-safety/signoff").mkdir(parents=True)
        (self.root / sr.LOG).write_text("id,hazard\nH-01,x\n")
        rec = {"cso_name": "", "approved_on": "", "approval_ref": "", "hazard_log_sha256": ""}
        if signed:
            rec = {"cso_name": "Test CSO", "approved_on": "2026-10-19", "approval_ref": "https://example.invalid/sig",
                   "hazard_log_sha256": hashlib.sha256((self.root / sr.LOG).read_bytes()).hexdigest()}
        (self.root / sr.SIGNOFF).write_text(json.dumps(rec))

    def write_controls(self, ctl):
        (self.root / "docs/security").mkdir(parents=True, exist_ok=True)
        (self.root / pg.CONTROLS).write_text(yaml.safe_dump({"controls": ctl}))

    def controls(self):
        return yaml.safe_load((self.root / pg.CONTROLS).read_text())["controls"]

    def result(self, names, failing=()):
        cases = "".join(f'<testcase classname="fx.{t}" name="m"/>' for t in names)
        cases += "".join(f'<testcase classname="fx.{t}" name="m2"><failure/></testcase>' for t in failing)
        (self.junit / "TEST-all.xml").write_text(f"<testsuite>{cases}</testsuite>")

    def pack(self, evidence=GOOD_EVIDENCE):
        out = self.root / "out"
        args = ["pack", "--root", self.root, "--sha", "abc1234", "--junit", self.junit, "--out", out]
        if evidence is not None:
            (self.root / "ev.json").write_text(json.dumps(evidence))
            args += ["--evidence", self.root / "ev.json"]
        rc, text = call(*args)
        return rc, text, json.loads((out / "phase0-gate-pack.json").read_text()), (out / "phase0-gate-pack.md").read_text()


class Controls(unittest.TestCase):
    def test_ac1_real_controls_file_has_67_rows_owners_and_resolvable_tests(self):
        ctl = yaml.safe_load(open(pg.CONTROLS))["controls"]
        self.assertEqual(len(ctl), 67)
        self.assertEqual([c["id"] for c in ctl], [f"SC-{i:02d}" for i in range(1, 68)])
        self.assertTrue(all(c["owner"] for c in ctl))
        cs = pg.evaluate_controls(pathlib.Path("."), [])
        # the only errors allowed today are unmapped rows (Needs external action), never a dangling test id
        self.assertTrue(all(e.endswith("no test id or evidence link") for e in cs["mapping_errors"]), cs["mapping_errors"])

    def test_ac1_complete_mapping_passes_and_percent_comes_from_results(self):
        r = Repo(tested=50)
        rc, out = call("controls", "--root", r.root, "--junit", r.junit)
        self.assertEqual(rc, 0, out)
        self.assertIn("50/67 = 74.6%", out)  # evidence rows are not accepted yet, so they do not count
        r.result([f"T{i}" for i in range(1, 51)], failing=["T1", "T2"])
        self.assertIn("48/67 = 71.6%", call("controls", "--root", r.root, "--junit", r.junit)[1])

    def test_ac1_completeness_check_fails_on_gaps(self):  # CI: controls mapping completeness check
        r = Repo()
        ctl = r.controls()
        ctl[4]["tests"] = []
        ctl[5]["tests"] = ["NoSuchTest"]
        ctl[6]["owner"] = ""
        r.write_controls(ctl[:66] + [ctl[0]])
        rc, out = call("controls", "--root", r.root)
        self.assertEqual(rc, 1)
        for e in ("SC-05: no test id or evidence link", "SC-06: test NoSuchTest not found", "SC-07: no owner", "duplicate control ids"):
            self.assertIn(e, out)
        r.write_controls(ctl[:66])
        self.assertIn("expected 67 controls, found 66", call("controls", "--root", r.root)[1])

    def test_accepted_evidence_counts_as_verified(self):
        r = Repo(tested=60)
        ctl = r.controls()
        ctl[66]["evidence"][0].update(accepted_by="@team1", accepted_on="2026-10-19")
        r.write_controls(ctl)
        self.assertIn("61/67", call("controls", "--root", r.root, "--junit", r.junit)[1])


class Pack(unittest.TestCase):
    def setUp(self):
        os.environ["SOURCE_DATE_EPOCH"] = "1790000000"

    def test_ac3_all_criteria_pass_with_evidence_links(self):
        rc, out, p, md = Repo().pack()
        self.assertEqual(rc, 0, out)
        self.assertEqual([c["status"] for c in p["criteria"]], ["PASS"] * 8)
        for link in ("https://example.invalid/k6", "https://example.invalid/run/1", "https://example.invalid/defects",
                     sr.SIGNOFF, "ClinicalReadScopeTest", "PhiReadAuditTest"):
            self.assertIn(link, md)
        self.assertEqual(md.count("\n## "), 8)

    def test_ac2_below_60_percent_is_red_with_unverified_list_and_owners(self):
        rc, _, p, md = Repo(tested=40).pack()  # 40/67 = 59.7%
        self.assertEqual(rc, 1)
        c5 = p["criteria"][4]
        self.assertEqual(c5["status"], "FAIL")
        self.assertIn("59.7%", c5["detail"])
        self.assertIn("## 5. Security Controls >= 60% verified: FAIL (red)", md)
        self.assertIn("| SC-41 | @team2 | control 41 |", md)
        self.assertEqual(len(p["security_controls"]["unverified"]), 27)
        self.assertEqual(Repo(tested=41).pack()[2]["criteria"][4]["status"], "PASS")  # 61.2%

    def test_ac3_each_criterion_fails_on_its_own_evidence(self):
        bad = json.loads(json.dumps(GOOD_EVIDENCE))
        bad["defects"]["open_security_s2"] = 1
        bad["prod_config"]["conclusion"] = "failure"
        bad["k6"]["registry_p95_ms"] = 300  # boundary: must be strictly below
        bad["k6"]["checkin_p95_ms"] = 299
        st = [c["status"] for c in Repo().pack(bad)[2]["criteria"]]
        self.assertEqual(st, ["FAIL", "FAIL", "FAIL", "PASS", "PASS", "PASS", "PASS", "PASS"])
        st = [c["status"] for c in Repo(signed=False, extra_passing=()).pack(None)[2]["criteria"]]
        self.assertEqual(st, ["FAIL", "FAIL", "FAIL", "FAIL", "PASS", "FAIL", "FAIL", "FAIL"])

    def test_ac4_failing_pack_says_onboarding_stays_blocked(self):
        _, _, p, md = Repo(signed=False).pack()
        self.assertEqual((p["result"], p["real_data_onboarding"]), ("FAIL", "stays blocked"))
        self.assertIn("Real-data onboarding stays blocked", md)

    def test_nfr_reproducible_from_same_commit_and_evidence(self):
        r = Repo(tested=30)
        a, b = r.pack()[3], r.pack()[3]
        self.assertEqual(a, b)
        self.assertIn("abc1234", a)


class Record(unittest.TestCase):
    def tmp(self, text):
        root = pathlib.Path(tempfile.mkdtemp())
        (root / "docs/gates").mkdir(parents=True)
        (root / pg.RECORD).write_text(text)
        return root

    def test_real_record_is_valid_and_not_yet_held(self):
        self.assertEqual(call("record")[0], 0)
        self.assertEqual(pg.check_record(pathlib.Path("."))[0], "not yet held")

    def test_ac5_passed_decision_needs_date_attendees_and_pack_commit(self):
        base = open(pg.RECORD).read().replace("- Decision: not yet held", "- Decision: passed")
        _, errs = pg.check_record(self.tmp(base))
        self.assertEqual(len(errs), 6, errs)
        full = (base.replace("- Date:", "- Date: 2026-11-02").replace("- Pack commit:", "- Pack commit: abc1234")
                .replace("- Attendee Founder:", "- Attendee Founder: A").replace("- Attendee CTO:", "- Attendee CTO: B")
                .replace("- Attendee CSO:", "- Attendee CSO: C").replace("- Attendee DPO:", "- Attendee DPO: D"))
        self.assertEqual(pg.check_record(self.tmp(full)), ("passed", []))
        self.assertIn("Decision must be one of", pg.check_record(self.tmp(base.replace("passed", "maybe")))[1][0])


class Workflow(unittest.TestCase):
    def test_workflow_shape(self):
        wf = yaml.safe_load(open(".github/workflows/phase0-gate.yml"))
        j = wf["jobs"]
        run = json.dumps(j["controls-mapping"]["steps"])
        for w in ("test_phase0_gate.py", "phase0_gate.py record", "phase0_gate.py controls"):
            self.assertIn(w, run)
        self.assertEqual(j["controls-mapping"]["steps"][1]["shell"], "bash")
        pack = json.dumps(j["gate-pack"]["steps"])
        for w in ("phase0_gate.py pack", "--evidence", "SOURCE_DATE_EPOCH", "surefire-reports"):
            self.assertIn(w, pack)


if __name__ == "__main__":
    unittest.main(verbosity=2)
