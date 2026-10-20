#!/usr/bin/env python3
"""OPS-036 synthetic preview data: generator + pre-load real-PHI scanner (stdlib only).

  python3 tools/synthetic-data/synth.py generate --out data.json [--seed 36] [--patients 2000]
  python3 tools/synthetic-data/synth.py scan data.json        # exit 1 and list findings when anything looks real

Data: 4 tenants (IN, AE, SA, QA), one staff per RBAC role (frontend/tests/e2e/smoke/roles.json), patients with
Indian / Arabic names split across the tenants, a day of appointments per tenant on the 15-minute grid.
Phones: reserved test range = the national number starts with 0000. E.164 national numbers never start with 0
(that is the trunk prefix), so these can never reach a real subscriber.
National ids: id-shaped but with the checksum deliberately WRONG (Aadhaar fails Verhoeff, Emirates ID and Saudi
id/Iqama fail Luhn); Qatar gets a TEST- token because no public QID checksum exists to break.
Region: the platform knows only IN and KSA, so AE/SA/QA tenants run in the KSA (GCC) region.
"""
import argparse, datetime as dt, json, random, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ROLES = json.loads((ROOT / "frontend/tests/e2e/smoke/roles.json").read_text())
ROLE_KEYS = [k for k in ROLES if not k.startswith("_")]

TENANTS = [  # key, country calling code, region, IANA zone, currency
    ("IN", "91", "IN", "Asia/Kolkata", "INR"),
    ("AE", "971", "KSA", "Asia/Dubai", "AED"),
    ("SA", "966", "KSA", "Asia/Riyadh", "SAR"),
    ("QA", "974", "KSA", "Asia/Qatar", "QAR"),
]
IN_FIRST = ["Asha", "Kavya", "Priya", "Ananya", "Divya", "Meera", "Rohan", "Arjun", "Vikram", "Rahul", "Siddharth", "Karthik", "Lakshmi", "Neha", "Aditya", "Ishaan"]
IN_LAST = ["Verma", "Iyer", "Sharma", "Reddy", "Nair", "Patel", "Gupta", "Rao", "Menon", "Das", "Kulkarni", "Joshi"]
AR_FIRST = ["Noura", "Reem", "Fatimah", "Layla", "Maha", "Huda", "Omar", "Khalid", "Faisal", "Yousef", "Abdullah", "Saad", "Lulwa", "Mariam", "Hamad", "Turki"]
AR_LAST = ["Al-Harbi", "Al-Qahtani", "Al-Otaibi", "Al-Mansouri", "Al-Kuwari", "Al-Shamsi", "Al-Thani", "Al-Mutairi", "Al-Dosari", "Al-Marri", "Al-Nuaimi", "Al-Zahrani"]

# ---- checksums -------------------------------------------------------------------------------
_D = [[0,1,2,3,4,5,6,7,8,9],[1,2,3,4,0,6,7,8,9,5],[2,3,4,0,1,7,8,9,5,6],[3,4,0,1,2,8,9,5,6,7],[4,0,1,2,3,9,5,6,7,8],
      [5,9,8,7,6,0,4,3,2,1],[6,5,9,8,7,1,0,4,3,2],[7,6,5,9,8,2,1,0,4,3],[8,7,6,5,9,3,2,1,0,4],[9,8,7,6,5,4,3,2,1,0]]
_P = [[0,1,2,3,4,5,6,7,8,9],[1,5,7,6,2,8,3,0,9,4],[5,8,0,3,7,9,6,1,4,2],[8,9,1,6,0,4,3,5,2,7],[9,4,5,3,1,2,6,8,7,0],
      [4,2,8,6,5,7,3,9,0,1],[2,7,9,3,8,0,6,4,1,5],[7,0,4,6,9,1,3,2,5,8]]

def verhoeff_ok(num: str) -> bool:
    c = 0
    for i, ch in enumerate(reversed(num)):
        c = _D[c][_P[i % 8][int(ch)]]
    return c == 0

def luhn_ok(num: str) -> bool:
    s = 0
    for i, ch in enumerate(reversed(num)):
        d = int(ch) * (2 if i % 2 else 1)
        s += d - 9 if d > 9 else d
    return s % 10 == 0

def _break(body: str, ok) -> str:
    """Appends a check digit that makes `ok` fail (the id can never be a real one)."""
    for d in "0123456789":
        if not ok(body + d):
            return body + d
    raise AssertionError("unreachable")

def fake_id(country: str, rnd: random.Random) -> str:
    if country == "IN":
        return _break(str(rnd.randint(2, 9)) + "".join(str(rnd.randint(0, 9)) for _ in range(10)), verhoeff_ok)
    if country == "AE":
        return _break("784" + str(rnd.randint(1950, 2015)) + "".join(str(rnd.randint(0, 9)) for _ in range(7)), luhn_ok)
    if country == "SA":
        return _break(rnd.choice("12") + "".join(str(rnd.randint(0, 9)) for _ in range(8)), luhn_ok)
    return "TEST-" + "".join(str(rnd.randint(0, 9)) for _ in range(8))

def test_phone(cc: str, n: int) -> str:
    return f"+{cc}0000{n:06d}"

# ---- generator -------------------------------------------------------------------------------
def generate(seed=36, patients=2000, day=None):
    rnd = random.Random(seed)
    day = day or (dt.date.today() + dt.timedelta(days=1)).isoformat()
    out = {"generatedFor": "preview", "appointmentDay": day, "tenants": []}
    per = patients // len(TENANTS)
    for i, (key, cc, region, zone, cur) in enumerate(TENANTS):
        first, last = (IN_FIRST, IN_LAST) if key == "IN" else (AR_FIRST, AR_LAST)
        t = {"key": key, "slug": f"preview-{key.lower()}", "name": f"Synthetic Clinic {key}", "region": region, "timezone": zone, "currency": cur,
             "staff": [{"role": r, "name": f"{rnd.choice(first)} {rnd.choice(last)} ({ROLES[r]['name']})",
                        "email": f"{r.replace('_', '-')}@preview-{key.lower()}.example.test", "phone": test_phone(cc, 900 + j)} for j, r in enumerate(ROLE_KEYS)],
             "patients": [], "appointments": []}
        for n in range(per + (patients - per * len(TENANTS) if i == 0 else 0)):
            dob = dt.date(1940, 1, 1) + dt.timedelta(days=rnd.randint(0, 365 * 66))  # adults only (born before 2006): minors need a guardian
            t["patients"].append({"ref": f"{key}-{n}", "name": f"{rnd.choice(first)} {rnd.choice(last)}", "phone": test_phone(cc, n + 1),
                                  "dob": dob.isoformat(), "gender": rnd.choice(["female", "male"]), "nationalId": fake_id(key, rnd)})
        # A day of appointments for the seeded doctor: 09:00-17:00 local, one per 15-minute slot, no overlaps.
        for s in range(32):
            hh, mm = divmod(9 * 60 + 15 * s, 60)
            t["appointments"].append({"patientRef": t["patients"][(s + 10) % len(t["patients"])]["ref"], "doctorRole": "doctor", "localStart": f"{day}T{hh:02d}:{mm:02d}:00"})
        # Smoke fixtures: one severe-allergy patient (J6); the allergy names a synthetic substance, not a real drug.
        t["allergy"] = {"patientRef": t["patients"][0]["ref"], "substance": "Synthamoxil", "severity": "severe", "category": "drug"}
        out["tenants"].append(t)
    return out

# ---- scanner ---------------------------------------------------------------------------------
TEST_PHONE = re.compile(r"^\+(91|971|966|974)0000\d{6}$")
AADHAAR = re.compile(r"(?<!\d)[2-9]\d{3}[ -]?\d{4}[ -]?\d{4}(?!\d)")
EMIRATES = re.compile(r"(?<!\d)784[ -]?\d{4}[ -]?\d{7}[ -]?\d(?!\d)")
SAUDI = re.compile(r"(?<!\d)[12]\d{9}(?!\d)")
QID = re.compile(r"(?<!\d)[23]\d{10}(?!\d)")
PHONE_KEYS = re.compile(r"phone|mobile|msisdn|whatsapp", re.I)

def _walk(o, path="$"):
    if isinstance(o, dict):
        for k, v in o.items():
            yield from _walk(v, f"{path}.{k}")
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from _walk(v, f"{path}[{i}]")
    elif isinstance(o, str):
        yield path, o

def scan(data):
    """Findings for anything that could be real: valid-checksum ids, QID shapes, phones outside the test range."""
    f = []
    mask = lambda s: s[:3] + "*" * max(0, len(s) - 5) + s[-2:]
    for path, v in _walk(data):
        if PHONE_KEYS.search(path.rsplit(".", 1)[-1]):
            if not TEST_PHONE.match(v.replace(" ", "")):
                f.append(f"{path}: phone {mask(v)} is outside the reserved test range")
            continue
        for m in AADHAAR.finditer(v):
            if verhoeff_ok(re.sub(r"\D", "", m.group())): f.append(f"{path}: Aadhaar-shaped {mask(m.group())} passes Verhoeff")
        for m in EMIRATES.finditer(v):
            if luhn_ok(re.sub(r"\D", "", m.group())): f.append(f"{path}: Emirates ID-shaped {mask(m.group())} passes Luhn")
        for m in SAUDI.finditer(v):
            if luhn_ok(m.group()): f.append(f"{path}: Saudi ID/Iqama-shaped {mask(m.group())} passes Luhn")
        for m in QID.finditer(v):
            f.append(f"{path}: QID-shaped {mask(m.group())} (no checksum to prove it fake)")
    return f

def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate"); g.add_argument("--out", required=True); g.add_argument("--seed", type=int, default=36); g.add_argument("--patients", type=int, default=2000)
    s = sub.add_parser("scan"); s.add_argument("file")
    a = ap.parse_args()
    if a.cmd == "generate":
        Path(a.out).write_text(json.dumps(generate(a.seed, a.patients), indent=1))
        print(f"wrote {a.out}")
    else:
        found = scan(json.loads(Path(a.file).read_text()))
        if found:
            print(f"REJECTED: {len(found)} value(s) look real; nothing was loaded.\n  " + "\n  ".join(found[:50]))
            sys.exit(1)
        print("scan ok: no real-looking ids or phones")

if __name__ == "__main__":
    main()
