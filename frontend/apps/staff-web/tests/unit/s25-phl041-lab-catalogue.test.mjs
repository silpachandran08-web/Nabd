// PHL-041 lab catalogue: form and panel rules, the nav gate (no lab module = no lab nav) and the screen's wiring. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const m = await import("../../app/lib/labCatalogue.ts");
const nav = await import("../../app/shell/navRegistry.ts");
const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const messages = (lang) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${lang}/shell.json`, import.meta.url), "utf8")).lab;
const ent = (lab) => ({ modules: [{ code: "lab", enabled: lab }, { code: "billing", enabled: true }], killedFeatures: [], seatLimit: null });
const test1 = { id: "t1", code: "CRP", name: "C-reactive protein", loinc: "1988-5", sampleType: "serum", container: "sst_gold", volumeMl: 3, method: null, unit: "mg/L",
  tatMinutes: 240, price: 500, taxCode: null, chargeItemId: "c1", chargeCode: "LAB-CRP", active: true, updatedAt: "2026-11-20T08:00:00Z" };

test("AC1: without the lab module the lab role has no lab nav and a direct URL is blocked; with it the catalogue is listed", () => {
  const perms = [nav.LAB_GRANT, "lab:catalogue"];
  const off = nav.navFor({ perms, owner: false, ent: ent(false) });
  assert.ok(!off.items.some((i) => i.id.startsWith("lab-")), "no lab item when the plan has no lab module");
  assert.equal(nav.counterRouteState("lab", { perms, owner: false, ent: ent(false) }, "/lab/catalog", true).kind, "blocked");
  assert.equal(nav.counterRouteState("lab", { perms, owner: false, ent: null }, "/lab/catalog", false).kind, "wait", "not mounted before the first entitlements answer");
  const on = nav.navFor({ perms, owner: false, ent: ent(true) });
  assert.equal(on.role, "lab");
  assert.ok(on.items.some((i) => i.id === "lab-catalog" && i.href === "/lab/catalog"));
  assert.equal(nav.counterRouteState("lab", { perms, owner: false, ent: ent(true) }, "/lab/catalog", true).kind, "open");
  assert.ok(!nav.itemVisible(nav.findItem("lab-catalog"), ["patients:view"], ent(true)), "no lab:view, no item");
  // an owner (no lab shell) opening the URL goes through the same item gate on the page
  assert.match(src("(clinic)/lab/catalog/page.tsx"), /<PlannedScreen id="lab-catalog"><LabCatalogue \/><\/PlannedScreen>/);
});

test("the screen: controls that change the catalogue are absent without lab:catalogue; loading, empty and error states exist", () => {
  const s = src("components/lab/LabCatalogue.tsx");
  assert.match(s, /const canEdit = can\("lab:catalogue"\)/);
  for (const control of ['t("tests.add")', 't("import.open")', 't("tests.edit")', 't("panels.add")']) {
    const line = s.split("\n").find((l) => l.includes(control));
    assert.ok(line && line.includes("canEdit &&"), `${control} is rendered only for lab:catalogue`);
  }
  assert.ok(!/disabled=\{!canEdit\}/.test(s), "absent, not disabled");
  for (const state of ["<Skeleton", "<EmptyState", "<ErrorState"]) assert.ok(s.split(state).length > 3, state);
  assert.match(s, /dryRun: true/); assert.match(s, /dryRun: false/);
  assert.ok(s.indexOf("dryRun: true") < s.indexOf("dryRun: false"), "the diff is fetched before the import can be confirmed");
  assert.match(s, /!diff\.reviewed && /, "the unreviewed-pack warning is shown from the server's flag");
});

test("every string of the screen exists in English and Hindi with the same keys", () => {
  const keys = (d, p = "") => Object.entries(d).flatMap(([k, v]) => (typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`]));
  const en = keys(messages("en")), hi = keys(messages("hi"));
  assert.deepEqual(hi.sort(), en.sort());
  const s = src("components/lab/LabCatalogue.tsx");
  const used = [...s.matchAll(/\bt\("([a-zA-Z.]+)"/g)].map((x) => x[1]);
  assert.ok(used.length > 50);
  for (const k of used) assert.ok(en.includes(k), `missing message shell.lab.${k}`);
  for (const v of m.SAMPLE_TYPES) assert.ok(en.includes(`sample.${v}`), v);
  for (const v of m.CONTAINERS) assert.ok(en.includes(`container.${v}`), v);
  for (const e of ["code", "name", "loinc", "volume", "unit", "tat", "price"]) assert.ok(en.includes(`errors.${e}`), e);
  for (const e of ["code", "name", "tests", "price"]) assert.ok(en.includes(`panelErrors.${e}`), e);
  for (const u of ["minutes", "hours", "days"]) assert.ok(en.includes(`tat.${u}`), u);
});

test("turnaround text and the needs-a-price state of an imported test", () => {
  assert.deepEqual(m.tatParts(30), { unit: "minutes", value: 30 });
  assert.deepEqual(m.tatParts(240), { unit: "hours", value: 4 });
  assert.deepEqual(m.tatParts(90), { unit: "hours", value: 1.5 });
  assert.deepEqual(m.tatParts(1440), { unit: "days", value: 1 });
  assert.deepEqual(m.tatParts(2000), { unit: "hours", value: 33.3 });
  assert.deepEqual(m.tatParts(4320), { unit: "days", value: 3 });
  assert.equal(m.needsPrice({ price: 0, active: false }), true);
  assert.equal(m.needsPrice({ price: 0, active: true }), false, "a free test the lab activated is not waiting for a price");
  assert.equal(m.needsPrice({ price: 120, active: false }), false);
  assert.equal(m.plain("edta_lavender"), "Edta lavender");
});

test("test form: validation, the create body and a PATCH that sends only what changed", () => {
  const f = { ...m.emptyForm(), code: "crp", name: " C-reactive protein ", loinc: "1988-5", volumeMl: "3", unit: "mg/L", price: "500" };
  assert.equal(m.formError(f, true), null);
  assert.equal(m.formError({ ...f, code: "has space" }, true), "code");
  assert.equal(m.formError({ ...f, name: " " }, true), "name");
  assert.equal(m.formError({ ...f, loinc: "19885" }, true), "loinc");
  assert.equal(m.formError({ ...f, volumeMl: "" }, true), "volume");
  assert.equal(m.formError({ ...f, volumeMl: "" }, false), null, "an imported test has no volume yet");
  assert.equal(m.formError({ ...f, unit: "" }, true), "unit");
  assert.equal(m.formError({ ...f, tatMinutes: "0" }, true), "tat");
  assert.equal(m.formError({ ...f, tatMinutes: "1.5" }, true), "tat");
  for (const bad of ["", "-1", "1.234", "abc", "1e3"]) assert.equal(m.formError({ ...f, price: bad }, true), "price", bad);
  assert.deepEqual(m.testBody(f), { code: "CRP", name: "C-reactive protein", loinc: "1988-5", sampleType: "serum", container: "sst_gold", volumeMl: 3,
    method: undefined, unit: "mg/L", tatMinutes: 240, price: 500, taxCode: undefined });

  const form = m.formOf(test1);
  assert.deepEqual(m.testBody(form, test1), {}, "nothing changed, nothing sent");
  assert.deepEqual(m.testBody({ ...form, price: "650" }, test1), { price: 650 });
  assert.deepEqual(m.testBody({ ...form, price: "500.00", tatMinutes: "120", active: false }, test1), { tatMinutes: 120, active: false });
  assert.deepEqual(m.testBody({ ...form, method: "Immunoturbidimetry" }, test1), { method: "Immunoturbidimetry" });
});

test("AC3 panel builder: one panel price against the sum of its tests", () => {
  const tests = [{ id: "a", price: 100 }, { id: "b", price: 150 }, { id: "c", price: 200 }, { id: "d", price: 250 }, { id: "e", price: 300 }];
  assert.deepEqual(m.panelMaths(tests, ["a", "b", "c", "d", "e"], "800"), { total: 1000, saving: 200 });
  assert.deepEqual(m.panelMaths(tests, ["a", "b"], "400"), { total: 250, saving: 0 }, "a panel dearer than its tests shows no saving");
  assert.deepEqual(m.panelMaths(tests, ["a"], ""), { total: 100, saving: 0 });
  assert.equal(m.panelError("LIPID", "Lipid profile", ["a", "b"], "800"), null);
  assert.equal(m.panelError("", "Lipid profile", ["a"], "800"), "code");
  assert.equal(m.panelError("LIPID", "", ["a"], "800"), "name");
  assert.equal(m.panelError("LIPID", "Lipid profile", [], "800"), "tests");
  assert.equal(m.panelError("LIPID", "Lipid profile", ["a"], "8,00"), "price");
});

test("the role editor offers the lab module, so a clinic can build its own lab roles", () => {
  assert.match(src("(clinic)/staff/staffModel.ts"), /\{ key: "lab", label: "Lab" \}/);
});
