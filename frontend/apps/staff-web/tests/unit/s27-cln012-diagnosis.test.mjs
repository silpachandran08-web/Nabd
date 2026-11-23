// CLN-012: pure rules of the coded-diagnosis field (diagnosisModel.ts) and how it is wired into the consult screen.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  addDx, canSaveAmendment, fieldMode, matchFavourites, removeDx, sameDx, setPrimary, setQualifier, sourceKey, toRequest, withoutPicked, MAX_DIAGNOSES,
} from "../../app/components/consult/diagnosis/diagnosisModel.ts";

const APP = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "app");
const E119 = { code: "E11.9", title: "Type 2 diabetes mellitus without complications", favourite: true };
const I10 = { code: "I10", title: "Essential hypertension", favourite: true };
const J069 = { code: "J06.9", title: "Acute upper respiratory infection, unspecified", favourite: false };

test("the first code picked is the primary diagnosis, later ones are secondary, a code is added once", () => {
  let items = addDx([], E119);
  items = addDx(items, I10);
  assert.deepEqual(items.map((d) => [d.code, d.primary]), [["E11.9", true], ["I10", false]]);
  assert.equal(addDx(items, I10), items, "same code again: nothing changes");
  assert.deepEqual(toRequest(items), { items: [{ code: "E11.9", primary: true }, { code: "I10", primary: false }] });
});

test("free text typed before the first code is kept as that code's note, never dropped", () => {
  const items = addDx([], J069, "  viral, day 3  ");
  assert.equal(items[0].qualifier, "viral, day 3");
  assert.equal(addDx(items, I10, "ignored for a second code")[1].qualifier, null);
  assert.equal(addDx([], J069, "x".repeat(500))[0].qualifier.length, 200);
  assert.deepEqual(toRequest(items).items[0], { code: "J06.9", primary: true, qualifier: "viral, day 3" });
});

test("there is always exactly one primary while codes exist", () => {
  let items = addDx(addDx(addDx([], E119), I10), J069);
  items = setPrimary(items, "I10");
  assert.deepEqual(items.filter((d) => d.primary).map((d) => d.code), ["I10"]);
  assert.equal(setPrimary(items, "Z99"), items, "unknown code: unchanged");
  items = removeDx(items, "I10");
  assert.deepEqual(items.map((d) => [d.code, d.primary]), [["E11.9", true], ["J06.9", false]], "removing the primary promotes the next");
  assert.deepEqual(removeDx(removeDx(items, "E11.9"), "J06.9"), []);
  let many = [];
  for (let i = 0; i < MAX_DIAGNOSES + 5; i++) many = addDx(many, { code: `A${String(i).padStart(2, "0")}`, title: "t" });
  assert.equal(many.length, MAX_DIAGNOSES, "the server's limit of 20 is not exceeded");
});

test("a qualifier is trimmed, capped at 200 characters and blank means none", () => {
  const items = addDx([], I10);
  assert.equal(setQualifier(items, "I10", "  poorly controlled ")[0].qualifier, "poorly controlled");
  assert.equal(setQualifier(items, "I10", "   ")[0].qualifier, null);
  assert.equal(setQualifier(items, "I10", "y".repeat(300))[0].qualifier.length, 200);
  assert.ok(sameDx(setQualifier(items, "I10", ""), items), "an untouched note field is not a change (no request is sent)");
  assert.ok(!sameDx(setQualifier(items, "I10", "new"), items));
});

test("favourites answer from the device: every word must match the code or the name, picked codes are not offered", () => {
  const favs = [E119, I10];
  assert.deepEqual(matchFavourites(favs, "type 2 diab").map((f) => f.code), ["E11.9"]);
  assert.deepEqual(matchFavourites(favs, "i1").map((f) => f.code), ["I10"]);
  assert.deepEqual(matchFavourites(favs, "").map((f) => f.code), ["E11.9", "I10"], "empty query: all favourites");
  assert.deepEqual(matchFavourites(favs, "asthma"), []);
  assert.deepEqual(withoutPicked([E119, I10, J069], addDx([], I10)).map((f) => f.code), ["E11.9", "J06.9"]);
  const many = Array.from({ length: 50 }, (_, i) => ({ code: `B${String(i).padStart(2, "0")}`, title: `Generated favourite ${i}`, favourite: true }));
  const t0 = performance.now();
  for (let k = 0; k < 200; k++) matchFavourites(many, "favourite 4");
  assert.ok((performance.now() - t0) / 200 < 5, "a keystroke over 50 favourites is far under 100 ms");
});

test("the source of the list is labelled: WHO (also the fallback), a regional list, or unknown", () => {
  assert.equal(sourceKey("WHO"), "sourceWho");
  assert.equal(sourceKey("AM"), "sourceAm");
  assert.equal(sourceKey(null), "sourceOther");
  const en = JSON.parse(readFileSync(join(APP, "..", "..", "..", "packages", "i18n", "messages", "en", "consult.json"), "utf8")).diagnosisCombobox;
  for (const k of ["sourceWho", "sourceAm", "sourceOther"]) assert.match(en[k], /\{version\}/, k);
  assert.match(en.sourceWho, /WHO/);
});

test("without an active list and without codes the old free-text field shows; loading and errors never show the coded field", () => {
  assert.equal(fieldMode({ load: "ok", version: null, coded: 0 }), "freeText");
  assert.equal(fieldMode({ load: "ok", version: "who-2019", coded: 0 }), "coded");
  assert.equal(fieldMode({ load: "ok", version: null, coded: 2 }), "coded", "codes recorded earlier stay visible when the list is gone");
  assert.equal(fieldMode({ load: "loading", version: null, coded: 0 }), "loading");
  assert.equal(fieldMode({ load: "error", version: "who-2019", coded: 1 }), "error");
});

test("a signed note: the amendment needs a changed set and a reason of 5 characters, and the reason is sent", () => {
  const saved = addDx([], E119);
  const draft = addDx(saved, I10);
  assert.equal(canSaveAmendment(saved, draft, "ok"), false, "reason too short");
  assert.equal(canSaveAmendment(saved, saved, "Entered in error"), false, "nothing changed");
  assert.equal(canSaveAmendment(saved, draft, "  Hypertension confirmed "), true);
  assert.equal(toRequest(draft, "  Hypertension confirmed ").reason, "Hypertension confirmed");
  assert.ok(!("reason" in toRequest(draft)), "a draft note sends no reason");
});

test("wiring: the Assess step mounts the combobox once, restricted controls are absent, calls go through the api client", () => {
  const steps = readFileSync(join(APP, "components/consult/note/NoteSteps.tsx"), "utf8");
  assert.equal((steps.match(/<DiagnosisCombobox /g) ?? []).length, 1);
  const ws = readFileSync(join(APP, "components/consult/visit/VisitWorkspace.tsx"), "utf8");
  assert.match(ws, /<AssessStep \{\.\.\.field\} visit=\{\{ entryId: entry\.id, locked, canAmend: mayAmendNote \}\} \/>/);
  const c = readFileSync(join(APP, "components/consult/diagnosis/DiagnosisCombobox.tsx"), "utf8");
  assert.match(c, /visit\.locked && visit\.canAmend && !amending/, "the amend button exists only for who may amend");
  assert.match(c, /\{canSearch && \(/, "no search box on a read-only or signed note");
  assert.equal((c.match(/\{editable && /g) ?? []).length, 3, "make-primary, star and remove are rendered only when editable");
  assert.ok(!/disabled=\{!editable|disabled=\{!visit\.canAmend/.test(c), "restricted controls are absent, not disabled");
  for (const path of ["/clinical/icd10/search?q=", "/clinical/icd10/favourites", "/diagnoses"]) assert.ok(c.includes(path), path);
  assert.match(c, /onChange\(r\.summary \?\? ""\)/, "the note editor gets the server's summary, so the autosave sends the same text");
});
