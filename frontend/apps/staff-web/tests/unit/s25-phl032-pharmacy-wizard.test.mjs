// PHL-032: the pharmacy setup wizard's steps and blocker badge, the licence-map merge, and the pharmacy-only navigation.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { blockerText, doneCount, mergeLicences, wizardSteps, printerTestHtml, PIC_REGISTRATION_KEY } from "../../app/(clinic)/setup/pharmacy-wizard/pharmacyWizardModel.ts";
import { editionAllows } from "../../app/lib/entitlementRules.ts";
import { navFor, counterRouteState } from "../../app/shell/navRegistry.ts";

const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const setup = (over = {}) => ({ pharmacyEdition: true, region: "IN", requiredLicenceKeys: ["IN_20B", "IN_21B"], missingLicenceKeys: ["IN_20B", "IN_21B"], pharmacistInChargeSet: false,
  pharmacistRegistrationSet: false, printerTestedAt: null, stockedItems: 0, invoicingBlocked: true, ...over });
const profile = { name: "Sri Chemist", taxId: null };

test("five steps in order; a missing drug licence is the only blocker and it carries the badge", () => {
  const steps = wizardSteps(setup(), profile);
  assert.deepEqual(steps.map((s) => s.key), ["business", "licence", "in_charge", "printer", "stock"]);
  assert.deepEqual(steps.filter((s) => s.blocker).map((s) => s.key), ["licence"]);
  assert.equal(doneCount(steps), 0);
  assert.match(blockerText(setup()), /Invoices cannot be issued until .*20B.* and .*21B.* are entered/);
});

test("with both licences the blocker and its text are gone; an extra licence key never counts as done", () => {
  const ok = setup({ missingLicenceKeys: [], invoicingBlocked: false, pharmacistInChargeSet: true, pharmacistRegistrationSet: true, printerTestedAt: "2026-11-20T10:00:00Z", stockedItems: 2000 });
  const steps = wizardSteps(ok, { name: "Sri Chemist", taxId: "29ABCDE1234F1Z5" });
  assert.ok(steps.every((s) => s.done && !s.blocker));
  assert.equal(blockerText(ok), null);
  assert.equal(blockerText(setup({ pharmacyEdition: false })), null, "a clinic is never told it cannot invoice");
  assert.equal(wizardSteps(setup({ pharmacistInChargeSet: true }), profile).find((s) => s.key === "in_charge").done, false, "a name without a registration number is not done");
});

test("saving licences keeps the keys the wizard does not edit and removes a cleared one", () => {
  assert.deepEqual(mergeLicences({ IN_20B: "A", [PIC_REGISTRATION_KEY]: "R1", OTHER: "x" }, { IN_20B: " B ", IN_21B: "C" }), { IN_20B: "B", IN_21B: "C", [PIC_REGISTRATION_KEY]: "R1", OTHER: "x" });
  assert.deepEqual(mergeLicences({ IN_20B: "A" }, { IN_20B: "  " }), {});
});

test("the printer test page has no markup from the shop name", () => {
  const html = printerTestHtml("<b>Shop & Co", new Date("2026-11-20T10:05:00Z"));
  assert.ok(!html.includes("<b>") && html.includes("2026-11-20 10:05") && html.includes("80mm"));
});

const ent = (edition) => ({ modules: ["pharmacy_inhouse", "billing"].map((code) => ({ code, enabled: true })), killedFeatures: [], seatLimit: 10, edition });
const owner = ["setup:view", "setup:edit", "pharmacy:view", "pharmacy:edit", "billing:view", "billing:create", "queue:view", "clinical:view", "reports:view", "staff:view", "patients:view"];

test("a pharmacy-only owner sees pharmacy navigation and no clinical or scheduling item; a clinic owner is unchanged", () => {
  const items = navFor({ perms: owner, owner: true, ent: ent("pharmacy") }).items;
  const hrefs = items.map((i) => i.href);
  assert.ok(hrefs.includes("/pharmacy") && hrefs.includes("/pharmacy/pos") && hrefs.includes("/setup/pharmacy-wizard"));
  for (const h of hrefs) assert.ok(editionAllows(h, ent("pharmacy")), `${h} must be a pharmacy area`);
  assert.ok(!hrefs.some((h) => ["/schedule", "/arrivals", "/consult", "/my-day", "/nursing", "/packages", "/specialty", "/recalls"].includes(h)));
  const clinic = navFor({ perms: owner, owner: true, ent: ent("clinic") }).items.map((i) => i.href);
  assert.ok(clinic.includes("/schedule") && !clinic.includes("/setup/pharmacy-wizard"));
  assert.equal(editionAllows("/schedule", null), true, "unknown entitlements never hide anything");
});

test("the wizard sits outside the Setup tab frame and is gated on setup:view; its stock step reuses the ADM-013 import", () => {
  const page = src("(clinic)/setup/pharmacy-wizard/page.tsx");
  assert.match(page, /AccessGate permission="setup:view"/);
  assert.match(page, /<PatientImport startWith=\{\{ importType: "stock", source: "csv" \}\}/);
  assert.match(page, /api\.post\("\/pharmacy\/setup\/printer-test"/);
  assert.ok(!/fetch\(/.test(page));
  assert.match(src("components/setup/PatientImport.tsx"), /startWith\?\.importType/);
});

test("direct URL: a pharmacy tenant cannot open a clinical counter route", () => {
  const r = counterRouteState("pharmacist", { perms: ["pharmacy:view", "pharmacy:edit", "billing:view", "billing:create"], owner: false, ent: ent("pharmacy") }, "/pharmacy/pos", true);
  assert.equal(r.kind, "open");
});

test("T1-BUG-12: a pharmacy edition's overview neither renders nor requests the patient-time cards", async () => {
  const { pharmacyEdition } = await import("../../app/lib/entitlementRules.ts");
  assert.equal(pharmacyEdition({ modules: [], killedFeatures: [], seatLimit: null, edition: "pharmacy" }), true);
  assert.equal(pharmacyEdition({ modules: [], killedFeatures: [], seatLimit: null }), false);
  const page = src("(clinic)/overview/page.tsx");
  assert.match(page, /const clinical = settled && !pharmacyEdition\(ent\);/, "decided after the entitlements answer: no request before it");
  // every mount of a card that reads queue / consult data is behind `clinical` (the tiles fetch on mount)
  for (const card of ["EtaAccuracyTile", "FlowOverviewSection"]) {
    const mounts = page.match(new RegExp(`[^\\n]*<${card} />`, "g")) ?? [];
    assert.equal(mounts.length, 1, card);
    assert.match(mounts[0], /\{clinical && /, card);
  }
});
