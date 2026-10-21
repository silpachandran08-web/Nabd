#!/usr/bin/env python3
"""OPS-056 tests: edge terraform rules, prod-in manifests, digest promotion, TLS check (against real local TLS servers), cutover checks."""
import http.server
import pathlib
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "infra" / "security"), str(ROOT / "infra" / "prod-in")]
import check_cutover as cut  # noqa: E402
import check_edge  # noqa: E402
import check_prod_in  # noqa: E402
import promote  # noqa: E402
import tls_check  # noqa: E402

HSTS = "max-age=63072000; includeSubDomains; preload"


def cert(d, days):
    c, k = f"{d}/c{days}.pem", f"{d}/k{days}.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", str(days), "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost", "-keyout", k, "-out", c], check=True, capture_output=True)
    return c, k


def server(c, k, hsts=HSTS, minv=ssl.TLSVersion.TLSv1_2, maxv=None, ciphers=None):
    class H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            if hsts:
                self.send_header("Strict-Transport-Security", hsts)
            self.send_header("Content-Length", "0")
            self.end_headers()
        log_message = lambda *a: None
    s = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    x = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    x.load_cert_chain(c, k)
    # default = forward-secrecy AEAD only (what the ALB policy offers); legacy cases pass their own list
    x.set_ciphers(ciphers or ("ALL:@SECLEVEL=0" if minv < ssl.TLSVersion.TLSv1_2 else "ECDHE+AESGCM:ECDHE+CHACHA20"))
    x.minimum_version = minv
    if maxv:
        x.maximum_version = maxv
    s.socket = x.wrap_socket(s.socket, server_side=True)
    threading.Thread(target=s.serve_forever, daemon=True).start()
    return s


class Static(unittest.TestCase):
    def test_edge_terraform(self):
        self.assertEqual(check_edge.problems(check_edge.load()), [])
        check_edge.selftest()

    def test_prod_in_manifests(self):
        self.assertEqual(check_prod_in.problems(check_prod_in.load()), [])
        check_prod_in.selftest()

    def test_workflow_parses(self):
        import yaml
        wf = yaml.safe_load((ROOT / ".github/workflows/prod-in.yml").read_text())
        self.assertIn("cosign verify", str(wf["jobs"]["promote"]))
        self.assertIn("staging-in runs", str(wf["jobs"]["promote"]))

    def test_terraform_static_rules_still_pass(self):
        r = subprocess.run([sys.executable, str(ROOT / "infra/terraform/check-terraform.py")], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


class Promote(unittest.TestCase):
    D = "sha256:" + "a" * 64

    def test_digest_only(self):
        with tempfile.TemporaryDirectory() as d:
            promote.render(f"ghcr.io/o/nabd-core@{self.D}", f"ghcr.io/o/nabd-web@{self.D}", pathlib.Path(d))
            self.assertIn(f"nabd-core@{self.D}", (pathlib.Path(d) / "worker.yaml").read_text())
            self.assertNotIn("@sha256:REPLACE", (pathlib.Path(d) / "bff.yaml").read_text())
            for bad in ("ghcr.io/o/nabd-core:latest", "ghcr.io/o/nabd-core@sha256:abc", "docker.io/o/x@" + self.D):
                with self.assertRaises(SystemExit):
                    promote.render(bad, f"ghcr.io/o/nabd-web@{self.D}", pathlib.Path(d))


class Tls(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def run_check(self, srv, ca):
        try:
            return tls_check.check_host("localhost", srv.server_address[1], cafile=ca)
        finally:
            srv.shutdown()

    def test_good_server_passes(self):
        c, k = cert(self.d, 365)
        self.assertEqual(self.run_check(server(c, k), c), [])

    def test_tls10_accepted_is_caught(self):
        c, k = cert(self.d, 365)
        srv = server(c, k, minv=ssl.TLSVersion.TLSv1, maxv=ssl.TLSVersion.TLSv1_2)
        try:
            ok, _ = tls_check.handshake("localhost", srv.server_address[1], ssl.TLSVersion.TLSv1)
        finally:
            pass
        if not ok:
            srv.shutdown()
            self.skipTest("this OpenSSL cannot speak TLS 1.0, calibration impossible")
        self.assertTrue(any("TLS1.0 accepted" in p for p in self.run_check(srv, c)))

    def test_static_rsa_cipher_is_caught(self):
        c, k = cert(self.d, 365)
        srv = server(c, k, maxv=ssl.TLSVersion.TLSv1_2, ciphers="AES128-SHA:@SECLEVEL=0")
        self.assertTrue(any("weak cipher" in p for p in self.run_check(srv, c)))

    def test_tls13_refused_is_caught(self):
        c, k = cert(self.d, 365)
        self.assertTrue(any("TLS1.3 refused" in p for p in self.run_check(server(c, k, maxv=ssl.TLSVersion.TLSv1_2), c)))

    def test_short_certificate_is_caught(self):
        c, k = cert(self.d, 10)
        self.assertTrue(any("days left" in p for p in self.run_check(server(c, k), c)))

    def test_certificate_boundary_21_days(self):
        c, k = cert(self.d, 22)
        self.assertEqual(self.run_check(server(c, k), c), [])

    def test_hsts_variants(self):
        c, k = cert(self.d, 365)
        for h in (None, "max-age=63072000; includeSubDomains", "max-age=300; includeSubDomains; preload"):
            self.assertTrue(any("HSTS" in p for p in self.run_check(server(c, k, hsts=h), c)), h)

    def test_http_must_redirect(self):
        class R(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(self.server.code)
                self.send_header("Location", "https://localhost/")
                self.send_header("Content-Length", "0")
                self.end_headers()
            log_message = lambda *a: None
        c, k = cert(self.d, 365)
        for code, expect in ((301, False), (200, True)):
            r = http.server.HTTPServer(("127.0.0.1", 0), R)
            r.code = code
            threading.Thread(target=r.serve_forever, daemon=True).start()
            srv = server(c, k)
            try:
                bad = tls_check.check_host("localhost", srv.server_address[1], http_port=r.server_address[1], cafile=c)
            finally:
                srv.shutdown()
                r.shutdown()
            self.assertEqual(any("redirect" in p for p in bad), expect)


class Cutover(unittest.TestCase):
    def test_resolve(self):
        mumbai = lambda h: ("x-1.ap-south-1.elb.amazonaws.com", [], ["1.2.3.4"])
        self.assertEqual(cut.resolve(["api.x"], mumbai), [])
        self.assertTrue(cut.resolve(["api.x"], lambda h: ("nabd-f0zb.onrender.com", [], [])))
        self.assertTrue(cut.resolve(["api.x"], lambda h: ("y.us-east-1.elb.amazonaws.com", [], [])))

    def test_egress_log(self):
        self.assertEqual(cut.egress("GET api.nabd.example\nGET s3.ap-south-1.amazonaws.com\n"), [])
        self.assertEqual(len(cut.egress("ok\nPOST nabd-f0zb.onrender.com/v1/x\nGET nabd-nu.vercel.app/a.js\n")), 2)

    def test_csp(self):
        api = "https://api.nabd.example"
        self.assertEqual(cut.csp(f"default-src 'self'; connect-src 'self' {api}; frame-ancestors 'none'", api), [])
        self.assertTrue(cut.csp(f"connect-src 'self' {api} https://nabd-f0zb.onrender.com", api))
        self.assertTrue(cut.csp("default-src 'self'", api))

    def test_certificate(self):
        tpl = (ROOT / "docs/runbooks/render-destruction-certificate.md").read_text()
        self.assertTrue(cut.certificate(tpl))
        filled = "\n".join(f"- {f}: recorded" for f in cut.FIELDS)
        self.assertEqual(cut.certificate(filled), [])
        self.assertTrue(cut.certificate(filled.replace("Destroyed by: recorded", "Destroyed by:")))


if __name__ == "__main__":
    unittest.main(verbosity=2)
