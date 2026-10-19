#!/usr/bin/env python3
"""OPS-037 dynamic checks on previews (run from repo root).
  dast.py zap-gate   ZAP_JSON          markdown summary; exit 1 on any High alert or a token-bearing URL; Mediums listed with owners
  dast.py zap-issues ZAP_JSON          one JSON line per High alert ({"title","body"}) for the nightly job to open defects
  dast.py headers    WEB_URL API_URL   live checks: CSP + HSTS on both, CORS preflight per verb from STAFF_ORIGIN, foreign
                                       origin refused, GET /v1/appointments?limit=0 -> 400 Problem+JSON (needs DAST_TOKEN)
  dast.py st-gate    RC                Schemathesis exit code -> job result (blocking only from ci/dast-gate.json date)
"""
import datetime as dt, json, os, re, sys, urllib.error, urllib.request

GATE = "ci/dast-gate.json"
OWNERS = "ci/zap-owners.json"
VERBS = ("GET", "POST", "PUT", "PATCH", "DELETE")
TOKEN_IN_URL = re.compile(r"[?&#](access_token|id_token|refresh_token|token|jwt|bearer)=", re.I)
RISK = {"3": "High", "2": "Medium", "1": "Low", "0": "Informational"}
today = lambda: dt.date.fromisoformat(os.environ.get("GATE_TODAY", dt.date.today().isoformat()))


def alerts(report):
    for site in report.get("site", []):
        for a in site.get("alerts", []):
            yield a


def zap_gate(report, owners):
    highs, mediums, leaks = [], [], []
    for a in alerts(report):
        if a.get("riskcode") == "3": highs.append(a)
        elif a.get("riskcode") == "2": mediums.append(a)
        for i in a.get("instances", []):
            if TOKEN_IN_URL.search(i.get("uri", "")): leaks.append(i["uri"].split("?")[0] + "?<redacted>")
    owner = lambda a: owners.get("plugins", {}).get(str(a.get("pluginid")), owners.get("default", "unowned"))
    md = ["<!-- zap-baseline -->", "### ZAP baseline", "", f"High: {len(highs)}, Medium: {len(mediums)}, token-bearing URLs: {len(leaks)}", ""]
    if highs: md += ["High (fails the PR):"] + [f"- {a['alert']} (plugin {a.get('pluginid')}, {len(a.get('instances', []))} instance(s))" for a in highs]
    if mediums:
        md += ["", "| Medium alert | plugin | owner | instances |", "|---|---|---|---|"]
        md += [f"| {a['alert']} | {a.get('pluginid')} | {owner(a)} | {len(a.get('instances', []))} |" for a in mediums]
    if leaks: md += ["", "Token in URL (fails the PR):"] + [f"- {u}" for u in sorted(set(leaks))]
    fails = bool(highs or leaks)
    md += ["", "Gate: FAIL" if fails else "Gate: pass"]
    return "\n".join(md), fails


def zap_issues(report):
    for a in alerts(report):
        if a.get("riskcode") == "3":
            uris = sorted({i.get("uri", "").split("?")[0] for i in a.get("instances", [])})[:20]
            yield {"title": f"[ZAP High] {a['alert']} (plugin {a.get('pluginid')})",
                   "body": "Found by the nightly authenticated ZAP full scan (OPS-037).\n\nURLs (query strings removed):\n" + "\n".join(f"- {u}" for u in uris)}


def fetch(url, method="GET", headers=None):
    req = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as r: return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()


def header_checks(web, api, staff_origin, token=None, f=fetch):
    """Return a list of failure strings (empty = pass)."""
    fails = []
    for name, url in (("web", web), ("api", api.rstrip("/") + "/v1/appointments")):
        _, h, _ = f(url)
        for hdr in ("content-security-policy", "strict-transport-security"):
            if hdr not in h: fails.append(f"{name}: missing {hdr}")
    target = api.rstrip("/") + "/v1/appointments/00000000-0000-0000-0000-000000000001"
    for verb in VERBS:
        s, h, _ = f(target, "OPTIONS", {"Origin": staff_origin, "Access-Control-Request-Method": verb,
                                         "Access-Control-Request-Headers": "authorization,content-type,idempotency-key"})
        if s >= 400 or verb not in h.get("access-control-allow-methods", "").upper() or h.get("access-control-allow-origin") != staff_origin:
            fails.append(f"CORS preflight {verb} from staff origin refused (status {s})")
    s, h, _ = f(target, "OPTIONS", {"Origin": "https://evil.example.com", "Access-Control-Request-Method": "PUT"})
    if h.get("access-control-allow-origin") in ("*", "https://evil.example.com"): fails.append("CORS: foreign origin allowed")
    if token:
        s, h, body = f(api.rstrip("/") + "/v1/appointments?limit=0", "GET", {"Authorization": "Bearer " + token})
        if s != 400 or "application/problem+json" not in h.get("content-type", ""):
            fails.append(f"GET /v1/appointments?limit=0 -> {s} {h.get('content-type')}, expected 400 Problem+JSON")
    return fails


def st_blocking():
    d = json.load(open(GATE)).get("schemathesis_blocking_from")
    return d is not None and dt.date.fromisoformat(d) <= today()


def main(a):
    if not a: sys.exit(__doc__)
    if a[0] == "zap-gate":
        owners = json.load(open(OWNERS)) if os.path.exists(OWNERS) else {}
        md, fails = zap_gate(json.load(open(a[1])), owners)
        print(md); sys.exit(1 if fails else 0)
    if a[0] == "zap-issues":
        for i in zap_issues(json.load(open(a[1]))): print(json.dumps(i))
        return
    if a[0] == "headers":
        fails = header_checks(a[1], a[2], os.environ.get("STAFF_ORIGIN", a[1].rstrip("/")), os.environ.get("DAST_TOKEN"))
        print("\n".join(fails) or "headers/CORS: pass"); sys.exit(1 if fails else 0)
    if a[0] == "st-gate":
        rc = int(a[1]); block = st_blocking()
        print(f"schemathesis exit {rc}; {'blocking' if block else 'report only until ' + GATE + ' schemathesis_blocking_from (S4)'}")
        sys.exit(1 if rc and block else 0)
    sys.exit(__doc__)


if __name__ == "__main__": main(sys.argv[1:])
