// FEF-017 AC2 + AC4 as greps: the five screens use the shared client and generated types; the waitlist tick lives in its own cell.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";

const app = (p) => new URL(`../../app/${p}`, import.meta.url);
const files = [
  ...["patients", "schedule", "billing", "pharmacy", "packages"].flatMap((d) => {
    const dir = app(d + "/");
    return readdirSync(dir).filter((f) => /\.tsx?$/.test(f)).map((f) => `${d}/${f}`);
  }),
  "components/setup/PharmacyPanel.tsx",
];
const src = Object.fromEntries(files.map((f) => [f, readFileSync(app(f), "utf8")]));

test("0 authedFetch and 0 API_BASE on patients, schedule, billing, pharmacy and packages", () => {
  for (const [f, s] of Object.entries(src)) {
    assert.doesNotMatch(s, /authedFetch|API_BASE|apiFetch|\bfetch\(/, f);
  }
});

test("0 hand-written API types: response shapes come from the generated client", () => {
  const banned = /^(export )?type (Patient|PatientDetail|Allergy|AllergyStatus|Condition|Prescription|PrescriptionItem|Encounter|Tooth|ToothHistoryEntry|TodayVisit|Registry|RegistryRow|Booking|Cell|Session|Doctor|Day|Week|Summary|HistoryItem|PackageT|PackageItem|InstanceT|InstanceItem|InstanceEvent|RefundT|ExpiringT|LiabilityT|SettingsT|StaffOption|PatientOption|WaitlistEntry|SoldInvoice|PharmacySettings|PharmacyItem|DispensingQueueEntry|Due|Item) = \{/m;
  for (const [f, s] of Object.entries(src)) assert.doesNotMatch(s, banned, f);
  for (const f of ["patients/page.tsx", "schedule/page.tsx", "billing/page.tsx", "packages/page.tsx"]) assert.match(src[f], /from "@nabd\/api-client\/generated"/, f);
});

test("no useEffect fetches: server state goes through useQuery", () => {
  for (const f of ["schedule/page.tsx", "billing/page.tsx", "packages/page.tsx", "schedule/LiveQueueBoard.tsx"]) {
    assert.doesNotMatch(src[f], /Promise\.resolve\(\)\.then\(load/, f);
    assert.match(src[f], /useQuery/, f);
  }
});

test("AC4: the waitlist panel has no 1 s interval of its own; the Countdown cell owns it", () => {
  const s = src["schedule/WaitlistPanel.tsx"];
  assert.equal((s.match(/setInterval\(/g) ?? []).length, 1);
  const cd = s.slice(s.indexOf("export function Countdown"), s.indexOf("const STATUS") > s.indexOf("export function Countdown") ? s.length : undefined);
  assert.ok(cd.indexOf("setInterval(") > cd.indexOf("export function Countdown"));
  const panel = s.slice(s.indexOf("export default function WaitlistPanel"));
  assert.doesNotMatch(panel, /setInterval|setNow|useState\(\(\) => Date\.now\(\)\)/);
});

test("AC1: the registry has no 'first 200' wording and pages by cursor", () => {
  assert.doesNotMatch(src["patients/page.tsx"], /first 200|showing first/i);
  assert.match(src["patients/page.tsx"], /cursor/);
});

test("AC3: every migrated dialog shows failures through the shared form errors", () => {
  for (const f of ["patients/RegistryDialogs.tsx", "patients/PhoneDataQuality.tsx", "schedule/NewAppointmentDialog.tsx", "schedule/AppointmentActions.tsx",
    "schedule/WaitlistPanel.tsx", "billing/page.tsx", "packages/page.tsx", "components/setup/PharmacyPanel.tsx"]) {
    assert.match(src[f], /useFormErrors/, f);
    assert.match(src[f], /<ErrorSummary/, f);
    assert.match(src[f], /<FieldError/, f);
  }
});
