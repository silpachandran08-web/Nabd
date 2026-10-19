#!/usr/bin/env python3
"""Security baseline helper (OPS-031).
  baseline.py validate [file]            every entry needs owner/added/expires/reason, none expired
  baseline.py npm-audit audit.json [file] fail on High/Critical advisories not baselined (scanner "npm")
"""
import json, sys, datetime as dt

BASE = ".github/security-baseline.json"
REQ = ("id", "scanner", "owner", "added", "expires", "reason")

def load(path):
    return json.load(open(path))["findings"]

def validate(entries, today=None):
    today = today or dt.date.today()
    errs = []
    for e in entries:
        miss = [k for k in REQ if not e.get(k)]
        if miss:
            errs.append(f"{e.get('id','?')}: missing {miss}"); continue
        a, x = dt.date.fromisoformat(e["added"]), dt.date.fromisoformat(e["expires"])
        if x < today: errs.append(f"{e['id']}: baseline expired {x}")
        if (x - a).days > 90: errs.append(f"{e['id']}: expiry more than 90 days after added")
    return errs

def new_npm_findings(audit, entries, today=None):
    ok = {e["id"] for e in entries if e["scanner"] == "npm" and not validate([e], today)}
    out = set()
    for name, v in audit.get("vulnerabilities", {}).items():
        if v.get("severity") not in ("high", "critical"): continue
        ids = {str(x["source"]) for x in v.get("via", []) if isinstance(x, dict)} or {name}
        if not ids <= ok: out.add(name)
    return sorted(out)

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "validate":
        errs = validate(load(sys.argv[2] if len(sys.argv) > 2 else BASE))
    elif cmd == "npm-audit":
        errs = [f"new high/critical npm finding: {n}" for n in
                new_npm_findings(json.load(open(sys.argv[2])), load(sys.argv[3] if len(sys.argv) > 3 else BASE))]
    else:
        sys.exit(__doc__)
    print("\n".join(errs) or "ok"); sys.exit(1 if errs else 0)
