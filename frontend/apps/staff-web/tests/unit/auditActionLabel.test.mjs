// PLT-036: platform audit actions render readably; unknown or missing codes never crash. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { actionLabel } from "../../app/(platform)/platform/audit/actionLabel.ts";

test("known codes get a readable label", () => {
  assert.equal(actionLabel("support_access.grant"), "Support access granted");
});

test("unknown codes fall back to the raw code", () => {
  assert.equal(actionLabel("platform.operator_signin_failed"), "platform.operator_signin_failed");
});

test("missing or non-string codes do not throw", () => {
  for (const v of [null, undefined, "", 42, {}]) assert.equal(actionLabel(v), "Unknown action");
});
