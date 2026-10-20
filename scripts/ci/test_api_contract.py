#!/usr/bin/env python3
"""OPS-035 artefact checks: python3 scripts/ci/test_api_contract.py (run from repo root).
With OASDIFF=/path/to/oasdiff (or oasdiff on PATH) the fixture "PRs" also run through the real pinned oasdiff binary."""
import importlib.util, json, os, shutil, subprocess, sys, tempfile, unittest
from pathlib import Path

import yaml

sys.path.insert(0, "scripts/ci")
import api_contract

FIX = Path("scripts/ci/fixtures/api-contract")
OASDIFF = os.environ.get("OASDIFF") or shutil.which("oasdiff")


def spec(name):
    return yaml.safe_load((FIX / name).read_text())


def finding(path, fid="response-optional-property-removed", op="GET"):
    return {"id": fid, "text": "removed the optional property 'name' from the response with the '200' status", "level": 2, "operation": op, "path": path}


class BreakingRule(unittest.TestCase):
    def test_removed_response_field_on_a_live_operation_blocks(self):
        blocking, exempt = api_contract.check([finding("/v1/things/{id}")], spec("removed-field.yaml"))
        self.assertEqual(len(blocking), 1)
        self.assertIn("breaking change: GET /v1/things/{id}", blocking[0])
        self.assertEqual(exempt, [])

    def test_removed_response_field_on_a_deprecated_operation_with_sunset_is_allowed(self):
        blocking, exempt = api_contract.check([finding("/v1/old-things/{id}")], spec("removed-field-deprecated.yaml"))
        self.assertEqual(blocking, [])
        self.assertIn("Sunset 2027-03-31", exempt[0])

    def test_deprecated_without_sunset_blocks_and_gets_no_exemption(self):
        blocking, _ = api_contract.check([finding("/v1/old-things/{id}")], spec("deprecated-no-sunset.yaml"))
        self.assertIn("deprecated without Sunset: GET /v1/old-things/{id} (add x-sunset: YYYY-MM-DD)", blocking)
        self.assertTrue(any(b.startswith("breaking change: GET /v1/old-things/{id}") for b in blocking))

    def test_sunset_findings_and_removed_operations_are_never_exempt(self):
        h = spec("removed-field-deprecated.yaml")
        blocking, _ = api_contract.check([finding("/v1/old-things/{id}", "api-sunset-date-changed-too-small"),
                                          finding("/v1/gone", "api-removed-before-sunset")], h)
        self.assertEqual(len(blocking), 2)

    def test_invalid_sunset_date_counts_as_missing(self):
        h = spec("removed-field-deprecated.yaml")
        h["paths"]["/v1/old-things/{id}"]["get"]["x-sunset"] = "soon"
        blocking, exempt = api_contract.check([finding("/v1/old-things/{id}")], h)
        self.assertEqual(exempt, [])
        self.assertEqual(len(blocking), 2)

    def test_cli_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            for head, findings, rc in [("removed-field.yaml", [finding("/v1/things/{id}")], 1),
                                       ("removed-field-deprecated.yaml", [finding("/v1/old-things/{id}")], 0),
                                       ("base.yaml", [], 0)]:
                f = Path(d) / "b.json"
                f.write_text(json.dumps(findings))
                r = subprocess.run([sys.executable, "scripts/ci/api_contract.py", "breaking", str(f), str(FIX / head)], capture_output=True, text=True)
                self.assertEqual(r.returncode, rc, head + r.stdout)

    def test_current_core_spec_has_no_deprecated_operation_without_sunset(self):
        blocking, _ = api_contract.check([], yaml.safe_load(open("api/openapi/core.yaml")))
        self.assertEqual(blocking, [])

    @unittest.skipUnless(OASDIFF, "set OASDIFF to the pinned oasdiff binary to run the fixture PRs end to end")
    def test_fixture_prs_through_real_oasdiff(self):
        for head, rc in [("removed-field.yaml", 1), ("removed-field-deprecated.yaml", 0), ("deprecated-no-sunset.yaml", 1), ("base.yaml", 0)]:
            out = subprocess.run([OASDIFF, "breaking", str(FIX / "base.yaml"), str(FIX / head), "-f", "json"], capture_output=True, text=True, check=True).stdout
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
                f.write(out)
            r = subprocess.run([sys.executable, "scripts/ci/api_contract.py", "breaking", f.name, str(FIX / head)], capture_output=True, text=True)
            os.unlink(f.name)
            self.assertEqual(r.returncode, rc, f"{head}: {r.stdout}")


class GeneratedClient(unittest.TestCase):
    def load(self):
        s = importlib.util.spec_from_file_location("generate_client", "scripts/api/generate_client.py")
        m = importlib.util.module_from_spec(s)
        s.loader.exec_module(m)
        return m

    def test_committed_client_is_current(self):
        r = subprocess.run([sys.executable, "scripts/api/generate_client.py", "--check"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout)

    def test_check_fails_on_a_stale_file_and_writes_only_the_generated_folder(self):
        m = self.load()
        self.assertEqual(m.OUT.relative_to(m.ROOT).as_posix(), "frontend/packages/api-client/src/generated")
        with tempfile.TemporaryDirectory() as d:
            m.OUT = Path(d) / "generated"
            m.ROOT = Path(d)
            self.assertEqual(m.main([]), 0)
            self.assertEqual(sorted(p.name for p in Path(d).rglob("*") if p.is_file()), ["handlers.ts", "index.ts", "schemas.ts", "types.ts"])
            self.assertEqual(m.main(["--check"]), 0)
            (m.OUT / "types.ts").write_text("// edited by hand\n")
            self.assertEqual(m.main(["--check"]), 1)

    def test_every_operation_is_generated_and_no_package_json(self):
        core = yaml.safe_load(open("api/openapi/core.yaml"))
        ids = [o["operationId"] for item in core["paths"].values() for v, o in item.items() if v in ("get", "post", "put", "patch", "delete")]
        gen = Path("frontend/packages/api-client/src/generated")
        types, schemas, handlers = (gen / "types.ts").read_text(), (gen / "schemas.ts").read_text(), (gen / "handlers.ts").read_text()
        for i in ids:
            self.assertIn(f"  {i}: {{ method:", types)
            self.assertIn(f"  {i}: {{", schemas)
            self.assertIn(f"// {i}\n", handlers)
        self.assertEqual(list(gen.rglob("package.json")), [])

    def test_generation_is_fast(self):  # NFR: generated client build < 60 s
        import time
        t = time.monotonic()
        self.load().render(yaml.safe_load(open("api/openapi/core.yaml")))
        self.assertLess(time.monotonic() - t, 60)


class Stage5Job(unittest.TestCase):
    wf = yaml.safe_load(open(".github/workflows/ci.yml"))

    def test_stage5_is_blocking_and_required(self):
        j = self.wf["jobs"]["stage5-api-contract"]
        self.assertNotIn("continue-on-error", j)
        self.assertIn("stage5-api-contract", json.load(open(".github/branch-protection.json"))["required_status_checks"]["contexts"])
        text = json.dumps(j["steps"])
        for needle in ("-Dtest=ApiContractGateTest", "oasdiff breaking", "api_contract.py breaking", "generate_client.py --check", 'OASDIFF_VERSION": "1.10.23'):
            self.assertIn(needle, text)
        oas = next(s for s in j["steps"] if "oasdiff breaking" in s.get("run", ""))
        self.assertEqual(oas["shell"], "bash")  # pipefail, so tee cannot swallow the exit code

    def test_report_job_is_still_non_blocking(self):
        self.assertTrue(self.wf["jobs"]["api-contract-report"]["continue-on-error"])


if __name__ == "__main__":
    unittest.main()
