// ACC-007: when the reason picker allows submit.
import { test } from "node:test";
import assert from "node:assert/strict";
const { reasonReady } = await import("../../app/lib/reasonPicker.ts");

const illness = { code: "illness", label: "Illness", requiresNote: false };
const other = { code: "other", label: "Other", requiresNote: true };

test("nothing picked: not ready", () => assert.equal(reasonReady(null, ""), false));
test("a plain code is ready without a note", () => assert.equal(reasonReady(illness, ""), true));
test("other needs a note", () => {
  assert.equal(reasonReady(other, ""), false);
  assert.equal(reasonReady(other, "   "), false);
  assert.equal(reasonReady(other, "moved abroad"), true);
});
test("a kind without notes (no-show) does not ask for one", () => assert.equal(reasonReady(other, "", false), true));
