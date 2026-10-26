#!/usr/bin/env python3
"""PLT-021 gate: no new code may use the removed brand-visibility GUCs.

Fails when a migration outside the frozen baseline, or a Java file outside the allow-list,
mentions accessible_tenant_ids / visible_tenant_ids. Baseline migrations are history (never edited).
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PAT = re.compile(r"(accessible|visible)_tenant_ids")
BASELINE = {f"V{n}" for n in (1220, 1222, 1223, 1230, 1232, 1290, 150, 1530, 17, 19, 210, 211, 2521, 312, 42, 6, 8)}
JAVA_ALLOWED = {"TenantContext.java", "TenantTransactionBinder.java", "OwnerService.java"}  # OwnerService: comment only


def version(p):
    return p.name.split("__")[0]


def scan(root=ROOT):
    bad = []
    for p in sorted((root / "db/migrations").glob("V*.sql")):
        if version(p) not in BASELINE and PAT.search(p.read_text(errors="ignore")):
            bad.append(str(p.relative_to(root)))
    for p in sorted((root / "backend/src/main/java").rglob("*.java")):
        if p.name not in JAVA_ALLOWED and PAT.search(p.read_text(errors="ignore")):
            bad.append(str(p.relative_to(root)))
    return bad


if __name__ == "__main__":
    bad = scan()
    for b in bad:
        print(f"brand-visibility gate: {b} references the removed visibility GUCs (PLT-021)")
    print("brand-visibility gate:", "FAIL" if bad else "ok")
    sys.exit(1 if bad else 0)
