#!/usr/bin/env python3
"""OPS-049 pilot UAT pack tooling: python3 scripts/ci/uat_pack.py check | stopwatch CSV | triage CSV --today D | golive CSV --clinics a,b.
Exit 1 when the pack is malformed or a gate is missed. Reads only the files it is given."""
import argparse, csv, datetime as dt, re, statistics, sys
from pathlib import Path

PERSONAS = ["reception", "doctor", "nurse", "billing", "solo", "owner"]
TARGETS = {"walk_in": 45, "booking": 60, "repeat_rx": 30, "checkout": 60}  # seconds, median; mirrored in docs/uat/stopwatch.md
SLA_DAYS = {"S1": 1, "S2": 5}
TRIAGE_COLS = "id,clinic,date_found,persona,screen,severity,description,owner,sla_due,status,fixed_on,notes".split(",")
STATUSES = {"open", "in_progress", "fixed", "wont_fix"}
DECISIONS = {"go", "no-go", "go-with-conditions"}


def rows(path, cols):
    with open(path, newline="") as f:
        r = csv.DictReader(f)
        if r.fieldnames != cols:
            raise SystemExit(f"{path}: header must be {','.join(cols)}")
        return list(r)


def check_pack(root="docs/uat"):
    errs = []
    for p in PERSONAS:
        f = Path(root, "scripts", f"{p}.md")
        if not f.exists():
            errs.append(f"missing script for {p}"); continue
        steps = [[c.strip() for c in l.strip().strip("|").split("|")] for l in f.read_text().splitlines() if re.match(r"\|\s*[A-Z]{3}-\d\d\s*\|", l)]
        if len(steps) < 5: errs.append(f"{p}: needs at least 5 steps, has {len(steps)}")
        for s in steps:
            if len(s) != 5 or not s[2] or not s[3] or not s[4]: errs.append(f"{p} {s[0] if s else '?'}: step needs action, expected result and target")
    doc = Path(root, "stopwatch.md").read_text() if Path(root, "stopwatch.md").exists() else ""
    for m, sec in TARGETS.items():
        if not re.search(rf"`{m}`.*\b{sec} s\b", doc): errs.append(f"stopwatch.md must state {m} target {sec} s")
    for name, cols in (("triage-log.csv", TRIAGE_COLS), ("stopwatch.csv", "clinic,moment,sample,seconds,date,timer,notes".split(",")), ("go-live.csv", "clinic,decision,reasons,decided_on,conditions".split(","))):
        try: rows(Path(root, name), cols)
        except (SystemExit, OSError) as e: errs.append(str(e))
    return errs


def stopwatch(path):
    by = {}
    for r in rows(path, "clinic,moment,sample,seconds,date,timer,notes".split(",")):
        if r["moment"] not in TARGETS: raise SystemExit(f"unknown moment {r['moment']!r}")
        by.setdefault((r["clinic"], r["moment"]), []).append(float(r["seconds"]))
    clinics = sorted({c for c, _ in by})
    out, ok = [], bool(clinics)
    for c in clinics:
        for m, t in TARGETS.items():
            xs = by.get((c, m))
            if not xs: out.append(f"{c} {m}: NO SAMPLES (target {t} s)"); ok = False; continue
            med = statistics.median(xs); hit = med <= t; ok &= hit
            out.append(f"{c} {m}: median {med:g} s over {len(xs)} samples, target {t} s -> {'OK' if hit else 'MISS'}")
    return ok, out


def triage(path, today):
    out, bad = [], False
    for r in rows(path, TRIAGE_COLS):
        sev, st = r["severity"], r["status"]
        if sev not in ("S1", "S2", "S3") or st not in STATUSES or not r["owner"]: out.append(f"{r['id']}: severity, status and owner are required"); bad = True; continue
        found = dt.date.fromisoformat(r["date_found"])
        if sev in SLA_DAYS:
            due = found + dt.timedelta(days=SLA_DAYS[sev])
            if r["sla_due"] != due.isoformat(): out.append(f"{r['id']}: sla_due must be {due}"); bad = True
        if st in ("fixed", "wont_fix") and not r["fixed_on"]: out.append(f"{r['id']}: {st} needs fixed_on"); bad = True
        if st in ("open", "in_progress") and sev in SLA_DAYS and today > found + dt.timedelta(days=SLA_DAYS[sev]):
            out.append(f"{r['id']} ({r['clinic']}, {sev}): SLA BREACH, due {found + dt.timedelta(days=SLA_DAYS[sev])}"); bad = True
        if st == "fixed" and sev in SLA_DAYS and dt.date.fromisoformat(r["fixed_on"]) > found + dt.timedelta(days=SLA_DAYS[sev]):
            out.append(f"{r['id']} ({r['clinic']}, {sev}): fixed late on {r['fixed_on']}"); bad = True
    return not bad, out


def golive(path, clinics):
    got = {r["clinic"]: r for r in rows(path, "clinic,decision,reasons,decided_on,conditions".split(","))}
    out, ok = [], True
    for c in clinics:
        r = got.get(c)
        if not r: out.append(f"{c}: NO DECISION"); ok = False
        elif r["decision"] not in DECISIONS or not r["reasons"].strip(): out.append(f"{c}: decision must be go|no-go|go-with-conditions with reasons"); ok = False
        elif r["decision"] == "go-with-conditions" and not r["conditions"].strip(): out.append(f"{c}: go-with-conditions needs conditions"); ok = False
        else: out.append(f"{c}: {r['decision']}")
    return ok, out


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("cmd", choices=["check", "stopwatch", "triage", "golive"]); ap.add_argument("file", nargs="?")
    ap.add_argument("--today", default=dt.date.today().isoformat()); ap.add_argument("--clinics", default="")
    a = ap.parse_args()
    if a.cmd == "check":
        e = check_pack(); print("\n".join(e) or "uat pack OK"); sys.exit(1 if e else 0)
    ok, lines = {"stopwatch": lambda: stopwatch(a.file), "triage": lambda: triage(a.file, dt.date.fromisoformat(a.today)), "golive": lambda: golive(a.file, [c.strip() for c in a.clinics.split(",") if c.strip()])}[a.cmd]()
    print("\n".join(lines) or "nothing to report"); sys.exit(0 if ok else 1)
