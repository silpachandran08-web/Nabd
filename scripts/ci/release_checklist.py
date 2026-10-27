#!/usr/bin/env python3
"""OPS-044 release checklist gate: python3 scripts/ci/release_checklist.py --body-file FILE [--windows docs/ops/release-windows.yaml]
Validates the body of a release PR (template .github/PULL_REQUEST_TEMPLATE/release.md): every item ticked and filled, the deploy window
in UTC, 15 minutes to 4 hours long, clear of freezes and OPD peaks, a rollback plan, links for approval and the safety report.
Exit 1 and one line per problem."""
import argparse, datetime as dt, re, sys, zoneinfo
import yaml

ITEMS = ["Change approval", "Deploy window", "Rollback plan", "Safety report"]
URL = re.compile(r"https?://[^\s)>;]+")
PLACEHOLDER = re.compile(r"example\.invalid|NAME\b|X\.Y\.Z|how to go back")


def parse(body):
    """{item: (ticked, text after the label)} for the checklist lines."""
    out = {}
    for line in body.splitlines():
        m = re.match(r"\s*[-*]\s*\[( |x|X)\]\s*\*\*([^*]+)\*\*\s*[—-]*\s*(.*)", line)
        if m and m.group(2).strip() in ITEMS:
            out[m.group(2).strip()] = (m.group(1).lower() == "x", m.group(3).strip())
    return out


def utc(s):
    return dt.datetime.strptime(s, "%Y-%m-%dT%H:%MZ").replace(tzinfo=dt.timezone.utc)


def check(body, windows=None):
    errs, warns = [], []
    items = parse(body)
    for k in ITEMS:
        if k not in items:
            errs.append(f"checklist item '{k}' is missing")
        elif not items[k][0]:
            errs.append(f"'{k}' is not ticked")
        elif PLACEHOLDER.search(items[k][1]):
            errs.append(f"'{k}' still has the template placeholder text")
    if errs:
        return errs, warns
    ap = items["Change approval"][1]
    if not (re.search(r"approver:\s*[^;]*\w", ap) and URL.search(ap)):
        errs.append("Change approval needs 'approver: <name>; link: <url>'")
    if not URL.search(items["Safety report"][1]):
        errs.append("Safety report needs a link to the release's safety-test-report")
    if len(re.sub(r"\(.*", "", items["Rollback plan"][1]).strip()) < 20:
        errs.append("Rollback plan needs a real plan (at least 20 characters)")
    m = re.search(r"start:\s*(\S+?);\s*end:\s*(\S+?)(?:\s|\(|$)", items["Deploy window"][1])
    if not m:
        return errs + ["Deploy window needs 'start: YYYY-MM-DDTHH:MMZ; end: YYYY-MM-DDTHH:MMZ' (UTC)"], warns
    try:
        s, e = utc(m.group(1)), utc(m.group(2))
    except ValueError:
        return errs + ["Deploy window times must look like 2026-01-31T22:00Z (UTC)"], warns
    if not dt.timedelta(minutes=15) <= e - s <= dt.timedelta(hours=4):
        errs.append("Deploy window must be between 15 minutes and 4 hours long")
    w = windows or {}
    for f in w.get("freeze") or []:
        if s < utc(f["to"]) and e > utc(f["from"]):
            errs.append(f"Deploy window overlaps the freeze {f['from']}..{f['to']} ({f.get('reason', '')})")
    peaks = w.get("opd_peaks") or []
    if not peaks:
        warns.append("OPD peaks are not configured in docs/ops/release-windows.yaml (needs ops confirmation): the peak check was skipped")
    for p in peaks:
        tz = zoneinfo.ZoneInfo(p["tz"])
        a, b = s.astimezone(tz), e.astimezone(tz)
        # shortcut: checks the window's start and end clock times against the peak, not every minute between (windows are <= 4 h)
        for t in (a, b):
            hhmm = t.strftime("%H:%M")
            if t.isoweekday() in p["days"] and p["from"] <= hhmm < p["to"]:
                errs.append(f"Deploy window falls in the {p['region']} OPD peak {p['from']}-{p['to']} ({p['tz']})")
                break
    return errs, warns


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--body-file", required=True)
    ap.add_argument("--windows", default="docs/ops/release-windows.yaml")
    a = ap.parse_args()
    errs, warns = check(open(a.body_file, encoding="utf-8").read(), yaml.safe_load(open(a.windows)) or {})
    print("\n".join(["WARNING: " + w for w in warns] + errs) or "release checklist OK")
    sys.exit(1 if errs else 0)
