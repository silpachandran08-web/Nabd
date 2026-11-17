// CLN-043: my-day widgets and the consult quick-action bar follow the specialty profile (GET /v1/specialty/me). Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  ageText, hidesWidget, MAX_QUICK_ACTIONS, myDayOptions, NO_MY_DAY, quickBar, validQuickActions, visitChip, workspaceView,
} from "../../app/(clinic)/specialty/specialtyModel.ts";

const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const migration = readFileSync(new URL("../../../../../db/migrations/V3350__cln043_myday_widgets.sql", import.meta.url), "utf8");

const action = (i, extra = {}) => ({ id: `a${i}`, href: `/route${i}`, label: { en: `Action ${i}`, hi: `क्रिया ${i}` }, ...extra });
const me = (config = {}, extra = {}) => ({
  primary: "DERM", secondary: null, name: { en: "Dermatology" }, configVersion: 2, writable: true,
  config: { module: "specialty_derm", widgets: [], fieldSets: [], templatePack: null, quickActions: [1, 2, 3].map((i) => action(i)), ...config },
  ...extra,
});

test("a profile with 6 quick actions shows exactly those 6, in order, in the consult bar", () => {
  const six = [1, 2, 3, 4, 5, 6].map((i) => action(i));
  assert.equal(MAX_QUICK_ACTIONS, 6);
  assert.deepEqual(quickBar(me({ quickActions: six }), "en"), six.map((a) => ({ id: a.id, label: a.label.en, href: a.href })));
  assert.equal(quickBar(me({ quickActions: six }), "hi")[0].label, "क्रिया 1");
});

test("a 7th action is rejected by schema validation (no bar, never cut to 6), in the bar and the workspace alike", () => {
  const seven = [1, 2, 3, 4, 5, 6, 7].map((i) => action(i));
  assert.equal(validQuickActions({ quickActions: seven }), null);
  assert.deepEqual(quickBar(me({ quickActions: seven }), "en"), []);
  assert.deepEqual(workspaceView(me({ quickActions: seven }), "en").actions, []);
  // the registry's own schema check refuses it before it can ship
  assert.match(migration, /jsonb_array_length\(config->'quickActions'\) <= 6/);
});

test("actions must be in-app routes with an English label", () => {
  for (const bad of [{ href: "https://evil.example" }, { href: "//evil.example" }, { href: "javascript:alert(1)" }, { label: { hi: "x" } }, { id: 7 }]) {
    assert.equal(validQuickActions({ quickActions: [action(1, bad)] }), null, JSON.stringify(bad));
  }
  assert.equal(validQuickActions({ quickActions: [action(1, { href: "/consult?step=plan" })] }).length, 1);
  assert.equal(validQuickActions(null), null);
});

test("after the plan lapses, the actions that write are absent from the bar (reads stay)", () => {
  const m = me({ quickActions: [action(1, { writes: true }), action(2)] }, { writable: false });
  assert.deepEqual(quickBar(m, "en").map((a) => a.id), ["a2"]);
  assert.deepEqual(quickBar({ primary: null, secondary: null, name: null, configVersion: null, writable: false, config: null }, "en"), []);
  assert.deepEqual(quickBar(undefined, "en"), []);
});

test("my-day options come from config.myDay; no profile or a malformed one is plain My Day", () => {
  assert.deepEqual(myDayOptions(me({ myDay: { widgets: ["photosDue"], visitTypeChips: true } })), { widgets: ["photosDue"], visitTypeChips: true, ageInMonthsUnder2: false });
  assert.deepEqual(myDayOptions(me({ myDay: { widgets: ["vaccinesDueToday", 3], ageInMonthsUnder2: true } })), { widgets: ["vaccinesDueToday"], visitTypeChips: false, ageInMonthsUnder2: true });
  assert.deepEqual(myDayOptions(me()), NO_MY_DAY);
  assert.deepEqual(myDayOptions(me({ myDay: { widgets: "photosDue" } })), NO_MY_DAY);
  assert.deepEqual(myDayOptions(me({}, { primary: null })), NO_MY_DAY);
  assert.deepEqual(myDayOptions(undefined), NO_MY_DAY);
});

test("PED: ages show in months under 2 years, whole years from 2 (and for every other profile)", () => {
  assert.equal(ageText("2025-09-20", "2026-11-17", true), "13 mo");
  assert.equal(ageText("2025-09-17", "2026-11-17", true), "14 mo");
  assert.equal(ageText("2026-11-01", "2026-11-17", true), "0 mo");
  assert.equal(ageText("2024-11-18", "2026-11-17", true), "23 mo");
  assert.equal(ageText("2024-11-17", "2026-11-17", true), "2y");
  assert.equal(ageText("2025-09-17", "2026-11-17", false), "1y");
  assert.equal(ageText("1990-01-01", "2026-11-17", true), "36y");
  assert.equal(ageText(null, "2026-11-17", true), "");
  assert.equal(ageText("2027-01-01", "2026-11-17", true), "");
});

test("DERM visit-type chips: new vs follow-up from the server's fields; package session / patch test never guessed", () => {
  assert.equal(visitChip({ visitType: "follow_up" }), "followUp");
  assert.equal(visitChip({ visitType: "walk_in" }), "new");
  assert.equal(visitChip({ visitType: "appointment" }), "new");
  assert.equal(visitChip({ visitType: "transfer" }), null);
  assert.equal(visitChip({ isFollowUp: true }), "followUp");
  assert.equal(visitChip({ isFollowUp: false }), "new");
  assert.equal(visitChip({}), null);
  const parts = src("components/myday/MyDayParts.tsx");
  assert.equal((parts.match(/<VisitTypePill v=\{/g) ?? []).length, 4); // in the room, up next, queue rows, appointments
  assert.match(parts, /opts\.visitTypeChips \? visitChip\(v\) : null/);
});

test("a widget whose endpoint answers 403 (or 404) is hidden without an error; other failures show the error state", () => {
  assert.equal(hidesWidget(403), true);
  assert.equal(hidesWidget(404), true);
  assert.equal(hidesWidget(500), false);
  assert.equal(hidesWidget(undefined), false);
  assert.match(src("components/myday/widgets/useWidgetData.ts"), /hidesWidget\(q\.error instanceof ApiError \? q\.error\.status : undefined\) \? \{ state: "hidden" \}/);
  assert.match(src("components/myday/widgets/WidgetCard.tsx"), /if \(data\.state === "hidden"\) return null;/);
});

// The registry's widget ids, read from its source (it imports next/dynamic, so it is not loaded here).
const registry = src("components/myday/widgets/registry.ts");
const registered = [...registry.matchAll(/^ {2}(\w+): \{ permission: "([a-z_]+:[a-z]+)", Component: dynamic\(\(\) => import\("\.\/(\w+)"\)\) \},$/gm)];

test("registry: each widget is its own lazily loaded chunk with a permission, and its read starts in parallel with My Day's", () => {
  assert.deepEqual(registered.map((m) => [m[1], m[2]]), [["vaccinesDueToday", "clinical:view"], ["followUpsDue", "queue:view"]]);
  assert.match(registry, /entry && can\(entry\.permission\)/); // restricted widgets are absent
  for (const m of registered) assert.match(src(`components/myday/widgets/${m[3]}.tsx`), /useWidgetData</);
  const myDay = src("components/myday/MyDay.tsx");
  // mounted outside the "data &&" block, so the widget chunks and reads do not wait for the queue
  assert.match(myDay, /\n {8}\)\}\n {6}<SpecialtyWidgets ids=\{opts\.widgets\} names=\{data\?\.patientNames \?\? \{\}\} \/>\n {4}<\/main>/);
  // no fake numbers: no widget component carries a profile code or a literal count
  for (const m of registered) assert.doesNotMatch(src(`components/myday/widgets/${m[3]}.tsx`), /\b(DERM|DENT|GP|PED|ENT|ORTH|GYN|HOM|AYU)\b/);
});

test("per-profile snapshot: which widgets render today for each of the 9 profiles", () => {
  const ids = new Set(registered.map((m) => m[1]));
  const rows = Object.fromEntries([...migration.matchAll(/WHEN '(\w+)'\s+THEN '(\{.*?\})'::jsonb/g)].map((m) => [m[1], JSON.parse(m[2])]));
  assert.deepEqual(Object.keys(rows).sort(), ["AYU", "DENT", "DERM", "ENT", "GP", "GYN", "HOM", "ORTH", "PED"]);
  const shown = Object.fromEntries(Object.entries(rows).map(([code, d]) => [code, d.widgets.filter((w) => ids.has(w))]));
  assert.deepEqual(shown, { GP: [], DERM: [], DENT: [], PED: ["vaccinesDueToday"], ENT: [], ORTH: [], GYN: [], HOM: ["followUpsDue"], AYU: [] });
  assert.equal(rows.DERM.visitTypeChips, true);
  assert.equal(rows.PED.ageInMonthsUnder2, true);
  assert.ok(rows.DERM.widgets.includes("photosDue")); // configured, hidden until CLN-047 gives it a data source
});

test("the consult bar sits above the note", () => {
  const ws = src("components/consult/visit/VisitWorkspace.tsx");
  assert.match(ws, /<div className=\{styles\.centerCol\}>\n {10}<QuickActionBar \/>\n {10}<StepRail/);
  assert.match(src("components/consult/specialty/QuickActionBar.tsx"), /quickBar\(useSpecialtyMe\(\)\.data, locale\)/);
});
