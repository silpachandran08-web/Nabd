// FEF-046 / FEF-047: print kit presets, amount in words, invoice document checks and view text (golden, synthetic data).
import { test } from "node:test";
import assert from "node:assert/strict";
import { PAPERS, BUILTIN_PAPER, resolvePaper, printCss, policyKey, isPaper, SHEET_MM } from "../../../../packages/ui/src/print/paper.ts";
import { amountInWords, integerInWords } from "../../../../packages/i18n/src/format/amountInWords.ts";
import { buildInvoiceView, documentProblem } from "../../app/components/checkout/invoiceDocument.ts";

test("presets: A4, A5 and an 80 mm roll whose height follows the content", () => {
  assert.deepEqual([...PAPERS], ["a4", "a5", "80mm"]);
  assert.match(printCss("a4"), /size: A4;/);
  assert.match(printCss("a5"), /size: A5;/);
  // Browsers reject "80mm auto", so a roll asks for its measured height (PrintSheet), 297 mm until measured.
  assert.match(printCss("80mm"), /size: 80mm 297mm;/);
  assert.match(printCss("80mm", 83), /size: 80mm 83mm;/);
  assert.doesNotMatch(printCss("80mm"), /size: 80mm auto/);
  assert.doesNotMatch(printCss("a5", 83), /80mm/);
  assert.ok(SHEET_MM["80mm"] <= 72 && SHEET_MM.a5 <= 128);
});

test("page x of y on A4/A5 only; a roll is one page", () => {
  for (const p of ["a4", "a5"]) assert.match(printCss(p), /counter\(page\) " of " counter\(pages\)/);
  assert.doesNotMatch(printCss("80mm"), /counter\(page\)/);
});

test("print is black on white whatever the theme: no colour other than #000/#fff, backgrounds cleared", () => {
  for (const p of PAPERS) {
    const css = printCss(p);
    assert.match(css, /background:#fff!important;color:#000!important/);
    assert.match(css, /\.nbp-sheet,\.nbp-sheet \*\{background:transparent!important;color:#000!important/);
    const colours = css.match(/#[0-9a-fA-F]{3,6}\b/g) ?? [];
    assert.ok(colours.every((c) => ["#000", "#fff", "#888"].includes(c.toLowerCase())), `${p}: ${colours}`);
  }
});

test("default paper: clinic value wins, unknown or missing falls back to the built-in (no prompt, no block)", () => {
  assert.equal(resolvePaper("rx", "a5"), "a5");
  assert.equal(resolvePaper("rx"), BUILTIN_PAPER.rx);
  assert.equal(resolvePaper("receipt", "legal"), "80mm");
  assert.equal(resolvePaper("token_slip", null), "80mm");
  assert.equal(policyKey("rx"), "print.default_paper.rx");
  assert.ok(!isPaper("A4") && isPaper("80mm"));
});

test("amount in words, Indian grouping", () => {
  assert.equal(amountInWords(0), "Rupees Zero Only");
  assert.equal(amountInWords(168000), "Rupees One Thousand Six Hundred Eighty Only");
  assert.equal(amountInWords(12345650), "Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Paise Fifty Only");
  assert.equal(amountInWords(1_00_00_000_00), "Rupees One Crore Only");
  assert.equal(amountInWords(101), "Rupees One and Paise One Only");
  assert.equal(integerInWords(1_000_000_000), "One Hundred Crore");
  assert.throws(() => integerInWords(-1));
});

const line = (o) => ({ description: "x", sac: "999999", quantity: 1, unitMinor: 0, taxableMinor: 0, exempt: false, cgstRate: 0, cgstMinor: 0, sgstRate: 0, sgstMinor: 0, igstRate: 0, igstMinor: 0, totalMinor: 0, ...o });
const base = {
  invoiceNumber: "KA01/25-26/000042", issuedAt: "2026-03-10T05:30:00Z", currency: "INR", placeOfSupply: "Karnataka",
  seller: { name: "Test Clinic", gstin: "29AAAAA0000A1Z5", address: "1 Test Road", phone: "+910000000000" },
  buyer: { name: "Test Patient", mrn: "MRN-1", gstin: null }, payments: [], reprintCount: 0, copy: "original",
};
const when = (iso) => `T(${iso})`;

const intra = {
  ...base,
  lines: [
    line({ description: "Consultation", taxableMinor: 50000, exempt: true, totalMinor: 50000 }),
    line({ description: "Cosmetic procedure", taxableMinor: 100000, cgstRate: 9, cgstMinor: 9000, sgstRate: 9, sgstMinor: 9000, totalMinor: 118000 }),
  ],
  totals: { taxableMinor: 100000, exemptMinor: 50000, cgstMinor: 9000, sgstMinor: 9000, igstMinor: 0, discountMinor: 0, roundOffMinor: 0, totalMinor: 168000, paidMinor: 168000, balanceMinor: 0 },
  payments: [{ method: "Cash", reference: null, amountMinor: 168000, paidAt: "2026-03-10T05:31:00Z" }],
};

test("golden: exempt consult + 18% cosmetic in-state = CGST 9% + SGST 9% on the taxable line only", () => {
  assert.equal(documentProblem(intra), null);
  const v = buildInvoiceView(intra, when);
  assert.equal(v.number, "KA01/25-26/000042");
  assert.equal(v.seller.gstin, "29AAAAA0000A1Z5");
  assert.deepEqual(v.rows.map((r) => [r.kind, r.sac, r.tax]), [["Exempt", "999999", "—"], ["Taxable", "999999", "CGST 9% + SGST 9% = ₹180.00"]]);
  assert.deepEqual(v.totals.map((t) => [t.label, t.value]), [
    ["Taxable value", "₹1,000.00"], ["Exempt value", "₹500.00"], ["CGST", "₹90.00"], ["SGST", "₹90.00"], ["Total", "₹1,680.00"], ["Paid", "₹1,680.00"], ["Balance due", "₹0.00"],
  ]);
  assert.equal(v.words, "Rupees One Thousand Six Hundred Eighty Only");
  assert.equal(v.copyNote, null);
});

test("golden: inter-state shows IGST, no CGST/SGST rows", () => {
  const d = { ...base, placeOfSupply: "Kerala",
    lines: [line({ taxableMinor: 100000, igstRate: 18, igstMinor: 18000, totalMinor: 118000 })],
    totals: { taxableMinor: 100000, exemptMinor: 0, cgstMinor: 0, sgstMinor: 0, igstMinor: 18000, discountMinor: 0, roundOffMinor: 0, totalMinor: 118000, paidMinor: 0, balanceMinor: 118000 } };
  assert.equal(documentProblem(d), null);
  const v = buildInvoiceView(d, when);
  assert.equal(v.rows[0].tax, "IGST 18% = ₹180.00");
  assert.deepEqual(v.totals.map((t) => t.label), ["Taxable value", "IGST", "Total", "Paid", "Balance due"]);
});

test("golden: fully exempt invoice has no tax rows", () => {
  const d = { ...base, lines: [line({ taxableMinor: 50000, exempt: true, totalMinor: 50000 })],
    totals: { taxableMinor: 0, exemptMinor: 50000, cgstMinor: 0, sgstMinor: 0, igstMinor: 0, discountMinor: 0, roundOffMinor: 0, totalMinor: 50000, paidMinor: 50000, balanceMinor: 0 } };
  assert.equal(documentProblem(d), null);
  assert.deepEqual(buildInvoiceView(d, when).totals.map((t) => t.label), ["Taxable value", "Exempt value", "Total", "Paid", "Balance due"]);
});

test("totals that do not add up to the paisa are refused", () => {
  assert.equal(documentProblem({ ...intra, totals: { ...intra.totals, totalMinor: 168001, balanceMinor: 1 - 0 } }), "total");
  assert.equal(documentProblem({ ...intra, totals: { ...intra.totals, cgstMinor: 9001 } }), "tax");
  assert.equal(documentProblem({ ...intra, totals: { ...intra.totals, balanceMinor: 5 } }), "balance");
  assert.equal(documentProblem({ ...intra, totals: { ...intra.totals, exemptMinor: 49999 } }), "taxable");
});

test("round-off and discount rows; a reprint is marked 'Duplicate copy' with the reprint time", () => {
  const d = { ...intra, copy: "duplicate", reprintCount: 1, reprintedAt: "2026-03-11T04:00:00Z",
    totals: { ...intra.totals, discountMinor: 100, roundOffMinor: 100 } };
  const v = buildInvoiceView(d, when);
  assert.equal(v.copyNote, "Duplicate copy · reprinted T(2026-03-11T04:00:00Z)");
  assert.deepEqual(v.totals.slice(4, 6).map((t) => [t.label, t.value]), [["Discount", "-₹1.00"], ["Round-off", "+₹1.00"]]);
  assert.deepEqual(v.payments, [{ label: "Cash · T(2026-03-10T05:31:00Z)", value: "₹1,680.00" }]);
});
