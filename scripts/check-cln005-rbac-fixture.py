#!/usr/bin/env python3
"""CLN-005: the test fixture backend/src/test/resources/cln005/rbac-clinical-records.csv must equal the
"Clinical Records" rows of the RBAC Matrix sheet in the task-plan workbook. Exit 0 when they match, 1 with a diff.
Usage: python3 scripts/check-cln005-rbac-fixture.py [path/to/workbook.xlsx]  (needs openpyxl)"""
import csv
import pathlib
import sys

import openpyxl

ROOT = pathlib.Path(__file__).resolve().parent.parent
xlsx = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "Nabd HMS - Implementation Task Plan v2.xlsx"
fixture = ROOT / "backend/src/test/resources/cln005/rbac-clinical-records.csv"

sheet = openpyxl.load_workbook(xlsx, read_only=True)["RBAC Matrix"]
matrix, role = {}, None
for row in sheet.iter_rows(values_only=True):
    if row[0] is not None:
        role = row[0]
    if row[1] == "Clinical Records" and role:
        matrix[role] = [str(c) for c in row[2:9]]

lines = [l for l in fixture.read_text().splitlines() if l and not l.startswith("#")]
rows = {r["role"]: [r[k] for k in ("view", "create", "edit", "delete", "approve", "refundDiscount", "export")]
        for r in csv.DictReader(lines)}

if rows != matrix:
    print("fixture differs from the RBAC Matrix:")
    for k in sorted(set(rows) | set(matrix)):
        if rows.get(k) != matrix.get(k):
            print(f"  {k}: fixture={rows.get(k)} matrix={matrix.get(k)}")
    sys.exit(1)
print(f"ok: {len(rows)} Clinical Records rows match the RBAC Matrix")
