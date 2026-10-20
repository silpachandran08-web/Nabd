#!/usr/bin/env python3
"""OPS-037 gate and live-check tests. A local HTTP server plays a good preview and a seeded vulnerable one."""
import json, os, sys, threading, unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
sys.path.insert(0, "scripts/ci")
import dast
import yaml

STAFF = "http://staff.local"


def server(vulnerable):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def send(self, code, headers, body=b""):
            self.send_response(code)
            for k, v in headers.items(): self.send_header(k, v)
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def sec(self):
            return {} if vulnerable else {"Content-Security-Policy": "default-src 'none'", "Strict-Transport-Security": "max-age=31536000"}
        def do_GET(self):
            if "limit=0" in self.path:
                if vulnerable: return self.send(500, {"Content-Type": "text/html"}, b"boom")  # QA-19
                return self.send(400, {**self.sec(), "Content-Type": "application/problem+json"}, b'{"status":400}')
            self.send(200, self.sec(), b"ok")
        def do_OPTIONS(self):
            o = self.headers.get("Origin"); m = self.headers.get("Access-Control-Request-Method")
            allowed = "GET,POST,PATCH,DELETE" if vulnerable else "GET,POST,PUT,PATCH,DELETE"  # QA-05: PUT missing
            if o == STAFF or (vulnerable and o):
                return self.send(200, {"Access-Control-Allow-Origin": o, "Access-Control-Allow-Methods": allowed})
            self.send(403, {})
    s = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=s.serve_forever, daemon=True).start()
    return s, f"http://127.0.0.1:{s.server_address[1]}"


def zap(*alerts): return {"site": [{"@name": "https://pr.example", "alerts": list(alerts)}]}
def alert(risk, pid, name, uri="https://pr.example/x"): return {"pluginid": pid, "alert": name, "riskcode": risk, "instances": [{"uri": uri}]}


class ZapGate(unittest.TestCase):
    owners = {"default": "@nabd/platform-owners", "plugins": {"10038": "@nabd/frontend"}}
    def test_high_fails(self):
        md, fails = dast.zap_gate(zap(alert("3", "40018", "SQL Injection")), self.owners)
        self.assertTrue(fails); self.assertIn("SQL Injection", md); self.assertIn("Gate: FAIL", md)
    def test_medium_listed_with_owner_and_passes(self):
        md, fails = dast.zap_gate(zap(alert("2", "10038", "CSP Header Not Set"), alert("2", "99999", "Other"), alert("1", "1", "Low")), self.owners)
        self.assertFalse(fails)
        self.assertIn("| CSP Header Not Set | 10038 | @nabd/frontend | 1 |", md)
        self.assertIn("| Other | 99999 | @nabd/platform-owners | 1 |", md)
        self.assertIn("Medium: 2", md)
    def test_token_in_url_fails_and_is_redacted(self):
        md, fails = dast.zap_gate(zap(alert("1", "1", "x", "https://pr.example/cb?access_token=abc123")), {})
        self.assertTrue(fails); self.assertNotIn("abc123", md)
    def test_nightly_issues_only_for_highs(self):
        got = list(dast.zap_issues(zap(alert("3", "40018", "SQL Injection", "https://a/x?q=1"), alert("2", "10038", "CSP"))))
        self.assertEqual([i["title"] for i in got], ["[ZAP High] SQL Injection (plugin 40018)"])
        self.assertIn("https://a/x", got[0]["body"]); self.assertNotIn("q=1", got[0]["body"])


class LiveChecks(unittest.TestCase):
    def test_good_preview_passes(self):
        s, u = server(False)
        try: self.assertEqual(dast.header_checks(u, u, STAFF, token="t"), [])
        finally: s.shutdown()
    def test_seeded_vulnerable_preview_is_caught(self):
        s, u = server(True)
        try: f = " | ".join(dast.header_checks(u, u, STAFF, token="t"))
        finally: s.shutdown()
        for want in ("web: missing content-security-policy", "api: missing strict-transport-security",
                     "CORS preflight PUT from staff origin refused", "CORS: foreign origin allowed", "limit=0 -> 500"):
            self.assertIn(want, f)
        self.assertNotIn("preflight GET", f)


class Gates(unittest.TestCase):
    def test_schemathesis_report_only_until_s4(self):
        self.assertIsNone(json.load(open(dast.GATE))["schemathesis_blocking_from"])
        self.assertFalse(dast.st_blocking())
    def test_schemathesis_blocks_after_date(self):
        orig = dast.GATE
        try:
            p = os.path.join(os.environ.get("TMPDIR", "/tmp"), "dast-gate-test.json")
            json.dump({"schemathesis_blocking_from": "2027-01-01"}, open(p, "w")); dast.GATE = p
            os.environ["GATE_TODAY"] = "2026-12-31"; self.assertFalse(dast.st_blocking())
            os.environ["GATE_TODAY"] = "2027-01-01"; self.assertTrue(dast.st_blocking())
        finally:
            dast.GATE = orig; os.environ.pop("GATE_TODAY", None)


class Workflow(unittest.TestCase):
    wf = yaml.safe_load(open(".github/workflows/dast.yml"))
    def test_jobs_and_budgets(self):
        j = self.wf["jobs"]
        self.assertEqual(j["zap-baseline"]["timeout-minutes"], 5)
        self.assertIn("6", str(j["schemathesis"]["timeout-minutes"]))
        self.assertEqual(j["schemathesis"]["strategy"]["matrix"]["role"], j["zap-full-nightly"]["strategy"]["matrix"]["role"])
        self.assertTrue({"deployment_status", "schedule", "workflow_dispatch"} <= set(self.wf[True]))
    def test_checks_and_gates_wired(self):
        t = open(".github/workflows/dast.yml").read()
        for w in ("not_a_server_error,status_code_conformance,response_schema_conformance", "--stateful=links", "dast.py st-gate",
                  "dast.py zap-gate", "dast.py zap-issues", "dast.py headers", "ZAP_AUTH_HEADER_VALUE", "gh issue create", "::add-mask::"):
            self.assertIn(w, t)
    def test_not_a_required_stage_check(self):  # gates by its own status; branch protection change is external
        req = json.load(open(".github/branch-protection.json"))["required_status_checks"]["contexts"]
        self.assertFalse(any(k in req for k in self.wf["jobs"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
