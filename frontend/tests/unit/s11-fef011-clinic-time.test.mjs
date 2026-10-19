// FEF-011: clinic-timezone dates. `npm run -s test:unit`. The device runs in Asia/Kolkata on purpose.
process.env.TZ = "Asia/Kolkata";
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { ESLint } from "eslint";
import {
  clinicToday, formatClinicDate, formatClinicTime, toClinicDateTime, resolveClinicTimeZone, loadClinicTimeZone,
  getClinicTimeZone, clinicTimeZoneReady, resetClinicTimeZone, setClinicNow, REGION_DEFAULT_TZ,
} from "../../app/lib/clinicTime.ts";

const at = (iso) => () => Date.parse(iso);
const deviceDate = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; };

beforeEach(() => { resetClinicTimeZone(); setClinicNow(); });

test("AC1: device in Kolkata at 23:50 (22:20 Dubai) -> Dubai date", () => {
  setClinicNow(at("2026-03-10T18:20:00Z")); // 23:50 Kolkata, 22:20 Dubai
  assert.equal(toClinicDateTime(Date.parse("2026-03-10T18:20:00Z"), "Asia/Dubai").time, "22:20");
  assert.equal(clinicToday("Asia/Dubai"), "2026-03-10");
  // past device midnight, still the evening before in Dubai: the device date is wrong, the clinic date is right
  setClinicNow(at("2026-03-10T18:45:00Z")); // 00:15 Kolkata on 11 Mar, 22:45 Dubai on 10 Mar
  assert.equal(deviceDate(Date.parse("2026-03-10T18:45:00Z")), "2026-03-11");
  assert.equal(clinicToday("Asia/Dubai"), "2026-03-10");
});

for (const zone of ["Asia/Kolkata", "Asia/Dubai", "Asia/Riyadh"]) {
  test(`23:40-00:20 in ${zone}: the day flips exactly once, at 00:00 clinic time`, () => {
    const offsetMin = { "Asia/Kolkata": 330, "Asia/Dubai": 240, "Asia/Riyadh": 180 }[zone];
    const start = Date.UTC(2026, 2, 10, 23, 40) - offsetMin * 60_000;
    let flips = 0;
    let last = null;
    for (let m = 0; m <= 40; m++) {
      const t = start + m * 60_000;
      setClinicNow(() => t);
      const d = clinicToday(zone);
      if (last && d !== last) { flips++; assert.equal(formatClinicTime(t, zone), "00:00"); }
      last = d;
      if (m === 0) assert.equal(d, "2026-03-10");
    }
    assert.equal(flips, 1);
    assert.equal(last, "2026-03-11");
  });
}

test("DST zone (Europe/London) for visiting doctors: GMT then BST", () => {
  assert.equal(formatClinicTime("2026-03-29T00:30:00Z", "Europe/London"), "00:30"); // GMT
  assert.equal(formatClinicTime("2026-03-29T01:30:00Z", "Europe/London"), "02:30"); // BST from 01:00 UTC
  setClinicNow(at("2026-03-29T23:30:00Z")); // 00:30 BST on 30 Mar
  assert.equal(clinicToday("Europe/London"), "2026-03-30");
  setClinicNow(at("2026-10-25T23:30:00Z")); // back on GMT
  assert.equal(clinicToday("Europe/London"), "2026-10-25");
});

test("calendar dates are never shifted by a zone; instants are", () => {
  assert.equal(formatClinicDate("1990-01-01", undefined, "Pacific/Kiritimati"), "01 Jan 1990");
  assert.equal(formatClinicDate("1990-01-01", undefined, "Pacific/Pago_Pago"), "01 Jan 1990");
  assert.equal(formatClinicDate("2026-03-10T20:30:00Z", undefined, "Asia/Riyadh"), "10 Mar 2026");
  assert.equal(formatClinicDate("2026-03-10T21:30:00Z", undefined, "Asia/Riyadh"), "11 Mar 2026");
  assert.equal(formatClinicDate("not a date"), "not a date");
});

test("AC2: Riyadh clinic formats in the session zone (no region map involved)", () => {
  const iso = "2026-03-10T21:15:00Z"; // 00:15 Riyadh on 11 Mar, 02:45 Kolkata
  assert.equal(formatClinicTime(iso, "Asia/Riyadh"), "00:15");
  assert.equal(formatClinicDate(iso, { weekday: "short", day: "2-digit", month: "short" }, "Asia/Riyadh"), "Wed 11 Mar");
});

test("AC4: null timezone falls back to the region default", () => {
  assert.deepEqual(resolveClinicTimeZone(null, "IN"), { tz: "Asia/Kolkata", fallback: true });
  assert.deepEqual(resolveClinicTimeZone(null, "AE"), { tz: "Asia/Dubai", fallback: true });
  assert.deepEqual(resolveClinicTimeZone(null, "SA"), { tz: "Asia/Riyadh", fallback: true });
  assert.deepEqual(resolveClinicTimeZone(undefined, "KSA"), { tz: "Asia/Riyadh", fallback: true });
  assert.deepEqual(resolveClinicTimeZone(null, "QA"), { tz: "Asia/Qatar", fallback: true });
  assert.deepEqual(resolveClinicTimeZone("IST", "IN"), { tz: "Asia/Kolkata", fallback: true }); // abbreviations are not zones
  assert.deepEqual(resolveClinicTimeZone(null, "ZZ"), { tz: "UTC", fallback: true });
  assert.deepEqual(resolveClinicTimeZone("Asia/Dubai", "IN"), { tz: "Asia/Dubai", fallback: false });
  assert.equal(Object.keys(REGION_DEFAULT_TZ).length, 5);
});

const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

test("AC4: shell boot with tenants.timezone null warns exactly once", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const fetcher = async () => json(200, { timezone: null, region: "AE" });
  assert.equal(await loadClinicTimeZone(fetcher, "AE", "t1"), "Asia/Dubai");
  assert.equal(await loadClinicTimeZone(fetcher, "AE", "t1"), "Asia/Dubai");
  assert.equal(await loadClinicTimeZone(fetcher, "AE", "t2"), "Asia/Dubai"); // another clinic reloads, still one warning
  assert.equal(warn.mock.callCount(), 1);
  assert.match(String(warn.mock.calls[0].arguments[0]), /clinic-timezone-fallback/);
  assert.equal(getClinicTimeZone(), "Asia/Dubai");
});

test("shell boot: configured zone from /setup/profile, no warning", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const seen = [];
  const pending = clinicTimeZoneReady(); // a consumer that asks before boot gets the zone once it lands
  assert.equal(await loadClinicTimeZone(async (p) => { seen.push(p); return json(200, { timezone: "Asia/Riyadh" }); }, "IN"), "Asia/Riyadh");
  assert.equal(await pending, "Asia/Riyadh");
  assert.deepEqual(seen, ["/setup/profile"]);
  assert.equal(warn.mock.callCount(), 0);
});

test("shell boot without setup:view reads the zone from /schedule/day; a dead network falls back", async (t) => {
  t.mock.method(console, "warn", () => {});
  const seen = [];
  const tz = await loadClinicTimeZone(async (p) => { seen.push(p); return p === "/setup/profile" ? json(403, {}) : json(200, { timezone: "Asia/Dubai" }); }, "IN", "a");
  assert.equal(tz, "Asia/Dubai");
  assert.deepEqual(seen, ["/setup/profile", "/schedule/day"]);
  assert.equal(await loadClinicTimeZone(async () => { throw new TypeError("offline"); }, "KSA", "b"), "Asia/Riyadh");
});

test("AC3: eslint fails toLocale*String / Intl.DateTimeFormat in migrated files, allows the format module", async () => {
  const eslint = new ESLint({ cwd: new URL("../..", import.meta.url).pathname });
  const bad = [
    'export const a = (d: Date) => d.toLocaleDateString("en-GB");',
    'export const b = (d: Date) => d.toLocaleTimeString("en-GB");',
    'export const c = new Intl.DateTimeFormat("en-GB");',
  ];
  for (const code of bad) {
    for (const filePath of ["app/components/myday/X.tsx", "app/components/consult/FollowUpPanel.tsx", "app/components/consult/DocumentSheet.tsx", "app/components/myschedule/X.tsx"]) {
      const [r] = await eslint.lintText(code, { filePath });
      assert.ok(r.messages.some((m) => m.ruleId === "no-restricted-syntax" && m.severity === 2), `${filePath}: ${code}`);
    }
  }
  const [ok] = await eslint.lintText('export const n = (x: number) => x.toLocaleString("en-IN");', { filePath: "app/components/myday/X.tsx" });
  assert.equal(ok.messages.filter((m) => m.ruleId === "no-restricted-syntax").length, 0);
  const [mod] = await eslint.lintText('export const c = new Intl.DateTimeFormat("en-GB");', { filePath: "app/lib/clinicTime.ts" });
  assert.equal(mod.messages.filter((m) => m.ruleId === "no-restricted-syntax").length, 0);
});
