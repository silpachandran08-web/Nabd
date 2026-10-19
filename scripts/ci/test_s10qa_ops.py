#!/usr/bin/env python3
"""Independent QA (S10) for OPS-032 / OPS-034 / OPS-025 / OPS-053 static + gate-script checks.
Run from the repo root: python3 -W ignore scripts/ci/test_s10qa_ops.py
Tests whose name starts with test_known_defect_ assert the target behaviour of an acceptance criterion that the merged tree does not meet."""
import json, os, re, shutil, subprocess, sys, tempfile, unittest
import yaml

ROOT = os.getcwd()
Q = os.path.join(ROOT, "scripts/ci/quality.py")
CFG0 = json.load(open("ci/quality-gates.json"))
yml = lambda p: yaml.safe_load(open(p))


def counter(t, cov, miss):
    return f'<counter type="{t}" missed="{miss}" covered="{cov}"/>'


class Sandbox:
    """A throwaway repo root holding quality.py's config, so the real ci/quality-gates.json is never touched."""
    def __init__(self, **cov):
        self.d = tempfile.mkdtemp()
        os.makedirs(f"{self.d}/ci")
        self.cfg = json.loads(json.dumps(CFG0))
        self.cfg["coverage"].update(cov)
        json.dump(self.cfg, open(f"{self.d}/ci/quality-gates.json", "w"))

    def run(self, *args, env=None):
        return subprocess.run(["python3", Q, *args], cwd=self.d, capture_output=True, text=True, env={**os.environ, **(env or {})})

    def file(self, name, text):
        p = f"{self.d}/{name}"
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w").write(text)
        return p

    def jacoco(self, classes, overall=(80, 20), sources=""):
        body = "".join(f'<class name="{n}">{counter("LINE", *l)}{counter("BRANCH", *b)}</class>' for n, l, b in classes)
        return self.file("j.xml", f'<report name="t"><package name="com/nabd/hms/x">{body}{sources}</package>{counter("LINE", *overall)}{counter("BRANCH", 50, 50)}</report>')

    def mutations(self, spec):
        ms = ""
        for cls, (killed, survived, other) in spec.items():
            ms += f'<mutation detected="true" status="KILLED"><mutatedClass>{cls}</mutatedClass></mutation>' * killed
            ms += f'<mutation detected="false" status="SURVIVED"><mutatedClass>{cls}</mutatedClass></mutation>' * survived
            ms += f'<mutation detected="false" status="NON_VIABLE"><mutatedClass>{cls}</mutatedClass></mutation>' * other
        return self.file("m.xml", f"<mutations>{ms}</mutations>")


ON = "2020-01-01"
RX, AL, CK = "com/nabd/hms/clinical/RxSafetyChecks", "com/nabd/hms/clinical/AllergyDrugClasses", "com/nabd/hms/billing/CheckoutService"
PS = "com/nabd/hms/clinical/PrescriptionService"
GOOD = [(RX, (95, 5), (90, 10)), (AL, (95, 5), (90, 10)), (CK, (95, 5), (90, 10)), (PS, (95, 5), (90, 10)), ("com/nabd/hms/auth/AuthService", (95, 5), (90, 10))]


class Ops032Coverage(unittest.TestCase):
    def test_targets_and_thresholds_are_the_story_numbers(self):
        c, p = CFG0["coverage"], CFG0["pit"]
        self.assertEqual((c["overall_min_line"], c["new_code_min"], c["per_class_min_line"], c["per_class_min_branch"]), (75, 80, 90, 85))
        self.assertEqual((p["min_score"], p["max_drop_points"], p["class_min_score"]["com.nabd.hms.clinical.AllergyDrugClasses"]), (85, 2, 90))
        for n in ("RxSafetyChecks", "AllergyDrugClasses", "PrescriptionService"):
            self.assertTrue(os.path.exists(f"backend/src/main/java/com/nabd/hms/clinical/{n}.java"), n)
        self.assertTrue(os.path.exists("backend/src/main/java/com/nabd/hms/billing/CheckoutService.java"))
        for cls in p["target_classes"]:
            self.assertTrue(os.path.exists("backend/src/main/java/" + cls.split("$")[0].replace(".", "/") + ".java"), cls)

    def test_all_gates_report_only_until_a_date_is_set(self):
        s = Sandbox()
        x = s.jacoco([(RX, (1, 99), (1, 99))], overall=(1, 99))
        self.assertEqual(s.run("gate", x, env={"GATE_TODAY": "2999-01-01"}).returncode, 0)

    def test_per_class_gate_boundaries_90_line_85_branch(self):
        s = Sandbox(per_class_enabled_from=ON)
        ok = s.jacoco([(n, (90, 10), (85, 15)) for n in (RX, AL, CK, PS, "com/nabd/hms/auth/AuthService")])
        self.assertEqual(s.run("gate", ok).returncode, 0, s.run("gate", ok).stdout)
        low_line = s.jacoco([(RX, (899, 101), (90, 10))] + GOOD[1:])
        r = s.run("gate", low_line); self.assertEqual(r.returncode, 1); self.assertIn("RxSafetyChecks", r.stdout)
        low_branch = s.jacoco([(RX, (95, 5), (849, 151))] + GOOD[1:])
        self.assertEqual(s.run("gate", low_branch).returncode, 1)

    def test_gate_date_is_inclusive_and_a_future_date_is_off(self):
        low = lambda s: s.jacoco([(RX, (10, 90), (10, 90))] + GOOD[1:])
        s = Sandbox(per_class_enabled_from="2026-11-01")
        self.assertEqual(s.run("gate", low(s), env={"GATE_TODAY": "2026-10-31"}).returncode, 0)
        self.assertEqual(s.run("gate", low(s), env={"GATE_TODAY": "2026-11-01"}).returncode, 1)

    def test_renamed_gated_class_is_a_failure_not_a_silent_pass(self):
        s = Sandbox(per_class_enabled_from=ON)
        x = s.jacoco(GOOD[:4])  # no auth/* class at all
        r = s.run("gate", x); self.assertEqual(r.returncode, 1); self.assertIn("no class matched", r.stdout)

    def test_nested_classes_roll_up_into_their_outer_class(self):
        s = Sandbox(per_class_enabled_from=ON)
        x = s.jacoco([(RX, (95, 5), (90, 10)), (RX + "$Inner", (0, 100), (0, 100))] + GOOD[1:])  # inner class drags the outer down
        self.assertEqual(s.run("gate", x).returncode, 1)

    def test_subpackages_of_the_auth_module_are_covered_by_the_pattern(self):
        s = Sandbox(per_class_enabled_from=ON)
        x = s.jacoco(GOOD[:4] + [("com/nabd/hms/auth/AuthService", (95, 5), (90, 10)), ("com/nabd/hms/auth/dto/Foo", (0, 100), (0, 100))])
        self.assertEqual(s.run("gate", x).returncode, 1)

    def test_overall_gate_75_boundary(self):
        s = Sandbox(overall_enabled_from=ON)
        self.assertEqual(s.run("gate", s.jacoco(GOOD, overall=(75, 25))).returncode, 0)
        self.assertEqual(s.run("gate", s.jacoco(GOOD, overall=(749, 251))).returncode, 1)

    def test_summary_shows_overall_new_code_and_the_class_table_and_never_fails(self):
        s = Sandbox()
        r = s.run("summary", s.jacoco(GOOD, overall=(80, 20)))
        self.assertEqual(r.returncode, 0)
        for needle in ("Overall: line 80.0%", "New code:", "RxSafetyChecks", "AllergyDrugClasses", "CheckoutService", "auth/*"):
            self.assertIn(needle, r.stdout)

    def test_new_code_gate_80_percent_on_the_changed_lines_only(self):
        s = Sandbox(new_code_enabled_from=ON)
        g = lambda *a: subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *a], cwd=s.d, check=True, capture_output=True, text=True)
        g("init", "-q"); src = "backend/src/main/java/com/nabd/hms/x/Foo.java"
        s.file(src, "".join(f"// old {i}\n" for i in range(10))); g("add", "-A"); g("commit", "-qm", "base"); base = g("rev-parse", "HEAD").stdout.strip()
        s.file(src, "".join(f"// old {i}\n" for i in range(10)) + "".join(f"int a{i};\n" for i in range(10))); g("add", "-A"); g("commit", "-qm", "head")

        def xml(covered):
            lines = "".join(f'<line nr="{n}" mi="{0 if n - 10 <= covered else 1}" ci="{1 if n - 10 <= covered else 0}" mb="0" cb="0"/>' for n in range(11, 21))
            return s.jacoco(GOOD, sources=f'<sourcefile name="Foo.java">{lines}</sourcefile>').replace('name="com/nabd/hms/x"', 'name="com/nabd/hms/x"')
        self.assertEqual(s.run("gate", xml(8), base).returncode, 0, s.run("gate", xml(8), base).stdout)   # 80% exactly
        r = s.run("gate", xml(7), base); self.assertEqual(r.returncode, 1); self.assertIn("new code 70.0%", r.stdout)
        # nothing executable changed -> not applicable, not a failure
        self.assertEqual(s.run("gate", s.jacoco(GOOD), g("rev-parse", "HEAD").stdout.strip()).returncode, 0)

    def test_baseline_records_date_and_numbers(self):
        s = Sandbox()
        out = f"{s.d}/b.json"
        self.assertEqual(s.run("baseline", s.jacoco(GOOD), out, env={"GATE_TODAY": "2026-10-19"}).returncode, 0)
        b = json.load(open(out)); self.assertEqual(b["recorded"], "2026-10-19"); self.assertIn("overall_line", b)

    def test_known_defect_OPS032_pit_profile_survived_the_integration_merge(self):
        pom = open("backend/pom.xml").read()
        self.assertTrue("pitest-maven" in pom, "the -Ppit profile that pit-nightly.yml runs is missing from backend/pom.xml")
        self.assertRegex(pom, r"<id>pit</id>")
        for cls in CFG0["pit"]["target_classes"]:
            self.assertTrue(cls.split("$")[0] in pom, f"PIT target {cls} not configured in the pom")


class Ops032Pit(unittest.TestCase):
    AL = "com.nabd.hms.clinical.AllergyDrugClasses"
    RX = "com.nabd.hms.clinical.RxSafetyChecks"

    def test_overall_85_boundary_and_allergy_class_90(self):
        s = Sandbox()
        ok = s.mutations({self.AL: (90, 10, 3), self.RX: (80, 20, 0)})             # total 85.0, Allergy 90.0 (non-viable ignored)
        r = s.run("pit", ok); self.assertEqual(r.returncode, 0, r.stdout); self.assertEqual(json.loads(r.stdout)["score"], 85.0)
        r = s.run("pit", s.mutations({self.AL: (100, 0, 0), self.RX: (69, 31, 0)})); self.assertEqual(r.returncode, 1)  # 84.5
        r = s.run("pit", s.mutations({self.AL: (89, 11, 0), self.RX: (100, 0, 0)})); self.assertEqual(r.returncode, 1)  # Allergy 89 < 90
        self.assertIn("AllergyDrugClasses", r.stdout)

    def test_missing_mutants_for_the_90_percent_class_fails(self):
        r = Sandbox().run("pit", Sandbox().mutations({self.RX: (100, 0, 0)})); self.assertEqual(r.returncode, 1)

    def test_drop_gate_more_than_two_points_blocks(self):
        s = Sandbox()
        hist = s.file("h.json", json.dumps({"last_green": {"score": 90.0}}))
        cur = lambda v: s.file("c.json", json.dumps({"score": v}))
        self.assertEqual(s.run("pit-drop", cur(88.0), hist).returncode, 0)   # exactly 2.0: allowed
        self.assertEqual(s.run("pit-drop", cur(87.9), hist).returncode, 1)   # 2.1: blocked
        self.assertEqual(s.run("pit-drop", cur(95.0), hist).returncode, 0)   # improvement
        self.assertEqual(s.run("pit-drop", cur(10.0), f"{s.d}/none.json").returncode, 0)  # first ever run

    def test_nightly_workflow_shape(self):
        w = yml(".github/workflows/pit-nightly.yml"); on = w.get("on") or w.get(True)
        self.assertIn("schedule", on)
        self.assertLessEqual(w["jobs"]["pit"]["timeout-minutes"], 30)
        text = open(".github/workflows/pit-nightly.yml").read()
        self.assertIn("pit-history", text); self.assertIn("PIT_BLOCKED", text); self.assertIn("pit-drop", text)
        ci = open(".github/workflows/ci.yml").read()
        for path in CFG0["pit"]["guarded_paths"]:
            mod = path.rstrip("/").split("/")[-1]
            self.assertIn(mod, re.search(r"grep -qE '([^']+)'", ci).group(1), f"{path} not guarded by the PR block")


class CiWiring(unittest.TestCase):
    def test_every_workflow_parses(self):
        for f in os.listdir(".github/workflows"):
            self.assertIsInstance(yml(f".github/workflows/{f}"), dict, f)

    def test_known_defect_OPS032_OPS034_two_jobs_overwrite_each_others_single_pr_comment(self):
        ci = open(".github/workflows/ci.yml").read()
        n = len(re.findall(r"gh pr comment[^\n]*--edit-last", ci))
        # `gh pr comment --edit-last` edits the last comment by the same author: the coverage summary (stage 3) and the API contract report (stage 5) would replace one another
        self.assertLessEqual(n, 1, f"{n} jobs post with --edit-last; give each report its own marker-based comment")


class Ops034Contract(unittest.TestCase):
    def test_core_yaml_is_a_valid_openapi_document(self):
        r = subprocess.run(["python3", "-m", "openapi_spec_validator", "api/openapi/core.yaml"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_stage5_is_report_only_comment_job_within_two_minutes(self):
        j = yml(".github/workflows/ci.yml")["jobs"]["api-contract-report"]
        self.assertTrue(j["continue-on-error"]); self.assertLessEqual(j["timeout-minutes"], 2)
        self.assertIn("pull_request", j["if"]); self.assertEqual(j["permissions"]["pull-requests"], "write")
        self.assertEqual(j["steps"][0]["with"]["fetch-depth"], 0)
        text = json.dumps(j)
        for needle in ("oasdiff changelog", "oasdiff breaking", "gh pr comment", "api/openapi/core.yaml"):
            self.assertIn(needle, text)
        required = json.load(open(".github/branch-protection.json"))["required_status_checks"]["contexts"]
        self.assertFalse([c for c in required if "contract" in c or "stage5" in c], "stage 5 must not be a required check")

    def test_generated_vs_committed_diff_runs_in_a_required_stage(self):
        self.assertIn("OpenApiSpecApiTest", open("backend/src/test/java/com/nabd/hms/config/OpenApiSpecApiTest.java").read())
        self.assertIn("*ApiTest", open(".github/workflows/ci.yml").read())  # stage 3 selects *ApiTest, which includes OpenApiSpecApiTest


class Ops025Image(unittest.TestCase):
    D = open("Dockerfile").read()

    def test_runtime_is_distroless_nonroot_uid10001_with_ram_percentage_and_no_shell_setup(self):
        self.assertIn("distroless", self.D); self.assertIn("nonroot", self.D)
        self.assertRegex(self.D, r"(?m)^USER 10001:10001")
        self.assertGreaterEqual(self.D.count("-XX:MaxRAMPercentage=75"), 2)
        runs = [l for l in self.D.splitlines() if l.startswith("RUN ")]
        stage = None; bad = []
        for l in self.D.splitlines():
            if l.startswith("FROM "): stage = l
            if l.startswith("RUN ") and "AS base" not in (stage or "") and "layers" not in (stage or ""): bad.append(l)
        self.assertEqual(bad, [], "no RUN (needs a shell) in the runtime stages")
        self.assertIsNotNone(runs)

    def test_default_target_is_the_api_image_and_migrate_stage_runs_migratemain(self):
        froms = re.findall(r"(?m)^FROM .* AS (\w+)", self.D)
        self.assertEqual(froms[-1], "app")
        self.assertRegex(self.D, r"(?s)AS migrate.*MigrateMain")
        self.assertNotRegex(self.D, r"EXPOSE[^\n]*8081")
        self.assertNotIn("skipTests", self.D)

    def test_management_port_exposure_and_flyway_in_application_yml(self):
        y = yml("backend/src/main/resources/application.yml")
        self.assertEqual(y["management"]["endpoints"]["web"]["exposure"]["include"], "health,prometheus")
        self.assertIn("8081", str(y["management"]["server"]["port"]))
        self.assertIs(y["spring"]["flyway"]["enabled"], False)
        self.assertEqual(y["server"]["shutdown"], "graceful")
        self.assertIn("db", y["management"]["endpoint"]["health"]["group"]["readiness"]["include"])
        self.assertIn("outbox", y["management"]["endpoint"]["health"]["group"]["readiness"]["include"])

    def test_prod_config_job_migrates_as_migrator_then_boots_app_flyway_off(self):
        t = open(".github/workflows/prod-config.yml").read()
        self.assertLess(t.index("MIGRATE_DB_USER=nabd_migrator"), t.index("DB_USER=nabd_app"))
        self.assertIn("8081/actuator/health/readiness", t)

    def test_known_defect_OPS025_checker_script_agrees_with_application_yml(self):
        r = subprocess.run(["python3", "infra/ci/check-ops025.py"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_sigterm_budget_is_at_least_the_20s_the_story_names(self):
        y = yml("backend/src/main/resources/application.yml")
        self.assertGreaterEqual(int(y["spring"]["lifecycle"]["timeout-per-shutdown-phase"].rstrip("s")), 20)

    def test_known_defect_OPS025_sse_clients_get_a_reconnect_hint_on_shutdown(self):
        src = "".join(open(os.path.join(d, f)).read() for d, _, fs in os.walk("backend/src/main/java") for f in fs if f.endswith(".java"))
        self.assertTrue(re.search(r"SseEmitter|text/event-stream", src), "no SSE endpoint exists in Core at all")
        self.assertTrue(re.search(r"retry:|ContextClosedEvent|SmartLifecycle", src), "nothing sends a reconnect hint on SIGTERM")


class Ops053Staging(unittest.TestCase):
    M = list(yaml.safe_load_all(open("infra/staging-in/core.yaml")))
    DEP = next(d for d in M if d["kind"] == "Deployment")
    C = DEP["spec"]["template"]["spec"]["containers"][0]

    def test_two_replicas_surge_only_rollout_pdb_no_affinity_digest_pinned_probes(self):
        s = self.DEP["spec"]
        self.assertEqual(s["replicas"], 2); self.assertEqual(s["strategy"]["rollingUpdate"]["maxUnavailable"], 0)
        self.assertIn("@sha256:", self.C["image"])
        svc = next(d for d in self.M if d["kind"] == "Service")
        self.assertNotIn("sessionAffinity", svc["spec"]); self.assertTrue(any(d["kind"] == "PodDisruptionBudget" for d in self.M))
        self.assertTrue(all(k in self.C for k in ("readinessProbe", "livenessProbe", "startupProbe")))

    def test_grace_period_exceeds_prestop_plus_shutdown_phase(self):
        y = yml("backend/src/main/resources/application.yml")
        phase = int(y["spring"]["lifecycle"]["timeout-per-shutdown-phase"].rstrip("s"))
        prestop = int(self.C["lifecycle"]["preStop"]["exec"]["command"][1])
        self.assertGreater(self.DEP["spec"]["template"]["spec"]["terminationGracePeriodSeconds"], prestop + phase)

    def test_known_defect_OPS053_probes_hit_the_port_the_actuator_actually_listens_on(self):
        y = yml("backend/src/main/resources/application.yml")
        mgmt = int(re.search(r"(\d+)\}?$", str(y["management"]["server"]["port"])).group(1))
        ports = {p["name"]: p["containerPort"] for p in self.C["ports"]}
        for probe in ("readinessProbe", "livenessProbe", "startupProbe"):
            tgt = self.C[probe]["httpGet"]["port"]
            self.assertEqual(ports.get(tgt, tgt), mgmt, f"{probe} -> {tgt} but /actuator/* is served on {mgmt} only (API port answers 404)")

    def test_known_defect_OPS053_every_env_the_app_needs_to_boot_is_supplied(self):
        names = {e["name"] for e in self.C["env"]}
        y = open("backend/src/main/resources/application.yml").read()
        required = set(re.findall(r"\$\{([A-Z_]+)\}", y))  # placeholders without a default: boot fails fast when unset
        self.assertEqual(required - names, set(), "no default in application.yml and not in the staging-in Deployment")

    def test_known_defect_OPS053_k6_probe_url_is_a_route_the_api_serves(self):
        k6 = open("infra/staging-in/rolling-deploy.k6.js").read()
        url = re.search(r"\$\{__ENV.BASE\}(/[^`'\"]+)", k6).group(1)
        jwks = open("backend/src/main/java/com/nabd/hms/config/JwksController.java").read()
        self.assertTrue(f'"{url}"' in jwks, f"k6 requests {url}; JwksController serves a different path, every request would be a 404 (http_req_failed 100%)")

    def test_rolling_restart_script_uses_only_real_endpoints(self):
        sh = open("infra/staging-in/rolling-restart-test.sh").read()
        auth = open("backend/src/main/java/com/nabd/hms/auth/AuthController.java").read()
        for ep in ("/auth/login", "/auth/refresh", "/auth/sessions"):
            self.assertIn(f'"{ep.replace("/auth", "")}"', auth)
            self.assertIn(ep, sh)

    def test_manifest_checker_rejects_each_regression(self):
        good = open("infra/staging-in/core.yaml").read()
        muts = {"one replica": good.replace("replicas: 2", "replicas: 1"), "unavailable": good.replace("maxUnavailable: 0", "maxUnavailable: 1"),
                "no readiness": good.replace("readinessProbe:", "xProbe:"), "sticky": good.replace("type: LoadBalancer", "type: LoadBalancer\n  sessionAffinity: ClientIP"),
                "no jwt keys": good.replace("JWT_KEYS_FILE", "X_FILE"), "tag not digest": good.replace("@sha256:REPLACE_DIGEST", ":latest"),
                "grace 35": good.replace("terminationGracePeriodSeconds: 45", "terminationGracePeriodSeconds: 35")}
        for name, bad in muts.items():
            f = tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False); f.write(bad); f.close()
            r = subprocess.run(["python3", "infra/staging-in/check-staging-in.py", f.name], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1, f"mutation accepted: {name}")
            os.unlink(f.name)
        self.assertEqual(subprocess.run(["python3", "infra/staging-in/check-staging-in.py"], capture_output=True).returncode, 0)

    def test_known_defect_OPS053_each_scheduled_job_runs_once_across_replicas_shedlock(self):
        pom = open("backend/pom.xml").read()
        src = "".join(open(os.path.join(d, f)).read() for d, _, fs in os.walk("backend/src/main/java") for f in fs if f.endswith(".java"))
        scheduled = len(re.findall(r"@Scheduled", src))
        self.assertGreater(scheduled, 0)
        self.assertTrue("shedlock" in pom.lower(), f"{scheduled} @Scheduled jobs and no ShedLock: both replicas run every job (story waits on OPS-013)")

    def test_workflow_staging_in_is_manual_only(self):
        w = yml(".github/workflows/staging-in.yml"); on = w.get("on") or w.get(True)
        self.assertEqual(set(on), {"workflow_dispatch"})


if __name__ == "__main__":
    unittest.main()
