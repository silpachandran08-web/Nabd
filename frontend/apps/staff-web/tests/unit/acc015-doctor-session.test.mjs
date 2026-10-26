// ACC-015: which session controls a doctor sees in each state, labels, error text.
import { test } from "node:test";
import assert from "node:assert/strict";
const { sessionActions, sessionLabel, problemMessage, EXTEND_MINUTES } = await import("../../app/lib/doctorSession.ts");

test("out offers only Start; in offers Pause and End; paused offers Resume and End", () => {
  assert.deepEqual(sessionActions("out").map((a) => a.action), ["start"]);
  assert.deepEqual(sessionActions("in").map((a) => a.action), ["pause", "end"]);
  assert.deepEqual(sessionActions("paused").map((a) => a.action), ["resume", "end"]);
});
test("labels match the desk wording", () => {
  assert.equal(sessionLabel("in"), "Doctor in"); assert.equal(sessionLabel("paused"), "Paused"); assert.equal(sessionLabel("out"), "Out");
});
test("extend offers exactly 5, 10 and 15", () => assert.deepEqual([...EXTEND_MINUTES], [5, 10, 15]));
test("problemMessage prefers detail, then title, then the fallback", async () => {
  assert.equal(await problemMessage(new Response(JSON.stringify({ detail: "d", title: "t" })), "f"), "d");
  assert.equal(await problemMessage(new Response(JSON.stringify({ title: "t" })), "f"), "t");
  assert.equal(await problemMessage(new Response("nope"), "f"), "f");
});
