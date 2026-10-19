// Independent QA (S11) for FEF-011, written from the story's acceptance criteria. The device runs in Asia/Kolkata.
process.env.TZ = "Asia/Kolkata";
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { ESLint } from "eslint";
import {
  clinicToday, formatClinicDate, formatClinicTime, toClinicDateTime, resolveClinicTimeZone, loadClinicTimeZone,
  resetClinicTimeZone, setClinicNow, getClinicTimeZone,
} from "../../app/lib/clinicTime.ts";

const at = (iso) => () => Date.parse(iso);
beforeEach(() => { resetClinicTimeZone(); setClinicNow(); });

test("AC1: Kolkata device 23:50, Dubai clinic -> Dubai date; mirror: Dubai device, Kolkata clinic past midnight -> next day", () => {
  setClinicNow(at("2026-03-10T18:20:00Z"));
  assert.equal(clinicToday("Asia/Dubai"), "2026-03-10");
  setClinicNow(at("2026-03-10T18:31:00Z")); // 00:01 Kolkata on 11 Mar, 22:31 Dubai on 10 Mar
  assert.equal(clinicToday("Asia/Kolkata"), "2026-03-11");
  assert.equal(clinicToday("Asia/Dubai"), "2026-03-10");
  assert.equal(formatClinicTime("2026-03-10T18:31:00Z", "Asia/Dubai"), "22:31");
});

test("AC1 boundary: Dubai midnight exactly, one second before and after", () => {
  setClinicNow(at("2026-03-10T19:59:59Z"));
  assert.equal(clinicToday("Asia/Dubai"), "2026-03-10");
  setClinicNow(at("2026-03-10T20:00:00Z"));
  assert.equal(clinicToday("Asia/Dubai"), "2026-03-11");
  assert.deepEqual(toClinicDateTime("2026-03-10T20:00:00Z", "Asia/Dubai"), { date: "2026-03-11", time: "00:00" });
});

test("AC2: Riyadh clinic formats in Riyadh whatever the tenant region; calendar dates are never shifted", () => {
  assert.equal(formatClinicDate("2026-03-10T21:30:00Z", undefined, "Asia/Riyadh"), "11 Mar 2026"); // 00:30 Riyadh
  assert.equal(formatClinicDate("2026-03-10T20:30:00Z", undefined, "Asia/Riyadh"), "10 Mar 2026"); // 23:30 Riyadh
  assert.equal(formatClinicTime("2026-03-10T20:30:00Z", "Asia/Riyadh"), "23:30");
  for (const tz of ["Asia/Riyadh", "Asia/Kolkata", "Pacific/Kiritimati", "America/Los_Angeles"]) {
    assert.equal(formatClinicDate("2026-03-10", undefined, tz), "10 Mar 2026", tz);
  }
  assert.equal(formatClinicDate("not a date"), "not a date");
});

test("AC2: REGION_TZ is gone from the app and the migrated files go through the format module", () => {
  const hits = [];
  const walk = (d) => { for (const n of readdirSync(d)) { const p = join(d, n); if (statSync(p).isDirectory()) walk(p); else if (/\.(tsx?|mjs|js)$/.test(n) && /REGION_TZ\b/.test(readFileSync(p, "utf8"))) hits.push(p); } };
  walk("app");
  assert.deepEqual(hits, []);
  for (const f of ["app/components/consult/FollowUpPanel.tsx", "app/components/consult/DocumentSheet.tsx", "app/components/myday/useMyDay.ts"]) {
    const src = readFileSync(f, "utf8");
    assert.match(src, /clinicTime/, f);
    assert.doesNotMatch(src, /toLocale(Date|Time)String|Intl\.DateTimeFormat|timeZone:\s*["']/, f);
  }
});

test("AC4: null or unusable tenants.timezone -> region default for every listed region; unknown region -> UTC", () => {
  const cases = { IN: "Asia/Kolkata", AE: "Asia/Dubai", SA: "Asia/Riyadh", KSA: "Asia/Riyadh", QA: "Asia/Qatar" };
  for (const [region, tz] of Object.entries(cases)) {
    for (const bad of [null, undefined, "", "IST", "+05:30", "Mars/Olympus"]) {
      assert.deepEqual(resolveClinicTimeZone(bad, region), { tz, fallback: true }, `${region} ${bad}`);
    }
  }
  assert.deepEqual(resolveClinicTimeZone(null, "XX"), { tz: "UTC", fallback: true });
  assert.deepEqual(resolveClinicTimeZone("Asia/Dubai", "IN"), { tz: "Asia/Dubai", fallback: false });
});

test("AC4: shell boot with a null zone warns exactly once, even if boot runs again for the same clinic", async () => {
  const warns = [];
  const orig = console.warn;
  console.warn = (...a) => warns.push(a.join(" "));
  try {
    const fetcher = async () => new Response(JSON.stringify({ timezone: null, region: "AE" }), { status: 200 });
    assert.equal(await loadClinicTimeZone(fetcher, "AE", "t1"), "Asia/Dubai");
    assert.equal(await loadClinicTimeZone(fetcher, "AE", "t1"), "Asia/Dubai");
    assert.equal(getClinicTimeZone(), "Asia/Dubai");
  } finally {
    console.warn = orig;
  }
  assert.equal(warns.filter((w) => w.includes("clinic-timezone-fallback")).length, 1);
});

test("DST zone for visiting doctors: Europe/London switches GMT -> BST and back", () => {
  assert.equal(formatClinicTime("2026-03-29T00:30:00Z", "Europe/London"), "00:30"); // GMT
  assert.equal(formatClinicTime("2026-03-29T01:30:00Z", "Europe/London"), "02:30"); // BST
  setClinicNow(at("2026-10-24T23:30:00Z")); // 00:30 BST on 25 Oct
  assert.equal(clinicToday("Europe/London"), "2026-10-25");
  setClinicNow(at("2026-10-25T23:30:00Z")); // 23:30 GMT on 25 Oct
  assert.equal(clinicToday("Europe/London"), "2026-10-25");
});

test("AC3: eslint fails raw date formatting in every migrated file, allows it in the format module", async () => {
  const eslint = new ESLint();
  const bad = "export const a = (d: Date) => d.toLocaleDateString('en-GB');\nexport const b = (d: Date) => d.toLocaleTimeString();\nexport const c = new Intl.DateTimeFormat('en', {});\n";
  for (const f of ["app/components/myday/MyDay.tsx", "app/components/myschedule/MySchedule.tsx", "app/components/consult/FollowUpPanel.tsx", "app/components/consult/DocumentSheet.tsx", "app/components/myday/nested/New.tsx"]) {
    const [r] = await eslint.lintText(bad, { filePath: f });
    const n = r.messages.filter((m) => m.ruleId === "no-restricted-syntax").length;
    assert.equal(n, 3, `${f}: ${JSON.stringify(r.messages.map((m) => m.message))}`);
  }
  const [lib] = await eslint.lintText(bad, { filePath: "app/lib/clinicTime.ts" });
  assert.equal(lib.messages.filter((m) => m.ruleId === "no-restricted-syntax").length, 0);
  const [num] = await eslint.lintText("export const n = (x: number) => x.toLocaleString('en-IN');\n", { filePath: "app/components/myday/MyDay.tsx" });
  assert.equal(num.messages.filter((m) => m.ruleId === "no-restricted-syntax").length, 0);
});
