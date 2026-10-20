#!/usr/bin/env python3
"""Independent QA (S12 OPS-035): the oasdiff breaking rule, the generated client and the CI wiring, exercised on the REAL
api/openapi/core.yaml (not the engineer's toy fixtures). Run from the repo root:
  OASDIFF=/path/to/oasdiff-1.10.23 python3 scripts/ci/test_s12qa_ops035.py
"""
import copy, json, os, re, shutil, subprocess, sys, tempfile, time, unittest
from pathlib import Path

import yaml

sys.path.insert(0, "scripts/ci")
import api_contract

CORE = Path("api/openapi/core.yaml")
GEN = Path("frontend/packages/api-client/src/generated")
OASDIFF = os.environ.get("OASDIFF") or shutil.which("oasdiff")
VERBS = ("get", "post", "put", "patch", "delete")


def core():
    return yaml.safe_load(CORE.read_text())


def ops(spec):
    return [(v, p, o) for p, item in spec["paths"].items() for v, o in item.items() if v in VERBS]


def single_use_response_schema(spec):
    """(verb, path, schemaName, property): a component schema referenced exactly once in the whole spec, as a 200 response."""
    text = json.dumps(spec)
    for v, p, o in ops(spec):
        for code, r in (o.get("responses") or {}).items():
            for media in (r.get("content") or {}).values():
                ref = (media.get("schema") or {}).get("$ref")
                if not ref or not code.startswith("2"):
                    continue
                name = ref.rsplit("/", 1)[-1]
                props = spec["components"]["schemas"][name].get("properties") or {}
                if text.count(f'"#/components/schemas/{name}"') == 1 and len(props) >= 2:
                    return v, p, name, sorted(props)[0]
    raise AssertionError("no single-use response schema in core.yaml")


def gate(base_spec, head_spec):
    """Runs the real pinned oasdiff + the stage 5 rule exactly as ci.yml does. Returns (rc, stdout)."""
    with tempfile.TemporaryDirectory() as d:
        b, h, j = Path(d, "base.yaml"), Path(d, "head.yaml"), Path(d, "breaking.json")
        b.write_text(yaml.safe_dump(base_spec, sort_keys=False))
        h.write_text(yaml.safe_dump(head_spec, sort_keys=False))
        out = subprocess.run([OASDIFF, "breaking", str(b), str(h), "-f", "json"], capture_output=True, text=True, check=True).stdout
        j.write_text(out)
        r = subprocess.run([sys.executable, "scripts/ci/api_contract.py", "breaking", str(j), str(h)], capture_output=True, text=True)
        return r.returncode, r.stdout


@unittest.skipUnless(OASDIFF, "set OASDIFF to the pinned oasdiff 1.10.23 binary")
class BreakingRuleOnRealSpec(unittest.TestCase):
    def setUp(self):
        self.base = core()
        self.verb, self.path, self.schema, self.prop = single_use_response_schema(self.base)

    def removed_field(self):
        head = copy.deepcopy(self.base)
        s = head["components"]["schemas"][self.schema]
        del s["properties"][self.prop]
        if self.prop in (s.get("required") or []):
            s["required"].remove(self.prop)
        return head

    def test_unchanged_spec_passes(self):
        rc, out = gate(self.base, self.base)
        self.assertEqual(rc, 0, out)

    def test_removing_a_response_field_of_a_live_operation_blocks(self):
        rc, out = gate(self.base, self.removed_field())
        self.assertEqual(rc, 1, out)
        self.assertIn(f"breaking change: {self.verb.upper()} {self.path}", out)
        self.assertIn(self.prop, out)

    def test_removing_a_response_field_of_a_deprecated_operation_with_sunset_is_allowed(self):
        head = self.removed_field()
        op = head["paths"][self.path][self.verb]
        op["deprecated"], op["x-sunset"] = True, "2027-06-30"
        rc, out = gate(self.base, head)
        self.assertEqual(rc, 0, out)
        self.assertIn("allowed under deprecation+Sunset", out)
        self.assertRegex(out, r"0 blocking, [1-9]\d* allowed")

    def test_deprecated_without_sunset_still_blocks(self):
        head = self.removed_field()
        head["paths"][self.path][self.verb]["deprecated"] = True
        rc, out = gate(self.base, head)
        self.assertEqual(rc, 1, out)
        self.assertIn(f"deprecated without Sunset: {self.verb.upper()} {self.path}", out)

    def test_sunset_on_one_operation_does_not_exempt_another(self):
        head = self.removed_field()
        other = next((v, p) for v, p, _ in ops(head) if (v, p) != (self.verb, self.path))
        op = head["paths"][other[1]][other[0]]
        op["deprecated"], op["x-sunset"] = True, "2027-06-30"
        rc, out = gate(self.base, head)
        self.assertEqual(rc, 1, out)

    def test_removing_a_whole_live_operation_blocks(self):
        head = copy.deepcopy(self.base)
        del head["paths"][self.path][self.verb]
        rc, out = gate(self.base, head)
        self.assertEqual(rc, 1, out)

    def test_adding_an_optional_response_field_is_not_breaking(self):
        head = copy.deepcopy(self.base)
        head["components"]["schemas"][self.schema]["properties"]["qaAddedField"] = {"type": "string"}
        rc, out = gate(self.base, head)
        self.assertEqual(rc, 0, out)

    def test_making_a_request_field_required_blocks(self):
        head = copy.deepcopy(self.base)
        login = head["components"]["schemas"]["LoginRequest"]
        login.setdefault("properties", {})["qaNewMandatory"] = {"type": "string"}
        login.setdefault("required", []).append("qaNewMandatory")
        rc, out = gate(self.base, head)
        self.assertEqual(rc, 1, out)
        self.assertIn("/v1/auth/login", out)


class GeneratedClient(unittest.TestCase):
    def sandbox(self, d):
        """A copy of exactly what the generator reads and writes, so it can run on a mutated spec."""
        for rel in ("scripts/api/generate_client.py", str(CORE)):
            Path(d, rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(rel, Path(d, rel))
        shutil.copytree(GEN, Path(d, GEN))
        return Path(d)

    def test_committed_client_is_up_to_date_and_generates_under_60s(self):
        t = time.monotonic()
        r = subprocess.run([sys.executable, "scripts/api/generate_client.py", "--check"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertLess(time.monotonic() - t, 60)

    def test_files_types_zod_and_msw_exist_with_one_entry_per_operation(self):
        n = len(ops(core()))
        types, schemas, handlers = (GEN / "types.ts").read_text(), (GEN / "schemas.ts").read_text(), (GEN / "handlers.ts").read_text()
        self.assertIn('from "zod"', schemas)
        self.assertEqual(len(re.findall(r"^  \w+: \{ method: ", types, re.M)), n)
        self.assertEqual(len(re.findall(r"^  \w+: \{", schemas.split("export const operationSchemas = {", 1)[1], re.M)), n)
        self.assertEqual(len(re.findall(r"http\.(?:get|post|put|patch|delete)\(", handlers)), n)
        for name in core()["components"]["schemas"]:
            self.assertRegex(schemas, rf"export const {re.escape(name)}Schema\b", name)

    def test_operation_permissions_in_generated_types_equal_the_spec(self):
        types = (GEN / "types.ts").read_text()
        gen = {(m, p): perm for m, p, perm in re.findall(r'method: "(\w+)"; path: "([^"]+)"; permission: "([^"]*)"', types)}
        bad = [(v, p) for v, p, o in ops(core()) if gen.get((v, p)) != o.get("x-required-permission")]
        self.assertEqual(bad, [])

    def test_stale_client_after_a_spec_change_fails_the_check(self):
        with tempfile.TemporaryDirectory() as d:
            root = self.sandbox(d)
            spec = core()
            v, p, name, prop = single_use_response_schema(spec)
            del spec["components"]["schemas"][name]["properties"][prop]
            (root / CORE).write_text(yaml.safe_dump(spec, sort_keys=False))
            r = subprocess.run([sys.executable, str(root / "scripts/api/generate_client.py"), "--check"], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            subprocess.run([sys.executable, str(root / "scripts/api/generate_client.py")], check=True, capture_output=True)
            regenerated = (root / GEN / "types.ts").read_text()
            line = next(l for l in regenerated.splitlines() if l.startswith(f"export type {name} ="))
            self.assertNotIn(f"{prop}?:", line)
            self.assertNotIn(f"{prop}:", line)

    def test_hand_edited_generated_file_fails_the_check(self):
        with tempfile.TemporaryDirectory() as d:
            root = self.sandbox(d)
            f = root / GEN / "schemas.ts"
            f.write_text(f.read_text().replace("pin: z.string()", "pin: z.string().optional()", 1))
            r = subprocess.run([sys.executable, str(root / "scripts/api/generate_client.py"), "--check"], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1, r.stdout)

    # known-defect-OPS-035 (AC3 "and the FE build uses them"): no app code imports the generated client (waits on FEF-014),
    # and the @nabd/api-client package exports neither ./src/generated nor declares msw.
    @unittest.expectedFailure
    def test_frontend_app_uses_the_generated_client(self):
        r = subprocess.run(["grep", "-rlE", "api-client/src/generated|@nabd/api-client/generated", "frontend/apps/staff-web/app"],
                           capture_output=True, text=True)
        self.assertTrue(r.stdout.strip(), "no staff-web module imports the generated client")
        pkg = json.loads(Path("frontend/packages/api-client/package.json").read_text())
        self.assertTrue(any("generated" in k or "generated" in str(v) for k, v in pkg.get("exports", {}).items()))


class Stage5Wiring(unittest.TestCase):
    def test_stage5_is_a_blocking_required_job_running_gate_client_check_and_oasdiff_rule(self):
        ci = yaml.safe_load(Path(".github/workflows/ci.yml").read_text())
        job = ci["jobs"]["stage5-api-contract"]
        self.assertFalse(job.get("continue-on-error", False))
        runs = "\n".join(s.get("run", "") for s in job["steps"])
        self.assertIn("-Dtest=ApiContractGateTest", runs)
        self.assertIn("generate_client.py --check", runs)
        self.assertIn("api_contract.py breaking", runs)
        for s in job["steps"]:
            self.assertFalse(s.get("continue-on-error", False), s.get("name"))
            if "api_contract.py" in s.get("run", ""):
                self.assertEqual(s.get("shell"), "bash", "pipefail needed: tee must not swallow the exit code")
        bp = json.loads(Path(".github/branch-protection.json").read_text())
        self.assertIn("stage5-api-contract", json.dumps(bp))


if __name__ == "__main__":
    unittest.main(verbosity=2)
