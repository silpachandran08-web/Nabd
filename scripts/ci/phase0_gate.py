#!/usr/bin/env python3
"""OPS-048 Phase 0 exit gate.

  phase0_gate.py controls [--junit DIR ...] [--root .]
      Completeness check of docs/security/controls.yaml (67 controls, each with a test id or evidence link, test ids
      that exist in backend/src/test/java) and the verified percentage from the latest JUnit results. Exit 1 if incomplete.
  phase0_gate.py pack --sha SHA [--junit DIR ...] [--evidence FILE] [--out DIR] [--root .]
      Gate pack: one section per criterion with PASS/FAIL and evidence links (docs/gates/README.md). Exit 1 if any fails.
  phase0_gate.py record [--root .]
      Checks docs/gates/phase0.md: a 'passed' decision needs a date, the four attendees and the pack commit SHA.
"""
import argparse, datetime as dt, json, os, pathlib, re, sys
import yaml

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import safety_report as sr

CONTROLS = "docs/security/controls.yaml"
RECORD = "docs/gates/phase0.md"
THRESHOLD = 60.0
EXPECTED_CONTROLS = 67
CONSENT_404_TESTS = ["ClinicalReadScopeApiTest"]  # IDC-001 as merged (backend/src/test/.../clinical/ClinicalReadScopeApiTest.java)
READ_LOG_TESTS = ["PhiReadAuditTest"]  # PLT-039 spec
P95_LIMIT_MS = 300


def test_index(junit_dirs):
    """{simple class name: [statuses]} and {Class#method: [statuses]} from JUnit XML."""
    idx = {}
    for cls, name, status in sr.junit_results(junit_dirs):
        simple = cls.rsplit(".", 1)[-1].split("$")[0]
        idx.setdefault(simple, []).append(status)
        idx.setdefault(f"{simple}#{re.split(r'[(\[]', name)[0]}", []).append(status)
    return idx


def test_passed(tid, idx):
    st = idx.get(tid, [])
    return bool(st) and "failed" not in st and "passed" in st


def known_test_classes(root):
    return {p.stem for p in (root / "backend/src/test/java").rglob("*.java")}


def evaluate_controls(root, junit_dirs):
    controls = yaml.safe_load((root / CONTROLS).read_text(encoding="utf-8"))["controls"]
    idx, known = test_index(junit_dirs), known_test_classes(root)
    errors, rows = [], []
    ids = [c["id"] for c in controls]
    if len(controls) != EXPECTED_CONTROLS:
        errors.append(f"expected {EXPECTED_CONTROLS} controls, found {len(controls)}")
    if len(set(ids)) != len(ids):
        errors.append("duplicate control ids")
    for c in controls:
        tests, ev = c.get("tests") or [], c.get("evidence") or []
        links = [e for e in ev if (e.get("link") or "").strip()]
        if not tests and not links:
            errors.append(f"{c['id']}: no test id or evidence link")
        if not str(c.get("owner") or "").strip():
            errors.append(f"{c['id']}: no owner")
        for t in tests:
            if t.split("#")[0] not in known:
                errors.append(f"{c['id']}: test {t} not found in backend/src/test/java")
        verified = bool(tests or links) and all(test_passed(t, idx) for t in tests) \
            and all(e.get("accepted_by") and e.get("accepted_on") for e in links)
        rows.append({"id": c["id"], "control": c["control"], "owner": c["owner"], "tests": tests,
                     "evidence": [e["link"] for e in links], "verified": verified})
    n = sum(r["verified"] for r in rows)
    pct = round(100.0 * n / len(rows), 1) if rows else 0.0
    return {"total": len(rows), "verified": n, "percent": pct, "mapping_errors": errors,
            "unverified": [{k: r[k] for k in ("id", "control", "owner")} for r in rows if not r["verified"]], "controls": rows}


def crit(cid, title, ok, evidence, detail):
    return {"id": cid, "title": title, "status": "PASS" if ok else "FAIL", "evidence": evidence, "detail": detail}


def build_pack(root, sha, junit_dirs, evidence, generated_at):
    ev = evidence or {}
    idx = test_index(junit_dirs)
    cs = evaluate_controls(root, junit_dirs)
    d, pc, rep, k6 = ev.get("defects"), ev.get("prod_config"), ev.get("two_replicas"), ev.get("k6")
    signed, rec, reason = sr.signoff_status(root)
    tests_detail = lambda ts: "; ".join(f"{t}: {'passed' if test_passed(t, idx) else 'no passing result'}" for t in ts)
    k6_val = lambda key: (k6 or {}).get(key)
    criteria = [
        crit(1, "Zero open S1 and zero open security S2 defects",
             bool(d) and d.get("open_s1") == 0 and d.get("open_security_s2") == 0,
             [d["link"]] if d and d.get("link") else [],
             f"open S1={d.get('open_s1')}, open security S2={d.get('open_security_s2')}" if d else "no defect query evidence supplied"),
        crit(2, "Two replicas pass the suite and the prod-config job",
             bool(pc and rep) and pc.get("conclusion") == "success" and rep.get("conclusion") == "success",
             [x["link"] for x in (pc, rep) if x and x.get("link")],
             f"prod-config={pc.get('conclusion') if pc else 'no evidence'}, two-replica run={rep.get('conclusion') if rep else 'no evidence'}"),
        crit(3, f"Registry p95 < {P95_LIMIT_MS} ms at 500k patients",
             isinstance(k6_val("registry_p95_ms"), (int, float)) and k6_val("registry_p95_ms") < P95_LIMIT_MS,
             [k6["link"]] if k6 and k6.get("link") else [], f"registry p95={k6_val('registry_p95_ms')} ms" if k6 else "no k6 report supplied"),
        crit(4, f"Check-in p95 < {P95_LIMIT_MS} ms",
             isinstance(k6_val("checkin_p95_ms"), (int, float)) and k6_val("checkin_p95_ms") < P95_LIMIT_MS,
             [k6["link"]] if k6 and k6.get("link") else [], f"check-in p95={k6_val('checkin_p95_ms')} ms" if k6 else "no k6 report supplied"),
        crit(5, f"Security Controls >= {THRESHOLD:.0f}% verified", cs["percent"] >= THRESHOLD and not cs["mapping_errors"], [CONTROLS],
             f"{cs['verified']} of {cs['total']} verified ({cs['percent']}%); mapping errors: {len(cs['mapping_errors'])}"),
        crit(6, "Hazard log v1 signed by the CSO", signed, [sr.SIGNOFF], reason
             + (f" ({rec.get('cso_name')}, {rec.get('approved_on')}, {rec.get('approval_ref')})" if signed else "")),
        crit(7, "Clinical reads of a withdrawn-consent patient return 404", all(test_passed(t, idx) for t in CONSENT_404_TESTS),
             CONSENT_404_TESTS, tests_detail(CONSENT_404_TESTS)),
        crit(8, "Every clinical GET writes a read-log row", all(test_passed(t, idx) for t in READ_LOG_TESTS),
             READ_LOG_TESTS, tests_detail(READ_LOG_TESTS)),
    ]
    ok = all(c["status"] == "PASS" for c in criteria)
    return {"pack": "phase0-gate-pack", "schema": 1, "commit_sha": sha, "generated_at": generated_at,
            "result": "PASS" if ok else "FAIL", "real_data_onboarding": "may open after the gate meeting" if ok else "stays blocked",
            "criteria": criteria, "security_controls": {k: cs[k] for k in ("total", "verified", "percent", "mapping_errors", "unverified")}}


def to_md(p):
    out = [f"# Phase 0 exit gate pack", "", f"Commit `{p['commit_sha']}`, generated {p['generated_at']}.", "",
           f"**Result: {p['result']}.** Real-data onboarding {p['real_data_onboarding']} "
           f"(`app.provisioning.real-data-onboarding`, see docs/gates/README.md).", "",
           "| # | Criterion | Status |", "|---|---|---|"]
    out += [f"| {c['id']} | {c['title']} | {'PASS (green)' if c['status'] == 'PASS' else 'FAIL (red)'} |" for c in p["criteria"]]
    for c in p["criteria"]:
        out += ["", f"## {c['id']}. {c['title']}: {'PASS (green)' if c['status'] == 'PASS' else 'FAIL (red)'}", "", c["detail"], "",
                "Evidence: " + (", ".join(f"[{e}]({e})" if "/" in e else f"`{e}`" for e in c["evidence"]) or "none supplied")]
        if c["id"] == 5 and c["status"] == "FAIL":
            sc = p["security_controls"]
            out += ["", "Mapping errors:" if sc["mapping_errors"] else ""] + [f"- {e}" for e in sc["mapping_errors"]]
            out += ["", "Unverified controls:", "", "| Control | Owner | Description |", "|---|---|---|"]
            out += [f"| {u['id']} | {u['owner']} | {u['control']} |" for u in sc["unverified"]]
    return "\n".join(out) + "\n"


def check_record(root):
    text = (root / RECORD).read_text(encoding="utf-8")
    field = lambda k: (re.search(rf"^- {k}:[ \t]*(.*)$", text, re.M) or [None, ""])[1].strip()
    decision = field("Decision").lower()
    errs = []
    if decision not in ("not yet held", "passed", "failed"):
        errs.append("Decision must be one of: not yet held, passed, failed")
    if decision in ("passed", "failed"):
        try:
            dt.date.fromisoformat(field("Date"))
        except ValueError:
            errs.append("Date must be an ISO date")
        for role in ("Founder", "CTO", "CSO", "DPO"):
            if not field(f"Attendee {role}"):
                errs.append(f"Attendee {role} missing")
        if not re.fullmatch(r"[0-9a-f]{7,40}", field("Pack commit")):
            errs.append("Pack commit must be the commit SHA the pack was generated from")
    return decision, errs


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("controls")
    p = sub.add_parser("pack")
    r = sub.add_parser("record")
    for x in (c, p, r):
        x.add_argument("--root", default=".")
    for x in (c, p):
        x.add_argument("--junit", action="append", default=[])
    p.add_argument("--sha", required=True)
    p.add_argument("--evidence")
    p.add_argument("--out", default="phase0-gate")
    a = ap.parse_args(argv)
    root = pathlib.Path(a.root)
    if a.cmd == "controls":
        cs = evaluate_controls(root, a.junit)
        print("\n".join(cs["mapping_errors"]))
        print(f"security controls verified: {cs['verified']}/{cs['total']} = {cs['percent']}% (gate {THRESHOLD:.0f}%)")
        return 1 if cs["mapping_errors"] else 0
    if a.cmd == "record":
        decision, errs = check_record(root)
        print("\n".join(errs) or f"gate record OK (decision: {decision})")
        return 1 if errs else 0
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    gen = (dt.datetime.fromtimestamp(int(epoch), dt.timezone.utc) if epoch else dt.datetime.now(dt.timezone.utc)).isoformat(timespec="seconds")
    ev = json.loads(pathlib.Path(a.evidence).read_text(encoding="utf-8")) if a.evidence else None
    pack = build_pack(root, a.sha, a.junit, ev, gen)
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "phase0-gate-pack.json").write_text(json.dumps(pack, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (out / "phase0-gate-pack.md").write_text(to_md(pack), encoding="utf-8")
    print("\n".join(f"{x['id']}. {x['title']}: {x['status']}" for x in pack["criteria"]))
    print(f"gate: {pack['result']}")
    return 0 if pack["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
