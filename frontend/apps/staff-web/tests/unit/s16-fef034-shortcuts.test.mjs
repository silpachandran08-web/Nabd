// FEF-034: shortcut registry scoping/conflicts, palette actions (permission filter, fuzzy), smart defaults, typing guard.
import { test } from "node:test";
import assert from "node:assert/strict";
import { SHORTCUTS, shortcutsFor, keyHint, isTyping, fuzzyMatch, DESK_SCREENS } from "../../app/shell/shortcuts.ts";
import { actionTargets } from "../../app/shell/paletteActions.ts";
import { defaultDoctor, defaultPayment } from "../../app/components/arrivals/walk-in/smartDefaults.ts";

const REC = ["queue:view", "queue:create", "patients:view", "patients:create", "billing:view"];
const DOC = ["clinical:edit", "queue:view", "patients:view"];
const ids = (rows) => rows.map((r) => r.id);

test("registry scoping: global keys everywhere, N only on My Day for a doctor, Ctrl+S only on /consult", () => {
  assert.deepEqual(ids(shortcutsFor("/billing", REC, "reception")), ["palette", "search", "walkin", "submit", "help"]);
  assert.deepEqual(ids(shortcutsFor("/my-day", DOC, "doctor")), ["palette", "search", "next", "submit", "help"]);
  assert.ok(ids(shortcutsFor("/consult", DOC, "doctor")).includes("save"));
  assert.ok(!ids(shortcutsFor("/my-day", DOC, "doctor")).includes("save"));
  assert.ok(!ids(shortcutsFor("/consult/x", ["queue:view"], "doctor")).includes("save"), "no clinical:edit, no save-note shortcut");
});

test("registry: a restricted shortcut is absent, not listed (grants and role)", () => {
  assert.ok(!ids(shortcutsFor("/billing", ["queue:view", "patients:view"], "reception")).includes("walkin"));
  assert.ok(!ids(shortcutsFor("/my-day", DOC, "reception")).includes("next"));
  assert.ok(!ids(shortcutsFor("/billing", ["queue:view"], "reception")).includes("search"));
});

test("registry conflicts: no two shortcuts share a key combination in an overlapping scope and role", () => {
  for (const a of SHORTCUTS) for (const b of SHORTCUTS) {
    if (a.id >= b.id || a.keys.join("+") !== b.keys.join("+")) continue;
    const scopeOverlap = a.scope === "all" || b.scope === "all" || a.scope.some((p) => b.scope.includes(p));
    const roleOverlap = !a.roles || !b.roles || a.roles.some((r) => b.roles.includes(r));
    assert.ok(!(scopeOverlap && roleOverlap), `${a.id} and ${b.id} conflict`);
  }
  const desk = ["F2", "F3", "F4", "F6"];
  for (const s of SHORTCUTS) assert.ok(!desk.includes(s.keys[0]), `${s.id} must not shadow a desk F-key`);
  assert.deepEqual(DESK_SCREENS, ["/arrivals", "/schedule", "/patients"]);
});

test("single-key shortcuts are marked single (disabled while typing); chords are not", () => {
  for (const s of SHORTCUTS) assert.equal(!!s.single, s.keys.length === 1, s.id);
  assert.equal(isTyping({ tagName: "INPUT" }), true);
  assert.equal(isTyping({ tagName: "TEXTAREA" }), true);
  assert.equal(isTyping({ tagName: "SELECT" }), true);
  assert.equal(isTyping({ tagName: "DIV", isContentEditable: true }), true);
  assert.equal(isTyping({ tagName: "BUTTON" }), false);
  assert.equal(isTyping(null), false);
});

test("every shortcut has a tooltip text and aria-keyshortcuts through keyHint", () => {
  for (const s of SHORTCUTS) {
    const h = keyHint(s.id);
    assert.ok(h.title.includes(s.label) && h.title.includes(s.keys.join("+")), s.id);
    assert.ok(h["aria-keyshortcuts"].length > 0);
  }
  assert.throws(() => keyHint("nope"));
});

test("palette actions: only what the role may run; walk-in found by 'walk-in', 'walkin' and a typo", () => {
  assert.ok(ids(actionTargets(REC, "reception", "walk-in")).includes("walkin"));
  assert.ok(ids(actionTargets(REC, "reception", "walkin")).includes("walkin"));
  assert.ok(ids(actionTargets(REC, "reception", "wlk")).includes("walkin"));
  assert.deepEqual(ids(actionTargets(["queue:view", "patients:view"], "reception", "")), [], "no queue:create = no walk-in, no check-in");
  assert.deepEqual(ids(actionTargets(REC, "reception", "zzz")), []);
  assert.ok(!ids(actionTargets(["queue:create", "patients:view"], "reception", "")).includes("new-patient"), "new patient needs patients:create");
});

test("palette 'next patient' is offered to a doctor with someone waiting, to nobody else", () => {
  assert.equal(actionTargets(DOC, "doctor", "next", "/consult?entry=e1")[0].href, "/consult?entry=e1");
  assert.ok(!ids(actionTargets(DOC, "doctor", "next", null)).includes("next"));
  assert.ok(!ids(actionTargets(REC, "reception", "next", "/consult?entry=e1")).includes("next"));
});

test("fuzzy: substring, in-order letters inside one word, but not letters scattered across words", () => {
  assert.ok(fuzzyMatch("walk", "Register walk-in"));
  assert.ok(fuzzyMatch("wlk", "Register walk-in"));
  assert.ok(fuzzyMatch("check in", "Check in today's appointment"));
  assert.ok(!fuzzyMatch("stock", "Check in today's appointment"));
  assert.ok(!fuzzyMatch("xyz", "Register walk-in"));
  assert.ok(fuzzyMatch("", "anything"));
});

test("smart default doctor: last used, else shortest wait, ties by fewer waiting, else the only doctor", () => {
  const staff = [{ id: "a" }, { id: "b" }, { id: "c" }];
  const q = [{ id: "a", waiting: 3, estimatedMinutes: 30 }, { id: "b", waiting: 1, estimatedMinutes: 10 }, { id: "c", waiting: 0, estimatedMinutes: 10 }];
  assert.equal(defaultDoctor(staff, q, "a"), "a", "last used wins");
  assert.equal(defaultDoctor(staff, q, "gone"), "c", "stale last-used ignored; 10 min tie broken by fewer waiting");
  assert.equal(defaultDoctor(staff, q, ""), "c");
  assert.equal(defaultDoctor(staff, [{ id: "a", waiting: 0 }], ""), "", "no estimates and several doctors: nothing guessed");
  assert.equal(defaultDoctor([{ id: "a" }], [], ""), "a");
  assert.equal(defaultDoctor([], q, ""), "");
});

test("smart default payment: last used, else the most common mode on today's queue, else cash", () => {
  const A = ["cash", "card", "upi", "other"];
  const rows = [{ paymentMode: "upi" }, { paymentMode: "upi" }, { paymentMode: "card" }, { paymentMode: "weird" }, {}];
  assert.equal(defaultPayment(rows, A, "card"), "card");
  assert.equal(defaultPayment(rows, A, ""), "upi");
  assert.equal(defaultPayment([], A, ""), "cash");
  assert.equal(defaultPayment(rows, A, "bitcoin"), "upi", "an unknown remembered mode is ignored");
});

test("wiring: Ctrl+S saves the draft in the visit workspace, its button carries the hint; the palette and hotkeys read the registry", async () => {
  const { readFileSync } = await import("node:fs");
  const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
  const ws = src("components/consult/visit/VisitWorkspace.tsx");
  assert.match(ws, /e\.key\.toLowerCase\(\) !== "s"/); assert.match(ws, /e\.preventDefault\(\);\s*if \(canSaveNow\.current\) void n\.saveNote\(\)/);
  assert.match(src("components/consult/closeout/ActionBar.tsx"), /keyHint\("save"\)/);
  const hot = src("shell/ShellHotkeys.tsx");
  assert.match(hot, /shortcutsFor\(/); assert.match(hot, /isTyping\(/); assert.match(hot, /DESK_SCREENS/);
  assert.doesNotMatch(hot, /MY_DAY_HREF|s\.role === "reception"/, "no second copy of the scoping rules");
  const pal = src("shell/CommandPalette.tsx");
  assert.match(pal, /actionTargets\(/); assert.match(pal, /usePatientSearch/); assert.match(pal, /typed\.length >= 3/);
  assert.match(src("TopBar.tsx"), /keyHint\("walkin"\)/);
});
