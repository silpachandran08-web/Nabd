"""CLN-018: feed validator. Brands are synthetic placeholders (BRAND0001...), generated here; no real KB content."""
import csv
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import feed_validate as fv  # noqa: E402


def write(path, header, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


class FeedValidate(unittest.TestCase):
    def run_case(self, resolved, total=500, blank_strength=0):
        d = pathlib.Path(tempfile.mkdtemp())
        top = [(f"BRAND{i:04d}", i) for i in range(1, total + 1)]
        feed = [(f"brand {i:04d}", "GENERIC", "" if i <= blank_strength else "10 mg") for i in range(1, resolved + 1)]
        write(d / "top.csv", ["brand", "rank"], top)
        write(d / "feed.csv", ["brand", "generic", "strength", "extra"], [r + ("x",) for r in feed])
        rc = fv.main(["--feed", str(d / "feed.csv"), "--top500", str(d / "top.csv"), "--report", str(d / "r.md")])
        return rc, (d / "r.md").read_text()

    def test_boundary_475_of_500_passes_474_fails(self):
        self.assertEqual(self.run_case(475)[0], 0)
        rc, report = self.run_case(474)
        self.assertEqual(rc, 1)
        self.assertIn("FAIL", report)
        self.assertIn("BRAND0475", report)

    def test_brand_without_strength_is_not_resolved(self):
        self.assertEqual(self.run_case(500, blank_strength=26)[0], 1)  # 474 usable
        self.assertEqual(self.run_case(500, blank_strength=25)[0], 0)  # 475 usable

    def test_fewer_than_500_brands_cannot_pass(self):
        self.assertEqual(self.run_case(100, total=100)[0], 1)

    def test_missing_column_is_a_clear_error(self):
        d = pathlib.Path(tempfile.mkdtemp())
        write(d / "top.csv", ["brand"], [["A"]])
        write(d / "feed.csv", ["brand", "generic"], [["A", "g"]])
        with self.assertRaises(SystemExit):
            fv.main(["--feed", str(d / "feed.csv"), "--top500", str(d / "top.csv")])


if __name__ == "__main__":
    unittest.main()
