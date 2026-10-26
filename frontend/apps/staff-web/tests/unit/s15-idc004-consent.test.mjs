// IDC-004/007/008: the desk consent request body, optional purposes default off, a child is never offered marketing, explainer rules.
import { test } from "node:test";
import assert from "node:assert/strict";
const { buildCaptureBody, NO_CONSENT, consentProblemText, explainerUsable, EXPLAINER_LANGUAGES } = await import("../../app/lib/consentCapture.ts");

const notices = { en: "3", hi: "2" };
const on = { processing: true, messaging: true, marketing: true };

test("ticked purposes are granted at the current notice version, channel DESK by default", () => {
  const b = buildCaptureBody({ processing: true, messaging: true, marketing: false }, notices, "en");
  assert.deepEqual(b.decisions, [{ purpose: "processing", granted: true }, { purpose: "messaging", granted: true }, { purpose: "marketing", granted: false }]);
  assert.equal(b.noticeVersion, "3"); assert.equal(b.channel, "DESK"); assert.equal(b.language, "en");
});
test("optional purposes cannot be granted without processing", () => {
  const b = buildCaptureBody({ processing: false, messaging: true, marketing: true }, notices, "en");
  assert.deepEqual(b.decisions.map((d) => d.granted), [false, false, false]);
});
test("a child is never sent a granted marketing decision", () => {
  const b = buildCaptureBody(on, notices, "en", { minor: true, guardianPatientId: "g1" });
  assert.equal(b.decisions.find((d) => d.purpose === "marketing").granted, false);
  assert.equal(b.guardianPatientId, "g1");
});
test("assisted capture carries channel, language notice and witnesses", () => {
  const b = buildCaptureBody(on, notices, "hi", { channel: "ASSISTED", witnessStaffIds: ["w1"] });
  assert.equal(b.channel, "ASSISTED"); assert.equal(b.noticeVersion, "2"); assert.deepEqual(b.witnessStaffIds, ["w1"]);
});
test("emergency basis is sent only when processing is declined", () => {
  assert.equal(buildCaptureBody(NO_CONSENT, notices, "en", { emergency: true }).emergencyTreatmentBasis, true);
  assert.equal("emergencyTreatmentBasis" in buildCaptureBody(on, notices, "en", { emergency: true }), false);
});
test("no notice in the language means no request", () => assert.equal(buildCaptureBody(on, notices, "kn"), null));
test("problem slugs map to desk text, unknown ones keep the server text", () => {
  assert.match(consentProblemText("second-witness-required", "x"), /second staff/);
  assert.equal(consentProblemText("nope", "server text"), "server text");
});
test("explainer needs text and audio of at most 60 s", () => {
  assert.equal(explainerUsable(null), false);
  assert.equal(explainerUsable({ language: "hi", text: null, audioUrl: null }), false);
  assert.equal(explainerUsable({ language: "en", text: "t", audioUrl: null }), true);
  assert.equal(explainerUsable({ language: "en", text: "t", audioUrl: "/a.mp3", audioSeconds: 61 }), false);
  assert.deepEqual([...EXPLAINER_LANGUAGES], ["en", "hi", "kn", "te"]);
});
