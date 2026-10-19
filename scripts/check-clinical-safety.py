#!/usr/bin/env python3
"""Lint docs/clinical-safety/hazard-log.csv and .github/CODEOWNERS. Usage: check-clinical-safety.py [repo_root]"""
import csv, re, sys, pathlib

REQUIRED = ["cause", "existing_control", "verifying_test", "residual_risk", "owner"]
SAFETY = ["RxSafetyChecks.java", "AllergyDrugClasses.java", "clinical-safety/"]


def check_log(path):
    errs = []
    rows = list(csv.DictReader(open(path, newline="")))
    ids = [r["id"] for r in rows]
    if len(set(ids)) != len(ids):
        errs.append("duplicate hazard ids")
    for n in range(1, 9):
        if f"H-0{n}" not in ids:
            errs.append(f"missing H-0{n}")
    for r in rows:
        for c in ["hazard"] + REQUIRED:
            if not (r.get(c) or "").strip():
                errs.append(f"{r.get('id')}: empty {c}")
        if r.get("status") == "closed" and not (r.get("closing_pr") or "").strip():
            errs.append(f"{r.get('id')}: closed without closing_pr")
    return errs


def check_owners(path):
    lines = [l for l in open(path).read().splitlines() if l.strip() and not l.startswith("#")]
    errs = []
    for need in SAFETY:
        if not any(need in l and re.search(r"\s@\S+", l) for l in lines):
            errs.append(f"CODEOWNERS has no owner for {need}")
    return errs


if __name__ == "__main__":
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    errs = check_log(root / "docs/clinical-safety/hazard-log.csv") + check_owners(root / ".github/CODEOWNERS")
    print("\n".join(errs) or "clinical-safety check OK")
    sys.exit(1 if errs else 0)
