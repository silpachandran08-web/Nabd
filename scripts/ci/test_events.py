#!/usr/bin/env python3
"""OPS-015 event contract gate on good and bad samples: python3 scripts/ci/test_events.py (from repo root)."""
import json, os, shutil, subprocess, tempfile, unittest

ENUM = "backend/src/main/java/com/nabd/hms/common/DomainEventType.java"
T = "visit.queue_entry.created.v1"


def run(*args):
    return subprocess.run(["python3", "scripts/ci/events.py", "check", *args], capture_output=True, text=True)


class Events(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        shutil.copytree("api/events", f"{self.root}/api/events")
        os.makedirs(os.path.dirname(f"{self.root}/{ENUM}"))
        shutil.copy(ENUM, f"{self.root}/{ENUM}")
        self.samples = f"{self.root}/samples"; os.makedirs(self.samples)

    def tearDown(self):
        shutil.rmtree(self.root)

    def schema(self, t=T):
        return f"{self.root}/api/events/{t}.schema.json"

    def edit(self, fn, t=T):
        s = json.load(open(self.schema(t))); fn(s); json.dump(s, open(self.schema(t), "w"))

    def envelope(self, **data):
        base = json.load(open(self.schema()))["examples"][0]
        return {"specversion": "1.0", "id": "42", "source": "core/visit", "type": T, "time": "2026-10-20T04:30:00Z",
                "subject": base["entryId"], "tenantid": base["tenantId"], "region": "in", "dataschema": "urn:nabd:event-schema:" + T,
                "datacontenttype": "application/json", "data": {**base, **data}}

    def sample(self, env):
        json.dump(env, open(f"{self.samples}/s.json", "w"))
        return run("--root", self.root, "--samples", self.samples)

    def test_repo_catalogue_passes_with_29_types(self):
        r = run(); self.assertEqual(r.returncode, 0, r.stdout); self.assertIn("29 type(s), 0 problem(s)", r.stdout)

    def test_good_sample_passes(self):
        r = self.sample(self.envelope()); self.assertEqual(r.returncode, 0, r.stdout)

    def test_phi_field_in_sample_fails(self):
        r = self.sample(self.envelope(patientName="X")); self.assertEqual(r.returncode, 1)
        self.assertIn("'patientName' matches the PHI denylist", r.stdout)

    def test_phi_field_in_schema_fails(self):
        self.edit(lambda s: s["properties"].update(mobilePhone={"type": "string", "pattern": "^[0-9]+$"}))
        r = run("--root", self.root); self.assertEqual(r.returncode, 1); self.assertIn("'mobilePhone' matches the PHI denylist", r.stdout)

    def test_payload_of_2kb_fails(self):
        self.edit(lambda s: s["properties"].update(pad={"type": "string", "pattern": "^x+$"}))
        r = self.sample(self.envelope(pad="x" * 2048)); self.assertEqual(r.returncode, 1); self.assertIn("limit < 2048", r.stdout)

    def test_unknown_field_fails_schema(self):
        r = self.sample(self.envelope(extra="1")); self.assertEqual(r.returncode, 1); self.assertIn("Additional properties", r.stdout)

    def test_missing_schema_for_catalogue_type_fails(self):
        os.remove(self.schema("clinical.rx.signed.v1"))
        r = run("--root", self.root); self.assertEqual(r.returncode, 1); self.assertIn("clinical.rx.signed.v1: in DomainEventType but no", r.stdout)

    def test_unbounded_string_fails(self):
        self.edit(lambda s: s["properties"]["status"].pop("pattern"))
        r = run("--root", self.root); self.assertEqual(r.returncode, 1); self.assertIn("'status' is unbounded", r.stdout)

    def test_envelope_without_type_fails(self):
        e = self.envelope(); e.pop("type")
        r = self.sample(e); self.assertEqual(r.returncode, 1); self.assertIn("'type' is a required property", r.stdout)


if __name__ == "__main__":
    unittest.main()
