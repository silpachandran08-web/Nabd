#!/usr/bin/env python3
"""OPS-035 stage 5 breaking-change rule (blocking).

  python3 scripts/ci/api_contract.py breaking <oasdiff-breaking.json> <head-spec.yaml> [accepted.yaml]

Input is `oasdiff breaking base head -f json` (oasdiff pinned in ci.yml). Every finding (WARN or ERR) blocks the PR, except a
change to an operation that the head spec marks `deprecated: true` with an `x-sunset: YYYY-MM-DD` date (the RFC 8594 Sunset
the API announces). Sunset changes themselves and removing a whole operation are never exempt: oasdiff already judges those
against the sunset date. A deprecated operation without a valid x-sunset is itself an error ("deprecated without Sunset").
Exit 1 when anything blocks.

Reviewed acceptances (scripts/ci/api_contract_accepted.yaml, default): a finding is allowed only when a non-expired group
lists it by rule id + operation + path + the exact oasdiff text (so the same rule on another property, or the same property
with a different change, still blocks). Every group needs a reason and an `expires` date; an expired group allows nothing.
Entries that match no finding are reported as stale and do not block (the base catches up once the PR merges).
"""
import datetime as dt
import json
import sys
from pathlib import Path

import yaml

ACCEPTED = Path(__file__).with_name("api_contract_accepted.yaml")

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


def load_accepted(path, today=None):
    """{(rule, operation, path, text): reason} for non-expired groups; a group without reason or date is an error."""
    today, out = today or dt.date.today(), {}
    for g in (yaml.safe_load(open(path)) or {}).get("groups") or []:
        exp = g.get("expires")
        exp = exp if isinstance(exp, dt.date) else dt.date.fromisoformat(str(exp))  # raises: no unbounded acceptance
        if not str(g.get("reason") or "").strip():
            raise ValueError(f"accepted group without a reason: {g.get('name')}")
        if exp >= today:
            for f in g["findings"]:
                out[(f["rule"], f["op"], f["path"], f["text"])] = f"group {g.get('name')} until {exp}"
    return out


def check(findings, head, accepted=None):
    """Returns (blocking, exempt) lists of human-readable lines."""
    accepted = accepted or {}
    ops = {(v.upper(), p): o for p, item in (head.get("paths") or {}).items() for v, o in item.items() if v in VERBS}
    blocking, exempt = [], []
    for (verb, path), op in sorted(ops.items()):
        if op.get("deprecated") and sunset_of(op) is None:
            blocking.append(f"deprecated without Sunset: {verb} {path} (add x-sunset: YYYY-MM-DD)")
    for f in findings:
        line = f"{f.get('operation')} {f.get('path')}: [{f.get('id')}] {f.get('text')}"
        op = ops.get((f.get("operation"), f.get("path")))
        why = accepted.get((f.get("id"), f.get("operation"), f.get("path"), f.get("text")))
        if why:
            exempt.append(f"{line} (accepted: {why})")
        elif op and op.get("deprecated") and sunset_of(op) and "sunset" not in str(f.get("id")):
            exempt.append(f"{line} (deprecated, Sunset {sunset_of(op)})")
        else:
            blocking.append("breaking change: " + line)
    return blocking, exempt


def main(argv):
    if len(argv) not in (3, 4) or argv[0] != "breaking":
        print(__doc__)
        return 2
    findings = json.load(open(argv[1])) or []
    head = yaml.safe_load(open(argv[2]))
    acc_path = Path(argv[3]) if len(argv) == 4 else ACCEPTED
    accepted = load_accepted(acc_path) if acc_path.exists() else {}
    blocking, exempt = check(findings, head, accepted)
    seen = {(f.get("id"), f.get("operation"), f.get("path"), f.get("text")) for f in findings}
    for k in sorted(set(accepted) - seen):
        print(f"stale acceptance (matches no finding, remove when the base has caught up): {k[0]} {k[1]} {k[2]}")
    for e in exempt:
        print("allowed: " + e)
    for b in blocking:
        print(b)
    print(f"{len(blocking)} blocking, {len(exempt)} allowed under deprecation+Sunset or a reviewed acceptance")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
