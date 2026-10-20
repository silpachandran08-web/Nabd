// PLT-005: staff-list MFA badge. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";

const { mfaBadge } = await import("../../app/staff/mfaStatus.ts");

test("enrolled staff show TOTP", () => {
  assert.deepEqual(mfaBadge({ loginMethod: "authenticator", loginFactors: ["pin", "authenticator"] }), { label: "TOTP", tone: "ok" });
});
test("privileged staff without MFA show Setup required", () => {
  assert.equal(mfaBadge({ loginMethod: "authenticator_setup_required", loginFactors: ["pin"] }).label, "Setup required");
});
test("owner shadow rows defer to the owner account", () => {
  assert.equal(mfaBadge({ loginMethod: "owner_account", loginFactors: [] }).label, "Owner sign-in");
});
test("PIN-only, invite pending and missing fields show None", () => {
  for (const s of [{ loginMethod: "pin", loginFactors: ["pin"] }, { loginMethod: "invite_pending", loginFactors: [] }, {}]) {
    assert.equal(mfaBadge(s).label, "None");
  }
});
