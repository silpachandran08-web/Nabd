#!/usr/bin/env python3
"""PLT-052 gate tests: python3 scripts/ci/test_classification_check.py (from repo root)."""
import glob, io, json, os, re, sys, unittest
from contextlib import redirect_stdout
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import classification_check as cc

FIX = "scripts/ci/fixtures/classification/"
SPECS = os.environ.get("NABD_SPECS", "/mnt/user-data/uploads/Nabd/nabd-workbench/data/specs.json")
MODELS = ["EP07-whatsapp", "EP08-consent", "EP12-patient-access-edge", "EP19-uploads-secure-links", "EP20-tax-billing"]


def run(*argv):
    buf = io.StringIO()
    with redirect_stdout(buf):
        rc = cc.main(list(argv))
    return rc, buf.getvalue()


class Gate(unittest.TestCase):
    def test_failing_sample_migration_fails(self):
        rc, out = run(FIX + "V9991__adds_untagged_column.sql")
        self.assertEqual(rc, 1)
        self.assertIn("public.patients.blood_group has no tier", out)
        self.assertIn("identity.person_links.id has no tier", out)
        self.assertNotIn("person_links_tenant_fk", out)  # constraints are not columns

    def test_passing_sample_migration_passes(self):
        rc, out = run(FIX + "V9992__adds_tagged_columns.sql")
        self.assertEqual(rc, 0, out)

    def test_parser_scope(self):
        cols = cc.added_columns("""
            CREATE TABLE master.x (a int); CREATE TABLE p_y PARTITION OF p FOR VALUES IN (1);
            ALTER TABLE consent.notices ADD COLUMN a text, ADD b int, ADD CONSTRAINT c CHECK (b > 0) NOT VALID;
            ALTER TABLE t RENAME COLUMN old TO "new"; CREATE TABLE "Q" (id numeric(10, 2), k int DEFAULT f(1, 2));""")
        self.assertEqual(cols, [("consent", "notices", "a"), ("consent", "notices", "b"), ("public", "t", "new"),
                                ("public", "q", "id"), ("public", "q", "k")])

    def test_every_existing_migration_column_parses_to_a_tag_or_was_dropped_later(self):
        tags = cc.load()
        found = {f"{s}.{t}.{c}" for f in glob.glob("db/migrations/V*.sql") for s, t, c in cc.added_columns(open(f).read())}
        self.assertEqual(set(tags) - found, set(), "tagged columns the static scan cannot see")

    def test_db_mode_reports_untagged_and_stale(self):
        tags = {"public.a.x": "pii", "public.a.gone": "phi"}
        self.assertEqual(cc.check_db({"public.a.x", "public.a.new"}, tags),
                         ["public.a.new: column has no tier in db/classification.yaml",
                          "public.a.gone: tagged in db/classification.yaml but not in the schema (remove the stale tag)"])

    def test_classification_file_is_valid(self):
        tags = cc.load()
        self.assertGreater(len(tags), 1000)
        self.assertEqual(tags["public.clinical_notes.diagnosis"], "phi")
        self.assertEqual(tags["public.patients.name"], "pii")
        self.assertEqual(tags["public.invoices.total"], "financial")
        bad = "schemas:\n  public:\n    t:\n      c: secret\n"
        path = os.path.join(os.environ.get("TMPDIR", "/tmp"), "plt052-bad.yaml")
        open(path, "w").write(bad)
        with self.assertRaises(ValueError):
            cc.load(path)


class Docs(unittest.TestCase):
    def test_standard_covers_tiers_and_channels(self):
        doc = open("docs/security/data-classification.md").read()
        for word in ["`phi`", "`pii`", "`financial`", "`operational`", "**Logs**", "**Caches**", "**Analytics",
                     "**Exports**", "**Messaging**", "DPO"]:
            self.assertIn(word, doc)

    def test_threat_models_have_stride_and_linddun(self):
        for m in MODELS:
            doc = open(f"docs/security/threat-models/{m}.md").read()
            for cat in ["Spoofing", "Tampering", "Repudiation", "Information disclosure", "Denial of service",
                        "Elevation of privilege", "LINDDUN", "Linkability"]:
                self.assertIn(cat, doc, f"{m}: {cat}")
            self.assertGreaterEqual(len(re.findall(r"\bT-\d{3}\b", doc)), 10, m)

    @unittest.skipUnless(os.path.exists(SPECS), "tracker (specs.json) not available")
    def test_threat_model_task_ids_exist_in_tracker(self):
        known = {t["id"] for t in json.load(open(SPECS))["tracker"]}
        for m in MODELS:
            ids = set(re.findall(r"\bT-\d{3}\b", open(f"docs/security/threat-models/{m}.md").read()))
            self.assertEqual(ids - known, set(), m)


if __name__ == "__main__":
    unittest.main(verbosity=2)
