// FIN-011: the Tax exports card. Pure helpers run directly; the card is checked as source (no browser here) against the backend
// routes so a path changed on one side fails here.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { canRetry, chip, defaultPeriod, exportPath, periodProblem, TAX_FORMATS } from "../../app/(clinic)/reports/taxExportModel.ts";

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), "utf8");
const card = read("../../app/(clinic)/reports/TaxExportsCard.tsx");
const library = read("../../app/(clinic)/reports/ReportsLibrary.tsx");
const gstr1 = read("../../../../../backend/src/main/java/com/nabd/hms/billing/gst/Gstr1Controller.java");
const einvoice = read("../../../../../backend/src/main/java/com/nabd/hms/billing/einvoice/EinvoiceController.java");
const en = JSON.parse(read("../../../../packages/i18n/messages/en/shell.json")).taxExports;
const hi = JSON.parse(read("../../../../packages/i18n/messages/hi/shell.json")).taxExports;

test("the default month is the last finished month, across the year end", () => {
  assert.equal(defaultPeriod("2027-05-03"), "2027-04");
  assert.equal(defaultPeriod("2027-01-31"), "2026-12");
});

test("the running month and the future are refused before any request; a finished month is fine", () => {
  assert.equal(periodProblem("2027-05", "2027-05-03"), "open");
  assert.equal(periodProblem("2027-06", "2027-05-03"), "open");
  assert.equal(periodProblem("2027-04", "2027-05-03"), null);
  assert.equal(periodProblem("2026-12", "2027-01-01"), null);
  assert.equal(periodProblem("", "2027-05-03"), "invalid");
  assert.equal(periodProblem("2027-13", "2027-05-03"), "invalid");
});

test("the export path and the formats are the controller's", () => {
  assert.equal(exportPath("2027-04", "xlsx"), "/billing/exports/gstr1?period=2027-04&format=xlsx");
  assert.deepEqual(TAX_FORMATS, ["xlsx", "json"]);
  assert.match(gstr1, /@GetMapping\("\/exports\/gstr1"\)/);
  assert.match(gstr1, /hasAuthority\('reports:export'\)/);
  assert.match(gstr1, /@RequestMapping\("\/v1\/billing"\)/);
});

test("IRN chips: pending reads as 'IRN pending' and can be retried, an issued IRN never can", () => {
  assert.deepEqual(chip("pending"), { tone: "wait", key: "chipPending" });
  assert.deepEqual(chip("accepted"), { tone: "ok", key: "chipIssued" });
  assert.deepEqual(chip("rejected"), { tone: "bad", key: "chipRejected" });
  assert.deepEqual(chip("cancelled"), { tone: "off", key: "chipCancelled" });
  assert.equal(canRetry("pending"), true);
  assert.equal(canRetry("rejected"), true);
  assert.equal(canRetry("accepted"), false);
  assert.equal(canRetry("cancelled"), false);
});

test("the card calls only routes the backend has, with the grants the server checks", () => {
  for (const [path, verb] of [["/einvoices", "GetMapping"], ["/einvoice/settings", "GetMapping"], ["/einvoice/settings", "PutMapping"], ["/invoices/{id}/einvoice/retry", "PostMapping"]]) {
    assert.ok(einvoice.includes(`@${verb}("${path}")`), `${verb} ${path}`);
  }
  assert.match(card, /"\/billing\/einvoices"/);
  assert.match(card, /"\/billing\/einvoice\/settings"/);
  assert.match(card, /\/billing\/invoices\/\$\{encodeURIComponent\(id\)\}\/einvoice\/retry/);
});

test("restricted controls are absent: no export grant or a non-GST market renders nothing; the switch needs the owner and a deployed adapter", () => {
  assert.match(card, /!mounted \|\| !can\("reports:export"\) \|\| taxRegime\(getIdentity\(\)\?\.tenantRegion\)\.label !== "GST"\) return null/);
  assert.match(card, /isOwner\(\) && can\("billing:approve"\) && !!st && \(st\.adapterAvailable \|\| st\.irpEnabled\)/);
  assert.match(card, /can\("billing:edit"\)/); // retry
  assert.match(library, /<TaxExportsCard \/>/);
});

test("every string exists in English and Hindi", () => {
  const used = [...card.matchAll(/\bt\("([A-Za-z0-9_]+)"/g)].map((m) => m[1]);
  const dynamic = ["format_xlsx", "format_json", "chipIssued", "chipPending", "chipRejected", "chipCancelled"];
  for (const k of new Set([...used, ...dynamic])) {
    assert.ok(typeof en[k] === "string" && en[k], `en ${k}`);
    assert.ok(typeof hi[k] === "string" && hi[k], `hi ${k}`);
  }
  assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort());
});
