// ADM-013: the "Old software" tab of Setup > Data (source picker, what to import, supported-exports note) and the
// read-only "Imported history" rows on the patient timeline. The model is pure; the wiring is checked on the source.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { SOURCES, SUPPORTED_NOTE, TYPES, TYPE_FIELDS, formatOf, missingFields, typeOf } from "../../app/components/setup/importModel.ts";

const read = (p) => readFileSync(new URL(p, import.meta.url), "utf8");
const panel = read("../../app/components/setup/PatientImport.tsx");
const page = read("../../app/(clinic)/setup/data/page.tsx");
const timeline = read("../../app/(clinic)/patients/drawer/TimelineSection.tsx");
const mapper = read("../../../../../backend/src/main/java/com/nabd/hms/organization/dataimport/ImportMapper.java");
const request = read("../../../../../backend/src/main/java/com/nabd/hms/organization/setup/dto/ImportJobRequest.java");

const javaList = (re) => [...mapper.match(re)[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);

test("model: fields, required fields, sources and import types are the server's", () => {
  assert.deepEqual(TYPE_FIELDS.patients.map((f) => f.key), javaList(/List<String> TARGETS = List\.of\(([^)]*)\)/));
  for (const t of ["visit_history", "drugs", "stock"]) {
    assert.deepEqual(TYPE_FIELDS[t].map((f) => f.key).sort(), javaList(new RegExp(`"${t}", List\\.of\\(([^)]*)\\)`)).sort(), `${t} targets`);
    const required = [...mapper.matchAll(new RegExp(`"${t}", List\\.of\\(([^)]*)\\)`, "g"))][1][1].match(/"([^"]+)"/g).map((s) => s.slice(1, -1));
    assert.deepEqual(TYPE_FIELDS[t].filter((f) => f.required).map((f) => f.key).sort(), required.sort(), `${t} required`);
  }
  const pattern = (field) => request.match(new RegExp(`regexp = "([^"]+)"\\) String ${field}\\b`))[1].split("|");
  assert.deepEqual(SOURCES.map((s) => s.key).sort(), pattern("source").sort());
  for (const t of TYPES) assert.ok(pattern("importType").includes(t.key), t.key);
  assert.ok(pattern("sourceFormat").includes("zip"));
});

test("model: a past visit needs a patient key; upload format; unknown type falls back to patients", () => {
  assert.deepEqual(missingFields("patients", { name: "Name", mobile: "Mobile", dob: "DOB", sex: "Sex" }), []);
  assert.deepEqual(missingFields("stock", { name: "Item" }), ["Batch number", "Expiry date", "Quantity"]);
  assert.equal(missingFields("visit_history", { visitDate: "Date", doctorName: "Doctor" }).length, 1);
  assert.deepEqual(missingFields("visit_history", { visitDate: "Date", doctorName: "Doctor", oldRecordNo: "Patient ID" }), []);
  assert.deepEqual(missingFields("visit_history", { visitDate: "Date", doctorName: "Doctor", mobile: "Mobile", dob: "DOB" }), []);
  assert.equal(missingFields("visit_history", { visitDate: "Date", doctorName: "Doctor", mobile: "Mobile" }).length, 1);
  assert.equal(formatOf("Export.ZIP", true), "zip");
  assert.equal(formatOf("export.zip", false), null, "the Excel / CSV tab takes no zip");
  assert.equal(formatOf("a.xlsx", false), "xlsx");
  assert.equal(formatOf("a.pdf", true), null);
  assert.equal(typeOf("stock"), "stock");
  assert.equal(typeOf("appointments"), "patients");
  assert.equal(typeOf(undefined), "patients");
});

test("supported-exports note names each market's software", () => {
  for (const s of SOURCES.filter((x) => x.key !== "csv")) assert.ok(SUPPORTED_NOTE.includes(s.label), s.label);
  assert.match(SUPPORTED_NOTE, /India:/);
  assert.match(SUPPORTED_NOTE, /Gulf/);
});

test("panel: source picker and import type on the Old software tab, same staged steps, states kept", () => {
  assert.match(panel, /pane === "software" && \(!job \|\| status === "failed"\)/);
  assert.match(panel, /SOURCES\.map\(/);
  assert.match(panel, /TYPES\.map\(/);
  assert.match(panel, /\{SUPPORTED_NOTE\}/);
  assert.match(panel, /accept="\.zip,\.xlsx,\.csv"/);
  assert.match(panel, /start\(f, importType, source\)/);
  assert.match(panel, /importType: type, sourceFormat: format, \.\.\.\(from !== null \? \{ source: from \} : \{\}\)/, "the Excel / CSV tab sends no source");
  assert.match(panel, /UNKNOWN_LAYOUT:/);
  assert.match(panel, /<Skeleton/);
  assert.match(panel, /<ErrorState/);
  assert.match(panel, /<EmptyState/);
  assert.match(panel, /type === "patients" && \(diff\.duplicates \?\? 0\) > 0/, "merge / create decisions are for patients only");
  assert.match(panel, /read-only &quot;Imported history&quot;/);
  assert.match(page, /can\("setup:edit"\) && \(/, "the import is absent without setup:edit");
});

test("timeline: asks for imported history and labels it with the date and the old doctor's name", () => {
  assert.match(timeline, /timeline\?limit=10&include=imported/);
  assert.match(timeline, /key=\{e\.queueEntryId \?\? e\.documentId\}/);
  assert.match(timeline, /Imported history · \$\{e\.doctorName/);
  assert.doesNotMatch(timeline, /<button[^>]*imported/i, "read-only: no action on an imported row");
});

test("T1-BUG-10: an imported visit's date is the clinic's day, also in a browser west of the clinic", async () => {
  const { formatClinicDate } = await import("../../app/lib/clinicTime.ts");
  const sent = "2019-03-04T18:30:00Z"; // 5 Mar 2019 00:00 in Asia/Kolkata, as TimelineRepository sends it
  const was = process.env.TZ;
  try {
    for (const browser of ["America/New_York", "UTC", "Asia/Riyadh", "Australia/Sydney"]) {
      process.env.TZ = browser;
      assert.equal(formatClinicDate(sent, undefined, "Asia/Kolkata"), "05 Mar 2019", browser);
    }
    process.env.TZ = "America/New_York";
    assert.equal(new Date(sent).getDate(), 4, "the browser's own calendar is a day early: the bug");
  } finally {
    if (was === undefined) delete process.env.TZ; else process.env.TZ = was;
  }
  assert.match(timeline, /formatClinicDate\(e\.occurredAt, undefined, tz\)/);
  assert.match(timeline, /const tz = useClinicTimeZone\(\)/);
  assert.doesNotMatch(timeline, /toLocale(Date)?String/);
});

test("T1-BUG-13: the unknown-layout sentence names the picker option as the picker labels it, on the server and on the page", async () => {
  const { SOURCES } = await import("../../app/components/setup/importModel.ts");
  const other = SOURCES.find((x) => x.key === "csv").label;
  const java = readFileSync(new URL("../../../../../backend/src/main/java/com/nabd/hms/organization/dataimport/ImportPipeline.java", import.meta.url), "utf8");
  const sentence = java.match(/"UNKNOWN_LAYOUT", "([^"]+)"/)[1];
  assert.ok(sentence.includes(`'${other}'`), sentence);
  assert.ok(panel.includes(`UNKNOWN_LAYOUT: "The headings are not the standard export of that software. Choose \\"${other}\\" and`), "the page's own text for the same code");
  assert.doesNotMatch(java + panel, /Other \(CSV\)/);
});
