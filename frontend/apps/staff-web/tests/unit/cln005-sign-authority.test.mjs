// CLN-005: what the consult screen says instead of a Sign button. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";

const { signBlockCode } = await import("../../app/components/consult/signAuthority.ts");

test("a caller who may sign gets no message (the Sign button shows)", () => {
  assert.equal(signBlockCode({ canSign: true, reason: null }), null);
});

test("each refusal code maps to its own explanation", () => {
  assert.equal(signBlockCode({ canSign: false, reason: "not-licensed-clinician" }), "notLicensed");
  assert.equal(signBlockCode({ canSign: false, reason: "licence-expired" }), "licenceExpired");
  assert.equal(signBlockCode({ canSign: false, reason: "break-glass-cannot-sign" }), "breakGlass");
  assert.equal(signBlockCode({ canSign: false, reason: "not-prescriber" }), "sendToDoctor");
});

test("loading and failure are explicit, never a silent Sign button", () => {
  assert.equal(signBlockCode(null), "checking");
  assert.equal(signBlockCode("error"), "error");
  assert.equal(signBlockCode({ canSign: false, reason: "something-new" }), "sendToDoctor");
});

// FEF-039: the code maps to the catalogue wording, which keeps the exact sentences the doctor saw before extraction.
test("each code has its English sentence in the consult catalogue", async () => {
  const { readFileSync } = await import("node:fs");
  const t = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/consult.json", import.meta.url), "utf8")).rxPanel.signBlock;
  assert.match(t.notLicensed, /registered clinician/); assert.match(t.licenceExpired, /expired/); assert.match(t.breakGlass, /Emergency access/);
  assert.equal(t.sendToDoctor, "Send to doctor to sign."); assert.match(t.checking, /Checking/); assert.match(t.error, /Couldn't check/);
});
