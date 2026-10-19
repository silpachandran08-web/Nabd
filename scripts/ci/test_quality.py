#!/usr/bin/env python3
"""OPS-032 gate scripts on fixture reports: python3 scripts/ci/test_quality.py (from repo root)."""
import json, os, subprocess, sys, tempfile, unittest, yaml

Q = ["python3", "scripts/ci/quality.py"]
def run(args, env=None):
    return subprocess.run(Q + args, capture_output=True, text=True, env={**os.environ, **(env or {})})

def jacoco(path, rx=(95, 5, 90, 10), chk=(50, 50, 40, 60), overall=(80, 20)):
    def c(t, v, m): return f'<counter type="{t}" missed="{m}" covered="{v}"/>'
    open(path, "w").write(f'''<report name="t">
<package name="com/nabd/hms/clinical">
 <class name="com/nabd/hms/clinical/RxSafetyChecks">{c("LINE", rx[0], rx[1])}{c("BRANCH", rx[2], rx[3])}</class>
 <class name="com/nabd/hms/clinical/RxSafetyChecks$Inner">{c("LINE", 0, 0)}{c("BRANCH", 0, 0)}</class>
</package>
<package name="com/nabd/hms/billing">
 <class name="com/nabd/hms/billing/CheckoutService">{c("LINE", chk[0], chk[1])}{c("BRANCH", chk[2], chk[3])}</class>
</package>
{c("LINE", overall[0], overall[1])}{c("BRANCH", 60, 40)}</report>''')

def mutations(path, killed, survived):
    ms = "".join(f'<mutation detected="true" status="KILLED"><mutatedClass>com.nabd.hms.clinical.AllergyDrugClasses</mutatedClass></mutation>' for _ in range(killed))
    ms += "".join(f'<mutation detected="false" status="SURVIVED"><mutatedClass>com.nabd.hms.clinical.AllergyDrugClasses</mutatedClass></mutation>' for _ in range(survived))
    ms += '<mutation detected="false" status="NON_VIABLE"><mutatedClass>x.Y</mutatedClass></mutation>'
    open(path, "w").write(f"<mutations>{ms}</mutations>")

class Coverage(unittest.TestCase):
    def setUp(self): self.d = tempfile.mkdtemp(); self.x = f"{self.d}/j.xml"; jacoco(self.x)
    def test_summary_lists_overall_and_classes(self):
        r = run(["summary", self.x]); self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("line 80.0%", r.stdout); self.assertIn("RxSafetyChecks", r.stdout); self.assertIn("95.0%", r.stdout)
        self.assertIn("CheckoutService", r.stdout); self.assertIn("50.0%", r.stdout)
    def test_gates_report_only_by_default(self):
        self.assertEqual(run(["gate", self.x]).returncode, 0)
    def test_enabled_gate_fails_below_90_85_and_names_class(self):
        r = run(["gate", self.x], {"GATE_TODAY": "2999-01-01"}); self.assertEqual(r.returncode, 0)  # still null in config
        cfg = json.load(open("ci/quality-gates.json")); cfg["coverage"]["per_class_enabled_from"] = "2026-11-01"
        saved = open("ci/quality-gates.json").read()
        try:
            json.dump(cfg, open("ci/quality-gates.json", "w"))
            self.assertEqual(run(["gate", self.x], {"GATE_TODAY": "2026-10-31"}).returncode, 0)  # before the recorded date
            r = run(["gate", self.x], {"GATE_TODAY": "2026-11-01"})
            self.assertEqual(r.returncode, 1); self.assertIn("CheckoutService", r.stdout); self.assertNotIn("RxSafetyChecks:", r.stdout)
            jacoco(self.x, chk=(90, 10, 85, 15))  # exactly 90 line / 85 branch passes (boundary)
            r = run(["gate", self.x], {"GATE_TODAY": "2026-11-01"})
            self.assertIn("com/nabd/hms/auth/*: no class matched", r.stdout)  # fixture has no iam class
            self.assertNotIn("CheckoutService", r.stdout)
        finally: open("ci/quality-gates.json", "w").write(saved)
    def test_baseline_records_date(self):
        out = f"{self.d}/b.json"; self.assertEqual(run(["baseline", self.x, out], {"GATE_TODAY": "2026-10-19"}).returncode, 0)
        b = json.load(open(out)); self.assertEqual(b["recorded"], "2026-10-19"); self.assertEqual(b["overall_line"], 80.0)

class NewCode(unittest.TestCase):
    def test_only_changed_executable_lines_counted(self):
        d = tempfile.mkdtemp(); g = lambda *a: subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@e.invalid", *a], cwd=d, check=True, capture_output=True)
        os.makedirs(f"{d}/ci"); os.makedirs(f"{d}/backend/src/main/java/com/nabd/hms/billing")
        open(f"{d}/ci/quality-gates.json", "w").write(open("ci/quality-gates.json").read())
        f = f"{d}/backend/src/main/java/com/nabd/hms/billing/A.java"
        g("init", "-q"); open(f, "w").write("l1\nl2\n"); g("add", "."); g("commit", "-qm", "a"); base = "HEAD"
        open(f, "a").write("l3\nl4\nl5\n"); g("commit", "-qam", "b")
        x = f"{d}/j.xml"; jacoco(x)
        t = open(x).read().replace("</package>\n<package name=\"com/nabd/hms/billing\">", '</package>\n<package name="com/nabd/hms/billing">', 1)
        t = t.replace(' <class name="com/nabd/hms/billing/CheckoutService">', '<sourcefile name="A.java"><line nr="3" mi="0" ci="1"/><line nr="4" mi="1" ci="0"/><line nr="1" mi="1" ci="0"/></sourcefile>\n <class name="com/nabd/hms/billing/CheckoutService">')
        open(x, "w").write(t)
        r = subprocess.run(Q[:1] + [os.path.abspath("scripts/ci/quality.py"), "summary", x, "HEAD~1"], cwd=d, capture_output=True, text=True)
        self.assertIn("New code: 50.0% of 2 lines", r.stdout, r.stdout + r.stderr)  # line 5 not executable, line 1 not changed

class Pit(unittest.TestCase):
    def setUp(self): self.d = tempfile.mkdtemp()
    def score(self, k, s):
        mutations(f"{self.d}/m.xml", k, s); return run(["pit", f"{self.d}/m.xml"])
    def test_allergy_needs_90(self):
        r = self.score(89, 11); self.assertEqual(r.returncode, 1); self.assertIn("AllergyDrugClasses", json.loads(r.stdout)["failures"][0])
        self.assertEqual(self.score(90, 10).returncode, 0)  # boundary; NON_VIABLE ignored
    def test_drop_gate(self):
        h = f"{self.d}/h.json"; json.dump({"last_green": {"score": 92.0}}, open(h, "w"))
        s = f"{self.d}/s.json"
        for score, code in ((90.0, 0), (89.9, 1), (80.0, 1)):
            json.dump({"score": score}, open(s, "w")); self.assertEqual(run(["pit-drop", s, h]).returncode, code, score)
        self.assertEqual(run(["pit-drop", s, f"{self.d}/none.json"]).returncode, 0)  # first run

class Wiring(unittest.TestCase):
    def test_workflows(self):
        n = yaml.safe_load(open(".github/workflows/pit-nightly.yml"))
        self.assertEqual(n["jobs"]["pit"]["timeout-minutes"], 30)
        self.assertIn("schedule", n[True]); self.assertIn("-Ppit", open(".github/workflows/pit-nightly.yml").read())
        ci = open(".github/workflows/ci.yml").read(); yaml.safe_load(ci)
        self.assertIn("scripts/ci/quality.py gate", ci); self.assertIn("PIT_BLOCKED", ci)
    def test_config_consistent(self):
        c = json.load(open("ci/quality-gates.json")); self.assertEqual(c["pit"]["class_min_score"]["com.nabd.hms.clinical.AllergyDrugClasses"], 90)
        for cls in c["pit"]["target_classes"]:
            if "$" not in cls: self.assertTrue(os.path.exists("backend/src/main/java/" + cls.replace(".", "/") + ".java"), cls)
    def test_pom_profile_keeps_default_build(self):
        t = open("backend/pom.xml").read(); self.assertLess(t.index("<profile>"), t.index("pitest-maven")); self.assertEqual(t.count("pitest-maven</artifactId>"), 1)

if __name__ == "__main__": unittest.main()
