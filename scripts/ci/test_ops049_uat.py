#!/usr/bin/env python3
"""OPS-049: UAT pack structure and its tools on good and bad synthetic samples: python3 scripts/ci/test_ops049_uat.py (from repo root)."""
import datetime as dt, os, shutil, tempfile, unittest
import uat_pack as u

D = dt.date


def w(d, name, text):
    p = os.path.join(d, name); open(p, "w").write(text); return p


class Pack(unittest.TestCase):
    def test_real_pack_is_complete(self):
        self.assertEqual(u.check_pack("docs/uat"), [])

    def test_missing_persona_and_bad_step_are_found(self):
        d = tempfile.mkdtemp(); shutil.copytree("docs/uat", d, dirs_exist_ok=True)
        os.remove(f"{d}/scripts/nurse.md")
        s = open(f"{d}/scripts/doctor.md").read().replace("| median ≤ 30 s |", "|  |")
        open(f"{d}/scripts/doctor.md", "w").write(s)
        e = u.check_pack(d)
        self.assertTrue(any("nurse" in x for x in e) and any("doctor" in x and "target" in x for x in e), e)
        shutil.rmtree(d)

    def test_every_persona_has_a_timed_step_where_the_story_sets_a_target(self):
        for p, needle in (("reception", "≤ 45 s"), ("reception", "≤ 60 s"), ("doctor", "≤ 30 s"), ("billing", "≤ 60 s")):
            self.assertIn(needle, open(f"docs/uat/scripts/{p}.md").read())


class Tools(unittest.TestCase):
    def setUp(self): self.d = tempfile.mkdtemp()
    def tearDown(self): shutil.rmtree(self.d)

    def sw(self, rows):
        return w(self.d, "sw.csv", "clinic,moment,sample,seconds,date,timer,notes\n" + "".join(f"{c},{m},{i},{s},2026-11-02,qa,\n" for c, m, i, s in rows))

    def test_stopwatch_medians(self):
        good = [("A", m, i, t - 5) for m, t in u.TARGETS.items() for i in range(3)]
        ok, out = u.stopwatch(self.sw(good)); self.assertTrue(ok, out)
        slow = good + [("A", "walk_in", 9, 500), ("A", "walk_in", 10, 500), ("A", "walk_in", 11, 500)]
        ok, out = u.stopwatch(self.sw(slow)); self.assertFalse(ok); self.assertTrue(any("walk_in" in x and "MISS" in x for x in out))
        ok, out = u.stopwatch(self.sw([r for r in good if r[1] != "checkout"])); self.assertFalse(ok); self.assertTrue(any("NO SAMPLES" in x for x in out))
        self.assertIn("A walk_in: median 41 s over 3 samples, target 45 s -> OK", u.stopwatch(self.sw([("A", "walk_in", 1, 40), ("A", "walk_in", 2, 50), ("A", "walk_in", 3, 41)] + [r for r in good if r[1] != "walk_in"]))[1])

    def tr(self, *rows):
        return w(self.d, "t.csv", ",".join(u.TRIAGE_COLS) + "\n" + "".join(",".join(r) + "\n" for r in rows))

    def test_triage_sla(self):
        s1 = ["D1", "A", "2026-11-02", "reception", "arrivals", "S1", "token slip not printing", "eng1", "2026-11-03", "open", "", ""]
        ok, out = u.triage(self.tr(s1), D(2026, 11, 3)); self.assertTrue(ok, out)
        ok, out = u.triage(self.tr(s1), D(2026, 11, 4)); self.assertFalse(ok); self.assertIn("SLA BREACH", out[0])
        late = s1[:9] + ["fixed", "2026-11-04", ""]
        ok, out = u.triage(self.tr(late), D(2026, 11, 5)); self.assertFalse(ok); self.assertIn("fixed late", out[0])
        ontime = s1[:9] + ["fixed", "2026-11-03", ""]
        self.assertTrue(u.triage(self.tr(ontime), D(2026, 11, 9))[0])
        s2 = ["D2", "A", "2026-11-02", "doctor", "rx", "S2", "slow", "eng2", "2026-11-07", "open", "", ""]
        self.assertTrue(u.triage(self.tr(s2), D(2026, 11, 7))[0]); self.assertFalse(u.triage(self.tr(s2), D(2026, 11, 8))[0])
        self.assertFalse(u.triage(self.tr(s2[:8] + ["2026-11-06"] + s2[9:]), D(2026, 11, 3))[0])  # wrong sla_due
        self.assertFalse(u.triage(self.tr(s1[:5] + ["S9"] + s1[6:]), D(2026, 11, 3))[0])
        self.assertFalse(u.triage(self.tr(s1[:7] + [""] + s1[8:]), D(2026, 11, 3))[0])  # no owner
        with self.assertRaises(SystemExit): u.triage(w(self.d, "bad.csv", "a,b\n1,2\n"), D(2026, 11, 3))

    def test_golive_needs_a_reasoned_decision_per_clinic(self):
        p = w(self.d, "g.csv", "clinic,decision,reasons,decided_on,conditions\nA,go,all gates met,2026-11-13,\nB,go-with-conditions,one S2 open,2026-11-13,fix by 11-20\nC,no-go,,2026-11-13,\nD,go-with-conditions,x,2026-11-13,\n")
        ok, out = u.golive(p, ["A", "B", "C", "D", "E"])
        self.assertFalse(ok); self.assertEqual(out[0], "A: go"); self.assertIn("must be", out[2]); self.assertIn("needs conditions", out[3]); self.assertIn("NO DECISION", out[4])
        self.assertTrue(u.golive(p, ["A", "B"])[0])


if __name__ == "__main__":
    import sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    unittest.main()
