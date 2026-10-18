"""Independent QA (S9 OPS-002) tests for the migration lint, the workflow wiring and the playbook. Run: python3 ci/migration-lint/test_s9qa_lint.py
Cases marked expectedFailure document real gaps (QA-OPS002-Dn): they pass the suite while the gap exists and turn red the day it is fixed."""
import os, re, shutil, subprocess, sys, tempfile, time, unittest
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import lint

H = "SET lock_timeout = '5s';\n"
def rules(sql, **k): return sorted({r for r, _ in lint.lint_sql(sql, **k)})


class Mig001(unittest.TestCase):
    def test_variants_fail(self):
        for s in ("CREATE INDEX ix ON queue_entries (a);", "create index ix on queue_entries(a);", "CREATE UNIQUE INDEX ix ON queue_entries (a);",
                  "CREATE INDEX IF NOT EXISTS ix ON queue_entries (a);", "CREATE INDEX ix ON public.queue_entries (a);",
                  "CREATE INDEX ix ON queue_entries USING btree (a);", "CREATE INDEX ix\n  ON queue_entries\n  (a);", 'CREATE INDEX ix ON "queue_entries" (a);'):
            self.assertEqual(rules(H + s), ["MIG-001"], s)
    def test_ok(self):
        self.assertEqual(rules(H + "CREATE INDEX CONCURRENTLY IF NOT EXISTS ix ON queue_entries (a);"), [])
        self.assertEqual(rules(H + "CREATE TABLE t (a int);\nCREATE INDEX ix ON t (a);"), [])   # new table, no live traffic
        self.assertEqual(rules(H + "COMMENT ON TABLE q IS 'CREATE INDEX ix ON q(a)';"), [])
    def test_message_links_playbook_anchor_that_exists(self):
        pb = open(os.path.join(ROOT, "docs/architecture/migration-playbook.md")).read().lower()
        for rule, anchor in lint.LINK.items():
            self.assertIn("## " + anchor[1:].replace("-", " "), pb, f"{rule} links {anchor} but playbook has no such heading")


class Mig002_003(unittest.TestCase):
    def test_check_variants(self):
        for s in ("ALTER TABLE q ADD CONSTRAINT c CHECK ((a + 1) > (b * 2));", "ALTER TABLE q\n ADD CONSTRAINT c\n CHECK (a > 0);",
                  "Alter Table Q Add Constraint c Check (a>0);", "ALTER TABLE ONLY q ADD CONSTRAINT c CHECK (length(a) > 0);"):
            self.assertEqual(rules(H + s), ["MIG-002"], s)
        for s in ("ALTER TABLE q ADD CONSTRAINT c CHECK ((a + 1) > (b * 2)) NOT VALID;", "ALTER TABLE q ADD CONSTRAINT c CHECK (a > 0) not valid;"):
            self.assertEqual(rules(H + s), [], s)
    def test_set_not_null_needs_validated_check_on_same_column(self):
        ok = H + "ALTER TABLE q ADD CONSTRAINT c CHECK (a IS NOT NULL) NOT VALID;\nALTER TABLE q VALIDATE CONSTRAINT c;\nALTER TABLE q ALTER COLUMN a SET NOT NULL;"
        self.assertEqual(rules(ok), [])
        self.assertEqual(rules(ok.replace("VALIDATE CONSTRAINT c;", "")), ["MIG-003"])                       # added, never validated
        self.assertEqual(rules(H + "ALTER TABLE q ALTER COLUMN a SET NOT NULL;"), ["MIG-003"])               # no check at all
        self.assertEqual(rules(H + "ALTER TABLE newt ALTER COLUMN a SET NOT NULL;"), ["MIG-003"])           # table not created here
        self.assertEqual(rules(H + "CREATE TABLE t (a int);\nALTER TABLE t ALTER COLUMN a SET NOT NULL;"), [])  # created here: allowed
    @unittest.expectedFailure
    def test_QA_OPS002_D1_unnamed_check_without_not_valid_is_flagged(self):
        self.assertEqual(rules(H + "ALTER TABLE q ADD CHECK (a > 0);"), ["MIG-002"])   # valid PostgreSQL, currently passes the lint
    @unittest.expectedFailure
    def test_QA_OPS002_D2_validating_another_constraint_does_not_unlock_set_not_null(self):
        sql = H + ("ALTER TABLE q ADD CONSTRAINT c CHECK (a IS NOT NULL) NOT VALID;\nALTER TABLE q ADD CONSTRAINT d CHECK (b > 0) NOT VALID;\n"
                   "ALTER TABLE q VALIDATE CONSTRAINT d;\nALTER TABLE q ALTER COLUMN a SET NOT NULL;")
        self.assertEqual(rules(sql), ["MIG-003"])   # c was never validated


class Mig004_007(unittest.TestCase):
    def test_004(self):
        self.assertEqual(rules("CREATE TABLE t (a int);"), ["MIG-004"])
        self.assertEqual(rules("-- SET lock_timeout = '5s';\nCREATE TABLE t (a int);"), ["MIG-004"])         # comment does not count
        self.assertEqual(rules("set local lock_timeout to '3s'; CREATE TABLE t (a int);"), [])
    @unittest.expectedFailure
    def test_QA_OPS002_D3_lock_timeout_zero_is_not_a_timeout(self):
        self.assertEqual(rules("SET lock_timeout = 0; CREATE TABLE t (a int);"), ["MIG-004"])   # 0 = wait forever
    def test_007(self):
        for s in ("DROP TABLE IF EXISTS q;", "ALTER TABLE q DROP COLUMN IF EXISTS a;", "ALTER TABLE ONLY q DROP COLUMN a;",
                  "ALTER TABLE q ALTER COLUMN a TYPE bigint;", "ALTER TABLE q ALTER COLUMN a SET DATA TYPE bigint;", "ALTER TABLE q\n DROP\n COLUMN a;"):
            self.assertEqual(rules(H + s), ["MIG-007"], s)
            self.assertEqual(rules(H + s, contract=True), [], s)
    @unittest.expectedFailure
    def test_QA_OPS002_D4_drop_column_without_COLUMN_keyword_is_flagged(self):
        self.assertEqual(rules(H + "ALTER TABLE q DROP a;"), ["MIG-007"])   # COLUMN is optional in PostgreSQL


class Cli(unittest.TestCase):
    """Real git repo: base has V10; branch adds files. Exercises MIG-005/006, CONTRACT_REF and exit codes through lint.py."""
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.g("init", "-q", "-b", "main")
        os.makedirs(f"{self.d}/db/migrations")
        self.w("db/migrations/V10__base.sql", H + "CREATE TABLE b (a int);\n")
        self.w("db/migrations/VERSIONS", "# owners\nV11 a\nV12 a\nV9 a\n")
        self.g("add", "-A"); self.g("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base")
        self.g("checkout", "-qb", "feat")
    def tearDown(self): shutil.rmtree(self.d, ignore_errors=True)
    def g(self, *a): subprocess.run(["git", *a], cwd=self.d, check=True, capture_output=True)
    def w(self, p, t): open(f"{self.d}/{p}", "w").write(t)
    def commit(self): self.g("add", "-A"); self.g("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "f")
    def run_lint(self, **env):
        e = {k: v for k, v in os.environ.items() if k != "CONTRACT_REF"}; e.update(env)
        r = subprocess.run([sys.executable, f"{HERE}/lint.py", "--base", "main"], cwd=self.d, capture_output=True, text=True, env=e)
        return r.returncode, r.stdout
    def test_good_listed_greater_version_passes(self):
        self.w("db/migrations/V11__ok.sql", H + "CREATE TABLE t (a int);\n"); self.commit()
        rc, out = self.run_lint(); self.assertEqual(rc, 0, out)
    def test_version_equal_to_main_max_fails_MIG005(self):
        self.w("db/migrations/V10__dup.sql", H + "CREATE TABLE t (a int);\n"); self.commit()
        rc, out = self.run_lint(); self.assertEqual(rc, 1); self.assertIn("MIG-005", out)
    def test_version_lower_than_main_max_fails_MIG005(self):
        self.w("db/migrations/V9__low.sql", H + "CREATE TABLE t (a int);\n"); self.commit()
        rc, out = self.run_lint(); self.assertEqual(rc, 1); self.assertIn("MIG-005", out)
    def test_unreserved_version_fails_MIG006(self):
        self.w("db/migrations/V13__nolist.sql", H + "CREATE TABLE t (a int);\n"); self.commit()
        rc, out = self.run_lint(); self.assertEqual(rc, 1); self.assertIn("MIG-006", out)
    def test_two_new_files_same_version_fail(self):
        self.w("db/migrations/V11__a.sql", H + "CREATE TABLE t (a int);\n"); self.w("db/migrations/V11__b.sql", H + "CREATE TABLE u (a int);\n"); self.commit()
        rc, out = self.run_lint(); self.assertEqual(rc, 1); self.assertIn("duplicate", out)
    def test_bad_sql_fails_with_playbook_link_and_contract_ref_clears_only_007(self):
        self.w("db/migrations/V11__bad.sql", "CREATE INDEX ix ON b (a);\nALTER TABLE b DROP COLUMN a;\n"); self.commit()
        rc, out = self.run_lint(); self.assertEqual(rc, 1)
        for r in ("MIG-001", "MIG-004", "MIG-007"): self.assertIn(r, out)
        self.assertIn("migration-playbook.md#concurrent-indexes", out)
        rc, out = self.run_lint(CONTRACT_REF="expand shipped in PR #12")
        self.assertEqual(rc, 1); self.assertNotIn("MIG-007", out); self.assertIn("MIG-001", out)
    def test_blank_contract_ref_does_not_clear_007(self):
        self.w("db/migrations/V11__drop.sql", H + "ALTER TABLE b DROP COLUMN a;\n"); self.commit()
        rc, out = self.run_lint(CONTRACT_REF="   "); self.assertEqual(rc, 1); self.assertIn("MIG-007", out)
    def test_no_added_migrations_passes(self):
        rc, out = self.run_lint(); self.assertEqual(rc, 0, out)
    def test_edit_of_existing_migration_is_not_linted_as_added(self):
        # shortcut in lint.py: --diff-filter=A only; an in-place edit of an applied migration is not caught here (Flyway checksum catches it at migrate)
        self.w("db/migrations/V10__base.sql", "CREATE INDEX ix ON b (a);\n"); self.commit()
        rc, _ = self.run_lint(); self.assertEqual(rc, 0)


class Perf(unittest.TestCase):
    def test_whole_real_migration_directory_lints_under_30s(self):
        files = sorted(f for f in os.listdir(f"{ROOT}/db/migrations") if f.endswith(".sql"))
        t = time.time()
        n = sum(len(lint.lint_sql(open(f"{ROOT}/db/migrations/{f}").read())) for f in files)
        el = time.time() - t
        print(f"lint of {len(files)} real migrations: {el:.2f}s, {n} findings (legacy files are expected to be noisy)")
        self.assertLess(el, 30)


class Wiring(unittest.TestCase):
    def setUp(self):
        import yaml
        self.wf = yaml.safe_load(open(f"{ROOT}/.github/workflows/migrations.yml"))
        self.on = self.wf.get("on") or self.wf.get(True)
    def test_triggers_on_db_changes_and_main(self):
        self.assertIn("db/**", self.on["pull_request"]["paths"]); self.assertIn("main", self.on["push"]["branches"])
    def test_lint_runs_fixture_tests_then_linter_with_full_history(self):
        steps = self.wf["jobs"]["migration-lint"]["steps"]
        self.assertEqual(steps[0]["with"]["fetch-depth"], 0)
        runs = " ".join(s.get("run", "") for s in steps)
        self.assertIn("test_lint.py", runs); self.assertIn("lint.py --base origin/", runs)
        self.assertIn("contract", " ".join(s.get("env", {}).get("CONTRACT_REF", "") for s in steps if isinstance(s.get("env"), dict)))
    def test_migrate_job_gated_by_lint_and_checks_empty_db(self):
        j = self.wf["jobs"]["migrate"]
        self.assertEqual(j["needs"], "migration-lint"); self.assertIn("postgres:16", j["services"]["postgres"]["image"])
        sh = open(f"{HERE}/migrate-check.sh").read()
        self.assertIn("migrate validate", sh); self.assertIn("180", sh)
        self.assertEqual(subprocess.run(["bash", "-n", f"{HERE}/migrate-check.sh"]).returncode, 0)
    def test_schema_docs_job_publishes_artifact_only_on_push(self):
        j = self.wf["jobs"]["schema-docs"]
        self.assertEqual(j["if"], "github.event_name == 'push'")
        self.assertTrue(any("upload-artifact" in s.get("uses", "") for s in j["steps"]))
        self.assertIn("schemaspy", yaml_dump(j))
    def test_pdf_is_marked_superseded(self):
        p = f"{ROOT}/docs/Nabd HMS - Database Architecture.SUPERSEDED.md"
        self.assertTrue(os.path.exists(p)); self.assertIn("superseded", open(p).read().lower())
        self.assertTrue(os.path.exists(f"{ROOT}/docs/Nabd HMS - Database Architecture.pdf"))   # the PDF itself is still there
    def test_versions_file_format_and_no_duplicates(self):
        ls = [l.split() for l in open(f"{ROOT}/db/migrations/VERSIONS") if l.strip() and not l.startswith("#")]
        vs = [l[0] for l in ls]
        self.assertTrue(all(re.fullmatch(r"V\d+", v) and len(l) >= 2 for v, l in zip(vs, ls)))
        self.assertEqual(len(vs), len(set(vs)), "duplicate reservation")
    def test_every_migration_file_added_in_this_tree_is_reserved(self):
        base = next(r for r in ("origin/main", "main", "develop-initial") if subprocess.run(["git", "rev-parse", "--verify", "-q", r], cwd=ROOT, capture_output=True).returncode == 0)
        out = subprocess.run([sys.executable, f"{HERE}/lint.py", "--base", base, "--versions", f"{ROOT}/db/migrations/VERSIONS"], cwd=ROOT, capture_output=True, text=True).stdout
        self.assertNotIn("MIG-006", out, out); self.assertNotIn("MIG-005", out, out)
    @unittest.expectedFailure
    def test_QA_OPS002_D5_lint_passes_on_the_migrations_merged_in_this_tree(self):
        base = next(r for r in ("origin/main", "main", "develop-initial") if subprocess.run(["git", "rev-parse", "--verify", "-q", r], cwd=ROOT, capture_output=True).returncode == 0)
        r = subprocess.run([sys.executable, f"{HERE}/lint.py", "--base", base], cwd=ROOT, capture_output=True, text=True)
        print(r.stdout)
        self.assertEqual(r.returncode, 0, r.stdout)


def yaml_dump(o):
    import yaml
    return yaml.safe_dump(o).lower()


if __name__ == "__main__":
    unittest.main(verbosity=2)
