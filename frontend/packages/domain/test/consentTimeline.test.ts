import { test } from "vitest";
import assert from "node:assert/strict";
import { buildTimeline, type TimelineInput } from "../src/consultFlow.ts";

test("CLN-014: a signed and then withdrawn consent shows both on the visit timeline", () => {
  const base: TimelineInput = { checkedInAt: null, vitals: { status: "none" }, status: "waiting", draft: { savedAt: null, pendingOnDevice: false }, signedAt: null, noteSigned: false, amendments: [] };
  const t = buildTimeline({ ...base, consents: [{ id: "p1", procedure: "Chemical peel", signedAt: "2026-11-11T05:00:00Z", withdrawnAt: "2026-11-11T05:10:00Z", reason: "changed mind" }] });
  assert.deepEqual(t.filter((e) => /^c[sw]-/.test(e.id)).map((e) => [e.id, e.label, e.kind]),
    [["cs-p1", "Consent signed", "ok"], ["cw-p1", "Consent withdrawn", "warn"]]);
  assert.equal(t.find((e) => e.id === "cw-p1")?.sub, "Chemical peel · changed mind");
  assert.deepEqual(buildTimeline(base).map((e) => e.id), ["in", "vitals"]); // no consents, no consent events
});
