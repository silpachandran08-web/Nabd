#!/usr/bin/env python3
"""FEF-041: catalogue completeness and the clinical-review gate (python3 scripts/ci/test_patient_catalogue.py)."""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import patient_catalogue as pc  # noqa: E402

SQL = ("INSERT INTO template_catalogue (key, language, category, body, example_values, slot_types, purpose) VALUES\n"
       "('hello','en','UTILITY','Hi {{1}}.',ARRAY['A']::text[],ARRAY['x']::text[],'p'),\n"
       "('hello','hi','UTILITY','Namaste {{1}}.',ARRAY['A']::text[],ARRAY['x']::text[],'p'),\n"
       "('hello','kn','UTILITY','Namaskara {{1}}.',ARRAY['A']::text[],ARRAY['x']::text[],'p'),\n"
       "('hello','te','UTILITY','Namaskaram {{1}}.',ARRAY['A']::text[],ARRAY['x']::text[],'p');\n")


def fixture(tmp, patient=None, sql=SQL, review=None):
    root = pathlib.Path(tmp)
    patient = patient or {l: {"rxValid": f"{l} valid {{date}}", "title": f"{l} title"} for l in pc.LOCALES}
    for l in pc.LOCALES:
        d = root / "frontend/packages/i18n/messages" / l
        d.mkdir(parents=True)
        (d / "patient.json").write_text(json.dumps(patient[l], ensure_ascii=False), encoding="utf-8")
    (root / "db/migrations").mkdir(parents=True)
    (root / "db/migrations/V1__t.sql").write_text(sql, encoding="utf-8")
    (root / "frontend/packages/i18n/catalogue").mkdir()
    (root / "frontend/packages/i18n/catalogue/review.json").write_text(
        json.dumps(review or {"clinicalKeys": {"patient": ["rxValid"], "templates": []}, "reviews": []}), encoding="utf-8")
    return root


def copies(root):
    return [root / "a.json", root / "b.json"]


def sync(root):
    for c in copies(root):
        c.write_text(pc.render(pc.build(root)), encoding="utf-8")


class PatientCatalogueTest(unittest.TestCase):
    def run_check(self, root, release=False):
        return pc.check(root, release=release, copies=copies(root))[0]

    def test_repository_catalogue_is_complete_and_current(self):
        self.assertEqual(pc.check()[0], [])

    def test_repository_release_gate_blocks_machine_drafts(self):
        problems = pc.check(release=True)[0]
        self.assertTrue(any("te/patient/rxValid" in p for p in problems), "unreviewed clinical keys block a release")

    def test_complete_catalogue_passes_and_copies_must_match(self):
        with tempfile.TemporaryDirectory() as t:
            root = fixture(t)
            self.assertTrue(any("out of date" in p for p in self.run_check(root)))
            sync(root)
            self.assertEqual(self.run_check(root), [])

    def test_missing_telugu_key_and_missing_template_language_fail(self):
        with tempfile.TemporaryDirectory() as t:
            patient = {l: {"rxValid": f"{l} {{date}}", "title": "t"} for l in pc.LOCALES}
            del patient["te"]["title"]
            root = fixture(t, patient=patient, sql=SQL.replace("('hello','kn'", "('other','kn'"))
            sync(root)
            problems = self.run_check(root)
            self.assertIn("patient/te: missing title", problems)
            self.assertIn("templates/hello: missing kn", problems)
            self.assertIn("templates/other: missing en", problems)

    def test_changed_placeholder_fails(self):
        with tempfile.TemporaryDirectory() as t:
            patient = {l: {"rxValid": "x {date}", "title": "t"} for l in pc.LOCALES}
            patient["hi"]["rxValid"] = "x {dat}"
            root = fixture(t, patient=patient)
            sync(root)
            self.assertIn("patient/hi/rxValid: placeholders differ from en", self.run_check(root))

    def test_release_needs_reviewer_id_date_and_matching_text(self):
        with tempfile.TemporaryDirectory() as t:
            root = fixture(t)
            sync(root)
            self.assertEqual(len([p for p in self.run_check(root, release=True) if "not reviewed" in p]), 3)
            text = {l: f"{l} valid {{date}}" for l in pc.LOCALES}
            reviews = [{"locale": l, "ns": "patient", "key": "rxValid", "reviewerId": "clinician-test", "reviewedOn": "2026-01-02",
                        "textSha": pc.sha(text[l])} for l in pc.REVIEWED_LOCALES]
            (root / "frontend/packages/i18n/catalogue/review.json").write_text(
                json.dumps({"clinicalKeys": {"patient": ["rxValid"], "templates": []}, "reviews": reviews}), encoding="utf-8")
            sync(root)
            self.assertEqual(self.run_check(root, release=True), [], "reviewed with id, date and the exact text")
            art = pc.build(root)
            self.assertEqual(art["review"]["te"]["patient"]["rxValid"]["reviewerId"], "clinician-test")
            self.assertEqual(art["review"]["te"]["patient"]["title"], {"status": "unreviewed"})
            reviews[2]["reviewerId"] = " "
            (root / "frontend/packages/i18n/catalogue/review.json").write_text(
                json.dumps({"clinicalKeys": {"patient": ["rxValid"], "templates": []}, "reviews": reviews}), encoding="utf-8")
            sync(root)
            problems = self.run_check(root, release=True)
            self.assertTrue(any("reviewerId missing" in p for p in problems))
            self.assertTrue(any("te/patient/rxValid" in p and "not reviewed" in p for p in problems))

    def test_editing_a_reviewed_text_makes_the_review_stale(self):
        with tempfile.TemporaryDirectory() as t:
            root = fixture(t)
            reviews = [{"locale": "hi", "ns": "patient", "key": "rxValid", "reviewerId": "c", "reviewedOn": "2026-01-02", "textSha": pc.sha("old text")}]
            (root / "frontend/packages/i18n/catalogue/review.json").write_text(
                json.dumps({"clinicalKeys": {"patient": ["rxValid"], "templates": []}, "reviews": reviews}), encoding="utf-8")
            sync(root)
            self.assertTrue(any("stale" in p for p in self.run_check(root)))
            self.assertEqual(pc.build(root)["review"]["hi"]["patient"]["rxValid"]["status"], "unreviewed")

    def test_version_changes_with_the_content(self):
        with tempfile.TemporaryDirectory() as t:
            root = fixture(t)
            v1 = pc.build(root)["version"]
            (root / "frontend/packages/i18n/messages/te/patient.json").write_text(json.dumps({"rxValid": "te changed {date}", "title": "x"}), encoding="utf-8")
            self.assertNotEqual(v1, pc.build(root)["version"])


if __name__ == "__main__":
    unittest.main()
