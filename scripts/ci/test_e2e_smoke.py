#!/usr/bin/env python3
"""FEF-049 artefact checks for the stage 7 smoke workflow: python3 scripts/ci/test_e2e_smoke.py (run from repo root)."""
import re, unittest
import yaml

WF = yaml.safe_load(open(".github/workflows/e2e-smoke.yml"))
ON = WF.get("on") or WF.get(True)  # PyYAML reads the bare key `on` as True


class SmokeWorkflow(unittest.TestCase):
    def test_reusable_with_preview_inputs(self):
        self.assertEqual(set(ON["workflow_call"]["inputs"]), {"app_url", "api_url", "seed_artifact"})

    def test_four_shards_inside_the_8_minute_budget(self):
        j = WF["jobs"]["smoke"]
        self.assertEqual(j["strategy"]["matrix"]["shard"], [1, 2, 3, 4])
        self.assertFalse(j["strategy"]["fail-fast"])
        self.assertLessEqual(j["timeout-minutes"], 8)
        run = next(s["run"] for s in j["steps"] if "run-smoke.mjs" in str(s.get("run", "")))
        self.assertIn("--shard=${{ matrix.shard }}/4", run)
        self.assertIn("--retries=1", run)          # one retry -> flaky detection
        self.assertIn("SMOKE_QUARANTINE", run)     # quarantined cases do not block

    def test_chromium_and_axe_pinned(self):
        self.assertRegex(WF["env"]["PLAYWRIGHT_VERSION"], r"^\d+\.\d+\.\d+$")
        self.assertRegex(WF["env"]["AXE_VERSION"], r"^\d+\.\d+\.\d+$")
        self.assertIn("playwright install --with-deps chromium", str(WF["jobs"]["smoke"]["steps"]))

    def test_report_gates_and_keeps_history(self):
        j = WF["jobs"]["report"]
        steps = str(j["steps"])
        self.assertIn("flaky.mjs", steps)
        self.assertIn("actions/cache/save", steps)
        self.assertIn("GITHUB_STEP_SUMMARY", steps)
        self.assertEqual(j["steps"][-1]["run"], "exit ${{ steps.gate.outputs.rc }}")

    def test_selfcheck_must_fail_on_a_broken_stack(self):
        run = WF["jobs"]["selfcheck"]["steps"][-1]["run"]
        self.assertRegex(run, r"if node .*run-smoke\.mjs --only=J9 --break .*; then .*exit 1; fi")
        self.assertRegex(run, r"\nnode .*run-smoke\.mjs --only=J9 --retries=0")

    def test_no_secrets_in_workflow(self):
        self.assertNotRegex(open(".github/workflows/e2e-smoke.yml").read(), re.compile(r"secrets\.|PIN=|password", re.I))


if __name__ == "__main__":
    unittest.main()
