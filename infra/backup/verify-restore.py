#!/usr/bin/env python3
"""OPS-054: verify a restored database against its source, for a sample of tenants.

  verify-restore.py --source "$SOURCE_URL" --restored "$RESTORED_URL" --target-time 2026-11-02T10:15:00Z [--tenants N | --tenant <uuid> ...]
  verify-restore.py --selftest

URLs are libpq connection strings WITHOUT passwords (use PGPASSFILE or the secret manager's env); connect as a read-only role
(nabd_reports). Tenant tables are RLS-forced, so every count runs inside a transaction with app.tenant_id set to that tenant.

Per sampled tenant and per tenant table:
  - tables with created_at: rows created at or before the target time must be in the restored copy. restored < source
    means data written before the target time is missing (FAIL). restored > source means rows were deleted on the
    source after the target time (OK, reported).
  - tables without created_at: counts are reported, not compared.
  - verify_audit_chain(tenant) must return NULL (no broken link) on the restored copy.
Exit 0 only if every check passes. Prints JSON to stdout (attach it to the drill record). Needs: psql on PATH.
"""
import argparse
import json
import subprocess
import sys
import uuid


def psql(url, sql):
    out = subprocess.run(["psql", url, "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                         input=sql, capture_output=True, text=True, check=True).stdout
    return [line.split("|") for line in out.splitlines() if line]


def tenant_tables(url):
    rows = psql(url, """
        SELECT c.table_name, bool_or(c2.column_name IS NOT NULL)
          FROM information_schema.columns c
          JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                                           AND t.table_type = 'BASE TABLE'
          LEFT JOIN information_schema.columns c2 ON c2.table_schema = c.table_schema AND c2.table_name = c.table_name
                                                 AND c2.column_name = 'created_at'
         WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id'
         GROUP BY c.table_name ORDER BY c.table_name""")
    return [(name, has == "t") for name, has in rows]


def counts(url, tenant, tables, target):
    """One transaction per tenant, app.tenant_id set locally (RLS). Returns {table: count} and the audit verify result."""
    t = str(uuid.UUID(tenant))  # validated: it is interpolated into SQL below
    ts = target.replace("'", "")
    selects = [f"SELECT '{name}', count(*) FROM \"{name}\" WHERE tenant_id = '{t}'"
               + (f" AND created_at <= '{ts}'::timestamptz" if has_created else "") for name, has_created in tables]
    sql = (f"BEGIN; SELECT set_config('app.tenant_id', '{t}', true);\n"
           + " UNION ALL ".join(selects)
           + f" UNION ALL SELECT '__audit_broken_at', coalesce(verify_audit_chain('{t}'), -1);\nCOMMIT;\n")
    rows = [r for r in psql(url, sql) if len(r) == 2]  # drops the set_config row
    result = {name: int(n) for name, n in rows}
    broken = result.pop("__audit_broken_at")
    return result, (None if broken == -1 else broken)


def compare(tables, src, dst, broken_at):
    """Pure comparison (unit-tested by --selftest)."""
    problems, notes = [], []
    for name, has_created in tables:
        s, d = src.get(name, 0), dst.get(name, 0)
        if not has_created:
            notes.append(f"{name}: source {s}, restored {d} (no created_at, not compared)")
        elif d < s:
            problems.append(f"{name}: {s - d} row(s) created before the target time are missing")
        elif d > s:
            notes.append(f"{name}: {d - s} row(s) deleted on the source after the target time")
    if broken_at is not None:
        problems.append(f"audit chain broken at audit_log.id {broken_at}")
    return problems, notes


def sample_tenants(url, n):
    return [r[0] for r in psql(url, f"SELECT id FROM tenants ORDER BY random() LIMIT {int(n)}")]


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", required=True)
    ap.add_argument("--restored", required=True)
    ap.add_argument("--target-time", required=True)
    ap.add_argument("--tenants", type=int, default=5)
    ap.add_argument("--tenant", action="append")
    a = ap.parse_args(argv)
    tables = tenant_tables(a.restored)
    tenants = a.tenant or sample_tenants(a.restored, a.tenants)
    report = {"target_time": a.target_time, "tables": len(tables), "tenants": {}, "ok": True}
    for t in tenants:
        src, _ = counts(a.source, t, tables, a.target_time)
        dst, broken = counts(a.restored, t, tables, a.target_time)
        problems, notes = compare(tables, src, dst, broken)
        report["tenants"][t] = {"rows_restored": sum(dst.values()), "audit_chain_ok": broken is None,
                                "problems": problems, "notes": notes}
        report["ok"] &= not problems
    if not tenants:
        report["ok"] = False
        report["error"] = "no tenants to sample"
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 1


def selftest():
    tables = [("patients", True), ("audit_log", True), ("settings", False)]
    p, n = compare(tables, {"patients": 10, "audit_log": 5, "settings": 1}, {"patients": 10, "audit_log": 5, "settings": 2}, None)
    assert p == [] and len(n) == 1, (p, n)
    p, _ = compare(tables, {"patients": 10}, {"patients": 9}, None)
    assert p == ["patients: 1 row(s) created before the target time are missing"], p
    p, n = compare(tables, {"patients": 9}, {"patients": 10}, None)
    assert p == [] and "deleted on the source" in n[0], (p, n)
    p, _ = compare(tables, {}, {}, 42)
    assert p == ["audit chain broken at audit_log.id 42"], p
    try:
        counts("postgresql://x", "1' OR '1'='1", tables, "now")
        raise AssertionError("non-uuid tenant accepted")
    except ValueError:
        pass
    print("verify-restore selftest: 5/5 passed")
    return 0


if __name__ == "__main__":
    sys.exit(selftest() if sys.argv[1:] == ["--selftest"] else main(sys.argv[1:]))
