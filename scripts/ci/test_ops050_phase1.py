#!/usr/bin/env python3
"""OPS-050: Phase 1 exit gate tooling on synthetic samples: python3 scripts/ci/test_ops050_phase1.py (from repo root)."""
import csv, datetime as dt, json, os, pathlib, shutil, tempfile, unittest
import phase1_gate as g

D = dt.date


def write(path, header, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f); w.writerow(header); w.writerows(rows)
    return path


class Sus(unittest.TestCase):
    def test_standard_scoring(self):
        self.assertEqual(g.sus_score([3] * 10), 50.0)
        self.assertEqual(g.sus_score([5, 1] * 5), 100.0)
        self.assertEqual(g.sus_score([1, 5] * 5), 0.0)
        with self.assertRaises(ValueError):
            g.sus_score([3] * 9)
        with self.assertRaises(ValueError):
            g.sus_score([0] + [3] * 9)

    def test_mean_against_target(self):
        d = tempfile.mkdtemp()
        hdr = ["clinic", "respondent", "role", "date"] + [f"q{i}" for i in range(1, 11)]
        f = write(f"{d}/sus.csv", hdr, [["A", "r1", "reception", "2027-01-10"] + [5, 1] * 5, ["B", "r2", "reception", "2027-01-10"] + [4, 2] * 5])
        ok, mean, out = g.sus(f)
        self.assertTrue(ok); self.assertEqual(mean, 87.5); self.assertIn("A: mean SUS 100.0 over 1 receptionists", out)
        f = write(f"{d}/low.csv", hdr, [["A", "r1", "reception", "2027-01-10"] + [3] * 10])
        self.assertFalse(g.sus(f)[0])
        shutil.rmtree(d)


class DayClose(unittest.TestCase):
    def test_longest_run_needs_consecutive_exact_days(self):
        d0 = D(2027, 1, 1)
        days = [(d0 + dt.timedelta(days=i), True) for i in range(10)]
        self.assertEqual(g.longest_exact_run(days), 10)
        self.assertEqual(g.longest_exact_run(days[:4] + days[5:]), 5)  # a missing day breaks the run
        broken = list(days); broken[3] = (broken[3][0], False)
        self.assertEqual(g.longest_exact_run(broken), 6)

    def test_exact_means_exact_decimal(self):
        d = tempfile.mkdtemp()
        rows = [["t1", (D(2027, 1, 1) + dt.timedelta(days=i)).isoformat(), "100.00", "100.0"] for i in range(10)]
        ok, out = g.dayclose(write(f"{d}/dc.csv", ["tenant_id", "close_date", "expected", "counted"], rows))
        self.assertTrue(ok, out)
        rows[4][3] = "100.01"
        ok, out = g.dayclose(write(f"{d}/dc.csv", ["tenant_id", "close_date", "expected", "counted"], rows))
        self.assertFalse(ok); self.assertIn("t1: longest exact run 5 days, target 10 -> MISS", out)
        shutil.rmtree(d)


class Uat(unittest.TestCase):
    def test_every_persona_signed_per_clinic(self):
        d = tempfile.mkdtemp(); hdr = ["clinic", "persona", "result", "signed_by", "signed_on", "evidence"]
        rows = [[c, p, "pass", "Clinic owner", "2027-01-12", "link"] for c in ("b1", "b2") for p in g.uat_pack.PERSONAS]
        ok, out = g.uat(write(f"{d}/u.csv", hdr, rows), ["b1", "b2"]); self.assertTrue(ok, out)
        rows[0][2] = "fail"; rows = rows[:-1]
        ok, out = g.uat(write(f"{d}/u.csv", hdr, rows), ["b1", "b2"])
        self.assertFalse(ok)
        self.assertTrue(any("b1 reception: needs result" in x for x in out) and any("NOT SIGNED" in x for x in out), out)
        self.assertFalse(g.uat(write(f"{d}/u.csv", hdr, rows), [])[0])
        shutil.rmtree(d)


class Controls(unittest.TestCase):
    def test_real_controls_report_percent_and_undated_gaps(self):
        ok, cs, undated = g.controls(pathlib.Path("."), [])
        self.assertFalse(ok)  # no JUnit results given: nothing verified
        self.assertEqual(cs["total"], 67)
        self.assertEqual(len(undated), 67)

    def test_ninety_percent_with_dated_gaps_passes(self):
        d = pathlib.Path(tempfile.mkdtemp())
        (d / "docs/security").mkdir(parents=True); (d / "backend/src/test/java").mkdir(parents=True)
        (d / "backend/src/test/java/XTest.java").write_text("class XTest {}")
        ctl = [{"id": f"SC-{i:02d}", "control": "c", "owner": "@team", "tests": ["XTest"] if i <= 61 else [],
                "evidence": [] if i <= 61 else [{"link": "https://x"}], **({"due": "2027-02-01"} if i > 61 else {})} for i in range(1, 68)]
        (d / "docs/security/controls.yaml").write_text(json.dumps({"controls": ctl}))
        junit = d / "junit"; junit.mkdir()
        (junit / "TEST-X.xml").write_text('<testsuite><testcase classname="a.XTest" name="t"/></testsuite>')
        ok, cs, undated = g.controls(d, [str(junit)])
        self.assertEqual(cs["percent"], 91.0); self.assertEqual(undated, []); self.assertTrue(ok)
        ctl[-1].pop("due"); (d / "docs/security/controls.yaml").write_text(json.dumps({"controls": ctl}))
        ok, _, undated = g.controls(d, [str(junit)])
        self.assertFalse(ok); self.assertEqual(undated, ["SC-67"])
        shutil.rmtree(d)


class Report(unittest.TestCase):
    def test_report_rows_have_value_target_status_and_source_and_are_reproducible(self):
        d = pathlib.Path(tempfile.mkdtemp())
        write(d / "eta_within10.csv", ["tenant_id", "value", "n"], [["t1", "0.85", "100"], ["t2", "0.70", "100"]])
        write(d / "wait_median.csv", ["tenant_id", "value", "n"], [["t1", "12.0", "50"]])
        write(d / "stopwatch.csv", "clinic,moment,sample,seconds,date,timer,notes".split(","),
              [["A", m, i, s, "2027-01-10", "qa", ""] for m, s in (("booking", 50), ("walk_in", 40), ("repeat_rx", 25), ("dispense", 30)) for i in range(5)])
        os.environ["SOURCE_DATE_EPOCH"] = "1800000000"
        p = g.report(pathlib.Path("."), d, "abc1234", [], ["b1"], "2027-01-20T00:00:00+00:00")
        m = {x["id"]: x for x in p["metrics"]}
        self.assertEqual(m["eta_within10"]["value"], "77.5% of visits"); self.assertEqual(m["eta_within10"]["status"], "FAIL")
        self.assertEqual(m["wait_median"]["status"], "PASS"); self.assertIn("queue_entry_events", m["wait_median"]["source"])
        self.assertEqual(m["booking"]["status"], "PASS"); self.assertEqual(m["dispense"]["status"], "FAIL")
        self.assertIsNone(m["sus"]["value"]); self.assertEqual(m["sus"]["status"], "FAIL")  # no questionnaire: no data, not a pass
        self.assertEqual(p["result"], "FAIL")
        for x in p["metrics"]:
            self.assertTrue({"value", "target", "status", "source"} <= set(x))
        md = g.to_md(p)
        self.assertIn("| ETA within +/-10 min | 77.5% of visits | >= 80% of visits | FAIL |", md)
        self.assertEqual(json.dumps(p, sort_keys=True), json.dumps(g.report(pathlib.Path("."), d, "abc1234", [], ["b1"], "2027-01-20T00:00:00+00:00"), sort_keys=True))
        shutil.rmtree(d)

    def test_queries_and_runner_exist_for_every_sql_source(self):
        for name in ("eta_within10", "wait_median", "delay_broadcast", "day_close", "whatsapp_booking"):
            self.assertTrue(pathlib.Path(f"docs/gates/phase1/{name}.sql").exists(), name)
            self.assertIn(name, pathlib.Path("docs/gates/phase1/run.sh").read_text())


if __name__ == "__main__":
    unittest.main()
