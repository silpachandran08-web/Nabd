#!/usr/bin/env python3
"""OPS-050 Phase 1 exit gate (cohort B entry and Phase 1 exit). Run from the repo root.

  phase1_gate.py controls [--junit DIR ...]
      Security Controls >= 90% verified (same rules as phase0_gate.py) and every unverified control has an owner and an
      ISO `due` date in docs/security/controls.yaml. Exit 1 otherwise.
  phase1_gate.py uat FILE --clinics a,b,...
      Cohort B UAT sign-off: every clinic x persona (docs/uat/scripts) has result pass|pass-with-issues, signed_by, signed_on.
  phase1_gate.py sus FILE
      System Usability Scale per clinic and overall (standard SUS scoring, 10 items on a 1..5 scale); target mean >= 70.
  phase1_gate.py dayclose FILE
      Day close exact (counted == expected) for >= 10 consecutive closed days per clinic.
  phase1_gate.py report --data DIR --sha SHA [--junit DIR ...] [--clinics a,b] [--out DIR]
      The exit report: one row per metric with value, target, PASS/FAIL and its data source. DIR holds the CSV output of
      the queries in docs/gates/phase1/*.sql plus stopwatch.csv, sus.csv and uat-signoff.csv (see docs/gates/phase1/README.md).
      Same commit + same DIR + SOURCE_DATE_EPOCH = identical report. Exit 1 unless every criterion passes.
Reads only the files it is given; never connects to a database (the SQL is run by the release owner, read-only).
"""
import argparse, csv, datetime as dt, json, os, pathlib, statistics, sys
from decimal import Decimal

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import phase0_gate as p0
import uat_pack

THRESHOLD = 90.0
SUS_TARGET = 70.0
DAYCLOSE_STREAK = 10
UAT_RESULTS = {"pass", "pass-with-issues"}
QUERIES = "docs/gates/phase1"


def read_csv(path, cols):
    with open(path, newline="", encoding="utf-8") as f:
        r = csv.DictReader(f)
        missing = [c for c in cols if c not in (r.fieldnames or [])]
        if missing:
            raise SystemExit(f"{path}: missing columns {','.join(missing)}")
        return list(r)


# ---------- security controls ----------
def controls(root, junit_dirs):
    cs = p0.evaluate_controls(root, junit_dirs)
    raw = {c["id"]: c for c in p0.yaml.safe_load((root / p0.CONTROLS).read_text(encoding="utf-8"))["controls"]}
    undated = []
    for u in cs["unverified"]:
        due = str(raw[u["id"]].get("due") or "")
        try:
            dt.date.fromisoformat(due)
            u["due"] = due
        except ValueError:
            u["due"] = None
            undated.append(u["id"])
    ok = cs["percent"] >= THRESHOLD and not cs["mapping_errors"] and not undated
    return ok, cs, undated


# ---------- UAT sign-off ----------
def uat(path, clinics):
    got = {(r["clinic"], r["persona"]): r for r in read_csv(path, ["clinic", "persona", "result", "signed_by", "signed_on", "evidence"])}
    out, ok = [], bool(clinics)
    if not clinics:
        out.append("no cohort B clinics given (--clinics)")
    for c in clinics:
        for p in uat_pack.PERSONAS:
            r = got.get((c, p))
            if not r:
                out.append(f"{c} {p}: NOT SIGNED"); ok = False; continue
            bad = r["result"] not in UAT_RESULTS or not r["signed_by"].strip()
            try:
                dt.date.fromisoformat(r["signed_on"])
            except ValueError:
                bad = True
            if bad:
                out.append(f"{c} {p}: needs result pass|pass-with-issues, signed_by and an ISO signed_on"); ok = False
            else:
                out.append(f"{c} {p}: {r['result']} (signed {r['signed_on']})")
    return ok, out


# ---------- SUS ----------
def sus_score(answers):
    """Standard SUS: odd items contribute (a - 1), even items (5 - a); sum x 2.5 -> 0..100."""
    if len(answers) != 10 or any(a not in (1, 2, 3, 4, 5) for a in answers):
        raise ValueError("SUS needs 10 answers on a 1..5 scale")
    return 2.5 * sum((a - 1) if i % 2 == 0 else (5 - a) for i, a in enumerate(answers))


def sus(path):
    rows = read_csv(path, ["clinic", "respondent", "role", "date"] + [f"q{i}" for i in range(1, 11)])
    by, out = {}, []
    for r in rows:
        try:
            by.setdefault(r["clinic"], []).append(sus_score([int(r[f"q{i}"]) for i in range(1, 11)]))
        except ValueError as e:
            raise SystemExit(f"{path}: {r['clinic']} {r['respondent']}: {e}")
    allv = [v for vs in by.values() for v in vs]
    for c in sorted(by):
        out.append(f"{c}: mean SUS {statistics.mean(by[c]):.1f} over {len(by[c])} receptionists")
    mean = round(statistics.mean(allv), 1) if allv else None
    out.append(f"overall: mean SUS {mean} over {len(allv)} responses, target >= {SUS_TARGET:g}" if allv else "no SUS responses")
    return mean is not None and mean >= SUS_TARGET, mean, out


# ---------- day close ----------
def longest_exact_run(days):
    """days: [(date, exact)] for one clinic. Longest run of consecutive calendar days that all closed exactly."""
    best = run = 0
    prev = None
    for d, exact in sorted(days):
        if not exact:
            run = 0
        elif prev is not None and d == prev + dt.timedelta(days=1):
            run += 1
        else:
            run = 1
        prev = d if exact else None
        best = max(best, run)
    return best


def dayclose(path):
    by = {}
    for r in read_csv(path, ["tenant_id", "close_date", "expected", "counted"]):
        exact = Decimal(r["expected"]) == Decimal(r["counted"])  # money: exact decimals, never floats
        by.setdefault(r["tenant_id"], []).append((dt.date.fromisoformat(r["close_date"]), exact))
    out, ok = [], bool(by)
    for c in sorted(by):
        n = longest_exact_run(by[c]); hit = n >= DAYCLOSE_STREAK; ok &= hit
        out.append(f"{c}: longest exact run {n} days, target {DAYCLOSE_STREAK} -> {'OK' if hit else 'MISS'}")
    return ok, out


# ---------- metrics ----------
def pct(xs, q):
    xs = sorted(xs)
    if not xs:
        return None
    k = (len(xs) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def weighted(rows):
    n = sum(int(r["n"]) for r in rows)
    return (sum(float(r["value"]) * int(r["n"]) for r in rows) / n if n else None), n


def stopwatch_values(data, moment):
    f = data / "stopwatch.csv"
    if not f.exists():
        return []
    return [float(r["seconds"]) for r in read_csv(f, ["clinic", "moment", "seconds"]) if r["moment"] == moment]


def metric(mid, title, value, target, ok, source, n):
    return {"id": mid, "title": title, "value": value, "target": target, "status": "PASS" if ok else "FAIL",
            "source": source, "n": n}


def metrics(data):
    """Each metric from its query output or stopwatch samples; a missing file or no rows is FAIL ("no data")."""
    out = []

    def q(name):
        f = data / f"{name}.csv"
        return read_csv(f, ["tenant_id", "value", "n"]) if f.exists() else []

    def sql(name):
        return f"{QUERIES}/{name}.sql"

    b = stopwatch_values(data, "booking")
    med, p90 = (statistics.median(b), pct(b, 0.9)) if b else (None, None)
    out.append(metric("booking", "Desk booking time (median < 60 s, p90 <= 90 s)", None if med is None else f"median {med:g} s, p90 {p90:g} s",
                      "median < 60 s, p90 <= 90 s", med is not None and med < 60 and p90 <= 90, "stopwatch.csv moment booking (OPS-049)", len(b)))
    wa = q("whatsapp_booking")
    v, n = weighted(wa)
    out.append(metric("whatsapp_booking", "WhatsApp booking time (median < 60 s)", None if v is None else f"{v:.0f} s", "< 60 s",
                      v is not None and v < 60, sql("whatsapp_booking") + " (Edge funnel, ACC-045)", n))
    v, n = weighted(q("eta_within10"))
    out.append(metric("eta_within10", "ETA within +/-10 min", None if v is None else f"{100 * v:.1f}% of visits", ">= 80% of visits",
                      v is not None and v >= 0.80, sql("eta_within10") + " (eta_accuracy_daily rollup)", n))
    v, n = weighted(q("wait_median"))
    out.append(metric("wait_median", "Waiting time (median)", None if v is None else f"{v:.1f} min", "< 15 min",
                      v is not None and v < 15, sql("wait_median") + " (queue_entry_events)", n))
    for mid, title, target in (("walk_in", "Walk-in registration", 45), ("repeat_rx", "Repeat prescription", 30), ("dispense", "Dispense", 20)):
        xs = stopwatch_values(data, mid)
        m = statistics.median(xs) if xs else None
        out.append(metric(mid, f"{title} (median)", None if m is None else f"{m:g} s", f"< {target} s", m is not None and m < target,
                          f"stopwatch.csv moment {mid}", len(xs)))
    v, n = weighted(q("delay_broadcast"))
    out.append(metric("delay_broadcast", "Delay broadcast queued after announcement (median)", None if v is None else f"{v:.0f} s", "< 60 s",
                      v is not None and v < 60, sql("delay_broadcast") + " (doctor_delays -> delay_broadcast_recipients)", n))
    f = data / "sus.csv"
    if f.exists():
        ok, mean, _ = sus(f)
        n = len(read_csv(f, ["clinic"]))
    else:
        ok, mean, n = False, None, 0
    out.append(metric("sus", "Receptionist SUS (mean)", mean, ">= 70", ok, "sus.csv (questionnaire)", n))
    f = data / "day_close.csv"
    ok, lines = dayclose(f) if f.exists() else (False, [])
    out.append(metric("day_close", "Day close exact, 10 consecutive days per clinic", "; ".join(lines) or None, "every clinic >= 10 days",
                      ok, sql("day_close") + " (day_closes)", len(lines)))
    return out


def report(root, data, sha, junit_dirs, clinics, generated_at):
    ok_c, cs, undated = controls(root, junit_dirs)
    f = data / "uat-signoff.csv"
    ok_u, uat_lines = uat(f, clinics) if f.exists() else (False, ["no uat-signoff.csv"])
    ms = metrics(data)
    criteria = [
        {"id": "controls", "title": f"Security Controls >= {THRESHOLD:.0f}% verified, every gap owned and dated",
         "status": "PASS" if ok_c else "FAIL",
         "detail": f"{cs['verified']} of {cs['total']} verified ({cs['percent']}%); unverified without a due date: {len(undated)}"},
        {"id": "uat", "title": "Cohort B UAT signed per persona", "status": "PASS" if ok_u else "FAIL", "detail": "; ".join(uat_lines)},
    ] + [{"id": m["id"], "title": m["title"], "status": m["status"],
          "detail": f"value {m['value']} (target {m['target']}), n={m['n']}, source {m['source']}"} for m in ms]
    ok = all(c["status"] == "PASS" for c in criteria)
    return {"pack": "phase1-exit-report", "schema": 1, "commit_sha": sha, "generated_at": generated_at, "result": "PASS" if ok else "FAIL",
            "criteria": criteria, "metrics": ms,
            "security_controls": {"total": cs["total"], "verified": cs["verified"], "percent": cs["percent"], "unverified": cs["unverified"]}}


def to_md(p):
    out = ["# Phase 1 exit report", "", f"Commit `{p['commit_sha']}`, generated {p['generated_at']}. **Result: {p['result']}.**", "",
           "| Metric | Value | Target | Status | Source |", "|---|---|---|---|---|"]
    out += [f"| {m['title']} | {m['value'] if m['value'] is not None else 'no data'} | {m['target']} | {m['status']} | {m['source']} |" for m in p["metrics"]]
    out += ["", "| Criterion | Status | Detail |", "|---|---|---|"]
    out += [f"| {c['title']} | {c['status']} | {c['detail']} |" for c in p["criteria"][:2]]
    out += ["", "## Unverified Security Controls", "", "| Control | Owner | Due | Description |", "|---|---|---|---|"]
    out += [f"| {u['id']} | {u['owner']} | {u.get('due') or 'NO DATE'} | {u['control']} |" for u in p["security_controls"]["unverified"]]
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("controls"); c.add_argument("--junit", action="append", default=[])
    u = sub.add_parser("uat"); u.add_argument("file"); u.add_argument("--clinics", default="")
    s = sub.add_parser("sus"); s.add_argument("file")
    d = sub.add_parser("dayclose"); d.add_argument("file")
    r = sub.add_parser("report"); r.add_argument("--data", required=True); r.add_argument("--sha", required=True)
    r.add_argument("--junit", action="append", default=[]); r.add_argument("--clinics", default=""); r.add_argument("--out", default="phase1-exit")
    for x in (c, r):
        x.add_argument("--root", default=".")
    a = ap.parse_args(argv)
    clinics = lambda: [x.strip() for x in getattr(a, "clinics", "").split(",") if x.strip()]
    if a.cmd == "controls":
        ok, cs, undated = controls(pathlib.Path(a.root), a.junit)
        print("\n".join(cs["mapping_errors"]))
        print(f"security controls verified: {cs['verified']}/{cs['total']} = {cs['percent']}% (gate {THRESHOLD:.0f}%)")
        if undated:
            print("unverified controls without an ISO due date: " + ", ".join(undated))
        return 0 if ok else 1
    if a.cmd == "uat":
        ok, lines = uat(a.file, clinics())
    elif a.cmd == "sus":
        ok, _, lines = sus(a.file)
    elif a.cmd == "dayclose":
        ok, lines = dayclose(a.file)
    else:
        epoch = os.environ.get("SOURCE_DATE_EPOCH")
        gen = (dt.datetime.fromtimestamp(int(epoch), dt.timezone.utc) if epoch else dt.datetime.now(dt.timezone.utc)).isoformat(timespec="seconds")
        p = report(pathlib.Path(a.root), pathlib.Path(a.data), a.sha, a.junit, clinics(), gen)
        out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
        (out / "phase1-exit-report.json").write_text(json.dumps(p, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        (out / "phase1-exit-report.md").write_text(to_md(p), encoding="utf-8")
        lines, ok = [f"{c['title']}: {c['status']}" for c in p["criteria"]] + [f"phase 1 exit: {p['result']}"], p["result"] == "PASS"
    print("\n".join(lines))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
