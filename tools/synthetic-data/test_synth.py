#!/usr/bin/env python3
"""OPS-036 unit tests: synthetic generator + pre-load scanner. Run: python3 tools/synthetic-data/test_synth.py"""
import copy, json, subprocess, sys, tempfile, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import synth

DATA = synth.generate(seed=36, patients=2000, day="2026-10-20")


def valid(body, ok):
    return next(body + d for d in "0123456789" if ok(body + d))


class Generator(unittest.TestCase):
    def test_four_tenants_one_staff_per_role_2000_patients(self):
        self.assertEqual([t["key"] for t in DATA["tenants"]], ["IN", "AE", "SA", "QA"])
        self.assertEqual({t["region"] for t in DATA["tenants"]}, {"IN", "KSA"})
        roles = set(synth.ROLE_KEYS)
        for t in DATA["tenants"]:
            self.assertEqual(sorted(s["role"] for s in t["staff"]), sorted(roles))
        self.assertEqual(sum(len(t["patients"]) for t in DATA["tenants"]), 2000)

    def test_a_day_of_appointments_on_the_15_minute_grid_without_overlap(self):
        for t in DATA["tenants"]:
            starts = [a["localStart"] for a in t["appointments"]]
            self.assertEqual(len(starts), 32)
            self.assertEqual(len(set(starts)), 32)
            self.assertTrue(all(s.startswith("2026-10-20T") and int(s[14:16]) % 15 == 0 for s in starts))
            self.assertEqual((starts[0][11:16], starts[-1][11:16]), ("09:00", "16:45"))

    def test_all_phones_in_reserved_test_ranges(self):
        phones = [v for p, v in synth._walk(DATA) if p.endswith(".phone")]
        self.assertGreater(len(phones), 2000)
        self.assertTrue(all(synth.TEST_PHONE.match(p) for p in phones))

    def test_fake_ids_have_broken_checksums(self):
        ids = {t["key"]: [p["nationalId"] for p in t["patients"]] for t in DATA["tenants"]}
        self.assertFalse(any(synth.verhoeff_ok(i) for i in ids["IN"]))
        self.assertFalse(any(synth.luhn_ok(i) for i in ids["AE"] + ids["SA"]))
        self.assertTrue(all(i.startswith("TEST-") for i in ids["QA"]))

    def test_deterministic_for_a_seed(self):
        self.assertEqual(json.dumps(synth.generate(seed=36, patients=40, day="2026-10-20")), json.dumps(synth.generate(seed=36, patients=40, day="2026-10-20")))

    def test_patients_are_adults(self):  # registering a minor needs a guardian record
        self.assertTrue(all(p["dob"] < "2006-01-01" for t in DATA["tenants"] for p in t["patients"]))

    def test_names_are_indian_or_arabic(self):
        self.assertIn(DATA["tenants"][0]["patients"][0]["name"].split()[0], synth.IN_FIRST)
        self.assertIn(DATA["tenants"][2]["patients"][0]["name"].split()[0], synth.AR_FIRST)


class Scanner(unittest.TestCase):
    def test_generated_data_passes(self):
        self.assertEqual(synth.scan(DATA), [])

    def bad(self, mutate):
        d = copy.deepcopy(DATA)
        mutate(d)
        return synth.scan(d)

    def test_rejects_real_looking_aadhaar(self):
        real = valid("23456789012", synth.verhoeff_ok)
        f = self.bad(lambda d: d["tenants"][0]["patients"][5].update(nationalId=real))
        self.assertEqual(len(f), 1); self.assertIn("passes Verhoeff", f[0]); self.assertNotIn(real, f[0])  # masked
        # spaced form, and inside free text
        f = self.bad(lambda d: d["tenants"][0]["patients"][5].update(name=f"note {real[:4]} {real[4:8]} {real[8:]}"))
        self.assertIn("passes Verhoeff", f[0])

    def test_rejects_valid_emirates_saudi_and_any_qid(self):
        self.assertIn("Emirates", self.bad(lambda d: d["tenants"][1]["patients"][0].update(nationalId=valid("78419901234567", synth.luhn_ok)))[0])
        self.assertIn("Saudi", self.bad(lambda d: d["tenants"][2]["patients"][0].update(nationalId=valid("108765432", synth.luhn_ok)))[0])
        self.assertIn("QID", self.bad(lambda d: d["tenants"][3]["patients"][0].update(nationalId="28963412345"))[0])

    def test_rejects_phone_outside_test_range(self):
        f = self.bad(lambda d: d["tenants"][0]["patients"][1].update(phone="+919876543210"))
        self.assertEqual(len(f), 1); self.assertIn("outside the reserved test range", f[0])
        self.assertTrue(self.bad(lambda d: d["tenants"][2]["staff"][0].update(phone="+966500000001")))

    def test_cli_rejects_the_load(self):
        d = copy.deepcopy(DATA); d["tenants"][0]["patients"][0]["phone"] = "+919876543210"
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as t:
            json.dump(d, t)
        r = subprocess.run([sys.executable, str(Path(__file__).parent / "synth.py"), "scan", t.name], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1); self.assertIn("REJECTED", r.stdout); self.assertNotIn("9876543210", r.stdout)


class Loader(unittest.TestCase):
    def setUp(self):
        import load
        self.load = load
        self.pins = {(t["key"], st["role"]): "12345678" for t in DATA["tenants"] for st in t["staff"]}

    def test_sql_creates_tenants_roles_staff_without_plaintext_pins(self):
        stmts, ids = self.load.sql(DATA, self.pins, hasher=lambda p: "HASHED")
        self.assertEqual(stmts.count("INSERT INTO tenants"), 4)
        self.assertEqual(stmts.count("INSERT INTO staff"), 4 * len(synth.ROLE_KEYS))
        self.assertNotIn("12345678", stmts)
        self.assertIn("'KSA'", stmts); self.assertNotIn("'AE', 'active'", stmts)   # AE/SA/QA run in the KSA region
        self.assertEqual(stmts, self.load.sql(DATA, self.pins, hasher=lambda p: "HASHED")[0])   # stable ids: re-run is a no-op
        self.assertEqual(set(ids["IN"]["staff"]), set(synth.ROLE_KEYS))

    def test_grants_follow_roles_json(self):
        rec = {g["module"]: g for g in self.load.grants("receptionist")}
        self.assertTrue(rec["billing"]["approve"] and not rec["billing"]["refundDiscount"])
        self.assertNotIn("clinical", rec)
        self.assertTrue(all(g["delete"] for g in self.load.grants("owner")))

    def test_smoke_slots_cover_every_case_and_retry(self):
        self.assertEqual(len(self.load.SLOTS), 3 * 2 * 2)
        self.assertIn("J9:mobile:2", self.load.SLOTS)

    def test_load_refuses_flagged_data_before_touching_the_db(self):
        d = copy.deepcopy(DATA); d["tenants"][0]["patients"][0]["phone"] = "+919876543210"
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as t:
            json.dump(d, t)
        r = subprocess.run([sys.executable, str(Path(__file__).parent / "load.py"), t.name, "--db", "postgresql://invalid", "--api", "http://invalid"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1); self.assertIn("REJECTED", r.stdout)


if __name__ == "__main__":
    unittest.main()
