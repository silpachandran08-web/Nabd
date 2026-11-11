// FEF-040: checkout, OTC, billing / day close, the app shell and packages/ui carry no literal UI string; their text lives in
// packages/i18n (en + machine-drafted hi, pending native review); tax names come from the market (GST split vs VAT).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { ESLint } from "eslint";
import i18next from "eslint-plugin-i18next";
import tseslint from "typescript-eslint";
import { pseudoMessage } from "@nabd/i18n";
import { taxRegime } from "../../../../packages/domain/src/market.ts";
import { buildInvoiceView } from "../../app/components/checkout/invoiceDocument.ts";

const app = new URL("../../", import.meta.url).pathname;
const frontend = new URL("../../../../", import.meta.url).pathname;
const msg = (l, ns) => JSON.parse(readFileSync(`${frontend}packages/i18n/messages/${l}/${ns}.json`, "utf8"));
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? flat(v, `${p}${k}.`) : [[`${p}${k}`, v]]));
const walk = (d) => readdirSync(d).flatMap((f) => { const p = `${d}/${f}`; return statSync(p).isDirectory() ? walk(p) : [p]; });
const SHELL = ["app/ClinicNav.tsx", "app/TopBar.tsx", "app/IdleLockGuard.tsx", "app/AppShell.tsx", "app/RouteChrome.tsx", "app/PlainFrame.tsx", "app/EntitlementsBanner.tsx",
  "app/HomeRedirect.tsx", "app/LanguagePicker.tsx", "app/not-found.tsx", "app/global-error.tsx", ...["(auth)", "(clinic)", "(owner)", "(platform)"].flatMap((g) => [`app/${g}/error.tsx`, `app/${g}/not-found.tsx`])];
const FILES = [...walk(`${app}app/(clinic)/checkout`), ...walk(`${app}app/components/checkout`), ...walk(`${app}app/(clinic)/billing`), ...walk(`${app}app/shell`)]
  .map((f) => f.replace(app, "")).concat(SHELL).filter((f) => f.endsWith(".tsx"));
const isTextRule = (m) => m.ruleId === "i18next/no-literal-string" || (m.ruleId === "no-restricted-syntax" && /template literal/.test(m.message));

test("AC1: 0 literal-string violations in checkout, OTC, billing/day close and the shell (the CI rule, now applied to them)", async () => {
  const cfg = readFileSync(`${app}eslint.config.mjs`, "utf8");
  for (const g of ["app/(clinic)/checkout/**/*.tsx", "app/components/checkout/**/*.tsx", "app/(clinic)/billing/**/*.tsx", "app/shell/**/*.tsx", "app/ClinicNav.tsx", "app/IdleLockGuard.tsx", "app/global-error.tsx"]) assert.ok(cfg.includes(`"${g}"`), g);
  const eslint = new ESLint({ cwd: app, ignore: false });
  const bad = (await eslint.lintFiles(FILES)).flatMap((r) => r.messages.filter(isTextRule).map((m) => `${r.filePath.replace(app, "")}:${m.line} ${m.message}`));
  assert.ok(FILES.length > 35, `${FILES.length} files`);
  assert.deepEqual(bad, []);
});

test("AC1: 0 literal-string violations in packages/ui (same rule, linted from here because the plugin lives in staff-web)", async () => {
  const eslint = new ESLint({ cwd: frontend, overrideConfigFile: true, ignore: false, overrideConfig: [{ files: ["**/*.tsx"], languageOptions: { parser: tseslint.parser }, plugins: { i18next },
    rules: { "i18next/no-literal-string": ["error", { mode: "jsx-text-only", words: { exclude: ["[^a-zA-Z]+", "[A-Z_-]+"] } }] } }] });
  const res = await eslint.lintFiles(["packages/ui/src/**/*.tsx"]);
  assert.ok(res.length >= 5);
  assert.deepEqual(res.flatMap((r) => r.messages.map((m) => `${r.filePath}:${m.line} ${m.message}`)), []);
});

test("AC1: the rule still bites: a literal in a migrated checkout file is reported", async () => {
  const eslint = new ESLint({ cwd: app, ignore: false });
  const [r] = await eslint.lintText("export default function X() { return <p>Collect cash</p>; }", { filePath: `${app}app/(clinic)/checkout/zz-fixture.tsx` });
  assert.ok(r.messages.some((m) => m.ruleId === "i18next/no-literal-string"));
});

test("AC2: hi has every checkout, billing and shell key with a non-empty value, and every message parses", () => {
  for (const ns of ["checkout", "billing", "shell", "common"]) {
    const en = Object.fromEntries(flat(msg("en", ns))), hi = Object.fromEntries(flat(msg("hi", ns)));
    for (const k of Object.keys(en)) {
      assert.ok(typeof hi[k] === "string" && hi[k].trim() !== "", `hi ${ns}.${k} missing`);
      assert.doesNotThrow(() => pseudoMessage(en[k]), `en ${ns}.${k}`);
      assert.doesNotThrow(() => pseudoMessage(hi[k]), `hi ${ns}.${k}`);
    }
  }
  assert.ok(Object.keys(Object.fromEntries(flat(msg("en", "checkout")))).length > 150);
});

test("AC2: every key the migrated sources ask for exists in the English catalogue", () => {
  const cat = { checkout: msg("en", "checkout"), billing: msg("en", "billing"), shell: msg("en", "shell"), common: msg("en", "common") };
  const has = (path) => path.split(".").reduce((o, k) => (o && typeof o === "object" ? o[k] : undefined), cat) !== undefined;
  let checked = 0;
  for (const f of [...FILES, "app/lib/paymentMethodText.ts"]) {
    const s = readFileSync(`${app}${f}`, "utf8");
    for (const [, v, ns] of s.matchAll(/const (\w+) = useTranslations\("([^"]+)"\)/g)) {
      if (!/^(checkout|billing|shell|common)/.test(ns)) continue;
      for (const m of s.matchAll(new RegExp(`\\b${v}\\("([A-Za-z0-9_.]+)"`, "g"))) { checked++; assert.ok(has(`${ns}.${m[1]}`), `${f}: ${ns}.${m[1]}`); }
    }
  }
  assert.ok(checked > 300, `only ${checked} keys checked`);
});

test("AC2: every clinic nav item (reception, doctor, nurse model) has en and hi labels; the fourth nav model is no longer English-only", async () => {
  const { NAV } = await import("../../app/shell/navRegistry.ts");
  const en = msg("en", "shell").clinicNav, hi = msg("hi", "shell").clinicNav;
  for (const r of ["reception", "doctor", "nurse"]) for (const i of NAV[r]) {
    assert.deepEqual([en[i.id]?.label, en[i.id]?.short], [i.label, i.short], `en ${i.id} equals the registry text`);
    assert.ok(hi[i.id]?.label && hi[i.id]?.short, `hi ${i.id}`);
  }
});

test("AC3: tax labels come from the market: India splits GST (CGST/SGST or IGST), a UAE or Saudi clinic shows VAT", () => {
  assert.deepEqual(taxRegime("IN"), { label: "GST", split: true, registration: "GSTIN" });
  for (const r of ["AE", "SA"]) assert.equal(taxRegime(r).label, "VAT");
  const doc = { invoiceNumber: "INV-1", issuedAt: "2026-11-01T10:00:00Z", currency: "INR", placeOfSupply: null, seller: { name: "C", gstin: null, address: null, phone: null }, buyer: { name: "P", mrn: null, gstin: null },
    lines: [{ description: "Consult", sac: "999312", quantity: 1, unitMinor: 10000, taxableMinor: 10000, exempt: false, cgstRate: 9, cgstMinor: 900, sgstRate: 9, sgstMinor: 900, igstRate: 0, igstMinor: 0, totalMinor: 11800 }],
    totals: { taxableMinor: 10000, exemptMinor: 0, cgstMinor: 900, sgstMinor: 900, igstMinor: 0, discountMinor: 0, roundOffMinor: 0, totalMinor: 11800, paidMinor: 0, balanceMinor: 11800 }, payments: [], reprintCount: 0, copy: "original" };
  const when = (iso) => iso;
  const india = buildInvoiceView(doc, when);
  assert.deepEqual(india.totals.map((t) => t.label).filter((l) => /GST|VAT/.test(l)), ["CGST", "SGST"]);
  assert.match(india.rows[0].tax, /^CGST 9% \+ SGST 9%/);
  const uae = buildInvoiceView({ ...doc, currency: "AED" }, when, taxRegime("AE"));
  assert.deepEqual(uae.totals.map((t) => t.label).filter((l) => /GST|VAT/.test(l)), ["VAT"]);
  assert.match(uae.rows[0].tax, /^VAT 18%/);
  for (const f of ["app/(clinic)/checkout/[queueEntryId]/page.tsx", "app/(clinic)/checkout/otc/page.tsx"]) {
    const s = readFileSync(`${app}${f}`, "utf8");
    assert.match(s, /taxRegime\(getIdentity\(\)\?\.tenantRegion\)\.label/, f);
    assert.ok(!/<span>Tax<\/span>/.test(s), `${f} still has a literal Tax label`);
  }
  assert.match(readFileSync(`${app}app/components/checkout/InvoiceSheet.tsx`, "utf8"), /taxRegime\(region\)\.registration/);
});

test("no behaviour change: the unlock reasons sent to the server stay the recorded English text; only the label is translated", () => {
  const s = readFileSync(`${app}app/(clinic)/billing/page.tsx`, "utf8");
  assert.match(s, /REOPEN_REASONS = \{ missingBill: "Missing bill found afterwards", cashCount: "Cash count entered incorrectly", method: "Payment method recorded wrongly" \}/);
  assert.match(s, /value=\{r\}/);
  assert.match(s, /api\.post<Summary>\("\/billing\/day-close\/reopen", \{ reason, date \}\)/);
});

test("T1-BUG-7: page titles and the route announcement come from shell.pages (en = the route table, hi for every page)", async () => {
  const { PAGES, pageKey, pageName, routeTitle } = await import("../../app/lib/routeTitle.ts");
  const en = msg("en", "shell").pages, hi = msg("hi", "shell").pages;
  assert.equal(Object.keys(en).length, PAGES.length, "one catalogue key per route prefix");
  for (const [prefix, name] of PAGES) {
    assert.equal(en[pageKey(prefix)], name, `en ${prefix}`);
    assert.ok(hi[pageKey(prefix)]?.trim(), `hi ${prefix}`);
  }
  const tr = (k) => hi[k];
  assert.equal(routeTitle("/checkout/otc", "QA Clinic", tr), "चेकआउट · QA Clinic · Nabd");
  assert.equal(pageName("/account", tr), "मेरा खाता");
  assert.equal(routeTitle("/nowhere", "C", tr), "C · Nabd");
  const chrome = readFileSync(`${app}app/RouteChrome.tsx`, "utf8");
  assert.match(chrome, /t\(`pages\.\$\{key\}`\)/);
  assert.match(chrome, /routeTitle\(pathname, getIdentity\(\)\?\.tenantName, tr\)/);
  assert.match(chrome, /pageName\(pathname, tr\)/);
});

test("T1-BUG-7: the route error panel, the Retry button and the stock pill read the catalogue (en + hi)", () => {
  const src = (f) => readFileSync(`${app}app/components/ui/${f}`, "utf8");
  assert.ok(!/This panel failed|Nothing you typed|Go home/.test(src("RouteError.tsx")));
  assert.match(src("RouteError.tsx"), /t\("routeError\.title"\)[\s\S]*t\("globalError\.body"\)[\s\S]*t\("routeError\.home"\)/);
  assert.ok(!/"Retry"/.test(src("StateViews.tsx")));
  assert.match(src("StateViews.tsx"), /retryLabel \?\? t\("retry"\)/);
  assert.ok(!/In stock|Out of stock|Low ·/.test(src("StockPill.tsx")));
  for (const l of ["en", "hi"]) {
    const shell = msg(l, "shell"), common = msg(l, "common");
    for (const v of [shell.routeError.title, shell.routeError.home, shell.globalError.body, common.retry, common.stock.out]) assert.ok(v?.trim(), `${l} ${v}`);
    for (const k of ["in", "low"]) assert.match(common.stock[k], /\{qty\}/, `${l} common.stock.${k}`);
  }
  assert.equal(msg("hi", "common").stock.in, "स्टॉक में · {qty}");
});
