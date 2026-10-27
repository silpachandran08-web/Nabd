// ACC-032: the exceptions inbox helpers and the SSE mapping. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { headline, sortItems, actionHref, actionPermission, removedIds, distanceText } from "../../app/components/exceptions/exceptionsLogic.ts";
import { resourcesFor, LIVE_RESOURCES } from "../../../../packages/api-client/sse/patch.ts";

const it = (id, severity, createdAt, extra = {}) => ({ id, kind: "handoff", severity, refType: "conversation", refId: "r" + id, summary: "", params: {}, actions: [], createdAt, ...extra });

test("headline says what happened, including the distance, from ids and codes only", () => {
  assert.match(headline({ kind: "checkin_failed", params: { reason: "outside_geofence", distanceM: 1400 } }), /1\.4 km away/);
  assert.match(headline({ kind: "checkin_failed", params: { reason: "no_appointment" } }), /no appointment today/);
  assert.match(headline({ kind: "unconfirmed_booking", params: {} }), /not confirmed/);
  assert.equal(headline({ kind: "zzz", params: {} }), "Needs a person.");
});

test("distanceText switches to km at 1000 m", () => {
  assert.equal(distanceText(640), "640 m");
  assert.equal(distanceText(1400), "1.4 km");
});

test("urgent first, then oldest first; the input is not mutated", () => {
  const a = [it("a", "info", "2026-01-01T10:00:00Z"), it("b", "urgent", "2026-01-01T12:00:00Z"), it("c", "urgent", "2026-01-01T11:00:00Z")];
  assert.deepEqual(sortItems(a).map((x) => x.id), ["c", "b", "a"]);
  assert.equal(a[0].id, "a");
});

test("link actions go to the right screen; buttons return null and need the right grant", () => {
  assert.equal(actionHref({ kind: "x", refId: "9" }, "ask_patient"), "/messages");
  assert.equal(actionHref({ kind: "x", refId: "9" }, "open_checkout"), "/checkout/9");
  assert.equal(actionHref({ kind: "x", refId: "9" }, "resolve"), null);
  assert.equal(actionPermission("confirm_arrival"), "queue:create");
  assert.equal(actionPermission("snooze"), "queue:edit");
  assert.equal(actionPermission("call"), null);
});

test("removedIds lists what left the list", () => {
  assert.deepEqual(removedIds([it("a", "info", "x"), it("b", "info", "x")], [it("b", "info", "x")]), ["a"]);
});

test("a desk.exception.* event refreshes the desk-exceptions resource", () => {
  assert.ok(LIVE_RESOURCES.includes("desk-exceptions"));
  assert.ok(resourcesFor("desk.exception.changed").includes("desk-exceptions"));
});
