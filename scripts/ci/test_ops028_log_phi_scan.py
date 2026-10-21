#!/usr/bin/env python3
"""OPS-028 log-stream scanner + nightly workflow checks. Run from the repo root: python3 -m unittest scripts/ci/test_ops028_log_phi_scan.py"""
import json, os, subprocess, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(__file__))
import log_phi_scan as s

GOOD = [
    json.dumps({"logger": "c.n.A", "message": "otp sent to +91******5678 for a***@***, code ******"}),
    json.dumps({"logger": "c.n.B", "message": "queue entry 3f2b8c1e-1234-4abc-9def-0123456789ab moved"}),
]


class Scanner(unittest.TestCase):
    def test_masked_stream_is_clean(self):
        self.assertEqual(s.scan_lines(GOOD), [])

    def test_each_escaped_shape_is_caught_and_names_the_logger(self):
        bad = {"marker": "patient SYNTH-PHI-NAME7 arrived", "email": "mail asha@example.com", "phone": "to +919812345678",
               "national_id": "aadhaar 1234 5678 9012", "otp": "the otp is 481516"}
        for rule, msg in bad.items():
            hits = s.scan_lines([json.dumps({"logger": "c.n.Leaky", "message": msg})])
            self.assertEqual([(h["rule"], h["logger"]) for h in hits][:1], [(rule, "c.n.Leaky")], rule)

    def test_report_never_holds_the_full_match(self):
        hits = s.scan_lines([json.dumps({"logger": "x.y.Z", "message": "SYNTH-PHI-SECRETNAME"})])
        self.assertNotIn("SECRETNAME", json.dumps(hits))

    def test_cli_exit_codes(self):
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as f:
            f.write("\n".join(GOOD))
        ok = subprocess.run([sys.executable, "scripts/ci/log_phi_scan.py", f.name], capture_output=True, text=True)
        with open(f.name, "a") as g:
            g.write("\n" + json.dumps({"logger": "c.n.Leaky", "message": "SYNTH-PHI-X1"}))
        bad = subprocess.run([sys.executable, "scripts/ci/log_phi_scan.py", f.name], capture_output=True, text=True)
        os.unlink(f.name)
        self.assertEqual(ok.returncode, 0, ok.stdout)
        self.assertEqual(bad.returncode, 1)
        self.assertIn("c.n.Leaky", bad.stdout)


class Workflows(unittest.TestCase):
    def load(self, p):
        import yaml
        return yaml.safe_load(open(p))

    def test_nightly_log_scan_opens_s2_defect_and_does_not_pass_silently(self):
        w = self.load(".github/workflows/log-phi-nightly.yml")
        on = w.get("on") or w.get(True)
        self.assertIn("schedule", on)
        text = open(".github/workflows/log-phi-nightly.yml").read()
        self.assertIn("scripts/ci/log_phi_scan.py", text)
        self.assertIn("gh issue create", text)
        self.assertIn("S2", text)
        self.assertIn("exit 1", text)  # unset log source must fail, not go green

    def test_nightly_fuzz_opens_s1_defect(self):
        w = self.load(".github/workflows/id-replay-fuzz.yml")
        on = w.get("on") or w.get(True)
        self.assertIn("schedule", on)
        text = open(".github/workflows/id-replay-fuzz.yml").read()
        self.assertIn("IdReplayFuzzerApiTest", text)
        self.assertIn("gh issue create", text)
        self.assertIn("S1", text)


if __name__ == "__main__":
    unittest.main()
