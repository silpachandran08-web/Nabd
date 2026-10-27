// COM-031 / COM-032: pure rules of the WhatsApp inbox.
import { test } from "node:test";
import assert from "node:assert/strict";
const L = await import("../../app/components/inbox/inboxLogic.ts");

const NOW = Date.parse("2026-11-02T10:00:00Z");
const win = (h) => ({ state: h > 0 ? "open" : "closed", closesAt: new Date(NOW + h * 3600_000).toISOString() });
const th = (extra = {}) => ({ id: "t", displayName: "x", lastMessagePreview: "hi", unread: 0, waitingMinutes: 0, window: win(10), mode: "human", optedOut: false, clinical: false, clinicalSuggested: false, ...extra });

test("session pill: open, closing under 4 h with a countdown, template only, opted out", () => {
  assert.deepEqual(L.sessionPill(th({ window: win(10) }), NOW), { kind: "open", label: "Open" });
  assert.deepEqual(L.sessionPill(th({ window: win(3.5) }), NOW), { kind: "closing", label: "Closes in 3 h 30 min" });
  assert.deepEqual(L.sessionPill(th({ window: win(0.25) }), NOW), { kind: "closing", label: "Closes in 15 min" });
  assert.equal(L.sessionPill(th({ window: win(4) }), NOW).kind, "open", "exactly 4 h is not closing yet");
  assert.equal(L.sessionPill(th({ window: win(-1) }), NOW).kind, "template");
  assert.equal(L.sessionPill(th({ window: win(0) }), NOW).kind, "template", "closes right now = closed");
  assert.equal(L.sessionPill(th({ window: { state: "closed", closesAt: null } }), NOW).label, "Template only");
  assert.deepEqual(L.sessionPill(th({ optedOut: true }), NOW), { kind: "optedout", label: "Opted out" });
});

test("the countdown moves with the clock (updated each minute by the screen)", () => {
  const t = th({ window: win(1) });
  assert.equal(L.sessionPill(t, NOW).label, "Closes in 1 h 00 min");
  assert.equal(L.sessionPill(t, NOW + 60_000).label, "Closes in 59 min");
});

test("free text only in an open window of a patient who has not opted out", () => {
  assert.equal(L.canFreeText(th(), NOW), true);
  assert.equal(L.canFreeText(th({ window: win(1) }), NOW), true);
  assert.equal(L.canFreeText(th({ window: win(-2) }), NOW), false);
  assert.equal(L.canFreeText(th({ optedOut: true }), NOW), false);
});

test("lock-screen safe preview: clinical threads read 'Clinical message', others are cut at 60", () => {
  assert.equal(L.previewText(th({ clinical: true, lastMessagePreview: "I have a rash" })), "Clinical message");
  assert.equal(L.previewText(th({ lastMessagePreview: "a".repeat(60) })).length, 60);
  const cut = L.previewText(th({ lastMessagePreview: "a".repeat(61) }));
  assert.equal(cut.length, 60);
  assert.ok(cut.endsWith("…"));
});

test("waiting chip", () => {
  assert.equal(L.waitingChip(0), "");
  assert.equal(L.waitingChip(12), "Waiting 12 min");
  assert.equal(L.waitingChip(65), "Waiting 1 h 5 min");
});

test("the oldest waiting thread is opened first", () => {
  const a = th({ id: "a", waitingMinutes: 5 }), b = th({ id: "b", waitingMinutes: 40 }), c = th({ id: "c", waitingMinutes: 0 });
  assert.equal(L.oldestWaiting([a, b, c]).id, "b");
  assert.equal(L.oldestWaiting([c, th({ id: "d" })]).id, "c", "nobody waiting: the first one");
  assert.equal(L.oldestWaiting([]), null);
});

test("author labels: patient, bot, template, you, a named colleague, a colleague", () => {
  assert.equal(L.authorLabel({ author: "patient" }, "me"), "Patient");
  assert.equal(L.authorLabel({ author: "bot" }, "me"), "Nabd assistant - automated");
  assert.equal(L.authorLabel({ author: "template", templateName: "slot_back" }, "me"), "Template: slot_back");
  assert.equal(L.authorLabel({ author: "staff", authorStaffRef: "me" }, "me"), "You");
  assert.equal(L.authorLabel({ author: "staff", authorStaffRef: "p" }, "me", (id) => (id === "p" ? "Priya" : undefined)), "Priya");
  assert.equal(L.authorLabel({ author: "staff", authorStaffRef: "q" }, "me", () => undefined), "Staff");
});

test("ticks and failures", () => {
  assert.equal(L.tick("sent").glyph, "✓");
  assert.equal(L.tick("delivered").label, "Delivered");
  assert.equal(L.tick("read").label, "Read");
  assert.equal(L.tick("queued").label, "Sending");
  assert.equal(L.isFailed("failed"), true);
  assert.equal(L.isFailed("delivered"), false);
});

test("result or diagnosis wording warns; ordinary replies do not", () => {
  assert.equal(L.looksLikeResultOrDiagnosis("Your HbA1c is 7.2 mg/dL"), true);
  assert.equal(L.looksLikeResultOrDiagnosis("The biopsy report shows"), true);
  assert.equal(L.looksLikeResultOrDiagnosis("See you tomorrow at 5 pm"), false);
});

test("template variables: count, fill, leave unfilled ones visible", () => {
  assert.equal(L.variableCount("Hi {{1}}, your slot is {{2}}"), 2);
  assert.equal(L.variableCount("No variables"), 0);
  assert.equal(L.fillTemplate("Hi {{1}}, slot {{2}}", ["Asha", ""]), "Hi Asha, slot {{2}}");
});

test("keys: J/K/R/T act, but not in a field, with a modifier or with a dialog open", () => {
  const k = (key, extra = {}) => L.keyAction({ key, target: { tagName: "BODY" }, ...extra }, false);
  assert.equal(k("j"), "next"); assert.equal(k("K"), "prev"); assert.equal(k("r"), "reply"); assert.equal(k("t"), "templates");
  assert.equal(k("a"), null);
  assert.equal(L.keyAction({ key: "r", target: { tagName: "TEXTAREA" } }, false), null);
  assert.equal(L.keyAction({ key: "r", ctrlKey: true, target: { tagName: "BODY" } }, false), null);
  assert.equal(L.keyAction({ key: "r", target: { tagName: "BODY" } }, true), null);
});
