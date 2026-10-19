#!/usr/bin/env python3
"""OPS-036 preview seed loader. Refuses to load anything the scanner flags, then:
  1. SQL (as the preview DB's admin, so RLS does not apply): tenants, default department, roles from roles.json,
     one staff per role with a random per-preview PIN (Argon2id, same parameters as SecurityConfig).
  2. API (as those staff, through the real validation): charge, doctor working hours, patients, the J6 allergy,
     a day of appointments, and the visits the smoke journeys need (in consult, checkout pending).
Writes <out>/logins.json (per-role logins, posted on the PR) and <out>/fixtures.json (ids for the smoke harness).

  python3 tools/synthetic-data/load.py data.json --db "$PREVIEW_DB_ADMIN_URL" --api https://pr-123.../v1 --out seed/
  python3 tools/synthetic-data/load.py data.json --sql-only > seed.sql     # phase 1 SQL only (PINs replaced by a marker)
Needs: psql, and `pip install argon2-cffi` for the PIN hashes.
"""
import argparse, datetime as dt, json, secrets, subprocess, sys, urllib.error, urllib.request, uuid
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).parent))
import synth

FLAGS = ["view", "create", "edit", "delete", "approve", "refundDiscount", "export"]
SMOKE_TENANTS = {"IN": "IN", "KSA": "SA"}  # smoke harness key -> seeded tenant
SLOTS = [f"{j}:{p}:{a}" for j in ("J5", "J6", "J9") for p in ("desktop", "mobile") for a in (1, 2)]  # journeys x projects x (1 + retries)
q = lambda s: "'" + str(s).replace("'", "''") + "'"


def grants(role):
    r = synth.ROLES[role]
    mods = r["full"] if "full" in r else list(r["grants"])
    return [dict(module=m, **{f: ("full" in r) or f in r["grants"][m] for f in FLAGS}, breakGlass=False) for m in mods]


def pin_hash(pin):
    from argon2 import PasswordHasher  # Spring Argon2PasswordEncoder(16, 32, 1, 19456, 2): salt 16, hash 32, p=1, m=19456 KiB, t=2
    return PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1, hash_len=32, salt_len=16).hash(pin)


def sql(data, pins, hasher=pin_hash):
    """Phase 1 statements; ids are stable per slug so a re-run is a no-op (ON CONFLICT DO NOTHING)."""
    out = ["BEGIN;"]
    ids = {}
    for t in data["tenants"]:
        tid = uuid.uuid5(uuid.NAMESPACE_URL, f"nabd-preview/{t['slug']}")
        ids[t["key"]] = {"tenant": str(tid), "staff": {}}
        out.append(f"INSERT INTO tenants (id, slug, name, region, status, timezone) VALUES ({q(tid)}, {q(t['slug'])}, {q(t['name'])}, {q(t['region'])}, 'active', {q(t['timezone'])}) ON CONFLICT DO NOTHING;")
        out.append(f"INSERT INTO departments (tenant_id, name, is_default) SELECT {q(tid)}, 'General', true WHERE NOT EXISTS (SELECT 1 FROM departments WHERE tenant_id = {q(tid)} AND is_default);")
        for s in t["staff"]:
            rid = uuid.uuid5(tid, "role/" + s["role"]); sid = uuid.uuid5(tid, "staff/" + s["role"])
            ids[t["key"]]["staff"][s["role"]] = str(sid)
            r = synth.ROLES[s["role"]]
            out.append(f"INSERT INTO roles (id, tenant_id, name, built_in, grants) VALUES ({q(rid)}, {q(tid)}, {q(r['name'])}, {str(bool(r.get('builtIn'))).lower()}, {q(json.dumps(grants(s['role'])))}::jsonb) ON CONFLICT DO NOTHING;")
            out.append("INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, pin_hash, status, email_verified, mobile_verified, mfa_enabled) "
                       f"VALUES ({q(sid)}, {q(tid)}, {q(rid)}, {q(s['email'])}, {q(s['name'])}, {q(s['phone'])}, {q(hasher(pins[(t['key'], s['role'])]))}, 'active', true, true, false) "
                       f"ON CONFLICT (id) DO UPDATE SET pin_hash = EXCLUDED.pin_hash;")
    out.append("COMMIT;")
    return "\n".join(out), ids


class Api:
    def __init__(self, base, slug, email, pin):
        self.base = base.rstrip("/")
        self.token = self.call("POST", "/auth/login", {"tenantSlug": slug, "email": email, "pin": pin}, auth=False)["accessToken"]

    def call(self, method, path, body=None, auth=True):
        h = {"content-type": "application/json", "accept": "application/json"}
        if auth: h["authorization"] = "Bearer " + self.token
        if method in ("POST", "PATCH", "PUT"): h["idempotency-key"] = str(uuid.uuid4())
        req = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(), method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"{method} {path} -> {e.code}: {e.read()[:300].decode(errors='replace')}") from None


def walk(owner, doctor, entry_id, target):
    """Moves a checked-in visit along the default pipeline to `target` (compare-and-set on each hop)."""
    path = ["checked_in", "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"]
    for cur, nxt in zip(path, path[1:]):
        if nxt == "vitals_done":
            owner.call("PATCH", f"/clinical/vitals/{entry_id}", {"pulseBpm": 72})  # synthetic reading so the vitals step completes
        if nxt == "checkout_pending":
            doctor.call("PATCH", f"/clinical/notes/{entry_id}", {"subjective": "Synthetic preview visit", "objective": "", "assessment": "", "plan": "", "diagnosis": "Synthetic"})
            doctor.call("POST", f"/clinical/notes/{entry_id}/sign")
        try:
            (doctor if nxt in ("in_consult", "checkout_pending") else owner).call("PATCH", f"/queue/{entry_id}/status", {"status": nxt, "expectedStatus": cur})
        except RuntimeError as e:  # recording vitals already advanced the visit to vitals_done
            if f'"currentStatus":"{nxt}"' not in str(e):
                raise
        if nxt == target:
            return


def api_phase(data, api_base, pins, ids):
    fixtures = {}
    for t in data["tenants"]:
        cred = {s["role"]: s for s in t["staff"]}
        owner = Api(api_base, t["slug"], cred["owner"]["email"], pins[(t["key"], "owner")])
        doctor = Api(api_base, t["slug"], cred["doctor"]["email"], pins[(t["key"], "doctor")])
        doc_id = ids[t["key"]]["staff"]["doctor"]
        owner.call("POST", "/setup/charges", {"code": "CONS", "name": "Consultation", "category": "Consultation", "baseAmount": 500, "active": True, "effectiveFrom": dt.date.today().isoformat()})
        for d in range(7):
            owner.call("POST", f"/doctors/{doc_id}/working-hours", {"dayOfWeek": d, "startTime": "00:00", "endTime": "23:45", "slotMinutes": 15})
        pid = {}
        for p in t["patients"]:
            pid[p["ref"]] = owner.call("POST", "/patients", {k: p[k] for k in ("name", "phone", "dob", "gender", "nationalId")} | {"confirmedNotDuplicate": True})["id"]
        zone = ZoneInfo(t["timezone"])
        for a in t["appointments"]:
            start = dt.datetime.fromisoformat(a["localStart"]).replace(tzinfo=zone).astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            owner.call("POST", "/appointments", {"patientId": pid[a["patientRef"]], "doctorId": doc_id, "startTime": start})
        slots = {}
        for n, slot in enumerate(SLOTS):  # one fresh visit per slot: a signed note or paid bill is never reused
            p = t["patients"][100 + n]
            if slot.startswith("J6"):
                al = t["allergy"]
                doctor.call("POST", f"/clinical/patients/{pid[p['ref']]}/allergies", {"substance": al["substance"], "severity": al["severity"], "category": al["category"], "source": "clinician"})
            e = owner.call("POST", "/queue/check-in", {"patientId": pid[p["ref"]], "doctorId": doc_id, "paymentMode": "cash", "source": "walk_in"})
            walk(owner, doctor, e["id"], "checkout_pending" if slot.startswith("J9") else "in_consult")
            slots[slot] = {"entryId": e["id"], "patientName": p["name"]}
        fixtures[t["key"]] = {"doctor": {"id": doc_id, "name": cred["doctor"]["name"]}, "chargeName": "Consultation", "allergyDrug": t["allergy"]["substance"],
                              "walkInPhonePrefix": "+" + {"IN": "91", "AE": "971", "SA": "966", "QA": "974"}[t["key"]] + "0000", "slots": slots}
        print(f"seeded {t['key']}: {len(t['patients'])} patients, {len(t['appointments'])} appointments, {len(slots)} smoke visits", flush=True)
    return fixtures


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data"); ap.add_argument("--db"); ap.add_argument("--api"); ap.add_argument("--out", default="seed"); ap.add_argument("--sql-only", action="store_true")
    a = ap.parse_args()
    data = json.loads(Path(a.data).read_text())
    found = synth.scan(data)
    if found:
        print(f"REJECTED: {len(found)} value(s) look real; nothing was loaded.\n  " + "\n  ".join(found[:50])); sys.exit(1)
    pins = {(t["key"], s["role"]): f"{secrets.randbelow(10**8):08d}" for t in data["tenants"] for s in t["staff"]}
    if a.sql_only:
        print(sql(data, pins, hasher=lambda _p: "PIN-HASH-AT-LOAD-TIME")[0]); return
    if not (a.db and a.api): ap.error("--db and --api are required")
    stmts, ids = sql(data, pins)
    subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-q", a.db], input=stmts, text=True, check=True)
    fixtures = api_phase(data, a.api, pins, ids)
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    by_key = {t["key"]: t for t in data["tenants"]}
    logins = {k: {"slug": t["slug"], "name": t["name"], "staff": {s["role"]: {"email": s["email"], "pin": pins[(k, s["role"])]} for s in t["staff"]}} for k, t in by_key.items()}
    for smoke, seeded in SMOKE_TENANTS.items():
        logins.setdefault(smoke, logins[seeded])
        fixtures.setdefault(smoke, fixtures[seeded])
    (out / "logins.json").write_text(json.dumps({"tenants": logins}, indent=1))
    (out / "fixtures.json").write_text(json.dumps({"tenants": fixtures}, indent=1))
    print(f"wrote {out}/logins.json and {out}/fixtures.json")


if __name__ == "__main__":
    main()
