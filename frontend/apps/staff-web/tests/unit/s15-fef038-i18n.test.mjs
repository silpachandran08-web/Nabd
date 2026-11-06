// FEF-038: login / MFA / accept-invite / arrivals are extracted. Catalogue parity (en, hi), every key used in source exists,
// ICU plurals, pseudo-locale, no template literal builds text (lint), flip list for directional icons, logical-CSS lint is repo-wide.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { ESLint } from "eslint";
import stylelint from "stylelint";
import { createTranslator } from "next-intl";
import { loadMessages } from "@nabd/i18n";

const app = new URL("../../", import.meta.url).pathname;
const msgDir = new URL("../../../../packages/i18n/messages/", import.meta.url).pathname;
const NS = ["login", "arrivals", "common"];
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? flat(v, `${p}${k}.`) : [[`${p}${k}`, v]]));
const cat = (loc, ns) => Object.fromEntries(flat(JSON.parse(readFileSync(`${msgDir}${loc}/${ns}.json`, "utf8"))));
const vars = (s) => [...new Set([...s.matchAll(/\{(\w+)/g)].map((m) => m[1]))].sort().join(",");
const walk = (d) => readdirSync(d).flatMap((f) => { const p = `${d}/${f}`; return statSync(p).isDirectory() ? walk(p) : [p]; });
const MIGRATED = [...walk(`${app}app/(auth)/login`), ...walk(`${app}app/(auth)/accept-invite`), ...walk(`${app}app/(clinic)/arrivals`), ...walk(`${app}app/components/arrivals`), `${app}app/components/MfaEnrolCard.tsx`, `${app}app/components/ui/Dialog.tsx`]
  .filter((f) => /\.tsx?$/.test(f));

test("hi has exactly the keys and ICU placeholders of en, and no empty text", () => {
  for (const ns of NS) {
    const en = cat("en", ns), hi = cat("hi", ns);
    assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort(), `${ns}: key sets differ`);
    for (const k of Object.keys(en)) { assert.ok(hi[k].trim() !== "", `${ns}.${k} empty`); assert.equal(vars(hi[k]), vars(en[k]), `${ns}.${k} placeholders`); }
  }
});

test("every literal key used with useTranslations in the migrated source exists in the English catalogue", () => {
  const all = Object.fromEntries(NS.flatMap((ns) => Object.entries(cat("en", ns)).map(([k, v]) => [`${ns}.${k}`, v])));
  let checked = 0;
  for (const f of MIGRATED) {
    const src = readFileSync(f, "utf8");
    const bound = Object.fromEntries([...src.matchAll(/const (\w+) = useTranslations\("([^"]+)"\)/g)].map((m) => [m[1], m[2]]));
    for (const [v, ns] of Object.entries(bound)) {
      for (const m of src.matchAll(new RegExp(`\\b${v}(?:\\.has)?\\("([A-Za-z0-9_.]+)"`, "g"))) {
        checked++;
        assert.ok(`${ns}.${m[1]}` in all, `${f.replace(app, "")}: ${ns}.${m[1]} is not in the catalogue`);
      }
    }
  }
  assert.ok(checked > 150, `only ${checked} keys checked`);
});

test("no migrated file keeps a user-facing English sentence in JSX text or a visible attribute", async () => {
  const eslint = new ESLint({ cwd: app, ignore: false });
  const bad = (await eslint.lintFiles(MIGRATED.map((f) => f.replace(app, "")).filter((f) => f.endsWith(".tsx"))))
    .flatMap((r) => r.messages.filter((m) => m.ruleId === "i18next/no-literal-string" || (m.ruleId === "no-restricted-syntax" && /template literal/.test(m.message))).map((m) => `${r.filePath.replace(app, "")}:${m.line} ${m.ruleId}`));
  assert.deepEqual(bad, []);
});

test("a template literal that builds text fails the lint (must-fail fixture)", async () => {
  const [r] = await new ESLint({ cwd: app, ignore: false }).lintFiles(["lint-fixtures/i18n/bad-template.tsx"]);
  assert.equal(r.messages.filter((m) => m.ruleId === "no-restricted-syntax" && /template literal/.test(m.message)).length, 2, JSON.stringify(r.messages));
});

const say = async (loc, key, values) => {
  const ms = await loadMessages(loc, ["arrivals"]);
  return createTranslator({ locale: loc === "pseudo" || loc === "pseudo-rtl" ? "en" : loc, messages: ms, namespace: "arrivals.delay" })(key, values);
};
test("ICU plural 'waiting patients are affected' for 1, 2, 5 in en and hi; no concatenation", async () => {
  assert.deepEqual(await Promise.all([1, 2, 5].map((n) => say("en", "affected", { count: n }))), ["1 waiting patient is affected.", "2 waiting patients are affected.", "5 waiting patients are affected."]);
  assert.deepEqual(await Promise.all([1, 2, 5].map((n) => say("hi", "affected", { count: n }))), ["प्रतीक्षा में 1 मरीज़ प्रभावित है।", "प्रतीक्षा में 2 मरीज़ प्रभावित हैं।", "प्रतीक्षा में 5 मरीज़ प्रभावित हैं।"]);
});
test("pseudo-locale renders every extracted string accented (no raw English survives) and keeps placeholders", async () => {
  for (const ns of ["login", "arrivals"]) {
    const en = cat("en", ns);
    const ms = await loadMessages("pseudo", [ns]);
    const ps = Object.fromEntries(flat(ms[ns]));
    for (const [k, v] of Object.entries(en)) {
      if (!/[A-Za-z]{2}/.test(v.replace(/\{[^}]*\}/g, ""))) continue; // symbols only
      assert.notEqual(ps[k], v, `${ns}.${k} not pseudo-localised`);
      assert.equal(vars(ps[k]), vars(v), `${ns}.${k} placeholders after pseudo`);
    }
  }
  assert.match(await say("pseudo", "invalid", {}), /[^\x00-\x7f]/);
});
test("the board statuses and visit types of the API are all in the catalogue (hi included)", () => {
  for (const loc of ["en", "hi"]) {
    const c = cat(loc, "arrivals");
    for (const s of ["checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done", "in_consult", "procedures_pending", "checkout_pending", "completed", "no_show"]) assert.ok(c[`status.${s}`], `${loc} status.${s}`);
    for (const v of ["appointment", "follow_up", "walk_in", "transfer", "referral", "online", "social_media", "returning", "other", "internal_transfer"]) assert.ok(c[`visit.${v}`], `${loc} visit.${v}`);
  }
});

test("stylelint with the logical-properties rule passes on every CSS file of the app and fails on physical declarations", async () => {
  const ok = await stylelint.lint({ files: `${app}app/**/*.css`, cwd: app });
  assert.deepEqual(ok.results.flatMap((r) => r.warnings.map((w) => `${r.source}:${w.line} ${w.text}`)), []);
  const bad = await stylelint.lint({ code: ".a{margin-left:4px;padding-right:2px;text-align:left;border-left:1px solid red;left:0;float:right}", codeFilename: `${app}app/x.css`, cwd: app });
  assert.equal(bad.results[0].warnings.filter((w) => w.rule === "liberty/use-logical-spec").length, 6);
});

test("directional icons mirror under rtl: flip list on Icon, one global rule, overview chevrons use the icon", () => {
  assert.match(readFileSync(new URL("../../../../packages/ui/src/Icon.tsx", import.meta.url), "utf8"), /FLIP_RTL: readonly IconName\[\] = \["chevron", "login"\]/);
  assert.match(readFileSync(`${app}app/globals.css`, "utf8"), /\[dir="rtl"\] svg\[data-flip\] \{ transform: scaleX\(-1\); \}/);
  const ov = readFileSync(`${app}app/(clinic)/overview/page.tsx`, "utf8");
  assert.ok(!ov.includes("›"), "a text chevron is left in overview");
  assert.equal((ov.match(/<Icon name="chevron" size=\{18\} \/>/g) ?? []).length, 6); // ADM-043 adds the daily summary button
});
