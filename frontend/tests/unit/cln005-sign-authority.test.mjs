// CLN-005: what the consult screen says instead of a Sign button. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";

const { signBlockMessage } = await import("../../app/components/consult/signAuthority.ts");

test("a caller who may sign gets no message (the Sign button shows)", () => {
  assert.equal(signBlockMessage({ canSign: true, reason: null }), null);
});

test("each refusal code maps to its own explanation", () => {
  assert.match(signBlockMessage({ canSign: false, reason: "not-licensed-clinician" }), /registered clinician/);
  assert.match(signBlockMessage({ canSign: false, reason: "licence-expired" }), /expired/);
  assert.match(signBlockMessage({ canSign: false, reason: "break-glass-cannot-sign" }), /Emergency access/);
  assert.equal(signBlockMessage({ canSign: false, reason: "not-prescriber" }), "Send to doctor to sign.");
});

test("loading and failure are explicit, never a silent Sign button", () => {
  assert.match(signBlockMessage(null), /Checking/);
  assert.match(signBlockMessage("error"), /Couldn't check/);
  assert.equal(signBlockMessage({ canSign: false, reason: "something-new" }), "Send to doctor to sign.");
});
