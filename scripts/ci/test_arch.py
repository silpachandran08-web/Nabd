#!/usr/bin/env python3
"""OPS-033 scanner fixtures and gate tests: python3 -m unittest discover -s scripts/ci -p 'test_*.py' (from repo root)."""
import collections, json, os, sys, tempfile, textwrap, unittest
sys.path.insert(0, "scripts/ci")
import arch
import yaml

MIGRATION = """
CREATE TABLE tenants (id uuid PRIMARY KEY, name text);
CREATE TABLE queue_entries (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenants(id),
  status text CHECK (status IN ('waiting', 'done'))
);
CREATE TABLE invoices (id uuid PRIMARY KEY, tenant_id uuid NOT NULL);
CREATE TABLE master.plans (id uuid PRIMARY KEY, tenant_id uuid);
CREATE TABLE audit_trail (id uuid PRIMARY KEY);
ALTER TABLE audit_trail ADD COLUMN tenant_id uuid;
CREATE TABLE ops_events (id uuid PRIMARY KEY, tenant_id uuid);
CREATE TABLE gone (id uuid, tenant_id uuid);
DROP TABLE gone;
"""
QUEUE_REPO = '''\
package com.nabd.hms.queue;
class QueueRepository {
    private static final String COLS = "SELECT q.id, q.status FROM queue_entries q ";
    // "SELECT id FROM queue_entries" in a comment is not SQL
    List<Row> byId(UUID id) {
        return jdbc.query(COLS + "WHERE q.id = ?", mapper, id);
    }
    List<Row> mine(UUID t) {
        return jdbc.query(COLS + "WHERE q.tenant_id = ? AND q.status = 'waiting'", mapper, t);
    }
    void bump(UUID id) {
        jdbc.update("""
            UPDATE queue_entries
               SET status = 'done' WHERE id = ?""", id);
    }
    int count(UUID t, boolean all) {
        String sql = "SELECT count(*) FROM queue_entries WHERE status = 'waiting'";
        if (!all) sql += " AND tenant_id = ?";
        return jdbc.queryForObject(sql, Integer.class, t);
    }
    void lock(String k) { jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, k); }
    void plans() { jdbc.query("SELECT id FROM master.plans", mapper); }
    void ops() { jdbc.query("SELECT id FROM ops_events", mapper); }
    void ins(UUID t) { jdbc.update("INSERT INTO queue_entries (id, tenant_id, status) VALUES (?, ?, 'waiting')", UUID.randomUUID(), t); }
}
'''
BILLING_REPO = '''\
package com.nabd.hms.billing;
class InvoiceRepository {
    void close(UUID t, UUID id) {
        jdbc.update("UPDATE queue_entries SET status = 'done' WHERE tenant_id = ? AND id = ?", t, id);
        jdbc.query("SELECT i.id FROM invoices i JOIN queue_entries q ON q.id = i.id WHERE i.tenant_id = ?", m, t);
        jdbc.update("INSERT INTO audit_trail (id, tenant_id) VALUES (?, ?)", id, t);
        jdbc.query("SELECT name FROM tenants WHERE id = ?", m, t);
    }
}
'''
OWNERS = {"modules": {"queue": {"owns": ["queue_entries", "ops_events", "master.plans"], "reads": []},
                      "billing": {"owns": ["invoices"], "reads": ["tenants"]}}}


def fixture_repo():
    d = tempfile.mkdtemp()
    def w(p, s):
        os.makedirs(os.path.dirname(f"{d}/{p}"), exist_ok=True); open(f"{d}/{p}", "w").write(s)
    w("db/migrations/V1__init.sql", MIGRATION)
    w(f"{arch.SRC}/queue/QueueRepository.java", QUEUE_REPO)
    w(f"{arch.SRC}/billing/InvoiceRepository.java", BILLING_REPO)
    w(arch.EXEMPT, "public.ops_events  # operator plane, reviewed\n")
    w(arch.OWNERSHIP, yaml.safe_dump(OWNERS))
    return d


def line_of(src, needle): return next(i for i, l in enumerate(src.splitlines(), 1) if needle in l)


class Schema(unittest.TestCase):
    def test_tenant_tables_from_migrations(self):
        tables, tenant = arch.schema(fixture_repo())
        self.assertEqual(tenant, {"queue_entries", "invoices", "audit_trail"})  # master.*, exempt, dropped and non-tenant tables out
        self.assertIn("master.plans", tables); self.assertNotIn("gone", tables)


class TenantPredicate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.v = [x for x in arch.scan(fixture_repo()) if x["rule"] == "tenant-predicate"]
    def test_queue_entries_without_tenant_id_reported_with_file_and_line(self):
        f = f"{arch.SRC}/queue/QueueRepository.java"
        got = sorted((x["file"], x["line"]) for x in self.v)
        self.assertEqual(got, sorted([(f, line_of(QUEUE_REPO, 'COLS + "WHERE q.id')), (f, line_of(QUEUE_REPO, "UPDATE queue_entries"))]))
        self.assertTrue(all("queue_entries" in x["detail"] and x["module"] == "queue" for x in self.v))
    def test_predicate_constant_append_insert_and_exempt_are_clean(self):
        lines = {x["line"] for x in self.v}
        for clean in ("q.tenant_id = ? AND", "String sql =", "INSERT INTO queue_entries", "master.plans", "ops_events", "pg_advisory"):
            self.assertNotIn(line_of(QUEUE_REPO, clean), lines, clean)
    def test_has_tenant_predicate(self):
        self.assertTrue(arch.has_tenant_predicate("SELECT 1 FROM t WHERE x.tenant_id = ?"))
        self.assertTrue(arch.has_tenant_predicate("DELETE FROM t WHERE tenant_id IN (?)"))
        self.assertFalse(arch.has_tenant_predicate("SELECT tenant_id FROM t WHERE id = ?"))  # selecting the column is not a predicate
        self.assertTrue(arch.has_tenant_predicate("INSERT INTO t (id, tenant_id) VALUES (?, ?)"))


class SqlOwnership(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.v = [x for x in arch.scan(fixture_repo()) if x["rule"] == "sql-ownership" and x["file"] != arch.OWNERSHIP]  # map gaps: OwnershipMap
    def test_foreign_write_reported(self):
        d = [(x["module"], x["detail"], x["line"]) for x in self.v]
        self.assertIn(("billing", "writes queue_entries owned by queue", line_of(BILLING_REPO, "UPDATE queue_entries")), d)
    def test_foreign_read_reported_allow_list_respected_unlisted_table_reported(self):
        d = {(x["module"], x["detail"]) for x in self.v}
        self.assertIn(("billing", "reads queue_entries owned by queue, not in billing reads allow-list"), d)
        self.assertIn(("billing", "writes audit_trail (not in table-ownership.yaml)"), d)
        self.assertFalse(any("tenants" in x or "invoices" in x for _, x in d))  # allow-listed read, own table
        self.assertFalse(any(m == "queue" for m, _ in d))                       # queue touches only its own tables


class OwnershipMap(unittest.TestCase):
    """PLT-033: every public/identity/consent table has exactly one owner; packages: maps Java packages onto a module."""
    def gaps(self, d):
        return {x["detail"] for x in arch.scan(d) if x["file"] == arch.OWNERSHIP}
    def test_unowned_public_tables_reported_master_schema_not_required(self):
        g = self.gaps(fixture_repo())
        self.assertEqual(g, {"table tenants has no owner in table-ownership.yaml", "table audit_trail has no owner in table-ownership.yaml"})
    def test_second_owner_and_identity_consent_schemas_reported(self):
        d = fixture_repo()
        open(f"{d}/db/migrations/V2__schemas.sql", "w").write("CREATE TABLE identity.users (id uuid);\nCREATE TABLE consent.grants (id uuid);\n")
        own = {"modules": {**OWNERS["modules"], "billing": {"owns": ["invoices", "queue_entries", "tenants", "audit_trail", "identity.users"], "reads": []}}}
        open(f"{d}/{arch.OWNERSHIP}", "w").write(yaml.safe_dump(own))
        self.assertEqual(self.gaps(d), {"table queue_entries has 2 owners in table-ownership.yaml: billing, queue",
                                        "table consent.grants has no owner in table-ownership.yaml"})
    def test_packages_map_java_packages_onto_one_module(self):
        d = fixture_repo()
        own = {"modules": {"queue": {"packages": ["queue", "billing"], "owns": ["queue_entries", "ops_events", "master.plans", "invoices", "tenants", "audit_trail"], "reads": []}}}
        open(f"{d}/{arch.OWNERSHIP}", "w").write(yaml.safe_dump(own))
        own_v = [x for x in arch.scan(d) if x["rule"] == "sql-ownership"]
        self.assertEqual(own_v, [])  # billing's queue_entries reads/writes are now inside the same module
        self.assertTrue(all(x["module"] == "queue" for x in arch.scan(d)))


class Gate(unittest.TestCase):
    a = {"tool": "sql", "rule": "tenant-predicate", "module": "queue", "file": "Q.java", "line": 1, "detail": "no tenant_id predicate on queue_entries"}
    j = {"tool": "archunit", "rule": "controllers-no-repository-or-jdbc", "module": "queue", "detail": "Field <X.jdbc> has type <JdbcTemplate> in (X.java:12)"}
    def base(self, *xs): return collections.Counter(arch.key(x) for x in xs)
    def test_key_ignores_line_numbers(self):
        self.assertEqual(arch.key(self.j), arch.key({**self.j, "detail": self.j["detail"].replace(":12", ":99")}))
        self.assertEqual(arch.key(self.a), arch.key({**self.a, "line": 50}))
    def test_report_mode_never_fails(self):
        self.assertEqual(arch.evaluate([self.a, self.j], collections.Counter(), "report")[2], [])
    def test_new_mode_fails_only_on_new(self):
        b = self.base(self.a, self.j)
        self.assertEqual(arch.evaluate([self.a, self.j], b, "new")[2], [])
        new, _, fails = arch.evaluate([self.a, {**self.a, "line": 9}, self.j], b, "new")  # a second occurrence is new
        self.assertEqual(len(new), 1); self.assertTrue(fails)
        new, fixed, fails = arch.evaluate([self.j], b, "new")
        self.assertEqual((new, fixed, fails), ([], 1, []))
    def test_all_mode_needs_empty_baseline_and_no_violation(self):
        self.assertTrue(arch.evaluate([], self.base(self.a), "all")[2])
        self.assertTrue(arch.evaluate([self.a], collections.Counter(), "all")[2])
        self.assertEqual(arch.evaluate([], collections.Counter(), "all")[2], [])
    def test_mode_switch_by_date(self):
        d = tempfile.mkdtemp(); os.makedirs(f"{d}/ci")
        json.dump({"new_code_blocking_from": "2027-01-04", "all_blocking_from": "2027-03-01"}, open(f"{d}/{arch.GATE}", "w"))
        for day, m in (("2026-12-31", "report"), ("2027-01-04", "new"), ("2027-03-01", "all")):
            os.environ["GATE_TODAY"] = day
            self.assertEqual(arch.mode(d), m, day)
        os.environ.pop("GATE_TODAY")
    def test_summary_counts_by_module(self):
        md, _ = arch.summary([self.a, self.j, {**self.a, "module": "billing"}], self.base(self.a), "report")
        self.assertIn("| module | modulith | archunit | sql-ownership | tenant-predicate | total |", md)
        self.assertIn("| queue | 0 | 1 | 0 | 1 | 2 |", md)
        self.assertIn("| billing | 0 | 0 | 0 | 1 | 1 |", md)
        self.assertIn("new 2", md); self.assertIn("Gate: pass.", md)
    def test_missing_java_report_is_a_violation(self):
        vs = arch.collect("/nonexistent.json", fixture_repo())
        self.assertTrue(any(x["rule"] == "report-missing" for x in vs))


class Committed(unittest.TestCase):
    def test_every_table_has_exactly_one_owner(self):
        tables, _ = arch.schema()
        owned = [arch.norm(t) for m in yaml.safe_load(open(arch.OWNERSHIP))["modules"].values() for t in m["owns"]]
        self.assertEqual(sorted(owned), sorted(set(owned)), "table listed under two owners")
        self.assertEqual(set(owned), tables)
    def test_gate_config_and_baseline(self):
        g = json.load(open(arch.GATE))
        self.assertIn("new_code_blocking_from", g); self.assertIn("all_blocking_from", g)
        b = json.load(open(arch.BASELINE))["violations"]
        self.assertTrue(all(v > 0 for v in b.values()))
        self.assertTrue(set(k.split("|")[0] for k in b) <= set(arch.CHECKS))
    def test_stage2_wiring(self):
        wf = yaml.safe_load(open(".github/workflows/ci.yml"))["jobs"]["stage2-unit"]
        self.assertEqual(wf["timeout-minutes"], 2)
        s = str(wf["steps"])
        for w in ("arch.py summary", "arch.py gate", "architecture-report", "upload-artifact"):
            self.assertIn(w, s)


if __name__ == "__main__":
    unittest.main(verbosity=2)
