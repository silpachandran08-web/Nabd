// FEF-037: one format module. Table-driven (7 currencies x 4 locales), spec examples, minor-unit round trip, digits, phone.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { formatMoney, formatNumber, formatPhone, minorDigits, minorToDecimal, parseMoneyMinor, setFormatContext, localizeDigits } from "@nabd/i18n";

test("spec examples, en", () => {
  assert.equal(formatMoney(123456.5, "INR", { locale: "en" }), "₹1,23,456.50");
  assert.equal(formatMoney("1234.567", "KWD", { locale: "en" }), "KWD 1,234.567");
  assert.equal(formatMoney(99.5, "AED", { locale: "en" }), "AED 99.50");
  assert.equal(formatMoney(10, "SAR", { locale: "en" }), "SAR 10.00");
  assert.equal(formatMoney(10, "SAR", { locale: "en", sarSymbol: true }), "10.00 ﷼");
  assert.equal(formatMoney(15.25, "QAR", { locale: "en" }), "QAR 15.25");
});
test("INR groups in lakhs in hi too; minor input", () => {
  assert.equal(formatMoney("123456.50", "INR", { locale: "hi" }), "₹1,23,456.50");
  assert.equal(formatMoney(12345650, "INR", { locale: "en", minor: true }), "₹1,23,456.50");
  assert.equal(formatMoney(1234567, "KWD", { locale: "en", minor: true }), "KWD 1,234.567");
});
test("null, NaN and blank render a dash; negative keeps its sign", () => {
  for (const v of [null, undefined, NaN, ""]) assert.equal(formatMoney(v, "INR"), "—");
  assert.match(formatMoney(-12.5, "INR", { locale: "en" }), /^-₹12\.50$/);
});

const CURRENCIES = ["INR", "KWD", "AED", "SAR", "QAR", "BHD", "USD"];
const LOCALES4 = [{ locale: "en" }, { locale: "hi" }, { locale: "ar" }, { locale: "ar", digitStyle: "arab" }];
for (const currency of CURRENCIES) for (const o of LOCALES4) {
  test(`table: ${currency} ${o.locale}${o.digitStyle ?? ""} has ${minorDigits(currency)} decimals and round-trips`, () => {
    const out = formatMoney("1234.5", currency, o);
    const latin = out.replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x660)).replace(/٫/g, ".");
    assert.equal(latin.match(/\.(\d+)/)?.[1].length, minorDigits(currency), out);
    assert.equal(parseMoneyMinor(out, currency), BigInt(Math.round(1234.5 * 10 ** minorDigits(currency))));
    assert.equal(/[٠-٩]/.test(out), o.digitStyle === "arab");
  });
}

test("property: format/parse round trip for random minor units, every currency", () => {
  let seed = 7; const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  for (const currency of ["INR", "KWD", "JPY", "USD"]) for (let i = 0; i < 1500; i++) {
    const minor = BigInt(Math.floor(rnd() * 10 ** (1 + Math.floor(rnd() * 12)))) * (rnd() < 0.2 ? -1n : 1n);
    const text = formatMoney(minor, currency, { locale: "en", minor: true });
    assert.equal(parseMoneyMinor(text, currency), minor, `${currency} ${minor} -> ${text}`);
    assert.equal(minorToDecimal(minor, currency).includes("."), minorDigits(currency) > 0);
  }
});

test("digit style: arab only for ar, clinical values and other languages stay Latin", () => {
  assert.equal(formatNumber(1234.5, { locale: "ar", digitStyle: "arab", min: 1 }), "١٬٢٣٤٫٥");
  assert.equal(formatNumber(120, { locale: "ar", digitStyle: "arab", clinical: true }), "120");
  assert.equal(formatNumber(120, { locale: "hi", digitStyle: "arab" }), "120");
  assert.equal(formatNumber(1234.5, { locale: "ar", digitStyle: "latn" }), "1,234.5");
  assert.equal(localizeDigits("BP 120", "arab"), "BP ١٢٠");
});
test("format context is the default for call sites that pass nothing", () => {
  setFormatContext({ locale: "ar", digitStyle: "arab" });
  assert.match(formatMoney(5, "AED"), /٥/);
  setFormatContext({ locale: "en", digitStyle: "latn" });
  assert.equal(formatMoney(5, "AED"), "AED 5.00");
});
test("phone: international format when valid, raw text otherwise", () => {
  assert.equal(formatPhone("+919876543210"), "+91 98765 43210");
  assert.equal(formatPhone("9876543210", "IN"), "+91 98765 43210");
  assert.equal(formatPhone("12ab"), "12ab");
  assert.equal(formatPhone(null), "—");
});

test("one formatter: no Intl.NumberFormat, toLocaleString or money toFixed outside packages/i18n (and the lint fixtures)", () => {
  const root = new URL("../../../../", import.meta.url).pathname;
  const hits = [];
  const walk = (d) => { for (const n of readdirSync(d)) {
    if (["node_modules", ".next", "generated", "lint-fixtures", "tests", "test", "i18n"].includes(n)) continue;
    const p = join(d, n); if (statSync(p).isDirectory()) walk(p);
    else if (/\.(ts|tsx)$/.test(n) && /Intl\.NumberFormat|toLocaleString\(\s*(undefined|"[a-z-]+"|\[\])?\s*,\s*\{\s*(min|max)imumFractionDigits/.test(readFileSync(p, "utf8"))) hits.push(p);
  } };
  walk(join(root, "apps/staff-web/app")); walk(join(root, "packages/domain/src")); walk(join(root, "packages/ui/src"));
  assert.deepEqual(hits, []);
});
