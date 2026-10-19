import sys, os, unittest
sys.path.insert(0, os.path.dirname(__file__))
import lint

HDR = "SET lock_timeout = '5s';\n"
def rules(sql, **k): return sorted({r for r, _ in lint.lint_sql(sql, **k)})

class Rules(unittest.TestCase):
    def test_001(self):
        self.assertEqual(rules(HDR + "CREATE INDEX ix ON queue_entries(a);"), ["MIG-001"])
        self.assertEqual(rules(HDR + "CREATE INDEX CONCURRENTLY ix ON queue_entries(a);"), [])
        self.assertEqual(rules(HDR + "CREATE TABLE t(a int); CREATE INDEX ix ON t(a);"), [])
    def test_002(self):
        self.assertEqual(rules(HDR + "ALTER TABLE q ADD CONSTRAINT c CHECK (a > 0);"), ["MIG-002"])
        self.assertEqual(rules(HDR + "ALTER TABLE q ADD CONSTRAINT c CHECK (a > 0) NOT VALID;"), [])
        self.assertEqual(rules(HDR + "ALTER TABLE q ADD COLUMN a int CHECK (a > 0);"), [])
    def test_003(self):
        self.assertEqual(rules(HDR + "ALTER TABLE q ALTER COLUMN a SET NOT NULL;"), ["MIG-003"])
        good = HDR + ("ALTER TABLE q ADD CONSTRAINT c CHECK (a IS NOT NULL) NOT VALID;\n"
                      "ALTER TABLE q VALIDATE CONSTRAINT c;\nALTER TABLE q ALTER COLUMN a SET NOT NULL;")
        self.assertEqual(rules(good), [])
        # check on a different column does not count
        self.assertEqual(rules(good.replace("ALTER COLUMN a", "ALTER COLUMN b")), ["MIG-003"])
    def test_004(self):
        self.assertEqual(rules("CREATE TABLE t(a int);"), ["MIG-004"])
        self.assertEqual(rules("SET LOCAL lock_timeout = '2s'; CREATE TABLE t(a int);"), [])
    def test_007(self):
        for ddl in ("ALTER TABLE q DROP COLUMN a;", "DROP TABLE q;", "ALTER TABLE q ALTER COLUMN a TYPE bigint;"):
            self.assertEqual(rules(HDR + ddl), ["MIG-007"], ddl)
            self.assertEqual(rules(HDR + ddl, contract=True), [], ddl)
    def test_comments_and_bodies_ignored(self):
        self.assertEqual(rules(HDR + "-- CREATE INDEX x ON q(a);\nDO $$ BEGIN CREATE INDEX y ON q(a); END $$;"), [])

class Versions(unittest.TestCase):
    def test(self):
        vt = "# owner\nV130 plt\nV131 plt\n"
        f = lambda *n: [f"db/migrations/V{i}__x.sql" for i in n]
        self.assertEqual(lint.lint_versions(f(130, 131), 126, vt), [])
        self.assertEqual([r for _, r, _ in lint.lint_versions(f(126), 126, vt + "V126 x\n")], ["MIG-005"])  # boundary: == max
        self.assertEqual([r for _, r, _ in lint.lint_versions(f(132), 126, vt)], ["MIG-006"])
        self.assertEqual([r for _, r, _ in lint.lint_versions(f(130, 130), 126, vt)], ["MIG-005"])

class Cli(unittest.TestCase):
    def run_cli(self, name, env=None):
        import subprocess
        d = os.path.dirname(os.path.abspath(__file__))
        return subprocess.run([sys.executable, os.path.join(d, "lint.py"), "--base", "HEAD", "--versions", d + "/fixtures/VERSIONS",
                               d + "/fixtures/" + name], capture_output=True, text=True, env={**os.environ, **(env or {})})
    def test_good_passes(self):
        r = self.run_cli("V9001__good.sql"); self.assertEqual(r.returncode, 0, r.stdout)
    def test_bad_fails_with_link(self):
        r = self.run_cli("V9002__bad.sql"); self.assertEqual(r.returncode, 1)
        for rule in ("MIG-001", "MIG-002", "MIG-003", "MIG-004", "MIG-007"): self.assertIn(rule, r.stdout)
        self.assertIn("migration-playbook.md#concurrent-indexes", r.stdout)
    def test_contract_label_clears_007(self):
        self.assertNotIn("MIG-007", self.run_cli("V9002__bad.sql", {"CONTRACT_REF": "PR-1"}).stdout)

if __name__ == "__main__":
    unittest.main()
