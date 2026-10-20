#!/usr/bin/env python3
"""QA-5 (S12) PLT-033 AC1: the SQL-ownership scan reports map gaps and violations on bad samples, accepts good ones.
Run from repo root: python3 -m unittest scripts/ci/test_s12qa_plt033_scan.py"""
import os, sys, tempfile, textwrap, unittest
sys.path.insert(0, "scripts/ci")
import arch
import yaml


def tree(migration, owners, java):
    root = tempfile.mkdtemp(prefix="s12qa-plt033-")
    os.makedirs(f"{root}/{arch.MIG}"); os.makedirs(f"{root}/docs/architecture")
    open(f"{root}/{arch.MIG}/V1__init.sql", "w").write(textwrap.dedent(migration))
    yaml.safe_dump({"modules": owners}, open(f"{root}/{arch.OWNERSHIP}", "w"))
    for rel, src in java.items():
        os.makedirs(os.path.dirname(f"{root}/{arch.SRC}/{rel}"), exist_ok=True)
        open(f"{root}/{arch.SRC}/{rel}", "w").write(textwrap.dedent(src))
    return root


MIG = """
CREATE TABLE staff (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, name text);
CREATE TABLE sessions (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, staff_id uuid);
CREATE TABLE departments (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, name text);
CREATE TABLE identity.subjects (id uuid PRIMARY KEY);
CREATE TABLE consent.grants (id uuid PRIMARY KEY);
CREATE TABLE master.plans (id uuid PRIMARY KEY);
"""
GOOD = {"iam": {"packages": ["iam", "auth", "staff"], "owns": ["staff", "sessions", "identity.subjects", "consent.grants"]},
        "organization": {"owns": ["departments"]}}
AUTH = '''\
package com.nabd.hms.auth;
class SessionRepo { void add(UUID t) { jdbc.update("INSERT INTO sessions (id, tenant_id, staff_id) VALUES (?,?,?)", a, t, s); }
  String who(UUID t) { return jdbc.queryForObject("SELECT name FROM staff WHERE tenant_id = ? AND id = ?", String.class, t, s); } }
'''
CLINICAL = '''\
package com.nabd.hms.clinical;
class NoteRepo { String actor(UUID t) { return jdbc.queryForObject("SELECT s.name FROM staff s WHERE s.tenant_id = ? AND s.id = ?", String.class, t, s); }
  void dept(UUID t) { jdbc.update("UPDATE departments SET name = ? WHERE tenant_id = ? AND id = ?", n, t, d); } }
'''


def details(root):
    return [(x["rule"], x["module"], x["detail"]) for x in arch.scan(root)]


class OwnershipScan(unittest.TestCase):
    def test_good_map_and_own_package_sql_is_clean(self):
        # auth package is part of iam (packages:), so its staff/sessions SQL is own-table SQL
        self.assertEqual(details(tree(MIG, GOOD, {"auth/SessionRepo.java": AUTH})), [])

    def test_table_without_owner_reported_per_schema_master_exempt(self):
        owners = {"iam": {"packages": ["iam", "auth", "staff"], "owns": ["staff", "sessions"]}, "organization": {"owns": ["departments"]}}
        d = details(tree(MIG, owners, {}))
        self.assertIn(("sql-ownership", "app", "table identity.subjects has no owner in table-ownership.yaml"), d)
        self.assertIn(("sql-ownership", "app", "table consent.grants has no owner in table-ownership.yaml"), d)
        self.assertFalse(any("master.plans" in x[2] for x in d), "master schema is outside public/identity/consent")

    def test_table_with_two_owners_reported(self):
        owners = {**GOOD, "organization": {"owns": ["departments", "staff"]}}
        d = details(tree(MIG, owners, {}))
        self.assertTrue(any(x[2].startswith("table staff has 2 owners") for x in d), d)

    def test_foreign_read_and_write_reported_against_owner(self):
        d = details(tree(MIG, GOOD, {"clinical/NoteRepo.java": CLINICAL}))
        self.assertIn(("sql-ownership", "clinical", "reads staff owned by iam, not in clinical reads allow-list"), d)
        self.assertIn(("sql-ownership", "clinical", "writes departments owned by organization"), d)

    def test_read_allow_list_accepts_listed_read_only(self):
        owners = {**GOOD, "clinical": {"owns": [], "reads": ["staff"]}}
        d = details(tree(MIG, owners, {"clinical/NoteRepo.java": CLINICAL}))
        self.assertFalse(any("reads staff" in x[2] for x in d), d)
        self.assertIn(("sql-ownership", "clinical", "writes departments owned by organization"), d)

    @unittest.expectedFailure  # known-defect-PLT-033: arch.schema() ignores ALTER TABLE ... RENAME TO (latent: no migration renames yet)
    def test_renamed_table_needs_its_new_name_owned(self):
        mig = MIG + "ALTER TABLE departments RENAME TO org_units;\n"
        owners = {**GOOD, "organization": {"owns": ["departments"]}}
        d = details(tree(mig, owners, {}))
        self.assertTrue(any("org_units has no owner" in x[2] for x in d), d)

    def test_real_repo_map_has_no_gaps(self):
        tables, _ = arch.schema(".")
        _, _, owners, _ = arch.ownership(".")
        self.assertEqual(arch.map_gaps(tables, owners), [])
        self.assertGreater(len(tables), 100)


if __name__ == "__main__":
    unittest.main()
