// CLN-042: the specialty workspace is registry-driven, the owner's picker shows only entitled profiles. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { localized, pickable, workspaceView } from "../../app/(clinic)/specialty/specialtyModel.ts";

const me = (extra = {}) => ({
  primary: "DENT", secondary: null, name: { en: "Dentistry", hi: "दंत चिकित्सा" }, configVersion: 1, writable: true,
  config: { module: "specialty_dental", fieldSets: [], templatePack: null,
    widgets: [{ id: "toothChart", label: { en: "Dental chart" } }],
    quickActions: [{ id: "toothChart", href: "/patients", writes: true, label: { en: "Open dental chart", hi: "डेंटल चार्ट खोलें" } },
      { id: "consult", href: "/consult", label: { en: "Start consult" } }] },
  ...extra,
});

test("a profile code the shell has never seen renders from its config alone (no per-specialty code)", () => {
  const v = workspaceView(me({ primary: "NEWSPEC", name: { en: "New specialty" }, config: { module: "specialty_newspec", fieldSets: [], templatePack: null,
    widgets: [{ id: "brandNewWidget", label: { en: "Brand new widget" } }], quickActions: [{ id: "x", href: "/consult", label: { en: "Do the thing" } }] } }), "en");
  assert.equal(v.title, "New specialty");
  assert.deepEqual(v.actions, [{ id: "x", label: "Do the thing", href: "/consult", disabled: false }]);
  assert.deepEqual(v.widgets, [{ id: "brandNewWidget", label: "Brand new widget", href: null }]);
  // the workspace component holds no profile code at all
  const src = readFileSync(new URL("../../app/(clinic)/specialty/SpecialtyWorkspace.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(src, /\b(DERM|DENT|GP|PED|ENT|ORTH|GYN|HOM|AYU)\b/);
});

test("labels follow the UI language and fall back to English, then the id", () => {
  assert.equal(workspaceView(me(), "hi").actions[0].label, "डेंटल चार्ट खोलें");
  assert.equal(workspaceView(me(), "hi").actions[1].label, "Start consult");
  assert.equal(localized(undefined, "en", "raw"), "raw");
});

test("after the trial ends only the actions that write are disabled; reads and links stay", () => {
  const v = workspaceView(me({ writable: false }), "en");
  assert.equal(v.readOnly, true);
  assert.deepEqual(v.actions.map((a) => a.disabled), [true, false]);
  assert.equal(v.widgets[0].href, "/patients");
  assert.equal(workspaceView(me(), "en").actions[0].disabled, false);
});

test("no profile assigned: the empty state", () => {
  assert.equal(workspaceView({ primary: null, secondary: null, name: null, configVersion: null, writable: false, config: null }, "en"), null);
});

test("the owner's picker offers only the profiles the plan unlocks", () => {
  const items = ["DERM", "GP", "DENT", "PED"].map((code) => ({ code, name: { en: code }, entitled: code === "DERM" || code === "GP", configVersion: 1 }));
  assert.deepEqual(pickable(items).map((p) => p.code), ["DERM", "GP"]);
});

test("T1-BUG-2: the Specialty profile button and dialog are absent unless the viewer is the owner and the row is a clinician", () => {
  const tables = readFileSync(new URL("../../app/(clinic)/staff/StaffTables.tsx", import.meta.url), "utf8");
  assert.match(tables, /\{d\.owner && g\.canEditRoles && s\.clinician && s\.status !== "suspended" && \(\s*<button className=\{styles\.actionBtn\} onClick=\{\(\) => onSpecEdit\(s\)\}/);
  const page = readFileSync(new URL("../../app/(clinic)/staff/page.tsx", import.meta.url), "utf8");
  assert.match(page, /\{specEdit && d\.owner && g\.canEditRoles && <SpecialtyProfileDialog/);
});
