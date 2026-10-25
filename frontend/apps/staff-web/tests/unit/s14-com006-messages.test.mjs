// COM-006: the delivery tick mapping and the SSE path (event type -> cache resource -> screen refresh). Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { tickOf } from "../../app/patients/messageTicks.ts";
import { createApplier, resourcesFor, LIVE_RESOURCES } from "../../../../packages/api-client/sse/patch.ts";

test("one grey tick = sent, two grey = delivered, two blue = read, red ! = failed", () => {
  assert.deepEqual(tickOf("sent"), { glyph: "✓", label: "Sent", tone: "grey" });
  assert.deepEqual(tickOf("delivered"), { glyph: "✓✓", label: "Delivered", tone: "grey" });
  assert.deepEqual(tickOf("read"), { glyph: "✓✓", label: "Read", tone: "blue" });
  assert.equal(tickOf("failed").tone, "red");
  assert.equal(tickOf("failed").glyph, "!");
  assert.equal(tickOf("blocked").tone, "red");
});

test("not-yet-sent and terminal states have their own wording, unknown falls back to Sending", () => {
  for (const s of ["scheduled", "queued", "sending", "something-new"]) assert.equal(tickOf(s).label, "Sending");
  assert.equal(tickOf("cancelled").label, "Cancelled");
  assert.equal(tickOf("expired").label, "Expired");
});

test("a comms.message.status_changed delta makes patient-messages stale, once per version", () => {
  assert.deepEqual(resourcesFor("comms.message.status_changed.v1"), ["patient-messages"]);
  assert.deepEqual(resourcesFor("visit.queue_entry.changed.v1"), []);
  const inv = [];
  const cache = { getQueriesData: () => [], setQueryData: () => {}, invalidateQueries: ({ queryKey }) => inv.push(queryKey.at(-1)) };
  const apply = createApplier(cache, (r) => ["t", "x", r]);
  const msg = (v) => ({ event: "delta", data: { type: "comms.message.status_changed.v1", id: "m1", v, fields: { status: "read" } } });
  assert.deepEqual(apply(msg(1)), ["patient-messages"]);
  assert.deepEqual(apply(msg(1)), []); // duplicate ignored
  assert.deepEqual(apply(msg(2)), ["patient-messages"]);
  assert.deepEqual(inv, ["patient-messages", "patient-messages"]);
});

test("a snapshot (connect, resume gap) also refreshes the messages", () => {
  assert.ok(LIVE_RESOURCES.includes("patient-messages"));
});
