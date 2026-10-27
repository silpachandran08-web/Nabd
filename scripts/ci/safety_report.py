#!/usr/bin/env python3
"""CLN-007 safety test report and release gate.

  safety_report.py report --sha SHA [--release TAG] [--junit DIR ...] [--playwright FILE ...] [--journeys FILE ...] [--dictionary REGION=VERSION ...]
                          [--require-signed] [--out DIR] [--root .]
      Joins docs/clinical-safety/hazard-log.csv to tests tagged @Hazard("H-xx") (JUnit sources + surefire XML) and to
      Playwright JSON-reporter results with an annotation {type: "hazard", description: "H-xx"}, and (--journeys, OPS-044) to
      the nightly journey JUnit (journeys-junit.xml) whose testcases carry <property name="hazard" value="H-xx"/>. Writes
      safety-test-report.json and .html. Exit 1 when any hazard has zero passing tests (ids listed), when a tag names an
      unknown hazard, or (with --require-signed) when hazard-log v1 has no valid CSO sign-off.
  safety_report.py paths BASE HEAD [--root .]
      Prints the files changed between two refs that fall under the @nabd/clinical-safety paths in .github/CODEOWNERS,
      and "changed=true|false" (append it to $GITHUB_OUTPUT).
"""
import argparse, csv, datetime as dt, hashlib, html, json, os, pathlib, re, subprocess, sys
import xml.etree.ElementTree as ET

LOG = "docs/clinical-safety/hazard-log.csv"
SIGNOFF = "docs/clinical-safety/signoff/hazard-log-v1.json"
SIGNOFF_FIELDS = ["cso_name", "approved_on", "approval_ref", "hazard_log_sha256"]
EXCEPTIONS = "docs/clinical-safety/signoff/hazard-exceptions.json"  # OPS-044: CSO-signed exceptions, one per hazard and release tag
EXCEPTION_FIELDS = ["hazard", "release", "reason", "cso_name", "approved_on", "approval_ref"]
HID = re.compile(r"H-\d+")
ANN = re.compile(r"@Hazard\s*\(([^)]*)\)")


def scan_java(src_root):
    """{(class FQN, method or None): [hazard ids]} from @Hazard on test classes and methods."""
    tags = {}
    for f in sorted(pathlib.Path(src_root).rglob("*.java")):
        text = f.read_text(encoding="utf-8")
        if "@Hazard" not in text:
            continue
        pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        pending, cls = [], None
        for line in text.splitlines():
            for a in ANN.findall(line):
                pending += HID.findall(a)
            m = re.match(r"\s*(?:(?:public|final|abstract|static)\s+)*(?:class|record)\s+(\w+)", line)
            if m and cls is None:  # shortcut: top-level class only; tags on @Nested classes attach to the outer class
                cls = (pkg.group(1) + "." if pkg else "") + m.group(1)
                if pending:
                    tags[(cls, None)] = pending
                pending = []
                continue
            m = re.search(r"\bvoid\s+(\w+)\s*\(", line)
            if m and cls:
                if pending:
                    tags[(cls, m.group(1))] = pending
                pending = []
    return tags


def junit_results(dirs):
    """[(classname, name, status)] from surefire/JUnit XML files."""
    out = []
    for d in dirs:
        for f in sorted(pathlib.Path(d).rglob("*.xml")):
            try:
                root = ET.parse(f).getroot()
            except ET.ParseError:
                continue
            for tc in root.iter("testcase"):
                kids = {c.tag for c in tc}
                status = "failed" if kids & {"failure", "error"} else "skipped" if "skipped" in kids else "passed"
                out.append((tc.get("classname", ""), tc.get("name", ""), status))
    return out


def playwright_results(files):
    """[(test id, [hazard ids], status)] from Playwright JSON reporter output."""
    out = []

    def walk(suite, path):
        for spec in suite.get("specs", []):
            for t in spec.get("tests", []):
                ids = [h for a in t.get("annotations", []) if a.get("type") == "hazard" for h in HID.findall(a.get("description", ""))]
                ids += HID.findall(" ".join(spec.get("tags", [])))
                if not ids:
                    continue
                st = [r.get("status") for r in t.get("results", [])]
                status = "passed" if st and st[-1] == "passed" else "skipped" if not st or st[-1] == "skipped" else "failed"
                out.append((f"{spec.get('file', path)} > {spec.get('title', '')}", ids, status))
        for s in suite.get("suites", []):
            walk(s, s.get("file", path))

    for f in files:
        for s in json.loads(pathlib.Path(f).read_text(encoding="utf-8")).get("suites", []):
            walk(s, s.get("file", ""))
    return out


def journey_results(files):
    """[(testcase id, [hazard ids], status)] from the journey JUnit export (report.mjs): testcases with <property name="hazard">."""
    out = []
    for f in files:
        for tc in ET.parse(f).getroot().iter("testcase"):
            ids = [p.get("value") for p in tc.iter("property") if p.get("name") == "hazard" and HID.fullmatch(p.get("value") or "")]
            if not ids:
                continue
            kids = {c.tag for c in tc}
            status = "failed" if "failure" in kids or "error" in kids else "skipped" if "skipped" in kids else "passed"
            out.append((f"journey {tc.get('name')}", ids, status))
    return out


def exceptions_for(root, release, hazard_ids):
    """({hazard: record} valid for this release, [problems]). A record is valid when every field is filled, approved_on is an ISO date, and it names this release tag."""
    p = root / EXCEPTIONS
    if not p.exists():
        return {}, []
    ok, problems = {}, []
    for rec in json.loads(p.read_text(encoding="utf-8")).get("exceptions", []):
        empty = [k for k in EXCEPTION_FIELDS if not str(rec.get(k) or "").strip()]
        try:
            dt.date.fromisoformat(str(rec.get("approved_on")))
            bad_date = False
        except ValueError:
            bad_date = True
        if empty or bad_date:
            problems.append(f"exception for {rec.get('hazard') or '?'} is not signed: " + (("fields empty: " + ", ".join(empty)) if empty else "approved_on is not an ISO date"))
        elif rec["hazard"] not in hazard_ids:
            problems.append(f"exception names unknown hazard {rec['hazard']}")
        elif rec["release"] == release:
            ok[rec["hazard"]] = rec
    return ok, problems


def sha256(p):
    return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()


def signoff_status(root):
    """(signed?, record or None, reason). Signed only if every field is filled and the hash matches the current log."""
    p = root / SIGNOFF
    if not p.exists():
        return False, None, f"{SIGNOFF} missing"
    rec = json.loads(p.read_text(encoding="utf-8"))
    empty = [k for k in SIGNOFF_FIELDS if not str(rec.get(k) or "").strip()]
    if empty:
        return False, rec, "sign-off fields empty: " + ", ".join(empty)
    try:
        dt.date.fromisoformat(rec["approved_on"])
    except ValueError:
        return False, rec, "approved_on is not an ISO date"
    if rec["hazard_log_sha256"] != sha256(root / LOG):
        return False, rec, "hazard-log.csv changed after sign-off (sha256 mismatch): re-sign"
    return True, rec, "signed"


def build(root, sha, release, junit_dirs, pw_files, dictionary, generated_at, journey_files=()):
    with open(root / LOG, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    known = [r["id"] for r in rows]
    tags = scan_java(root / "backend/src/test/java")
    results = junit_results(junit_dirs)
    tests = {h: [] for h in known}
    errors = []
    for (cls, meth), ids in sorted(tags.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
        hits = [(c, n, s) for c, n, s in results
                if (c == cls or c.startswith(cls + "$")) and (meth is None or n == meth or n.startswith((meth + "(", meth + "[")))]
        runs = hits or [(cls, meth or "*", "not run")]
        for h in ids:
            if h not in tests:
                errors.append(f"@Hazard(\"{h}\") on {cls}{'#' + meth if meth else ''} names a hazard not in hazard-log.csv")
                continue
            tests[h] += [{"id": f"{c}#{n}", "kind": "junit", "status": s} for c, n, s in runs]
    for tid, ids, status in playwright_results(pw_files):
        for h in ids:
            if h not in tests:
                errors.append(f"Playwright test '{tid}' names a hazard not in hazard-log.csv: {h}")
                continue
            tests[h].append({"id": tid, "kind": "playwright", "status": status})
    for tid, ids, status in journey_results(journey_files):
        for h in ids:
            if h not in tests:
                errors.append(f"journey '{tid}' names a hazard not in hazard-log.csv: {h}")
                continue
            tests[h].append({"id": tid, "kind": "journey", "status": status})
    excepted, exc_problems = exceptions_for(root, release, known)
    errors += exc_problems
    hazards = []
    for r in rows:
        ts = tests[r["id"]]
        hazards.append({"id": r["id"], "source": r["source"], "hazard": r["hazard"], "residual_risk": r["residual_risk"],
                        "log_status": r["status"], "verified": any(t["status"] == "passed" for t in ts),
                        "controls": {"existing": r["existing_control"], "planned": r["planned_control"]}, "verifying_test": r["verifying_test"],
                        "exception": excepted.get(r["id"]) if not any(t["status"] == "passed" for t in ts) else None,
                        "passing_tests": sum(t["status"] == "passed" for t in ts), "tests": ts})
    signed, rec, reason = signoff_status(root)
    unverified = [h["id"] for h in hazards if not h["verified"] and not h["exception"]]
    excepted_ids = [h["id"] for h in hazards if not h["verified"] and h["exception"]]
    return {
        "report": "safety-test-report", "schema": 1, "release": release, "commit_sha": sha, "generated_at": generated_at,
        "dictionary_versions": dictionary or {"unknown": "no active safety dictionary version supplied (CLN-004)"},
        "hazard_log": {"path": LOG, "sha256": sha256(root / LOG), "hazards": len(hazards),
                       "signoff": {"signed": signed, "reason": reason, "record": rec}},
        "summary": {"verified": len(hazards) - len(unverified) - len(excepted_ids), "unverified": unverified, "excepted": excepted_ids, "tag_errors": errors},
        "hazards": hazards,
    }


def to_html(rep):
    e = lambda v: html.escape(str(v))
    s = rep["summary"]
    rows = "".join(
        f"<tr><td>{e(h['id'])}</td><td>{e(h['hazard'])}<br><small>Existing control: {e(h['controls']['existing'])}<br>Planned control: {e(h['controls']['planned'])}</small></td>"
        f"<td>{'VERIFIED' if h['verified'] else ('EXCEPTION ' + e(h['exception']['cso_name']) + ' ' + e(h['exception']['approved_on']) + ': ' + e(h['exception']['reason'])) if h['exception'] else 'UNVERIFIED'}</td>"
        f"<td><ul>{''.join(f'<li>{e(t['status'])}: {e(t['id'])}</li>' for t in h['tests']) or '<li>none</li>'}</ul></td></tr>"
        for h in rep["hazards"])
    dicts = ", ".join(f"{e(k)}={e(v)}" for k, v in rep["dictionary_versions"].items())
    so = rep["hazard_log"]["signoff"]
    return (f"<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Safety test report {e(rep['release'])}</title>"
            f"<h1>Safety test report</h1><p>Release {e(rep['release'])}, commit {e(rep['commit_sha'])}, generated {e(rep['generated_at'])}</p>"
            f"<p>Dictionary versions: {dicts}</p><p>Hazard log sha256 {e(rep['hazard_log']['sha256'])}; CSO sign-off: {e(so['reason'])}</p>"
            f"<p>{s['verified']} of {rep['hazard_log']['hazards']} hazards verified. Unverified: {e(', '.join(s['unverified']) or 'none')}. Released on a CSO-signed exception: {e(', '.join(s['excepted']) or 'none')}</p>"
            + (f"<p>Tag errors: {e('; '.join(s['tag_errors']))}</p>" if s["tag_errors"] else "")
            + f"<table><thead><tr><th>Hazard</th><th>Description</th><th>Status</th><th>Verifying tests</th></tr></thead><tbody>{rows}</tbody></table></html>\n")


def gate_errors(rep, require_signed):
    errs = [f"hazard {h} has no passing verifying test" for h in rep["summary"]["unverified"]] + rep["summary"]["tag_errors"]
    if require_signed and not rep["hazard_log"]["signoff"]["signed"]:
        errs.append("hazard log v1 is not signed by the CSO: " + rep["hazard_log"]["signoff"]["reason"])
    return errs


def safety_patterns(root):
    pats = []
    for line in (root / ".github/CODEOWNERS").read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if parts and not parts[0].startswith("#") and "@nabd/clinical-safety" in parts[1:]:
            pats.append(parts[0].lstrip("/"))
    return pats


def safety_paths(changed, pats):
    return [f for f in changed if any(f == p or (p.endswith("/") and f.startswith(p)) for p in pats)]


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("report")
    r.add_argument("--root", default=".")
    r.add_argument("--sha", required=True)
    r.add_argument("--release", default="unreleased")
    r.add_argument("--junit", action="append", default=[])
    r.add_argument("--playwright", action="append", default=[])
    r.add_argument("--journeys", action="append", default=[], help="journey JUnit export (journeys-junit.xml)")
    r.add_argument("--dictionary", action="append", default=[], help="REGION=VERSION")
    r.add_argument("--require-signed", action="store_true")
    r.add_argument("--out", default="safety-report")
    p = sub.add_parser("paths")
    p.add_argument("base")
    p.add_argument("head")
    p.add_argument("--root", default=".")
    a = ap.parse_args(argv)
    root = pathlib.Path(a.root)
    if a.cmd == "paths":
        changed = subprocess.run(["git", "-C", str(root), "diff", "--name-only", a.base, a.head],
                                 check=True, capture_output=True, text=True).stdout.split()
        hit = safety_paths(changed, safety_patterns(root))
        print("\n".join(hit))
        print(f"changed={'true' if hit else 'false'}")
        return 0
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    gen = (dt.datetime.fromtimestamp(int(epoch), dt.timezone.utc) if epoch else dt.datetime.now(dt.timezone.utc)).isoformat(timespec="seconds")
    rep = build(root, a.sha, a.release, a.junit, a.playwright, dict(d.split("=", 1) for d in a.dictionary), gen, a.journeys)
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "safety-test-report.json").write_text(json.dumps(rep, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (out / "safety-test-report.html").write_text(to_html(rep), encoding="utf-8")
    errs = gate_errors(rep, a.require_signed)
    print("\n".join(errs) if errs else f"safety gate OK: {rep['summary']['verified']} hazards verified")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
