// ACC-031: the desk keyboard map (F2/F3/F4/F6, Ctrl+Enter, ?), the age-to-DOB rule, and the shortcut rows per grant. Pure modules, no React.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const base = new URL("../../app/components/arrivals/", import.meta.url).href;
const { deskAction, SHORTCUT_ROWS } = await import(base + "shortcuts/deskKeys.ts");
const { dobFromAge } = await import(base + "walk-in/ageDob.ts");
const k = (key, m = {}) => ({ key, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...m });

test("F2 F3 F4 F6 map to the five desk actions, with or without a text field focused", () => {
  for (const typing of [false, true]) {
    assert.equal(deskAction(k("F2"), typing), "newWalkIn");
    assert.equal(deskAction(k("F3"), typing), "search");
    assert.equal(deskAction(k("F4"), typing), "checkIn");
    assert.equal(deskAction(k("F6"), typing), "changeDoctor");
  }
});
test("Ctrl+Enter and Cmd+Enter confirm; a plain Enter is left to the form", () => {
  assert.equal(deskAction(k("Enter", { ctrlKey: true }), true), "confirm");
  assert.equal(deskAction(k("Enter", { metaKey: true }), false), "confirm");
  assert.equal(deskAction(k("Enter"), false), null);
});
test("? opens the sheet only when not typing; modified F-keys and other keys do nothing", () => {
  assert.equal(deskAction(k("?", { shiftKey: true }), false), "help");
  assert.equal(deskAction(k("?"), false), "help");
  assert.equal(deskAction(k("?", { shiftKey: true }), true), null, "typing a question mark in a field is just text");
  assert.equal(deskAction(k("?", { ctrlKey: true }), false), null);
  assert.equal(deskAction(k("F3", { ctrlKey: true }), false), null);
  assert.equal(deskAction(k("F2", { shiftKey: true }), false), null);
  assert.equal(deskAction(k("a"), false), null);
  assert.equal(deskAction(k("Escape"), false), null, "Esc belongs to the Dialog");
});
test("age -> date of birth: 1 January of the year that makes the patient exactly that age; junk and out-of-range give no date", () => {
  assert.equal(dobFromAge("30", "2026-10-26"), "1996-01-01");
  assert.equal(dobFromAge("0", "2026-10-26"), "2026-01-01");
  assert.equal(dobFromAge(" 17 ", "2026-01-01"), "2009-01-01");
  assert.equal(dobFromAge("120", "2026-10-26"), "1906-01-01");
  for (const bad of ["", "abc", "-5", "121", "1.5", "1000"]) assert.equal(dobFromAge(bad, "2026-10-26"), "", bad);
});
test("every shortcut row is documented: sheet rows cover the six keys of the story and the openers need grants", () => {
  assert.deepEqual(SHORTCUT_ROWS.map((r) => r.keys.join("+")), ["F2", "F3", "F4", "F6", "Ctrl+Enter", "Esc", "?"]);
  const need = Object.fromEntries(SHORTCUT_ROWS.map((r) => [r.action, r.needs]));
  assert.deepEqual(need.newWalkIn, ["queue:create", "patients:view", "patients:create"]);
  assert.deepEqual(need.checkIn, ["queue:create"]);
  assert.deepEqual(need.help, []);
});
test("the sheet text exists in en and hi for every row", () => {
  for (const loc of ["en", "hi"]) {
    const j = JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${loc}/arrivals.json`, import.meta.url), "utf8"));
    for (const r of SHORTCUT_ROWS) assert.ok(j.shortcuts.items[r.action], `${loc} ${r.action}`);
    assert.ok(j.shortcuts.title);
  }
});
