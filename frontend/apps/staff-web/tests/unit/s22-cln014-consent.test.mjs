import test from "node:test";
import assert from "node:assert/strict";
import { pickLang, plainText, signable, strokesToSvg } from "../../app/components/ui/consent/consentModel.ts";

const t = (lang, placeholder = false) => ({ id: lang, version: 1, lang, chargeCode: "PEEL", title: "x", bodyHtml: "", placeholder });

test("strokes become M/L path data in the 600 x 200 pad box", () => {
  assert.equal(strokesToSvg([[[10, 20], [30.26, 40]], []]),
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 200"><path d="M10 20 L30.3 40"/></svg>');
  assert.equal(strokesToSvg([]), '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 200"></svg>');
});

test("the form is shown as text, never as HTML", () => {
  assert.equal(plainText("<p>Risks:</p><ul><li>redness &amp; peeling</li></ul><script>alert(1)</script>"), "Risks:\n- redness & peeling\nalert(1)");
});

test("placeholders are never signable; the patient's language wins, then English", () => {
  const items = [t("en"), t("hi"), t("kn", true)];
  assert.deepEqual(signable(items).map((x) => x.lang), ["en", "hi"]);
  assert.equal(pickLang(items, "hi-IN"), "hi");
  assert.equal(pickLang(items, "kn"), "en");
  assert.equal(pickLang([t("te")], null), "te");
  assert.equal(pickLang([t("en", true)], "en"), null);
});

test("QA N-2: a dot or a short tap is not enough ink; ten points spanning 40 pad units is", async () => {
  const { enoughInk } = await import("../../app/components/ui/consent/consentModel.ts");
  assert.equal(enoughInk([]), false);
  assert.equal(enoughInk([[[0, 0]]]), false);
  assert.equal(enoughInk([Array.from({ length: 11 }, (_, i) => [i, 0])]), false); // 11 points, 10 wide
  assert.equal(enoughInk([Array.from({ length: 5 }, (_, i) => [i * 5, i]), Array.from({ length: 5 }, (_, i) => [25 + i * 5, 5 + i])]), true);
});

test("QA N-3: who signs comes from the order; an unknown age asks for the guardian first", async () => {
  const { signerPlan } = await import("../../app/components/ui/consent/consentModel.ts");
  assert.deepEqual(signerPlan({ patientMinor: true, guardianName: "Parent" }), { age: "minor", kinds: ["guardian"], defaultKind: "guardian", guardianName: "Parent" });
  assert.deepEqual(signerPlan({ patientMinor: false, guardianName: null }), { age: "adult", kinds: ["patient"], defaultKind: "patient", guardianName: null });
  assert.deepEqual(signerPlan({ patientMinor: false, guardianName: "Carer" }).kinds, ["patient", "guardian"]);
  for (const unknown of [{ patientMinor: null }, {}]) {
    const p = signerPlan({ ...unknown, guardianName: "Parent" });
    assert.equal(p.age, "unknown");
    assert.equal(p.defaultKind, "guardian");
    assert.deepEqual(p.kinds, ["guardian", "patient"]);
  }
});
