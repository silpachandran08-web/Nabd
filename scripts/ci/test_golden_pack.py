#!/usr/bin/env python3
"""FIN-010: the golden-pack sign-off gate. Run from the repo root: python3 -m unittest scripts/ci/test_golden_pack.py"""
import io, contextlib, json, os, shutil, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(__file__))
import golden_pack as g

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def run(*argv):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = g.main(list(argv))
    return rc, buf.getvalue()


class GoldenPackGate(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        for f in g.rule_sources(REPO) + [os.path.join(g.PACKS, "in", n) for n in ("cases.json", "signoff.json")]:
            dst = os.path.join(self.tmp, f)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy(os.path.join(REPO, f), dst)
        open(os.path.join(self.tmp, "sig.pdf"), "wb").write(b"signed by the CA")
        self.pack = os.path.join(self.tmp, g.PACKS, "in")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def edit(self, rel, fn):
        p = os.path.join(self.tmp, rel)
        s = open(p).read()
        open(p, "w").write(fn(s))

    def test_the_repository_pack_passes_the_gate(self):
        rc, out = run("check", "--root", REPO)
        self.assertEqual(rc, 0, out)

    def test_pack_has_at_least_forty_cases_with_review_blocks(self):
        doc = json.load(open(os.path.join(REPO, g.PACKS, "in", "cases.json")))
        self.assertGreaterEqual(len(doc["cases"]), 40)
        self.assertTrue(all(set(c["review"]) == {"ruleSetVersion", "reviewer", "signedOn"} for c in doc["cases"]))

    def test_a_tax_rule_change_without_a_re_sign_fails(self):
        self.edit("backend/src/main/java/com/nabd/hms/billing/tax/IndiaGst.java", lambda s: s + "\n// changed\n")
        rc, out = run("check", "--root", self.tmp)
        self.assertEqual(rc, 1)
        self.assertIn("golden pack not re-signed", out)

    def test_a_new_tax_rules_migration_without_a_re_sign_fails(self):
        d = os.path.join(self.tmp, "db/migrations")
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, "V9999__rate_change.sql"), "w").write("UPDATE tax_rules SET rate = 12 WHERE jurisdiction = 'IN';\n")
        rc, out = run("check", "--root", self.tmp)
        self.assertEqual((rc, "golden pack not re-signed" in out), (1, True))

    def test_a_changed_expected_figure_without_a_re_sign_fails(self):
        self.edit(os.path.join(g.PACKS, "in", "cases.json"), lambda s: __import__("re").sub(r'"totalMinor": (\d+)', lambda m: '"totalMinor": %d' % (int(m.group(1)) + 1), s, count=1))
        rc, out = run("check", "--root", self.tmp)
        self.assertEqual((rc, "cases.json changed" in out), (1, True))

    def test_rehash_after_a_change_passes_as_pending_and_blocks_a_release(self):
        self.edit("backend/src/main/java/com/nabd/hms/billing/tax/FlatVat.java", lambda s: s + "\n// changed\n")
        self.assertEqual(run("rehash", "--root", self.tmp)[0], 0)
        self.assertEqual(run("check", "--root", self.tmp)[0], 0)
        rc, out = run("check", "--root", self.tmp, "--require-signed")
        self.assertEqual((rc, "pending CA review" in out), (1, True))

    def test_signing_records_the_reviewer_on_every_case_and_a_later_change_unsigns(self):
        rc, out = run("sign", "--root", self.tmp, "--pack", "IN-GST", "--reviewer", "Test Reviewer", "--membership", "000000",
                      "--date", "2026-10-01", "--signature-file", "sig.pdf")
        self.assertEqual(rc, 0, out)
        self.assertEqual(run("check", "--root", self.tmp, "--require-signed")[0], 0)
        doc = json.load(open(os.path.join(self.pack, "cases.json")))
        self.assertTrue(all(c["review"] == {"ruleSetVersion": "IN-GST-1", "reviewer": "Test Reviewer", "signedOn": "2026-10-01"} for c in doc["cases"]))
        self.edit("backend/src/main/java/com/nabd/hms/billing/tax/RuleBasedTaxEngine.java", lambda s: s + "\n// changed\n")
        rc, out = run("check", "--root", self.tmp, "--require-signed")
        self.assertEqual((rc, "golden pack not re-signed" in out), (1, True))
        self.assertEqual(run("rehash", "--root", self.tmp)[0], 0)
        self.assertEqual(json.load(open(os.path.join(self.pack, "signoff.json")))["status"], "pending-ca-review")

    def test_signing_with_a_stale_hash_or_missing_file_is_refused(self):
        self.edit("backend/src/main/java/com/nabd/hms/billing/tax/IndiaGst.java", lambda s: s + "\n// changed\n")
        args = ["sign", "--root", self.tmp, "--pack", "IN-GST", "--reviewer", "R", "--membership", "1", "--date", "2026-10-01"]
        self.assertEqual(run(*args, "--signature-file", "sig.pdf")[0], 1)
        self.assertEqual(run("rehash", "--root", self.tmp)[0], 0)
        self.assertEqual(run(*args, "--signature-file", "missing.pdf")[0], 1)

    def test_a_pack_below_its_minimum_fails(self):
        p = os.path.join(self.pack, "cases.json")
        doc = json.load(open(p))
        doc["cases"] = doc["cases"][:5]
        json.dump(doc, open(p, "w"))
        rc, out = run("check", "--root", self.tmp)
        self.assertEqual((rc, "needs at least 40" in out), (1, True))

    def test_a_case_without_the_review_record_fails(self):
        p = os.path.join(self.pack, "cases.json")
        doc = json.load(open(p))
        del doc["cases"][3]["review"]
        json.dump(doc, open(p, "w"))
        rc, out = run("check", "--root", self.tmp)
        self.assertEqual((rc, "must record ruleSetVersion" in out), (1, True))


if __name__ == "__main__":
    unittest.main()
