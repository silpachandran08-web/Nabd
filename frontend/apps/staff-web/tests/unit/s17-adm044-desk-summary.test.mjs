// ADM-044: the desk summary panel before day close — checkout links, the pre-filled close, the queue:view gate.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { checkoutHref, prefillCounted, expectedCashOf, collectedTotal } = await import("../../app/(clinic)/billing/deskSummaryModel.ts");

const src = (f) => readFileSync(new URL(`../../app/(clinic)/billing/${f}`, import.meta.url), "utf8");
const panel = src("DeskSummary.tsx");
const page = src("page.tsx");
// FEF-040: visible text moved to packages/i18n (billing namespace); the source references the keys.
const billing = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/billing.json", import.meta.url), "utf8"));

test("AC2: an unbilled visit links to checkout for that visit", () => {
  assert.equal(checkoutHref("3f2a-1"), "/checkout/3f2a-1");
  assert.match(panel, /href=\{checkoutHref\(u\.queueEntryId\)\}/);
});

test("AC3: day close opens with the counted cash pre-filled to the expected cash, a typed count wins", () => {
  const methods = [{ method: "cash", amount: 1250.5 }, { method: "card", amount: 300 }, { method: "upi", amount: 0 }, { method: "other", amount: 0 }];
  assert.equal(expectedCashOf(methods), 1250.5);
  assert.equal(expectedCashOf([]), 0);
  assert.equal(prefillCounted("", 1250.5), "1250.5");
  assert.equal(prefillCounted("  ", 0), "0");
  assert.equal(prefillCounted("1200", 1250.5), "1200");
  assert.equal(collectedTotal(methods), 1550.5);
  assert.match(page, /setCounted\(prefillCounted\(counted, expectedCash\)\); setDialog\("close"\)/);
  assert.match(page, /aria-label=\{t\("close\.expectedTotals"\)\}/);
  assert.equal(billing.dayClose.close.expectedTotals, "Expected totals");
});

test("AC1: the panel reads the desk summary for the picked day and shows all four parts with loading and error states", () => {
  assert.match(panel, /\/reports\/desk-summary\?date=\$\{day\}/);
  for (const [key, part] of [["visits", "Visits"], ["collectionsByMode", "Collections by mode"], ["unbilled", "Unbilled visits"], ["noShows", "No-shows"]]) {
    assert.match(panel, new RegExp(`aria-label=\\{t\\("${key}"\\)\\}`));
    assert.equal(billing.desk[key], part);
  }
  assert.match(panel, /t\("loading"\)/);
  assert.equal(billing.desk.loading, "Loading desk summary…");
  assert.match(panel, /role="alert"/);
  assert.equal(billing.desk.noMissed, "No missed bookings.");
});

test("the panel is absent without queue:view (the endpoint's grant)", () => {
  assert.match(page, /const canDesk = mounted && can\("queue:view"\)/);
  assert.match(page, /\{canDesk && <DeskSummary day=\{day\} \/>\}/);
});
