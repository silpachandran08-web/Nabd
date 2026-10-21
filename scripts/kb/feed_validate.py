#!/usr/bin/env python3
"""CLN-018: does a vendor sample feed resolve the top-500 Indian brands (by volume) to a generic (salt) and strength?

  python3 scripts/kb/feed_validate.py --feed sample.csv --top500 top500.csv [--threshold 0.95] [--report out.md]

feed CSV   : header with columns brand, generic, strength (extra columns ignored). PROVISIONAL: the real column names are
             set by the signed feed spec; map them in `--columns brand=...,generic=...,strength=...`.
top500 CSV : columns brand, rank (the list comes from the vendor/IQVIA-type sales data we license, not from this repo).
A brand resolves when the feed has it (case, spacing and punctuation ignored) with a non-empty generic AND strength.
Exit 0 when coverage >= threshold and the list has 500 brands, else 1. The report names unresolved brands only (no feed content).
"""
import argparse
import csv
import re
import sys


def norm(s):
    return re.sub(r"[^a-z0-9]+", "", (s or "").casefold())


def read(path, want):
    with open(path, newline="", encoding="utf-8-sig") as f:
        r = csv.DictReader(f)
        missing = [w for w in want.values() if w not in (r.fieldnames or [])]
        if missing:
            raise SystemExit(f"{path}: missing column(s) {missing}")
        return [{k: (row[v] or "").strip() for k, v in want.items()} for row in r]


def validate(feed_rows, top_rows, threshold=0.95, expected=500):
    resolved = {norm(r["brand"]) for r in feed_rows if r["brand"] and r["generic"] and r["strength"]}
    seen, unresolved = set(), []
    for r in top_rows:
        key = norm(r["brand"])
        if not key or key in seen:
            continue
        seen.add(key)
        if key not in resolved:
            unresolved.append(r["brand"])
    n = len(seen)
    cov = (n - len(unresolved)) / n if n else 0.0
    ok = n >= expected and cov >= threshold
    return {"brands": n, "resolved": n - len(unresolved), "coverage": cov, "threshold": threshold,
            "expected": expected, "unresolved": unresolved, "ok": ok}


def render(r):
    lines = ["# Drug KB sample feed: top-500 brand resolution", "",
             f"- Brands checked: {r['brands']} (need {r['expected']})",
             f"- Resolved to generic and strength: {r['resolved']} ({r['coverage']:.1%}, threshold {r['threshold']:.0%})",
             f"- Result: {'PASS' if r['ok'] else 'FAIL'}", "", f"## Unresolved ({len(r['unresolved'])})"]
    lines += [f"- {b}" for b in r["unresolved"]] or ["- none"]
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--feed", required=True)
    ap.add_argument("--top500", required=True)
    ap.add_argument("--threshold", type=float, default=0.95)
    ap.add_argument("--columns", default="brand=brand,generic=generic,strength=strength")
    ap.add_argument("--report")
    a = ap.parse_args(argv)
    cols = dict(kv.split("=", 1) for kv in a.columns.split(","))
    res = validate(read(a.feed, cols), read(a.top500, {"brand": "brand"}), a.threshold)
    out = render(res)
    if a.report:
        open(a.report, "w", encoding="utf-8").write(out)
    print(out, end="")
    return 0 if res["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
