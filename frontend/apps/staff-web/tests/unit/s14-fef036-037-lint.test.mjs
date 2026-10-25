// FEF-036 literal-string lint and FEF-037 money-format lint, run on must-fail fixtures; plus the real migrated files stay clean.
import { test } from "node:test";
import assert from "node:assert/strict";
import { ESLint } from "eslint";

const app = new URL("../../", import.meta.url).pathname;
const lint = async (f) => (await new ESLint({ cwd: app, ignore: false }).lintFiles([f]))[0];

test("a JSX literal in a migrated folder fails with i18next/no-literal-string", async () => {
  const r = await lint("lint-fixtures/i18n/bad-literal.tsx");
  assert.ok(r.messages.some((m) => m.ruleId === "i18next/no-literal-string"), JSON.stringify(r.messages));
});
test("translated text and symbols pass", async () => {
  assert.deepEqual((await lint("lint-fixtures/i18n/ok-translated.tsx")).messages, []);
});
test("raw Intl.NumberFormat, number toLocaleString and money toFixed are refused", async () => {
  const r = await lint("lint-fixtures/money/bad-intl.ts");
  assert.equal(r.messages.filter((m) => m.ruleId === "no-restricted-syntax").length, 3, JSON.stringify(r.messages));
});
test("formatMoney passes", async () => {
  assert.deepEqual((await lint("lint-fixtures/money/ok-format.ts")).messages, []);
});
test("the migrated shell files are clean", async () => {
  for (const f of ["app/TopBar.tsx", "app/LanguagePicker.tsx"]) {
    const r = await lint(f);
    assert.deepEqual(r.messages.filter((m) => m.ruleId === "i18next/no-literal-string"), [], f);
  }
});
