// FEF-041: every patient string in en/hi/kn/te, the published artefact patient-web is built from, review state, Telugu fonts on the Rx page.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const pkg = (p) => new URL(`../../../../packages/i18n/${p}`, import.meta.url);
const json = (p) => JSON.parse(readFileSync(pkg(p), "utf8"));
const LANGS = ["en", "hi", "kn", "te"];
const cat = Object.fromEntries(LANGS.map((l) => [l, json(`messages/${l}/patient.json`)]));
const artefact = json("catalogue/patient-catalogue.json");
const read = (rel) => readFileSync(new URL(`../../${rel}`, import.meta.url), "utf8");

test("every patient key exists and is non-empty in en, hi, kn and te", () => {
  for (const l of LANGS) {
    assert.deepEqual(Object.keys(cat[l]).sort(), Object.keys(cat.en).sort(), l);
    for (const [k, v] of Object.entries(cat[l])) assert.ok(String(v).trim(), `${l}.${k}`);
  }
});
test("the artefact carries exactly the catalogue patient-web renders, with a version", () => {
  assert.match(artefact.version, /^[0-9a-f]{12}$/);
  for (const l of LANGS) assert.deepEqual(artefact.patient[l], cat[l], l);
});
test("hi, kn and te are marked unreviewed until a clinician's review is recorded, and every clinical key is listed", () => {
  for (const l of ["hi", "kn", "te"]) {
    for (const k of artefact.clinicalKeys.patient) {
      assert.ok(artefact.review[l].patient[k], `${l}.${k} has a review state`);
      assert.ok(["unreviewed", "reviewed"].includes(artefact.review[l].patient[k].status));
      if (artefact.review[l].patient[k].status === "reviewed") {
        assert.ok(artefact.review[l].patient[k].reviewerId && artefact.review[l].patient[k].reviewedOn);
      }
    }
  }
});
test("the secure Rx page renders Telugu with Noto Sans Telugu and stamps the catalogue version", () => {
  assert.match(read("app/globals.css"), /:lang\(te\) body[^}]*var\(--font-telu\)/);
  assert.match(read("app/fonts.ts"), /noto-sans-telugu-telugu-400-normal\.woff2/);
  assert.match(read("app/rx/[token]/page.tsx"), /messagesFor\(locale\)/);
  assert.match(read("app/layout.tsx"), /data-catalogue=\{catalogue\.version\}/);
});
