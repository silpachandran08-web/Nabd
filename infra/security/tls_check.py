#!/usr/bin/env python3
"""OPS-056 edge TLS check (testssl-style subset), run against the public endpoints in CI.
  python3 infra/security/tls_check.py api.example.org [app.example.org ...] [--port 443] [--http-port 80] [--cafile f.pem]
Fails when: TLS 1.0/1.1 accepted; TLS 1.2 or 1.3 refused; a weak cipher (RC4, 3DES, DES, NULL, EXPORT, anonymous, MD5,
static-RSA key exchange) accepted on TLS 1.2; HSTS lacks max-age>=1y, includeSubDomains or preload; the certificate has
<= 20 days left; port 80 does not redirect to https.
Limit: a refusal is only evidence if THIS client can speak the old protocol (CI runner OpenSSL); the test suite calibrates
against a server that accepts TLS 1.0, and the CI job also runs `openssl s_client -tls1_1` as a second opinion.
"""
import http.client
import socket
import ssl
import sys
import time

WEAK = "RC4:3DES:DES:NULL:EXPORT:aNULL:MD5:kRSA"
MIN_DAYS = 21


def _ctx(version, ciphers=None):
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    c.check_hostname, c.verify_mode = False, ssl.CERT_NONE  # protocol probe only; the chain is verified in check_host
    c.minimum_version = c.maximum_version = version
    try:
        c.set_ciphers(ciphers or "ALL:@SECLEVEL=0")
    except ssl.SSLError:
        return None  # this client has no such cipher: nothing to offer, cannot test
    return c


def handshake(host, port, version, ciphers=None, timeout=10):
    """(True, cipher_name) if the server completes a handshake at exactly this version, else (False, reason)."""
    c = _ctx(version, ciphers)
    if c is None:
        return False, "client cannot offer"
    try:
        with socket.create_connection((host, port), timeout=timeout) as s, c.wrap_socket(s, server_hostname=host) as t:
            return True, t.cipher()[0]
    except (ssl.SSLError, OSError) as e:
        return False, type(e).__name__


def check_host(host, port=443, http_port=None, cafile=None, now=None):
    out = []
    for v, name in ((ssl.TLSVersion.TLSv1, "TLS1.0"), (ssl.TLSVersion.TLSv1_1, "TLS1.1")):
        ok, why = handshake(host, port, v)
        if ok:
            out.append(f"{host}: {name} accepted")
    for v, name in ((ssl.TLSVersion.TLSv1_2, "TLS1.2"), (ssl.TLSVersion.TLSv1_3, "TLS1.3")):
        ok, why = handshake(host, port, v)
        if not ok:
            out.append(f"{host}: {name} refused ({why})")
    ok, cipher = handshake(host, port, ssl.TLSVersion.TLSv1_2, WEAK)
    if ok:
        out.append(f"{host}: weak cipher accepted on TLS1.2: {cipher}")
    c = ssl.create_default_context(cafile=cafile)  # verifies chain and hostname
    c.minimum_version = ssl.TLSVersion.TLSv1_2
    try:
        conn = http.client.HTTPSConnection(host, port, context=c, timeout=10)
        conn.connect()  # verifies chain + hostname
        cert = conn.sock.getpeercert()
        conn.request("GET", "/")
        hsts = conn.getresponse().getheader("Strict-Transport-Security") or ""
        conn.close()
    except (ssl.SSLError, OSError) as e:
        return out + [f"{host}: HTTPS request failed ({type(e).__name__}: {e})"]
    age = next((int(p.split("=")[1]) for p in (x.strip() for x in hsts.split(";")) if p.startswith("max-age=") and p[8:].isdigit()), 0)
    if age < 31536000 or "includesubdomains" not in hsts.lower() or "preload" not in hsts.lower():
        out.append(f"{host}: HSTS must be max-age>=31536000; includeSubDomains; preload (got {hsts!r})")
    days = (ssl.cert_time_to_seconds(cert["notAfter"]) - (now or time.time())) / 86400
    if days < MIN_DAYS:
        out.append(f"{host}: certificate has {days:.1f} days left (> 20 required)")
    if http_port:
        try:
            h = http.client.HTTPConnection(host, http_port, timeout=10)
            h.request("GET", "/")
            r = h.getresponse()
            loc = r.getheader("Location") or ""
            if r.status not in (301, 308) or not loc.startswith("https://"):
                out.append(f"{host}: port {http_port} must redirect to https (got {r.status} {loc!r})")
        except OSError as e:
            out.append(f"{host}: port {http_port} unreachable ({type(e).__name__})")
    return out


if __name__ == "__main__":
    a = sys.argv[1:]
    opt = {k: a[a.index(k) + 1] for k in ("--port", "--http-port", "--cafile") if k in a}
    hosts = [x for i, x in enumerate(a) if not x.startswith("--") and (i == 0 or a[i - 1] not in opt.keys())]
    if not hosts:
        sys.exit(__doc__)
    bad = [p for h in hosts for p in check_host(h, int(opt.get("--port", 443)), int(opt["--http-port"]) if "--http-port" in opt else None, opt.get("--cafile"))]
    print("\n".join(bad) if bad else f"tls ok: {', '.join(hosts)}")
    sys.exit(1 if bad else 0)
