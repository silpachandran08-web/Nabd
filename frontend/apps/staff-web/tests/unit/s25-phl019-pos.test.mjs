// PHL-019: fast counter sale. Client index search (5,000 items, brand / generic / barcode, under 100 ms), barcode add,
// cart rules, split tender body, the batch label, the keystroke count of the J10 three-item cash sale, and the screen's gates.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { buildIndex, byBarcode, searchIndex } from "../../app/(clinic)/pharmacy/pos/posIndex.ts";
import { addItem, batchLabels, needsRx, saleBody, setQty, tenderSettles } from "../../app/(clinic)/pharmacy/pos/posCart.ts";
import { createWedge, registerScanTarget, WEDGE_MAX_GAP_MS } from "../../app/shell/barcodeWedge.ts";
import { findItem, counterRouteState } from "../../app/shell/navRegistry.ts";

const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const gtin = (n) => String(8900000000000 + n);
const BRANDS = ["Crocin", "Dolo", "Azee", "Pantocid", "Telma", "Glycomet", "Allegra", "Zifi", "Combiflam", "Shelcal"];
const GENERICS = ["Paracetamol", "Azithromycin", "Pantoprazole", "Telmisartan", "Metformin", "Fexofenadine", "Cefixime", "Ibuprofen", "Calcium", "Amoxicillin"];
/** 5,000 rows as GET /pharmacy/items/index returns them (ordered by name). */
const rows = Array.from({ length: 5000 }, (_, n) => ({
  id: `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`, code: `PHM-${n}`, name: `${BRANDS[n % 10]} ${100 + n}`, generic: GENERICS[(n * 7) % 10],
  gtin: n % 3 === 0 ? null : gtin(n), price: 10 + (n % 90), class: n % 11 === 7 ? "IN_H" : "OTC", isRx: n % 11 === 7, onHand: n % 13 === 6 ? 0 : 40,
})).sort((a, b) => a.name.localeCompare(b.name));
const ix = buildIndex(rows);
const item = (over = {}) => ({ id: "i1", code: "PHM-A", name: "Crocin 650", generic: "Paracetamol", gtin: "8901234567890", price: 30, scheduleClass: "OTC", isRx: false, onHand: 5, ...over });

test("AC1: with 5,000 items, three typed characters find matches by brand, generic or barcode in under 100 ms", () => {
  assert.equal(ix.items.length, 5000);
  const queries = ["cro", "dol", "aze", "par", "azi", "met", "890", "tel 1", "amox", gtin(1234)];
  // median of three runs per query: the CI machine runs many jobs at once and a single cold call measures the scheduler, not the search
  let worst = 0;
  for (const q of queries) {
    const runs = [0, 1, 2].map(() => { const t0 = performance.now(); searchIndex(ix, q); return performance.now() - t0; }).sort((a, b) => a - b);
    worst = Math.max(worst, runs[1]);
    const hits = searchIndex(ix, q);
    assert.ok(hits.length > 0 && hits.length <= 8, `${q}: ${hits.length} hits`);
  }
  assert.ok(worst < 100, `slowest search took ${worst.toFixed(1)} ms`);
  assert.ok(searchIndex(ix, "cro").every((h) => h.name.toLowerCase().startsWith("cro")), "brand prefix");
  assert.ok(searchIndex(ix, "azi").every((h) => h.generic === "Azithromycin"), "generic prefix");
  assert.ok(searchIndex(ix, "890").every((h) => h.gtin?.startsWith("890")), "barcode prefix");
  assert.deepEqual(searchIndex(ix, "tel 1").map((h) => /^Telma 1/.test(h.name)), Array(8).fill(true), "every word must match");
  assert.deepEqual(searchIndex(ix, "zzz"), []);
  assert.deepEqual(searchIndex(ix, "  "), []);
});

test("AC1: ranking: the exact barcode first, then brand-name matches, then generic matches; rows that cannot be sold are dropped", () => {
  const small = buildIndex([
    { id: "1", code: "A", name: "Paracip 500", generic: "Paracetamol", gtin: "8901111111111", price: 1, onHand: 1 },
    { id: "2", code: "B", name: "Crocin 650", generic: "Paracetamol", gtin: "8902222222222", price: 2, onHand: 1 },
    { id: "3", code: "C", name: "Azee 500", generic: "Azithromycin", price: 3, onHand: 0 },
    { id: "4", name: "No code" }, { code: "X", name: "No id" }, { id: "5", code: "Y" },
  ]);
  assert.deepEqual(small.items.map((i) => i.code), ["A", "B", "C"]);
  assert.deepEqual(small.items[2], { id: "3", code: "C", name: "Azee 500", generic: "Azithromycin", gtin: null, price: 3, scheduleClass: "OTC", isRx: false, onHand: 0 });
  assert.deepEqual(searchIndex(small, "para").map((i) => i.code), ["A", "B"], "name prefix before generic-only");
  assert.deepEqual(searchIndex(small, "8902222222222").map((i) => i.code), ["B"]);
  assert.deepEqual(searchIndex(small, "500").map((i) => i.code), ["A", "C"], "digits also search names (strengths)");
  assert.deepEqual(searchIndex(small, "PARA", 1).map((i) => i.code), ["A"], "case-insensitive, limited");
});

test("AC2: a scanned GTIN resolves to its item (UPC-A / EAN-13 / GTIN-14 zero padding ignored); an unknown or too-short code does not", () => {
  const wanted = rows.find((r) => r.gtin === gtin(1234));
  assert.equal(byBarcode(ix, gtin(1234)).code, wanted.code);
  assert.equal(byBarcode(ix, `0${gtin(1234)}`).code, wanted.code, "GTIN-14 form of the same EAN-13");
  const upc = buildIndex([{ id: "1", code: "U", name: "Upc item", gtin: "012345678905", onHand: 1 }]);
  assert.equal(byBarcode(upc, "0012345678905").code, "U", "a scanner that sends the EAN-13 form of a UPC-A");
  assert.equal(byBarcode(ix, "8999999999999"), null);
  assert.equal(byBarcode(ix, "123"), null);
  assert.equal(byBarcode(ix, ""), null);
});

test("AC2: a keyboard-wedge scan outside a field reaches the screen's scan target and adds the item with quantity 1, then 2", () => {
  let cart = [];
  const one = buildIndex([{ id: "i1", code: "PHM-A", name: "Crocin 650", gtin: "8901234567890", price: 30, onHand: 5 }]);
  const off = registerScanTarget((code) => { const hit = byBarcode(one, code); if (hit) cart = addItem(cart, hit).cart; });
  const wedge = createWedge();
  const type = (text, t0) => { let t = t0; for (const ch of text) wedge({ key: ch, timeStamp: (t += 5), target: null }); return wedge({ key: "Enter", timeStamp: t + 5, target: null }); };
  assert.equal(type("8901234567890", 1000), true);
  assert.equal(type("8901234567890", 5000), true);
  assert.ok(WEDGE_MAX_GAP_MS >= 5);
  off();
  assert.deepEqual(cart.map((l) => [l.item.code, l.qty]), [["PHM-A", 2]]);
});

test("cart: adding never goes above the stock on hand, an out-of-stock item is not added, quantity 0 removes the line", () => {
  let r = addItem([], item({ onHand: 2 }));
  assert.equal(r.added, true);
  r = addItem(r.cart, item({ onHand: 2 }));
  assert.deepEqual([r.added, r.cart[0].qty], [true, 2]);
  const full = addItem(r.cart, item({ onHand: 2 }));
  assert.deepEqual([full.added, full.cart[0].qty], [false, 2]);
  assert.equal(addItem([], item({ onHand: 0 })).added, false);
  assert.equal(setQty(r.cart, "PHM-A", 99)[0].qty, 2, "capped at stock");
  assert.equal(setQty(r.cart, "PHM-A", 1)[0].qty, 1);
  assert.deepEqual(setQty(r.cart, "PHM-A", 0), []);
  const two = addItem(r.cart, item({ code: "PHM-B", name: "Dolo" })).cart;
  assert.deepEqual(two.map((l) => l.item.code), ["PHM-A", "PHM-B"], "a second item is a second line");
});

test("Rx-only items stay gated (PHL-001): the line waits until a prescription is linked; the PHL-020 paper-Rx line id is the other way through", () => {
  const rx = { item: item({ isRx: true, scheduleClass: "IN_H" }), qty: 1 };
  assert.equal(needsRx(rx), true);
  assert.equal(needsRx({ item: item(), qty: 1 }), false);
  assert.equal(needsRx({ ...rx, prescriptionItemId: "p1" }), false);
  assert.equal(needsRx({ ...rx, paperRxLineId: "paper-1" }), false);
  assert.deepEqual(saleBody([{ ...rx, prescriptionItemId: "p1" }]).lineItems, [{ chargeCode: "PHM-A", quantity: 1, prescriptionItemId: "p1" }]);
  assert.deepEqual(saleBody([{ ...rx, paperRxLineId: "paper-1" }]).lineItems, [{ chargeCode: "PHM-A", quantity: 1, paperRxLineId: "paper-1" }]);
  const screen = src("(clinic)/pharmacy/pos/PosScreen.tsx");
  // T1-BUG-3: the same dialog as /checkout/otc is mounted on an Rx-only line, next to "Link prescription", only with pharmacy:dispense
  assert.match(screen, /import PaperRxDialog/);
  assert.match(screen, /canPaperRx && needsRx\(l\) && paperFor !== code/);
  assert.match(screen, /<PaperRxDialog item=\{\{ id: l\.item\.id/);
  assert.match(screen, /paperRxLineId: paper\.paperRxLineId/);
  assert.doesNotMatch(screen + src("(clinic)/pharmacy/pos/posCart.ts"), /paperRxId\b/, "core only knows paperRxLineId");
  assert.match(screen, /cart\.length > 0 && !waitingRx && \(\s*<button ref=\{payRef\}/, "no pay button while an Rx line waits");
});

test("AC4: cash 200 + UPI 150 on a 350 bill settles it and is sent as two payment rows; any other sum does not settle", () => {
  const split = [{ id: 1, method: "cash", amount: "200" }, { id: 2, method: "upi", amount: "150.00" }];
  assert.equal(tenderSettles(split, 35000), true);
  const body = saleBody([{ item: item({ price: 175 }), qty: 2 }], split);
  assert.deepEqual(body, { lineItems: [{ chargeCode: "PHM-A", quantity: 2 }], payments: [{ method: "cash", amount: 200 }, { method: "upi", amount: 150 }] });
  assert.equal(tenderSettles([{ id: 1, method: "cash", amount: "200" }, { id: 2, method: "upi", amount: "100" }], 35000), false, "short");
  assert.equal(tenderSettles([{ id: 1, method: "cash", amount: "400" }], 35000), false, "over");
  assert.equal(tenderSettles([{ id: 1, method: "cash", amount: "abc" }], 35000), false);
  assert.equal(tenderSettles([{ id: 1, method: "cash", amount: "0.00" }], 0), false, "no total yet");
  assert.deepEqual(saleBody([{ item: item(), qty: 1 }], [{ id: 1, method: "cash", amount: "30.00" }, { id: 2, method: "card", amount: "" }]).payments,
    [{ method: "cash", amount: 30 }], "a blank row is not a payment");
  assert.equal("payments" in saleBody([{ item: item(), qty: 1 }]), false, "a role without billing:edit sends no payments");
});

test("AC2: the cart line names the first-expiry batch the server picked, with quantities when the line spans two", () => {
  const batches = [{ batchId: "b1", batchNo: "EARLY", expiryDate: "2027-01-31" }, { batchId: "b2", batchNo: "LATE", expiryDate: "2027-07-31" }, { batchId: "b0", expiryDate: null }];
  assert.deepEqual(batchLabels([{ batchId: "b1", qty: 5, expiryDate: "2027-01-31" }], batches), ["EARLY · exp 01/2027"]);
  assert.deepEqual(batchLabels([{ batchId: "b1", qty: 5, expiryDate: "2027-01-31" }, { batchId: "b2", qty: 3, expiryDate: "2027-07-31" }], batches),
    ["EARLY · exp 01/2027 ×5", "LATE · exp 07/2027 ×3"]);
  assert.deepEqual(batchLabels([{ batchId: "b0", qty: 1, expiryDate: null }], batches), [], "a Hybrid shelf has no batch details");
  assert.deepEqual(batchLabels([{ batchId: "b1", qty: 1, expiryDate: "2027-01-31" }], []), [], "batch list not loaded yet");
});

test("AC3 (proxy, not the timed J10 run): a three-item cash sale is 14 keystrokes and one sale request", () => {
  // per item: three characters + Enter (the wanted item is the first result); then F9 (cash for the full amount is prefilled) and Enter
  let cart = [], keys = 0;
  for (const typed of ["cro", "dol", "aze"]) {
    const hit = searchIndex(ix, typed)[0];
    assert.ok(hit && hit.onHand > 0 && !hit.isRx, `${typed}: the first result can be sold`);
    cart = addItem(cart, hit).cart;
    keys += typed.length + 1;
  }
  keys += 2; // F9, Enter
  assert.equal(cart.length, 3);
  assert.equal(keys, 14);
  const totalMinor = cart.reduce((s, l) => s + l.item.price * 100 * l.qty, 0);
  const cash = [{ id: 1, method: "cash", amount: (totalMinor / 100).toFixed(2) }]; // the screen's default tender
  assert.equal(tenderSettles(cash, totalMinor), true);
  assert.equal(saleBody(cart, cash).payments.length, 1);
  const screen = src("(clinic)/pharmacy/pos/PosScreen.tsx");
  assert.match(screen, /tenders \?\? \[\{ id: 1, method: "cash", amount: \(totalMinor \/ 100\)\.toFixed\(2\) \}\]/, "cash for the full amount is the default");
  assert.equal((screen.match(/api\.post<Invoice>\("\/billing\/otc-checkout"/g) ?? []).length, 1, "sale and payments are one request");
});

test("screen: keys, endpoints, gates and absent (not disabled) restricted controls", () => {
  const screen = src("(clinic)/pharmacy/pos/PosScreen.tsx"), page = src("(clinic)/pharmacy/pos/page.tsx"), cam = src("(clinic)/pharmacy/pos/CameraScan.tsx");
  for (const k of ['e.key === "F2"', 'e.key === "F9"', 'e.key === "Enter"', 'e.key === "+"', 'e.key === "-"']) assert.ok(screen.includes(k), k);
  for (const call of ['"/pharmacy/items/index"', '"/billing/quotes"', '"/pharmacy/picks"', '"/billing/otc-checkout"', "document?layout=thermal80&kind=invoice"]) assert.ok(screen.includes(call), call);
  assert.match(screen, /registerScanTarget\(\(code\) => scanRef\.current\(code\)\)/);
  assert.match(screen, /idempotencyKey: keys\.current\.get\("sale", body\)/);
  assert.match(page, /<AccessGate permission="billing:create"[^>]*>\s*<AccessGate permission="pharmacy:dispense"[^>]*>\s*<PosScreen \/>/, "the screen mounts only with both grants");
  assert.match(screen, /paying && canPay && totals && \(\s*<TenderRows/, "tender rows are absent without billing:edit");
  assert.match(screen, /saleBody\(cart, canPay \? rows : undefined\)/);
  assert.match(screen, /\{canView && \(\s*<section className=\{co\.card\} aria-label=\{t\("receipt"\)\}/, "no receipt panel without billing:view");
  assert.match(screen, /cameraScanSupported\(\) && <button/, "the camera button is absent where the browser cannot scan");
  assert.match(cam, /BarcodeDetector/);
  assert.match(cam, /"ean_13", "ean_8", "upc_a"/);
  assert.doesNotMatch(screen, /disabled=\{!can/);
});

test("nav: the pharmacist's POS item opens the new screen and still covers the older counter-sale page", () => {
  const pos = findItem("ph-pos");
  assert.equal(pos.href, "/pharmacy/pos");
  assert.ok(pos.active("/pharmacy/pos", null) && pos.active("/checkout/otc", null) && !pos.active("/pharmacy", null));
  assert.equal(findItem("ph-dispense").active("/pharmacy/pos", null), false);
  const ALL = { modules: [{ code: "pharmacy_inhouse", enabled: true }], killedFeatures: [], seatLimit: null };
  const perms = ["pharmacy:view", "pharmacy:edit", "pharmacy:dispense", "billing:view", "billing:create"];
  assert.equal(counterRouteState("pharmacist", { perms, owner: false, ent: ALL }, "/pharmacy/pos", true).kind, "open");
  assert.equal(counterRouteState("pharmacist", { perms: perms.filter((p) => p !== "billing:create"), owner: false, ent: ALL }, "/pharmacy/pos", true).kind, "blocked");
  assert.equal(counterRouteState("pharmacist", { perms, owner: false, ent: { ...ALL, modules: [{ code: "pharmacy_inhouse", enabled: false }] } }, "/pharmacy/pos", true).kind, "blocked");
});

test("i18n: every key the POS screen asks for exists in English and Hindi", () => {
  const cat = (l) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/checkout.json`, import.meta.url), "utf8")).pos;
  const en = cat("en"), hi = cat("hi");
  let n = 0;
  for (const f of ["PosScreen.tsx", "CameraScan.tsx", "page.tsx"]) {
    for (const m of src(`(clinic)/pharmacy/pos/${f}`).matchAll(/\bt\("([A-Za-z]+)"/g)) { n++; assert.ok(en[m[1]], `en ${m[1]}`); assert.ok(hi[m[1]]?.trim(), `hi ${m[1]}`); }
  }
  assert.ok(n > 50, `${n} keys checked`);
  assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort());
});

test("T1-BUG-4: a split-payment row wraps in the narrow third column and the amount box keeps at least 120 px", () => {
  const css = src("(clinic)/checkout/checkout.module.css");
  assert.match(css, /\.tenderRow \{[^}]*flex-wrap: wrap/);
  assert.match(css, /\.tenderAmount \{[^}]*flex: 1 1 120px; min-inline-size: 120px/);
});

test("T1-BUG-4 follow-up: the visit checkout keeps a payment on one line from 1280px; a narrow block (POS, phone) still wraps", () => {
  const css = src("(clinic)/checkout/checkout.module.css");
  assert.match(css, /\.tenders \{[^}]*container-type: inline-size/);
  assert.match(css, /@container \(min-width: 320px\) \{ \.tenderRow \{ flex-wrap: nowrap; \} \}/);
  assert.match(css, /@media \(min-width: 1280px\) \{ \.layout:has\(\.tenders\) \{ grid-template-columns: 220px 1fr 360px; \} \}/);
  assert.match(css, /@media \(min-width: 1340px\) \{ \.layout:has\(\.tenders\) \{ grid-template-columns: 280px 1fr 360px; \} \}/);
  // 360px column = 326px block: method (shrinks) + 120px amount + two 44px buttons + three 6px gaps fit; the POS column (226px) is under the 320px line
  assert.ok(360 - 34 >= 320 && 120 + 44 + 44 + 18 < 320);
  assert.match(css, /\.tenderAmount \{[^}]*min-inline-size: 120px/, "the amount never goes under 120px, wrapped or not");
});
