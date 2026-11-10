#!/usr/bin/env python3
"""IDC-019: the M1 runner's checklist validation, redaction, PHI scan and exit gate (no network: a fake opener stands in)."""
import io, json, pathlib, sys, tempfile, unittest, urllib.error

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import m1_runner as m  # noqa: E402


class FakeResponse(io.BytesIO):
    def __init__(self, status, body):
        super().__init__(json.dumps(body).encode())
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def opener(answers, seen):
    def f(req, timeout=None):
        seen.append(req)
        status, body = answers[req.full_url.split("://", 1)[1].split("/", 1)[1]]
        if status >= 400:
            raise urllib.error.HTTPError(req.full_url, status, "x", {}, io.BytesIO(json.dumps(body).encode()))
        return FakeResponse(status, body)
    return f


class M1(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())

    def test_repo_checklist_is_valid_and_every_case_waits_for_its_nha_mapping(self):
        cases = m.load_cases()
        self.assertEqual(m.check(cases), [])
        self.assertGreaterEqual(sum(c["mandatory"] for c in cases), 9)
        self.assertTrue(all("nha_ref" in c for c in cases))

    def test_check_names_broken_cases(self):
        bad = [{"id": "A", "kind": "auto", "mandatory": True, "title": "t", "nha_ref": None, "request": {"method": "GET", "path": "x"}, "expect": {}},
               {"id": "A", "kind": "manual", "mandatory": "yes", "title": "t"}]
        problems = m.check(bad)
        self.assertTrue(any("absolute request.path" in p for p in problems))
        self.assertTrue(any("expect.status" in p for p in problems))
        self.assertTrue(any("duplicate" in p for p in problems))
        self.assertTrue(any("needs steps" in p for p in problems))
        self.assertTrue(any("nha_ref" in p for p in problems))

    def test_redaction_masks_by_key_and_by_shape(self):
        r = m.redact({"abhaNumberOrAddress": "91-1234-5678-9012", "mobile": "9876543210", "name": "Test Person", "txnId": "abc",
                      "note": "call 9876543210 or a@b.co", "nested": [{"otp": "123456"}]})
        self.assertEqual(r["mobile"], "******3210")
        self.assertEqual(r["name"], "*******rson")
        self.assertEqual(r["nested"][0]["otp"], "**3456")
        self.assertNotIn("9876543210", json.dumps(r))
        self.assertIn("[email]", r["note"])
        self.assertEqual(r["txnId"], "abc")

    def test_run_records_redacted_evidence_merges_manual_results_and_never_stores_the_token(self):
        cases = [
            {"id": "A1", "nha_ref": "TC-1", "mandatory": True, "kind": "auto", "title": "status", "request": {"method": "GET", "path": "/v1/abha/status"}, "expect": {"status": 200, "json": {"enabled": True}}},
            {"id": "A2", "nha_ref": "TC-2", "mandatory": True, "kind": "auto", "title": "verify", "request": {"method": "POST", "path": "/v1/patients/${PID}/abha/verify", "body": {"step": "init", "abhaNumberOrAddress": "${ABHA}"}}, "expect": {"status": 200, "json_keys": ["txnId"]}},
            {"id": "A3", "nha_ref": "TC-3", "mandatory": True, "kind": "auto", "auth": False, "title": "callback", "request": {"method": "POST", "path": "/abdm/v3/callbacks/p", "body": {}}, "expect": {"status": 401}},
            {"id": "M1", "nha_ref": "TC-4", "mandatory": True, "kind": "manual", "title": "otp", "steps": "s"},
            {"id": "M2", "nha_ref": None, "mandatory": False, "kind": "manual", "title": "off", "steps": "s"},
        ]
        seen = []
        answers = {"v1/abha/status": (200, {"enabled": True}), "v1/patients/p-1/abha/verify": (200, {"txnId": "t-9"}), "abdm/v3/callbacks/p": (401, {"code": "unauthorized"})}
        env = {"NABD_M1_TOKEN": "secret-token-value", "PID": "p-1", "ABHA": "test@sbx"}
        s = m.run("http://nabd.test", self.tmp, {"M1": {"result": "pass", "evidence": ["shots/m1.png"]}}, cases, env, opener(answers, seen))
        self.assertEqual((s["mandatory_passed"], s["mandatory_total"]), (4, 4))
        self.assertEqual(seen[0].get_header("Authorization"), "Bearer secret-token-value")
        self.assertIsNone(seen[2].get_header("Authorization"))  # the unsigned-callback probe goes without credentials
        pack = "".join(p.read_text() for p in self.tmp.rglob("*") if p.is_file())
        self.assertNotIn("secret-token-value", pack)
        self.assertIn("p-1", pack)
        self.assertEqual(m.scan(self.tmp), [])
        self.assertIn("| PASS |", (self.tmp / "summary.md").read_text())

    def test_failures_and_unreachable_backends_fail_the_case(self):
        cases = [{"id": "A1", "nha_ref": "TC-1", "mandatory": True, "kind": "auto", "title": "s", "request": {"method": "GET", "path": "/v1/abha/status"}, "expect": {"status": 200, "json": {"enabled": True}}},
                 {"id": "M1", "nha_ref": "TC-2", "mandatory": True, "kind": "manual", "title": "otp", "steps": "s"}]
        s = m.run("http://nabd.test", self.tmp, None, cases, {}, opener({"v1/abha/status": (200, {"enabled": False})}, []))
        self.assertEqual(s["mandatory_passed"], 0)
        self.assertIn("enabled", s["results"][0]["detail"])
        self.assertEqual(s["results"][1]["detail"], "not run")

        def down(req, timeout=None):
            raise urllib.error.URLError("refused")
        s = m.run("http://nabd.test", self.tmp, None, cases[:1], {}, down)
        self.assertIn("unreachable", s["results"][0]["detail"])

    def test_scan_flags_unmasked_phi_shapes(self):
        (self.tmp / "x.json").write_text('{"a": "9876543210"}\n{"b": "ok"}\n{"c": "x@y.org"}\n{"d": "91-1234-5678-9012"}\n')
        self.assertEqual(m.scan(self.tmp), ["x.json:1", "x.json:3", "x.json:4"])

    def _submission(self, **over):
        sub = {"phase2_starts_on": "2027-04-01", "submission": {"nha_acknowledgement_ref": "ACK-1"},
               "security_audit": {"auditor": "Auditor Co", "cert_in_empanelled": True, "audit_date": "2027-05-10"}}
        sub.update(over)
        p = self.tmp / "sub.json"
        p.write_text(json.dumps(sub))
        return p

    def _summary(self, passed=True, mapped=True):
        (self.tmp / "summary.json").write_text(json.dumps({"results": [{"id": "A1", "nha_ref": "TC-1" if mapped else None, "mandatory": True, "pass": passed}]}))

    def test_gate_is_met_only_with_all_mandatory_passed_mapped_ack_and_a_phase2_audit(self):
        self._summary()
        self.assertEqual(m.gate(self.tmp, self._submission()), [])
        self.assertTrue(any("NHA acknowledgement" in r for r in m.gate(self.tmp, self._submission(submission={}))))
        early = self._submission(security_audit={"auditor": "A", "cert_in_empanelled": True, "audit_date": "2027-01-01"})
        self.assertTrue(any("auditor" in r for r in m.gate(self.tmp, early)))
        self.assertTrue(any("auditor" in r for r in m.gate(self.tmp, self._submission(phase2_starts_on=None))))
        self._summary(passed=False)
        self.assertTrue(any("not passed" in r for r in m.gate(self.tmp, self._submission())))
        self._summary(mapped=False)
        self.assertTrue(any("not mapped" in r for r in m.gate(self.tmp, self._submission())))
        self.assertEqual(m.gate(self.tmp / "missing", self._submission()), ["no sandbox run in " + str(self.tmp / "missing")])

    def test_repo_submission_record_is_not_met_until_the_owners_fill_it(self):
        self._summary()
        reasons = m.gate(self.tmp)
        self.assertTrue(any("NHA acknowledgement" in r for r in reasons))
        self.assertTrue(any("auditor" in r for r in reasons))


if __name__ == "__main__":
    unittest.main()
