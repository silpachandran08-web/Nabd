#!/usr/bin/env python3
"""OPS-039 artefact checks: python3 scripts/ci/test_ops039.py (repo root)."""
import json, re, subprocess, unittest
import yaml

WF = yaml.safe_load(open(".github/workflows/perf-budget.yml"))
RC = json.load(open("frontend/apps/staff-web/lighthouserc.json"))


class PerfBudget(unittest.TestCase):
    def test_budgets_per_route(self):
        b = RC["nabdBudgets"]
        self.assertEqual(b["lcpMs"], 3000)
        self.assertLessEqual(b["bundleKB"], 1536)
        self.assertEqual(b["routes"], ["/login", "/arrivals", "/consult", "/checkout/otc", "/my-day"])

    def test_throttling_is_3g_android_median_of_3(self):
        c = RC["ci"]["collect"]
        self.assertEqual(c["numberOfRuns"], 3)
        self.assertEqual(c["settings"]["formFactor"], "mobile")
        self.assertGreaterEqual(c["settings"]["throttling"]["cpuSlowdownMultiplier"], 4)
        self.assertLessEqual(c["settings"]["throttling"]["downloadThroughputKbps"], 1600)
        self.assertIn("Android", c["settings"]["emulatedUserAgent"])
        a = RC["ci"]["assert"]["assertions"]["largest-contentful-paint"]
        self.assertEqual(a[0], "error"); self.assertEqual(a[1]["aggregationMethod"], "median")

    def test_workflow_fails_on_breach_and_stays_in_5_min(self):
        for j in WF["jobs"].values():
            self.assertLessEqual(j["timeout-minutes"], 5)
            self.assertEqual(j["steps"][-1]["run"], 'test -z "${{ steps.gate.outputs.rc }}"')
        self.assertIn("perf-budget.mjs lhci", str(WF["jobs"]["lighthouse"]))
        self.assertIn("perf-budget.mjs bundle", str(WF["jobs"]["bundle"]))

    def test_preview_calls_it(self):
        p = yaml.safe_load(open(".github/workflows/preview.yml"))["jobs"]["perf"]
        self.assertEqual(p["uses"], "./.github/workflows/perf-budget.yml")

    def test_no_secret_literals(self):
        self.assertNotRegex(open(".github/workflows/perf-budget.yml").read(), re.compile(r"secrets\.|PIN=", re.I))

    def test_unit_fixtures_pass(self):
        r = subprocess.run(["node", "--test", "tests/unit/perf-budget.test.mjs"], cwd="frontend/apps/staff-web", capture_output=True, text=True,
                           env={"PATH": "/opt/node22/bin:/usr/bin:/bin"})
        self.assertEqual(r.returncode, 0, r.stdout[-800:])


if __name__ == "__main__":
    unittest.main()
