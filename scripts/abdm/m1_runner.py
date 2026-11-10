#!/usr/bin/env python3
"""IDC-019: ABDM M1 sandbox test-case runner and evidence pack (stdlib only).

  m1_runner.py check                                   validate docs/compliance/abdm/m1-test-cases.json
  m1_runner.py run --base URL --out DIR [--manual F]   run the auto cases against a Nabd backend wired to the ABDM sandbox;
                                                       merge manual results (JSON {case_id: {"result": "pass"|"fail", "evidence": [paths], "note": ""}})
  m1_runner.py scan DIR                                fail when the evidence pack holds anything that looks like real PHI
  m1_runner.py gate DIR                                exit 0 only when every mandatory case passed, the pack is clean and
                                                       docs/compliance/abdm/m1-submission.json records the NHA acknowledgement
                                                       and an empanelled auditor booked in Phase 2

The staff access token comes from NABD_M1_TOKEN (never stored); ${VAR} placeholders in a case are filled from the environment.
Request and response bodies are redacted before they are written: ids and codes keep their last 4 characters, names, phones,
Aadhaar, OTPs and emails are masked. Use sandbox test identities only.
"""
import argparse, datetime, json, os, pathlib, re, sys, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
CASES = ROOT / "docs/compliance/abdm/m1-test-cases.json"
SUBMISSION = ROOT / "docs/compliance/abdm/m1-submission.json"
SENSITIVE_KEYS = re.compile(r"(name|phone|mobile|aadhaar|otp|email|address|dob|birth|token|authorization)", re.I)
LONG_DIGITS = re.compile(r"\d{10,}")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
ABHA_NUMBER = re.compile(r"\b\d{2}-?\d{4}-?\d{4}-?\d{4}\b")


def load_cases(path=CASES):
    return json.loads(pathlib.Path(path).read_text())["cases"]


def check(cases):
    """Problems in the checklist (empty list = valid)."""
    out, seen = [], set()
    for c in cases:
        cid = c.get("id")
        if not cid or cid in seen:
            out.append(f"duplicate or missing id: {cid}")
        seen.add(cid)
        if c.get("kind") not in ("auto", "manual"):
            out.append(f"{cid}: kind must be auto or manual")
        if not isinstance(c.get("mandatory"), bool) or not c.get("title"):
            out.append(f"{cid}: mandatory (bool) and title are required")
        if "nha_ref" not in c:
            out.append(f"{cid}: nha_ref key is required (null until mapped from the NHA sheet)")
        if c.get("kind") == "auto":
            r, e = c.get("request") or {}, c.get("expect") or {}
            if r.get("method") not in ("GET", "POST", "PUT", "PATCH") or not str(r.get("path", "")).startswith("/"):
                out.append(f"{cid}: auto case needs request.method and an absolute request.path")
            if not isinstance(e.get("status"), int):
                out.append(f"{cid}: auto case needs expect.status")
        elif not c.get("steps"):
            out.append(f"{cid}: manual case needs steps")
    return out


def fill(value, env):
    if isinstance(value, str):
        return re.sub(r"\$\{(\w+)\}", lambda m: env.get(m.group(1), ""), value)
    if isinstance(value, dict):
        return {k: fill(v, env) for k, v in value.items()}
    if isinstance(value, list):
        return [fill(v, env) for v in value]
    return value


def mask(s):
    s = str(s)
    return "*" * max(0, len(s) - 4) + s[-4:] if len(s) > 4 else "****"


def redact(value, key=""):
    """Masks anything sensitive by key, and long digit runs and emails anywhere."""
    if isinstance(value, dict):
        return {k: redact(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, key) for v in value]
    if isinstance(value, str):
        if SENSITIVE_KEYS.search(key):
            return mask(value)
        return EMAIL.sub("[email]", LONG_DIGITS.sub(lambda m: mask(m.group(0)), value))
    return value


def scan(directory):
    """Lines in the pack that look like unmasked PHI: a 10+ digit run, an email, or an ABHA number."""
    hits = []
    for f in sorted(pathlib.Path(directory).rglob("*")):
        if f.is_file() and f.suffix in (".json", ".md", ".txt", ".log"):
            for n, line in enumerate(f.read_text(errors="replace").splitlines(), 1):
                if LONG_DIGITS.search(line) or EMAIL.search(line) or ABHA_NUMBER.search(line):
                    hits.append(f"{f.name}:{n}")
    return hits


def call(base, case, token, env, opener=urllib.request.urlopen):
    r = fill(case["request"], env)
    data = json.dumps(r["body"]).encode() if "body" in r else None
    req = urllib.request.Request(base.rstrip("/") + r["path"], data=data, method=r["method"])
    req.add_header("Content-Type", "application/json")
    if case.get("auth", True) and token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with opener(req, timeout=30) as res:
            status, raw = res.status, res.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    try:
        body = json.loads(raw or b"null")
    except ValueError:
        body = {"_raw": raw.decode(errors="replace")[:500]}
    return {"method": r["method"], "path": r["path"], "body": redact(r.get("body"))}, status, body


def verdict(case, status, body):
    e = case["expect"]
    if status != e["status"]:
        return False, f"status {status}, expected {e['status']}"
    for k, v in (e.get("json") or {}).items():
        if not isinstance(body, dict) or body.get(k) != v:
            return False, f"{k} != {v!r}"
    for k in e.get("json_keys") or []:
        if not isinstance(body, dict) or k not in body:
            return False, f"missing {k}"
    return True, "ok"


def run(base, out, manual, cases, env=os.environ, opener=urllib.request.urlopen):
    out = pathlib.Path(out)
    (out / "cases").mkdir(parents=True, exist_ok=True)
    manual = manual or {}
    results = []
    for c in cases:
        if c["kind"] == "auto":
            try:
                req, status, body = call(base, c, env.get("NABD_M1_TOKEN", ""), env, opener)
                ok, why = verdict(c, status, body)
                (out / "cases" / f"{c['id']}.json").write_text(json.dumps({"request": req, "status": status, "response": redact(body)}, indent=1))
            except OSError as e:
                ok, why = False, f"unreachable: {e.__class__.__name__}"
        else:
            m = manual.get(c["id"]) or {}
            ok, why = m.get("result") == "pass", m.get("note") or ("not run" if not m else m.get("result", ""))
            if m.get("evidence"):
                why += f" (evidence: {', '.join(m['evidence'])})"
        results.append({"id": c["id"], "nha_ref": c.get("nha_ref"), "mandatory": c["mandatory"], "title": c["title"], "pass": ok, "detail": redact(why)})
    summary = {"base": base, "ran_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"), "results": results,
               "mandatory_total": sum(r["mandatory"] for r in results), "mandatory_passed": sum(r["mandatory"] and r["pass"] for r in results)}
    (out / "summary.json").write_text(json.dumps(summary, indent=1))
    lines = [f"# ABDM M1 sandbox run {summary['ran_at']}", "", f"Mandatory passed: {summary['mandatory_passed']}/{summary['mandatory_total']}", "",
             "| Case | NHA ref | Mandatory | Result | Detail |", "|---|---|---|---|---|"]
    lines += [f"| {r['id']} {r['title']} | {r['nha_ref'] or 'unmapped'} | {'yes' if r['mandatory'] else 'no'} | {'PASS' if r['pass'] else 'FAIL'} | {r['detail']} |" for r in results]
    (out / "summary.md").write_text("\n".join(lines) + "\n")
    return summary


def gate(directory, submission_path=SUBMISSION):
    """Reasons the M1 exit item is not met (empty list = met)."""
    reasons = []
    s = pathlib.Path(directory) / "summary.json"
    if not s.exists():
        return ["no sandbox run in " + str(directory)]
    summary = json.loads(s.read_text())
    failed = [r["id"] for r in summary["results"] if r["mandatory"] and not r["pass"]]
    if failed:
        reasons.append("mandatory cases not passed: " + ", ".join(failed))
    unmapped = [r["id"] for r in summary["results"] if r["mandatory"] and not r.get("nha_ref")]
    if unmapped:
        reasons.append("mandatory cases not mapped to the NHA sheet: " + ", ".join(unmapped))
    hits = scan(directory)
    if hits:
        reasons.append("evidence pack holds possible PHI: " + ", ".join(hits[:5]))
    sub = json.loads(pathlib.Path(submission_path).read_text())
    if not (sub.get("submission") or {}).get("nha_acknowledgement_ref"):
        reasons.append("NHA acknowledgement reference not recorded")
    audit = sub.get("security_audit") or {}
    booked, phase2 = _date(audit.get("audit_date")), _date(sub.get("phase2_starts_on"))
    if not audit.get("auditor") or audit.get("cert_in_empanelled") is not True or booked is None or phase2 is None or booked < phase2:
        reasons.append("CERT-In empanelled auditor with a Phase 2 date not recorded")
    return reasons


def _date(v):
    try:
        return datetime.date.fromisoformat(v or "")
    except (TypeError, ValueError):
        return None


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check")
    r = sub.add_parser("run")
    r.add_argument("--base", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--manual")
    sub.add_parser("scan").add_argument("dir")
    sub.add_parser("gate").add_argument("dir")
    a = p.parse_args(argv)
    cases = load_cases()
    if a.cmd == "check":
        problems = check(cases)
        print(f"m1 checklist: {len(cases)} case(s), {len(problems)} problem(s)")
        print("\n".join(problems))
        return 1 if problems else 0
    if a.cmd == "run":
        if check(cases):
            print("checklist invalid: run `check` first")
            return 1
        s = run(a.base, a.out, json.loads(pathlib.Path(a.manual).read_text()) if a.manual else None, cases)
        print(f"mandatory passed {s['mandatory_passed']}/{s['mandatory_total']}; pack in {a.out}")
        return 0 if s["mandatory_passed"] == s["mandatory_total"] else 1
    if a.cmd == "scan":
        hits = scan(a.dir)
        print(f"evidence scan: {len(hits)} possible PHI line(s)")
        print("\n".join(hits))
        return 1 if hits else 0
    reasons = gate(a.dir)
    print("M1 exit item: " + ("MET" if not reasons else "NOT MET"))
    print("\n".join(reasons))
    return 1 if reasons else 0


if __name__ == "__main__":
    sys.exit(main())
