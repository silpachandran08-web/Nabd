#!/usr/bin/env python3
"""OPS-035 stage 5 breaking-change rule (blocking).

  python3 scripts/ci/api_contract.py breaking <oasdiff-breaking.json> <head-spec.yaml>

Input is `oasdiff breaking base head -f json` (oasdiff pinned in ci.yml). Every finding (WARN or ERR) blocks the PR, except a
change to an operation that the head spec marks `deprecated: true` with an `x-sunset: YYYY-MM-DD` date (the RFC 8594 Sunset
the API announces). Sunset changes themselves and removing a whole operation are never exempt: oasdiff already judges those
against the sunset date. A deprecated operation without a valid x-sunset is itself an error ("deprecated without Sunset").
Exit 1 when anything blocks.
"""
import datetime as dt
import json
import sys

import yaml

VERBS = ("get", "post", "put", "patch", "delete", "head", "options")


def sunset_of(op):
    """The operation's sunset date, or None when missing or not an ISO date."""
    v = op.get("x-sunset")
    if isinstance(v, dt.date):
        return v
    try:
        return dt.date.fromisoformat(str(v))
    except ValueError:
        return None


def check(findings, head):
    """Returns (blocking, exempt) lists of human-readable lines."""
    ops = {(v.upper(), p): o for p, item in (head.get("paths") or {}).items() for v, o in item.items() if v in VERBS}
    blocking, exempt = [], []
    for (verb, path), op in sorted(ops.items()):
        if op.get("deprecated") and sunset_of(op) is None:
            blocking.append(f"deprecated without Sunset: {verb} {path} (add x-sunset: YYYY-MM-DD)")
    for f in findings:
        line = f"{f.get('operation')} {f.get('path')}: [{f.get('id')}] {f.get('text')}"
        op = ops.get((f.get("operation"), f.get("path")))
        if op and op.get("deprecated") and sunset_of(op) and "sunset" not in str(f.get("id")):
            exempt.append(f"{line} (deprecated, Sunset {sunset_of(op)})")
        else:
            blocking.append("breaking change: " + line)
    return blocking, exempt


def main(argv):
    if len(argv) != 3 or argv[0] != "breaking":
        print(__doc__)
        return 2
    findings = json.load(open(argv[1])) or []
    head = yaml.safe_load(open(argv[2]))
    blocking, exempt = check(findings, head)
    for e in exempt:
        print("allowed: " + e)
    for b in blocking:
        print(b)
    print(f"{len(blocking)} blocking, {len(exempt)} allowed under deprecation+Sunset")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
