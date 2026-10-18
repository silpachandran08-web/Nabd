// LIVE-04: login lands on the first sidebar destination the role can open. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { landingPathFor } from "../../app/lib/session.ts";

test("owner lands on the Overview, or the first owner item a trimmed Owner role can open", () => {
  assert.equal(landingPathFor(["reports:view", "queue:view", "nursing:view", "clinical:edit"], true), "/overview");
  assert.equal(landingPathFor(["queue:view", "billing:view"], true), "/schedule");
});

test("a nursing:view role lands on /nursing even with clinical:edit and queue:view", () => {
  assert.equal(landingPathFor(["nursing:view", "clinical:edit", "queue:view", "patients:view"]), "/nursing");
});

test("nursing:create/edit without nursing:view never lands on the gated /nursing page", () => {
  assert.equal(landingPathFor(["nursing:create", "nursing:edit", "queue:view"]), "/arrivals");
});

test("doctor lands on My Day; without queue:view on a page that does not need it, never /consult", () => {
  assert.equal(landingPathFor(["clinical:edit", "clinical:view", "queue:view"]), "/my-day");
  assert.equal(landingPathFor(["clinical:edit", "patients:view"]), "/patients");
  assert.equal(landingPathFor(["clinical:edit", "clinical:view"]), "/prescriptions");
  assert.equal(landingPathFor(["clinical:edit"]), "/account");
});

test("other staff follow the generic sidebar order", () => {
  assert.equal(landingPathFor(["queue:view", "clinical:view", "billing:view"]), "/arrivals");
  assert.equal(landingPathFor(["patients:view", "billing:view"]), "/patients");
  assert.equal(landingPathFor(["pharmacy:view", "billing:view"]), "/pharmacy");
  assert.equal(landingPathFor(["billing:view"]), "/billing");
  assert.equal(landingPathFor([]), "/account");
});
