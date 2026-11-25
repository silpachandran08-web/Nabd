// PHL-023: Schedule H1 / X at the counter. The second-confirmation rule, the "no registered pharmacist on duty" banner, what the
// sale sends, and the wiring of the POS and the full counter sale screen. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { ackReason, ackRefusedCode, needsAck, pharmacistWarning, ACK_MAX } from "../../app/components/checkout/scheduleAck.ts";
import { needsRx, saleBody } from "../../app/(clinic)/pharmacy/pos/posCart.ts";

const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const msg = (lang) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${lang}/checkout.json`, import.meta.url), "utf8"));
// GET /pharmacy/counter-rules for an Indian clinic with nobody registered on duty
const rules = { pharmacistOnDuty: false, ackClasses: ["IN_H1"], pharmacistWarningClasses: ["IN_H1", "IN_X"] };
const onDuty = { ...rules, pharmacistOnDuty: true };
const line = (scheduleClass, over = {}) => ({ item: { id: "i1", code: `PHM-${scheduleClass}`, name: "Item", generic: null, gtin: null, price: 10, scheduleClass, isRx: scheduleClass !== "OTC", onHand: 9 }, qty: 1, ...over });

test("AC1: an IN_H1 line waits for the confirmation; a reason given clears it; rule matrix per class", () => {
  assert.equal(needsAck(line("IN_H1"), rules), true);
  assert.equal(needsAck(line("IN_H1", { ackReason: "Checked the prescription" }), rules), false);
  // the matrix: only the classes the server lists ask for it
  assert.deepEqual(["OTC", "IN_H", "IN_H1", "IN_X", "IN_NDPS", "SA_CONTROLLED"].map((c) => needsAck(line(c), rules)), [false, false, true, false, false, false]);
  // the confirmation is not the prescription: a confirmed H1 line without a linked prescription still waits for one
  assert.equal(needsRx(line("IN_H1", { ackReason: "Checked the prescription" })), true);
  // another market's rules (nothing flagged) ask for nothing
  assert.equal(needsAck(line("IN_H1"), { pharmacistOnDuty: true, ackClasses: [], pharmacistWarningClasses: [] }), false);
});

test("AC1: a reason is 3 to 200 characters after trimming, the same bounds the server enforces", () => {
  assert.equal(ackReason("  Checked the prescription "), "Checked the prescription");
  assert.equal(ackReason("yes"), "yes");
  assert.equal(ackReason("ok"), null);
  assert.equal(ackReason("  a   "), null);
  assert.equal(ackReason(""), null);
  assert.equal(ackReason("x".repeat(ACK_MAX)), "x".repeat(ACK_MAX));
  assert.equal(ackReason("x".repeat(ACK_MAX + 1)), null);
});

test("AC1: the sale carries the reason on its own line only", () => {
  const body = saleBody([line("IN_H1", { paperRxLineId: "paper-1", ackReason: "Checked the prescription" }), line("OTC")]);
  assert.deepEqual(body.lineItems, [
    { chargeCode: "PHM-IN_H1", quantity: 1, paperRxLineId: "paper-1", scheduleAckReason: "Checked the prescription" },
    { chargeCode: "PHM-OTC", quantity: 1 }]);
});

test("AC1: the POS shows the dialog on the line and offers no Pay button (and F9 does nothing) until every flagged line is confirmed", () => {
  const screen = src("(clinic)/pharmacy/pos/PosScreen.tsx");
  assert.match(screen, /api\.get<CounterRules>\("\/pharmacy\/counter-rules"\)/);
  assert.match(screen, /if \(!index \|\| !rules\) return <main className=\{co\.page\}><Skeleton/, "nothing is sold before the rules are known");
  assert.match(screen, /const waitingAck = !!rules && cart\.some\(\(l\) => needsAck\(l, rules\)\)/);
  assert.match(screen, /needsAck\(l, rules\) && linking !== code && paperFor !== code && <ScheduleAckDialog /);
  assert.match(screen, /cart\.length > 0 && !waitingRx && !waitingAck && \(\s*<button ref=\{payRef\}/, "no pay button while a line waits");
  assert.match(screen, /if \(busy \|\| cart\.length === 0 \|\| waitingRx \|\| waitingAck\) return;/, "completeSale");
  assert.match(screen, /if \(invoice \|\| cart\.length === 0 \|\| waitingRx \|\| waitingAck\) return;/, "F9 / pay");
  const dialog = src("components/checkout/ScheduleAckDialog.tsx");
  assert.match(dialog, /role="dialog"/);
  assert.match(dialog, /const r = ackReason\(reason\);\s*if \(r\) onConfirm\(r\);\s*else setBad\(true\);/, "no confirmation without a reason");
  // NFR (proxy, not a timed run): the last reason is prefilled and selected, so a repeat confirmation is one Enter
  assert.match(screen, /<ScheduleAckDialog itemName=\{l\.item\.name\} initial=\{lastAck\}/);
  assert.match(dialog, /autoFocus/);
  assert.match(dialog, /onFocus=\{\(e\) => e\.target\.select\(\)\}/);
});

test("AC1: the full counter sale opens the same dialog on the line the server refused, for a role without the pharmacy rules", () => {
  assert.equal(ackRefusedCode({ type: "https://nabd.health/problems/schedule-ack-required", chargeCode: "PHM-7" }), "PHM-7");
  assert.equal(ackRefusedCode({ type: "https://nabd.health/problems/rx-required", chargeCode: "PHM-7" }), null);
  assert.equal(ackRefusedCode({ type: "https://nabd.health/problems/schedule-ack-required" }), null);
  assert.equal(ackRefusedCode(null), null);
  const page = src("(clinic)/checkout/otc/page.tsx");
  assert.match(page, /if \(e instanceof ApiError\) setAckFor\(ackRefusedCode\(e\.problem\)\);/);
  assert.match(page, /ackFor === i\.chargeCode && \(\s*<ScheduleAckDialog /);
  assert.match(page, /\.\.\.\(i\.scheduleAckReason \? \{ scheduleAckReason: i\.scheduleAckReason \} : \{\}\)/);
  assert.doesNotMatch(page, /counter-rules/, "billing:create alone cannot read the pharmacy rules: this screen must not ask for them");
});

test("AC3: the banner shows on H1 / X lines while nobody registered is on duty, and only then", () => {
  assert.equal(pharmacistWarning([line("OTC"), line("IN_H1")], rules), true);
  assert.equal(pharmacistWarning([line("IN_X")], rules), true);
  assert.equal(pharmacistWarning([line("IN_H1", { ackReason: "Checked the prescription" })], rules), true, "confirming the line does not dismiss it");
  assert.equal(pharmacistWarning([line("IN_H1"), line("IN_X")], onDuty), false);
  assert.equal(pharmacistWarning([line("OTC"), line("IN_H")], rules), false);
  assert.equal(pharmacistWarning([], rules), false);
  const screen = src("(clinic)/pharmacy/pos/PosScreen.tsx");
  assert.match(screen, /\{pharmacistWarning\(cart, rules\) && <p className=\{co\.errorState\} role="alert" data-testid="pos-no-pharmacist">\{t\("noPharmacist"\)\}<\/p>\}/);
  assert.doesNotMatch(screen, /dismiss|setRules\(null\)/i, "persistent: there is no way to close it");
  assert.match(msg("en").pos.noPharmacist, /^No registered pharmacist on duty/);
});

test("AC4: an OTC cart needs no confirmation, raises no banner and sends nothing extra", () => {
  const cart = [line("OTC"), line("OTC", { item: { ...line("OTC").item, code: "PHM-B" } })];
  assert.equal(cart.some((l) => needsAck(l, rules)), false);
  assert.equal(pharmacistWarning(cart, rules), false);
  assert.deepEqual(saleBody(cart).lineItems, [{ chargeCode: "PHM-OTC", quantity: 1 }, { chargeCode: "PHM-B", quantity: 1 }]);
});

test("every string the dialog and the screen ask for exists in en and hi", () => {
  const dialog = src("components/checkout/ScheduleAckDialog.tsx");
  const keys = [...dialog.matchAll(/\bt\("([A-Za-z]+)"/g)].map((m) => m[1]);
  assert.ok(keys.length >= 6);
  for (const lang of ["en", "hi"]) {
    for (const k of keys) assert.ok(msg(lang).scheduleAck[k]?.trim(), `${lang} checkout.scheduleAck.${k}`);
    for (const k of ["noPharmacist", "ackWait", "ackDone"]) assert.ok(msg(lang).pos[k]?.trim(), `${lang} checkout.pos.${k}`);
  }
});
