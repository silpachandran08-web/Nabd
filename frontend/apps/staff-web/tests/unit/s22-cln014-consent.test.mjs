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
