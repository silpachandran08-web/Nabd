#!/usr/bin/env python3
"""FIN-010: the per-package mutation gate for the billing tax classes. Run from the repo root: python3 -m unittest scripts/ci/test_fin010_pit.py"""
import json, os, subprocess, sys, tempfile, unittest

CFG = json.load(open("ci/quality-gates.json"))["pit_packages"]["billing-tax"]
CLASSES = CFG["classes"]


def xml(spec):
    ms = ""
    for cls, (killed, survived) in spec.items():
        ms += f'<mutation detected="true" status="KILLED"><mutatedClass>{cls}</mutatedClass></mutation>' * killed
        ms += f'<mutation detected="false" status="SURVIVED"><mutatedClass>{cls}</mutatedClass></mutation>' * survived
    p = os.path.join(tempfile.mkdtemp(), "m.xml")
    open(p, "w").write(f"<mutations>{ms}</mutations>")
    return p


def run(path):
    r = subprocess.run([sys.executable, "scripts/ci/quality.py", "pit-pkg", path], capture_output=True, text=True)
    return r.returncode, json.loads(r.stdout)


class TaxPitGate(unittest.TestCase):
    def test_threshold_is_85_and_every_class_is_a_real_source_file(self):
        self.assertEqual(CFG["min_score"], 85)
        for c in CLASSES:
            self.assertTrue(os.path.exists("backend/src/main/java/" + c.replace(".", "/") + ".java"), c)
        pom = open("backend/pom.xml").read()
        for c in CLASSES:
            self.assertIn(c.split(".billing.")[1].split(".")[-1] if ".tax." not in c else c.split(".")[-1], pom)

    def test_85_percent_passes_84_fails(self):
        self.assertEqual(run(xml({c: (17, 3) for c in CLASSES}))[0], 0)       # 85.0 exactly
        rc, out = run(xml({c: (17, 3) for c in CLASSES[:-1]} | {CLASSES[-1]: (16, 4)}))  # 84.0 over 100 mutants
        self.assertEqual(rc, 1)
        self.assertIn("< 85", out["failures"][0])

    def test_a_class_without_mutants_fails(self):
        rc, out = run(xml({c: (20, 0) for c in CLASSES[:-1]}))
        self.assertEqual(rc, 1)
        self.assertIn(f"billing-tax: {CLASSES[-1]}: no mutants found", out["failures"])


if __name__ == "__main__":
    unittest.main()
