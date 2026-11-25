// PHL-056: the lab-only edition's navigation (generalised from the pharmacy edition, which must not move), the lab setup wizard's gate,
// steps, PUT body, letterhead preview and price list import summary.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  labWizardGate, labWizardSteps, requiredLeft, setupBody, letterheadError, letterheadPreview, priceTemplateCsv, priceImportSummary, labelTestHtml,
} from "../../app/(clinic)/setup/lab-wizard/labWizardModel.ts";
import { EDITION_HREFS, PHARMACY_EDITION_HREFS, editionAllows, editionHref, pharmacyHref, standaloneEdition } from "../../app/lib/entitlementRules.ts";
import { navFor, homeFor, counterRouteState, NAV } from "../../app/shell/navRegistry.ts";

const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const ent = (edition, codes = ["lab", "billing"]) => ({ modules: codes.map((code) => ({ code, enabled: true })), killedFeatures: [], seatLimit: 10, edition });
// the Owner preset of a lab tenant: every module's grants, the clinical ones included (the server refuses those by edition)
const owner = ["setup:view", "setup:edit", "lab:view", "lab:order", "lab:collect", "lab:result", "lab:validate", "lab:release", "lab:catalogue", "lab:qc",
  "pharmacy:view", "pharmacy:edit", "billing:view", "billing:create", "queue:view", "clinical:view", "reports:view", "staff:view", "patients:view", "packages:view", "compliance:view"];
const CLINIC_OR_PHARMACY = ["/schedule", "/arrivals", "/consult", "/my-day", "/nursing", "/packages", "/specialty", "/recalls", "/pharmacy", "/pharmacy/pos", "/checkout/otc", "/setup/pharmacy-wizard"];

test("edition rules: a lab sees the lab, billing, reports and admin areas; the pharmacy list is the one PHL-032 shipped", () => {
  assert.deepEqual(PHARMACY_EDITION_HREFS, ["/pharmacy", "/checkout/otc", "/billing", "/reports", "/overview", "/setup", "/staff", "/plan", "/compliance", "/account", "/patients"]);
  assert.equal(EDITION_HREFS.pharmacy, PHARMACY_EDITION_HREFS);
  for (const h of ["/lab", "/lab/catalog", "/lab/collect?x=1", "/billing", "/billing/invoices", "/reports", "/overview", "/setup/lab-wizard", "/staff", "/patients", "/account"]) {
    assert.ok(editionAllows(h, ent("lab")), `${h} is a lab area`);
  }
  for (const h of [...CLINIC_OR_PHARMACY.filter((x) => !x.startsWith("/setup")), "/laboratory", "/prescriptions", "/encounters"]) assert.ok(!editionAllows(h, ent("lab")), `${h} is not a lab area`);
  assert.ok(!editionAllows("/lab", ent("pharmacy")) && editionAllows("/pharmacy/pos", ent("pharmacy")) && pharmacyHref("/checkout/otc") && !pharmacyHref("/lab"));
  assert.ok(editionHref("lab", "/lab") && !editionHref("lab", "/pharmacy"));
  for (const e of [ent("clinic"), ent(undefined), null]) { assert.equal(standaloneEdition(e), null); assert.ok(editionAllows("/schedule", e) && editionAllows("/pharmacy", e)); }
  assert.equal(standaloneEdition(ent("lab")), "lab");
  assert.equal(standaloneEdition(ent("pharmacy")), "pharmacy");
});

test("AC1: a lab-only owner sees only lab navigation: the lab shell, the setup wizard and the owner's admin areas", () => {
  const items = navFor({ perms: owner, owner: true, ent: ent("lab") }).items;
  const hrefs = items.map((i) => i.href);
  assert.deepEqual(hrefs.slice(0, 6), ["/lab", "/lab/collect", "/lab/results", "/lab/validate", "/lab/catalog", "/setup/lab-wizard"], "the lab comes first");
  for (const h of hrefs) assert.ok(editionAllows(h, ent("lab")), `${h} must be a lab area`);
  assert.ok(!hrefs.some((h) => CLINIC_OR_PHARMACY.includes(h)), "no clinic or pharmacy item");
  assert.ok(hrefs.includes("/billing") && hrefs.includes("/reports") && hrefs.includes("/overview") && hrefs.includes("/staff"));
  assert.equal(new Set(hrefs).size, hrefs.length, "no duplicate destination");
  assert.equal(homeFor({ perms: owner, owner: true, ent: ent("lab") }), "/setup/lab-wizard", "the first built screen: the wizard, until the worklist is built");
  // the other editions
  const clinic = navFor({ perms: owner, owner: true, ent: ent("clinic") }).items.map((i) => i.href);
  assert.ok(clinic.includes("/schedule") && !clinic.includes("/setup/lab-wizard") && !clinic.includes("/lab"), "a clinic owner's nav is unchanged");
  const pharmacy = navFor({ perms: owner, owner: true, ent: ent("pharmacy", ["pharmacy_inhouse", "billing"]) }).items.map((i) => i.href);
  assert.ok(pharmacy.includes("/setup/pharmacy-wizard") && pharmacy.includes("/pharmacy/pos") && !pharmacy.some((h) => h.startsWith("/lab") || h === "/setup/lab-wizard"));
});

test("AC1: the lab's staff roles get the lab shell, and the billing desk of a lab has no clinic checkout queue", () => {
  const technician = ["lab:view", "lab:collect", "lab:result", "patients:view", "departments:view"];
  const tech = navFor({ perms: technician, owner: false, ent: ent("lab") });
  assert.equal(tech.role, "lab");
  assert.ok(tech.items.length === 5 && tech.items.every((i) => i.href.startsWith("/lab")));
  const frontDesk = ["patients:view", "patients:create", "patients:edit", "billing:view", "billing:create", "billing:edit", "lab:view", "lab:order", "departments:view"];
  const desk = navFor({ perms: frontDesk, owner: false, ent: ent("lab") });
  assert.deepEqual(desk.roles, ["lab", "billing"]);
  const till = navFor({ perms: frontDesk, owner: false, ent: ent("lab"), override: "billing" }).items.map((i) => i.href);
  assert.ok(till.includes("/billing") && !till.some((h) => h.startsWith("/arrivals") || h === "/packages"), "day close yes; the queue-based checkout no");
  assert.equal(counterRouteState("lab", { perms: technician, owner: false, ent: ent("lab") }, "/lab/catalog", true).kind, "open");
  assert.equal(counterRouteState("lab", { perms: technician, owner: false, ent: ent("lab", ["billing"]) }, "/lab/catalog", true).kind, "blocked");
  assert.ok(NAV.lab.every((i) => i.href.startsWith("/lab")), "the wizard is not part of the bench shell");
});

test("while the entitlements are unknown, the edition last seen on this device keeps the lab owner's menu; an answer always wins", () => {
  const hrefs = (input) => navFor({ perms: owner, owner: true, ...input }).items.map((i) => i.href);
  const hinted = hrefs({ ent: null, labHint: true });
  assert.ok(hinted.includes("/setup/lab-wizard") && !hinted.includes("/schedule") && !hinted.includes("/pharmacy"), "no clinic or pharmacy item flashes for a known lab");
  for (const h of hinted) assert.ok(editionAllows(h, ent("lab")), `${h} must be a lab area`);
  assert.deepEqual(hinted, hrefs({ ent: ent("lab") }).filter((h) => !h.startsWith("/lab")), "the answered menu, minus the screens that need the plan's answer");
  assert.ok(hrefs({ ent: null }).includes("/schedule"), "nothing remembered: the owner items, as before");
  assert.ok(!hrefs({ ent: ent("clinic"), labHint: true }).includes("/setup/lab-wizard"), "a clinic answer beats a stale hint");
  assert.ok(hrefs({ ent: ent("pharmacy", ["pharmacy_inhouse", "billing"]), labHint: true }).includes("/setup/pharmacy-wizard"));
  const hook = src("lib/entitlements.ts");
  assert.match(hook, /if \(ent\) return standaloneEdition\(ent\);/);
  assert.match(hook, /lab: "nabd_lab_edition"/);
  assert.match(hook, /pharmacy: "nabd_pharmacy_edition"/, "the pharmacy key is the one devices already hold");
  assert.match(src("shell/useShellNav.ts"), /lab: editionKnown\(null\) === "lab"/, "read in the effect, so server and first client render agree");
});

test("a lab's overview neither renders nor requests the patient-time cards", () => {
  const page = src("(clinic)/overview/page.tsx");
  assert.match(page, /const lab = settled && editionKnown\(ent\) === "lab";/, "decided after the entitlements answer");
  for (const card of ["EtaAccuracyTile", "FlowOverviewSection"]) {
    const mounts = page.match(new RegExp(`[^\\n]*<${card} />`, "g")) ?? [];
    assert.equal(mounts.length, 1, card);
    assert.match(mounts[0], /\{clinical && !lab && /, card);
  }
});

const setup = (over = {}) => ({ accreditationNo: null, pathologistStaffId: null, pathologistName: null, pathologistRegistrationNo: null, letterheadLines: null,
  printerTestedAt: null, catalogueTests: 0, activeTests: 0, ...over });
const profile = { name: "Synthetic Diagnostics", taxId: null, taxIdType: null };

test("the wizard mounts only when the entitlements answer has the lab module and the role can see the lab", () => {
  assert.equal(labWizardGate(null, false, true), "wait", "no request before the first answer");
  assert.equal(labWizardGate(null, true, true), "unverified", "a failed answer is fail-closed");
  assert.equal(labWizardGate(ent("clinic", ["billing"]), true, true), "blocked");
  assert.equal(labWizardGate({ modules: [{ code: "lab", enabled: false }], killedFeatures: [], seatLimit: null }, true, true), "blocked");
  assert.equal(labWizardGate(ent("lab"), true, false), "blocked", "no lab:view");
  assert.equal(labWizardGate(ent("lab"), true, true), "open");
  assert.equal(labWizardGate(ent("clinic"), true, true), "open", "a clinic with the lab module can use the wizard too");
  const page = src("(clinic)/setup/lab-wizard/page.tsx");
  assert.match(page, /AccessGate permission="setup:view"/);
  assert.match(page, /const gate = labWizardGate\(ent, settled, can\("lab:view"\)\);/);
  const gate = page.slice(page.indexOf("function Gate()"), page.indexOf("function Wizard()"));
  assert.ok(gate.indexOf('gate === "blocked"') < gate.indexOf("<Wizard />") && gate.indexOf('gate === "wait"') < gate.indexOf("<Wizard />"));
  assert.ok(!/api\./.test(gate), "the gate itself requests nothing");
  assert.ok(!/retry\(\)/.test(page), "the entitlements call is retried by hand only");
  assert.ok(!/fetch\(/.test(page));
});

test("six steps in order; accreditation is optional and a pathologist needs a registration number", () => {
  const steps = labWizardSteps(setup(), profile);
  assert.deepEqual(steps.map((s) => s.key), ["business", "accreditation", "pathologist", "letterhead", "catalogue", "printer"]);
  assert.deepEqual(steps.filter((s) => s.optional).map((s) => s.key), ["accreditation"]);
  assert.equal(requiredLeft(steps), 5);
  const ready = setup({ pathologistStaffId: "s1", pathologistName: "Dr Test", pathologistRegistrationNo: "SYN-1", letterheadLines: "1 Example Road", printerTestedAt: "2026-11-20T10:00:00Z", catalogueTests: 150, activeTests: 148 });
  const done = labWizardSteps(ready, { ...profile, taxId: "29ABCDE1234F1Z5" });
  assert.equal(requiredLeft(done), 0, "ready without an accreditation number");
  assert.equal(done.find((s) => s.key === "accreditation").done, false);
  assert.equal(labWizardSteps(setup({ pathologistStaffId: "s1" }), profile).find((s) => s.key === "pathologist").done, false, "a name without a registration number is not done");
  assert.equal(labWizardSteps(setup({ catalogueTests: 150 }), profile).find((s) => s.key === "catalogue").done, false, "an unpriced catalogue is not done");
  assert.equal(labWizardSteps(setup(), null)[0].done, false);
});

test("saving one step keeps the others: PUT /lab/setup replaces all four fields", () => {
  const cur = setup({ accreditationNo: "MC-0000", pathologistStaffId: "s1", pathologistRegistrationNo: "SYN-1", letterheadLines: "1 Example Road" });
  assert.deepEqual(setupBody(cur, { letterheadLines: " 2 Example Road\nExample City " }),
    { accreditationNo: "MC-0000", pathologistStaffId: "s1", pathologistRegistrationNo: "SYN-1", letterheadLines: "2 Example Road\nExample City" });
  assert.deepEqual(setupBody(cur, { accreditationNo: "  " }), { accreditationNo: null, pathologistStaffId: "s1", pathologistRegistrationNo: "SYN-1", letterheadLines: "1 Example Road" });
  assert.deepEqual(setupBody(setup(), { pathologistStaffId: "", pathologistRegistrationNo: "" }), { accreditationNo: null, pathologistStaffId: null, pathologistRegistrationNo: null, letterheadLines: null });
  const page = src("(clinic)/setup/lab-wizard/page.tsx");
  assert.match(page, /api\.put<LabSetup>\("\/lab\/setup", setupBody\(setup, edits\)\)/);
});

test("letterhead: limits as sentences, and a preview that shows only what was entered", () => {
  assert.equal(letterheadError("1 Example Road\nExample City"), null);
  assert.match(letterheadError("1\n2\n3\n4\n5\n6\n7"), /up to 6 lines/);
  assert.equal(letterheadError("1\n2\n3\n4\n5\n6\n"), null, "a trailing new line is not a seventh line");
  assert.match(letterheadError("x".repeat(401)), /400 characters/);
  assert.deepEqual(letterheadPreview(profile, setup()), { name: "Synthetic Diagnostics", lines: [], accreditation: null, tax: null, pathologist: null });
  const full = setup({ accreditationNo: "MC-0000", pathologistName: "Dr Test", pathologistRegistrationNo: "SYN-1", letterheadLines: "1 Example Road\n\n Example City " });
  assert.deepEqual(letterheadPreview({ name: " Synthetic Diagnostics ", taxId: "29ABCDE1234F1Z5", taxIdType: "GSTIN" }, full), {
    name: "Synthetic Diagnostics", lines: ["1 Example Road", "Example City"], accreditation: "NABL accreditation no. MC-0000", tax: "GSTIN 29ABCDE1234F1Z5", pathologist: "Dr Test, Reg. no. SYN-1" });
  assert.equal(letterheadPreview({ ...profile, taxId: "300000000000003", taxIdType: "VAT" }, full).tax, "VAT no. 300000000000003");
  assert.deepEqual(letterheadPreview(profile, full, "typed now").lines, ["typed now"], "the preview follows the draft while typing");
  assert.equal(letterheadPreview(profile, setup({ pathologistName: "Dr Test" })).pathologist, "Dr Test");
  const page = src("(clinic)/setup/lab-wizard/page.tsx");
  assert.match(page, /aria-label="Letterhead preview"/);
  assert.match(page, /letterheadPreview\(profile, setup, canEdit \? text : undefined\)/);
});

test("AC2: the price list: a template of the lab's own codes, a dry run in sentences, then one press to apply", () => {
  assert.equal(priceTemplateCsv([{ code: "HB", name: "Haemoglobin" }, { code: "X1", name: 'Test "A", serum' }]), 'code,price,name\r\nHB,,"Haemoglobin"\r\nX1,,"Test \'A\', serum"\r\n');
  const diff = { dryRun: true, toPrice: [{ code: "HB", name: "Haemoglobin", currentPrice: 0, newPrice: 120 }], unchanged: 2, unknown: ["ZZ"], problems: [{ line: 4, message: "Line 4 does not start with a test code." }], updated: 0 };
  assert.deepEqual(priceImportSummary(diff), ["1 test will get a price and become billable.", "2 tests already have that price.", "1 code is not in your catalogue: ZZ.", "1 line could not be used."]);
  assert.deepEqual(priceImportSummary({ ...diff, toPrice: [], unchanged: 0, unknown: [], problems: [] }), ["No test would change."]);
  assert.match(priceImportSummary({ ...diff, unknown: Array.from({ length: 12 }, (_, i) => `U${i}`) })[2], /12 codes are not in your catalogue: U0, .*U9 and more\./);
  const page = src("(clinic)/setup/lab-wizard/page.tsx");
  assert.match(page, /api\.post<PriceImport>\("\/lab\/catalogue\/price-imports", \{ csv: text, dryRun \}\)/);
  assert.match(page, /await run\(text, true\);/, "choosing a file only runs the dry run");
  const apply = page.split("\n").find((l) => l.includes("run(csv, false)"));
  assert.ok(apply && apply.includes("diff.toPrice.length > 0 &&"), "applying is offered only after a diff that changes something");
  assert.match(page, /<ImportDialog onClose=/, "the starter catalogue is the PHL-041 import, dry run included");
  assert.match(src("components/lab/LabCatalogue.tsx"), /export function ImportDialog\(/);
});

test("controls that change the setup are absent, not disabled, without the grants; the test label carries no markup", () => {
  const page = src("(clinic)/setup/lab-wizard/page.tsx");
  assert.match(page, /const canEdit = can\("setup:edit"\) && can\("lab:catalogue"\);/);
  assert.ok(!/disabled=\{!canEdit/.test(page), "absent, not disabled");
  for (const step of ["<Accreditation ", "<Pathologist ", "<BusinessDetailsStep "]) {
    const lines = page.split("\n");
    const at = lines.findIndex((l) => l.includes(step));
    assert.ok(at > 0 && /^\s*\? </.test(lines[at]) && lines[at - 1].trimEnd().endsWith("(canEdit"), `${step} only for an editor`);
  }
  assert.match(page, /if \(!canEdit\) return <p className=\{styles\.empty\}>\{done \? "The label printer was tested\."/);
  for (const state of ["<Skeleton", "<ErrorState", "<EmptyState"]) assert.ok(page.includes(state), state);
  const html = labelTestHtml("<b>Lab & Co");
  assert.ok(!html.includes("<b>Lab") && html.includes("Lab  Co") && html.includes("50mm 25mm"));
});

test("the pharmacy wizard keeps its Business details step: the same component, now shared with the lab wizard", () => {
  const pharmacy = src("(clinic)/setup/pharmacy-wizard/page.tsx");
  assert.match(pharmacy, /step\.key === "business" && <BusinessDetailsStep profile=\{profile\} canEdit=\{canEdit\} onSaved=\{load\} onError=\{setError\} \/>/);
  const step = src("components/setup/BusinessDetailsStep.tsx");
  assert.match(step, /api\.patch\("\/setup\/profile", \{ name: name\.trim\(\), timezone: profile\.timezone, taxIdType: type, taxId: taxId\.trim\(\) \|\| null, whatsappNumber: profile\.whatsappNumber, specialties: profile\.specialties \}\)/);
  for (const id of ["pw-name", "pw-taxtype", "pw-taxid"]) assert.ok(step.includes(`id="${id}"`), id);
});
