// COM-015: owner message usage model and wiring (cost by channel and category, allowance bar only when the plan has one, the
// one alert, absent without setup:view, loading / empty / error states). Synthetic data only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { shiftMonth, topAlert, totals } from "../../app/components/plan/messageUsage.ts";

const src = readFileSync(new URL("../../app/components/plan/MessageUsagePanel.tsx", import.meta.url), "utf8");
const bundle = (lang) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${lang}/billing.json`, import.meta.url), "utf8")).messageUsage;
const en = bundle("en");
const hi = bundle("hi");

test("model: totals stay per currency, months roll over the year, the highest alert wins", () => {
  const items = [
    { channel: "whatsapp", category: "utility", count: 120, costMinor: 1380, currency: "INR" },
    { channel: "whatsapp", category: "marketing", count: 10, costMinor: 785, currency: "INR" },
    { channel: "whatsapp", category: "utility", count: 3, costMinor: 4, currency: "USD" },
  ];
  assert.deepEqual(totals(items), [{ currency: "INR", count: 130, costMinor: 2165 }, { currency: "USD", count: 3, costMinor: 4 }]);
  assert.deepEqual(totals([]), []);
  assert.equal(shiftMonth("2026-01", -1), "2025-12");
  assert.equal(shiftMonth("2026-12", 1), "2027-01");
  assert.equal(shiftMonth("2026-11", -1), "2026-10");
  assert.equal(topAlert([]), null);
  assert.equal(topAlert([{ thresholdPct: 80, at: "a" }, { thresholdPct: 100, at: "b" }]).thresholdPct, 100);
  assert.equal(topAlert([{ thresholdPct: 80, at: "a" }]).thresholdPct, 80);
});

test("AC1: the page reads the month from the owner usage endpoint and lists count and cost by channel and category", () => {
  assert.match(src, /getter<MessageUsage>\("\/owner\/messaging\/usage"\)/);
  assert.match(src, /getter<MessageUsage>\(`\/owner\/messaging\/usage\?month=\$\{month\}`\)/);
  assert.match(src, /data\.channels\.map\(/);
  assert.match(src, /money\(i\.costMinor, i\.currency\)/);
  assert.match(src, /totals\(data\.channels\)\.map\(/);
  for (const k of ["colChannel", "colCategory", "colCount", "colCost"]) assert.match(src, new RegExp(`t\\("${k}"\\)`), k);
});

test("AC2: one alert banner, for the highest threshold the server raised", () => {
  assert.match(src, /const alert = topAlert\(data\.alerts\)/);
  assert.match(src, /\{alert && \(/);
  assert.match(src, /alert\.thresholdPct >= 100 \? t\("alert100"\) : t\("alert80", \{ pct: alert\.thresholdPct \}\)/);
  assert.match(en.alert80, /\{pct\}%/);
});

test("AC3: no allowance = no bar; the cost table renders either way", () => {
  assert.match(src, /const a = data\.allowance \?\? null/);
  assert.match(src, /\{a && pct !== null && \(/);
  assert.ok(src.indexOf('data-testid="usage-allowance"') < src.indexOf('data-testid="usage-table"'));
  assert.ok(!/allowance!\./.test(src));
});

test("absent without setup:view and never mounted before the permission answer; loading, empty and error states", () => {
  assert.match(src, /return mounted && can\("setup:view"\) \? <Usage \/> : null/);
  assert.match(src, /q\.isPending \? <Skeleton/);
  assert.match(src, /q\.isError \? <ErrorState message=\{reasonText\(q\.error, t\("loadFailed"\)\)\} onRetry/);
  assert.match(src, /data\.channels\.length === 0 \? <EmptyState/);
  assert.match(readFileSync(new URL("../../app/(clinic)/plan/page.tsx", import.meta.url), "utf8"), /<MessageUsagePanel \/>/);
});

test("strings: en and hi carry the same keys and every key the panel uses exists", () => {
  const keys = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`])).sort();
  assert.deepEqual(keys(hi), keys(en));
  for (const m of src.matchAll(/\bt\("([A-Za-z0-9]+)"/g)) assert.ok(m[1] in en, m[1]);
  for (const c of ["whatsapp", "sms"]) assert.ok(en.channel[c]);
  for (const c of ["marketing", "utility", "authentication", "service", "otp", "promotional"]) assert.ok(en.category[c] && hi.category[c], c);
});
