// ADM-014: paper-card quick entry. Debounce, duplicate pill states, card rules, request body, and how the form is wired
// (Enter saves and returns to Name, demo_only cannot save, the control is absent without patients:create).
import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { BLANK, CONTROL_KEYS_PER_CARD, FIELD_ORDER, LOOKUP_DEBOUNCE_MS, birthYear, cardBody, cardIssues, debouncer, lookupDigits, matches, pillState } from "../../app/lib/paperEntry.ts";

const read = (p) => readFileSync(new URL(`../../${p}`, import.meta.url), "utf8");
const card = { name: "Asha Rao", phone: "98450 12345", yearOfBirth: "1975", gender: "female", note: "" };
const onFile = { id: "p1", name: "Meera Rao", phone: "+919845012345", dob: "1971-05-09" };

test("debounce: only the last number typed is looked up, 120 ms after the last key, so the warning fits in 300 ms", () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const asked = [];
    const d = debouncer((v) => asked.push(v), LOOKUP_DEBOUNCE_MS);
    d.push("98450123"); mock.timers.tick(60);
    d.push("984501234"); mock.timers.tick(60);
    d.push("9845012345"); mock.timers.tick(LOOKUP_DEBOUNCE_MS - 1);
    assert.deepEqual(asked, [], "nothing while keys keep coming");
    mock.timers.tick(1);
    assert.deepEqual(asked, ["9845012345"]);
    d.push("9845012346"); d.cancel(); mock.timers.tick(1000);
    assert.equal(asked.length, 1, "a cancelled lookup never fires");
    assert.ok(LOOKUP_DEBOUNCE_MS <= 150, "leaves at least half of the 300 ms for the lookup itself");
  } finally { mock.timers.reset(); }
});

test("lookup digits: nothing under 8 digits; spaces, +, trunk 0 and 00 do not matter", () => {
  assert.equal(lookupDigits("98450"), "");
  assert.equal(lookupDigits("98450 12345"), "9845012345");
  assert.equal(lookupDigits("09845012345"), "9845012345");
  assert.equal(lookupDigits("+91 98450-12345"), "919845012345");
  assert.equal(lookupDigits("0091 9845012345"), "919845012345");
});

test("pill states: idle, checking, clear, match, new person, failed; stale answers and stale choices are ignored", () => {
  const d = "9845012345";
  assert.equal(pillState("98450", null, null), "idle");
  assert.equal(pillState(card.phone, null, null), "checking");
  assert.equal(pillState(card.phone, { digits: "984501234", hits: [onFile] }, null), "checking", "answer for an earlier number");
  assert.equal(pillState(card.phone, { digits: d, hits: [] }, null), "clear");
  assert.equal(pillState(card.phone, { digits: d, hits: [], failed: true }, null), "failed");
  assert.equal(pillState(card.phone, { digits: d, hits: [onFile] }, null), "match");
  assert.equal(pillState(card.phone, { digits: d, hits: [onFile] }, d), "new");
  assert.equal(pillState(card.phone, { digits: d, hits: [onFile] }, "9845012399"), "match", "'new person' was for another number");
});

test("match: the stored number must be the typed one (the search answers by prefix); the warning carries name and birth year", () => {
  const longer = { id: "p2", name: "Other", phone: "+9198450123456", dob: "1980-01-01" };
  const lookup = { digits: "9845012345", hits: [onFile, longer] };
  assert.deepEqual(matches(lookup, "9845012345"), [onFile]);
  assert.deepEqual(matches(lookup, "984501234"), [], "answer for other digits");
  assert.equal(birthYear(onFile.dob), "1971");
  assert.equal(birthYear(null), "");
  // the server's own 409 candidates (same name and year on another number) are shown as they come
  assert.deepEqual(matches({ digits: "9845012345", fromSave: true, hits: [longer] }, "9845012345"), [longer]);
  assert.equal(pillState(card.phone, { digits: "9845012345", fromSave: true, hits: [longer] }, null), "match");
});

test("card rules: all four fields, a full mobile, a real year, and no card for someone who may be under 18", () => {
  assert.deepEqual(cardIssues(card, 2026), {});
  assert.deepEqual(cardIssues(BLANK, 2026), { name: "required", phone: "required", yearOfBirth: "required", gender: "required" });
  assert.equal(cardIssues({ ...card, phone: "98450" }, 2026).phone, "phoneShort");
  for (const y of ["75", "1899", "2027", "19x5"]) assert.equal(cardIssues({ ...card, yearOfBirth: y }, 2026).yearOfBirth, "yearInvalid", y);
  assert.equal(cardIssues({ ...card, yearOfBirth: "2008" }, 2026).yearOfBirth, "yearMinor", "born in 2008 may still be 17 in 2026");
  assert.equal(cardIssues({ ...card, yearOfBirth: "2007" }, 2026).yearOfBirth, undefined);
  assert.equal(cardIssues({ ...card, gender: "m" }, 2026).gender, "required");
});

test("request body: source paper_import, year as a number, note only when typed, confirmedNotDuplicate only for 'new person'", () => {
  assert.deepEqual(cardBody({ ...card, name: " Asha Rao " }, false), { name: "Asha Rao", phone: "98450 12345", yearOfBirth: 1975, gender: "female", source: "paper_import" });
  assert.deepEqual(cardBody({ ...card, note: " 2024, Dr Rao " }, true), { name: "Asha Rao", phone: "98450 12345", yearOfBirth: 1975, gender: "female", source: "paper_import", note: "2024, Dr Rao", confirmedNotDuplicate: true });
});

test("throughput proxy: 5 control keys a card (3 Tab, 1 sex letter, 1 Enter); the measured 100 cards an hour needs a trained receptionist", () => {
  assert.deepEqual(FIELD_ORDER, ["name", "phone", "yearOfBirth", "gender"]);
  assert.equal(CONTROL_KEYS_PER_CARD, FIELD_ORDER.length - 1 + 1 + 1);
});

test("wiring: Enter saves through POST /patients and the cursor returns to Name; demo_only shows the DPA text and cannot save", () => {
  const src = read("app/components/setup/PaperQuickEntry.tsx");
  assert.match(src, /onSubmit=\{\(e\) => \{ e\.preventDefault\(\); void save\(\); \}\}/);
  assert.match(src, /api\.post\("\/patients", body, \{ idempotencyKey: keys\.current\.get\("card", body\) \}\)/);
  assert.match(src, /function next\([^)]*\) \{\s*setCard\(BLANK\);[^}]*nameRef\.current\?\.focus\(\);/);
  assert.match(src, /api\.get<[^>]+>\("\/patients\/data-gate"\)/);
  assert.match(src, /if \(saving \|\| gate !== "live"\) return;/);
  assert.match(src, /<fieldset className=\{s\.card\} disabled=\{blocked\}>/);
  assert.match(src, /t\("dpaTitle"\)[\s\S]*t\("dpaBody"\)/);
  assert.match(src, /pill === "match"\) \{ sameRef\.current\?\.focus\(\); return; \}/, "a number on file must be answered before the card saves");
  assert.match(src, /t\("samePerson"\)[\s\S]*t\("newPerson"\)/);
  assert.doesNotMatch(src, /onMouse|onDoubleClick/, "keyboard only");
});

test("wiring: the quick entry is absent without patients:create, in Setup > Data and in the Patient Registry", () => {
  assert.match(read("app/components/setup/PatientImport.tsx"), /\{can\("patients:create"\) && <PaperQuickEntry \/>\}/);
  const page = read("app/(clinic)/patients/page.tsx");
  assert.match(page, /\{canRegister && !forbidden && \(\s*<button[^>]*onClick=\{\(\) => setOverlay\("paper"\)\}>\{tPaper\("open"\)\}<\/button>/);
  assert.match(read("app/(clinic)/patients/registry/RegistryOverlays.tsx"), /\{typingPaper && <Dialog[\s\S]*?><PaperQuickEntry \/><\/Dialog>\}/);
});

test("strings: every paperEntry key exists in English and Hindi with the same placeholders", () => {
  const en = JSON.parse(read("../../packages/i18n/messages/en/shell.json")).paperEntry;
  const hi = JSON.parse(read("../../packages/i18n/messages/hi/shell.json")).paperEntry;
  assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort());
  const holes = (v) => (v.match(/\{\w+\}/g) ?? []).sort();
  for (const k of Object.keys(en)) assert.deepEqual(holes(hi[k]), holes(en[k]), k);
  const used = [...read("app/components/setup/PaperQuickEntry.tsx").matchAll(/\bt\("(\w+)"/g)].map((m) => m[1]);
  for (const k of [...used, "issue_required", "issue_phoneShort", "issue_yearInvalid", "issue_yearMinor", "gender_male", "gender_female", "gender_other", "yearOnly", "paperNote", "open"]) assert.ok(en[k], k);
});
