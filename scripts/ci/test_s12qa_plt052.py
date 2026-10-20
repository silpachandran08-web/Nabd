#!/usr/bin/env python3
"""Independent QA (S12 PLT-052): classification standard, the untagged-column gate on QA's own bad migrations, and the
five threat models. Run from the repo root: python3 scripts/ci/test_s12qa_plt052.py
(The --db coverage of the real migrated schema is S12QaPlt052ClassificationDbTest, backend.)"""
import json, os, re, subprocess, sys, tempfile, textwrap, unittest
from pathlib import Path

import yaml

sys.path.insert(0, "scripts/ci")
import classification_check as cc

DOC = Path("docs/security/data-classification.md")
MODELS = {"EP07": "whatsapp", "EP08": "consent", "EP12": "patient-access-edge", "EP19": "uploads-secure-links", "EP20": "tax-billing"}
SPECS = os.environ.get("NABD_SPECS", "/mnt/user-data/uploads/Nabd/nabd-workbench/data/specs.json")
STRIDE = ["Spoofing", "Tampering", "Repudiation", "Information disclosure", "Denial of service", "Elevation of privilege"]
LINDDUN = ["Linkability", "Identifiability", "Non-repudiation", "Detectability", "Disclosure of information", "Unawareness", "Non-compliance"]


def gate(sql):
    """Runs the CI static gate on one migration file; returns (rc, output)."""
    with tempfile.TemporaryDirectory() as d:
        f = Path(d, "V9999__qa_sample.sql")
        f.write_text(textwrap.dedent(sql))
        r = subprocess.run([sys.executable, "scripts/ci/classification_check.py", str(f)], capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


class Standard(unittest.TestCase):
    def setUp(self):
        self.doc = DOC.read_text()

    def test_four_tiers_are_defined(self):
        tiers = re.search(r"## Tiers(.*?)\n## ", self.doc, re.S).group(1)
        for t in ("phi", "pii", "financial", "operational"):
            self.assertRegex(tiers, rf"\|\s*`{t}`\s*\|\s*\w", t)

    def test_every_channel_has_a_rule_for_every_tier(self):
        rules = re.search(r"## Handling rules(.*?)\n## ", self.doc, re.S).group(1)
        for ch in ("Logs", "Caches", "Analytics", "Exports", "Messaging"):
            row = next((l for l in rules.splitlines() if l.startswith(f"| **{ch}")), None)
            self.assertIsNotNone(row, ch)
            cells = [c.strip() for c in row.strip("|").split("|")]
            self.assertEqual(len(cells), 5, ch)
            self.assertTrue(all(cells[1:]), f"{ch}: empty rule cell")
        self.assertRegex(rules, r"WhatsApp")

    # Needs external action: AC1 requires DPO approval; the approval table is empty. Kept as an expected failure.
    @unittest.expectedFailure
    def test_standard_is_approved_by_the_dpo(self):
        row = next(l for l in self.doc.splitlines() if l.startswith("| DPO"))
        self.assertNotIn("pending", row.lower())


class UntaggedColumnGate(unittest.TestCase):
    def test_new_table_in_identity_schema_with_untagged_column_fails(self):
        rc, out = gate("CREATE TABLE identity.qa_people (id uuid PRIMARY KEY, nickname text NOT NULL);\n")
        self.assertEqual(rc, 1, out)
        self.assertIn("identity.qa_people.nickname has no tier", out)
        self.assertIn("identity.qa_people.id has no tier", out)

    def test_add_column_to_existing_public_table_fails_and_names_the_column(self):
        rc, out = gate("ALTER TABLE patients ADD COLUMN qa_blood_group text;\n")
        self.assertEqual(rc, 1, out)
        self.assertIn("public.patients.qa_blood_group has no tier", out)

    def test_second_add_column_in_one_statement_is_seen(self):
        rc, out = gate("ALTER TABLE patients ADD COLUMN name text, ADD COLUMN IF NOT EXISTS qa_second text;\n")
        self.assertEqual(rc, 1, out)
        self.assertIn("public.patients.qa_second", out)
        self.assertNotIn("public.patients.name has no tier", out)  # already tagged

    def test_rename_to_an_untagged_name_fails(self):
        rc, out = gate("ALTER TABLE patients RENAME COLUMN name TO qa_full_name;\n")
        self.assertEqual(rc, 1, out)
        self.assertIn("public.patients.qa_full_name", out)

    def test_quoted_mixed_case_consent_column_fails(self):
        rc, out = gate('CREATE TABLE "consent"."QaConsents" ("Id" uuid, "Scope" text, CONSTRAINT qa_pk PRIMARY KEY ("Id"));\n')
        self.assertEqual(rc, 1, out)
        self.assertIn("consent.qaconsents.scope", out)
        self.assertNotIn("qa_pk", out)

    def test_constraints_and_indexes_are_not_columns(self):
        rc, out = gate("""
            ALTER TABLE patients ADD CONSTRAINT qa_chk CHECK (true);
            CREATE INDEX qa_idx ON patients (name);
        """)
        self.assertEqual(rc, 0, out)

    def test_fully_tagged_migration_passes(self):
        rc, out = gate("ALTER TABLE patients ADD COLUMN name text;\nCREATE TABLE IF NOT EXISTS invoices (id uuid, total numeric);\n")
        self.assertEqual(rc, 0, out)

    def test_master_schema_is_out_of_scope(self):
        rc, out = gate("CREATE TABLE master.qa_ops (id uuid, note text);\n")
        self.assertEqual(rc, 0, out)

    def test_unknown_tier_in_the_file_is_refused(self):
        with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
            f.write("schemas:\n  public:\n    patients:\n      name: secret\n")
        try:
            with self.assertRaises(ValueError):
                cc.load(f.name)
        finally:
            os.unlink(f.name)

    def test_db_mode_reports_untagged_and_stale(self):
        tags = {"public.patients.name": "pii", "public.gone.col": "phi"}
        problems = cc.check_db({"public.patients.name", "public.patients.qa_new"}, tags)
        self.assertEqual(len(problems), 2)
        self.assertIn("public.patients.qa_new: column has no tier", problems[0])
        self.assertIn("public.gone.col: tagged", problems[1])

    def test_ci_runs_static_gate_on_migration_prs_and_db_drift_after_migrate(self):
        wf = Path(".github/workflows/migrations.yml").read_text()
        self.assertIn("classification_check.py --base", wf)
        self.assertIn("classification_check.py --db", wf)
        doc = yaml.safe_load(wf)
        self.assertIn("db/**", json.dumps(doc.get(True, doc.get("on"))))
        for job in doc["jobs"].values():
            for s in job["steps"]:
                if "classification_check" in s.get("run", ""):
                    self.assertFalse(s.get("continue-on-error", False))
                    self.assertFalse(job.get("continue-on-error", False))

    # was expectedFailure (QA-4 finding 4); fixed in S12 FIX-B: CTAS columns are scanned statically.
    def test_create_table_as_select_with_untagged_columns_fails_statically(self):
        rc, out = gate("CREATE TABLE qa_copy AS SELECT name AS qa_alias FROM patients;\n")
        self.assertEqual(rc, 1, out)

    def test_log_fields_are_all_tagged(self):
        y = yaml.safe_load(Path("db/classification.yaml").read_text())
        self.assertTrue(set(y["log_fields"]) >= {"timestamp", "level", "logger", "message", "traceId"})
        self.assertTrue(all(v in cc.TIERS for v in y["log_fields"].values()))


class ThreatModels(unittest.TestCase):
    def doc(self, ep):
        return Path(f"docs/security/threat-models/{ep}-{MODELS[ep]}.md").read_text()

    def rows(self, ep):
        """Threat rows of the STRIDE and LINDDUN tables: [(category, task cell)]."""
        out = []
        for l in self.doc(ep).splitlines():
            cells = [c.strip() for c in l.strip().strip("|").split("|")]
            if l.startswith("|") and len(cells) >= 4 and any(c in l for c in STRIDE + LINDDUN) and not set(l) <= set("|- "):
                out.append((l, cells[-1]))
        return out

    def test_each_model_covers_all_stride_and_linddun_categories(self):
        for ep in MODELS:
            d = self.doc(ep)
            for cat in STRIDE + LINDDUN:
                self.assertIn(cat, d, f"{ep}: {cat}")

    @unittest.skipUnless(os.path.exists(SPECS), "tracker not available")
    def test_every_task_id_is_a_real_tracker_task(self):
        known = {t["id"] for t in json.load(open(SPECS))["tracker"]}
        for ep in MODELS:
            ids = set(re.findall(r"\bT-\d{3}\b", self.doc(ep)))
            self.assertTrue(ids, ep)
            self.assertEqual(ids - known, set(), ep)

    # known-defect-PLT-052: AC4 "mitigations linked to tracker tasks": EP20 LINDDUN Non-repudiation row is "gap: no tracker task".
    @unittest.expectedFailure
    def test_every_threat_row_mitigation_links_a_tracker_task(self):
        missing = [f"{ep}: {l}" for ep in MODELS for l, task in self.rows(ep) if not re.search(r"\bT-\d{3}\b", task)]
        self.assertEqual(missing, [])

    @unittest.skipUnless(os.path.exists(SPECS), "tracker not available")
    def test_models_recorded_before_the_first_build_sprint_of_their_epic_stories(self):
        stories = {s["id"]: s for s in json.load(open(SPECS))["stories"]}
        current = 3  # Sprint 12 = workbench S3
        built = []
        for ep in MODELS:
            scope = re.search(r"Stor(?:y|ies) (.*?)\. ", self.doc(ep), re.S)
            self.assertIsNotNone(scope, ep)
            for sid in re.findall(r"\b[A-Z]{3}-\d{3}\b", scope.group(1)):
                sp = stories.get(sid, {}).get("sprint", "")
                if sp and int(sp[1:]) <= current and sid != "PLT-052":
                    built.append(f"{ep} {sid} {sp}")
        # FIN-001 (payments, S1) is the only in-scope story already built; the tax stories (FIN-006+) start S4.
        self.assertEqual(built, ["EP20 FIN-001 S1"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
