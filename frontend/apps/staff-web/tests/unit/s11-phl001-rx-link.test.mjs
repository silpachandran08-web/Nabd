// PHL-001: Rx link helpers. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { unlinkedRx, autoLink, rxErrorMessage } from "../../app/components/checkout/rxLink.ts";

const charges = [{ code: "RX1", isRx: true }, { code: "OTC1", isRx: false }, { code: "SVC" }];

test("only Rx lines without a link block checkout", () => {
  assert.deepEqual(unlinkedRx([{ chargeCode: "RX1" }, { chargeCode: "OTC1" }, { chargeCode: "SVC" }], charges), ["RX1"]);
  assert.deepEqual(unlinkedRx([{ chargeCode: "RX1", prescriptionItemId: "p1" }, { chargeCode: "OTC1" }], charges), []);
  assert.deepEqual(unlinkedRx([], charges), []);
});

test("auto-link picks a same-name line with quantity left, never an exhausted one", () => {
  const pres = [
    { prescriptionItemId: "used", drugName: "Drug A", remainingQty: 0 },
    { prescriptionItemId: "ok", drugName: "drug a", remainingQty: 4 },
  ];
  assert.equal(autoLink("Drug A 10 tabs", pres), "ok");
  assert.equal(autoLink("Something else", pres), null);
  assert.equal(autoLink("Drug A", [{ prescriptionItemId: "x", drugName: "Drug A", remainingQty: 0 }]), null);
  assert.equal(autoLink("Drug A", [{ prescriptionItemId: "x", drugName: "  ", remainingQty: 3 }]), null);
});

test("rx problems name the rule; other problems fall through", () => {
  assert.match(rxErrorMessage({ type: "https://x/problems/rx-required", detail: "RX1 is prescription-only." }), /prescription-only.*RX1/);
  assert.match(rxErrorMessage({ type: "https://x/problems/rx-quantity-exhausted" }), /no quantity left/);
  assert.match(rxErrorMessage({ type: "https://x/problems/rx-patient-mismatch" }), /different patient/);
  assert.match(rxErrorMessage({ type: "https://x/problems/rx-not-signed" }), /draft/);
  assert.equal(rxErrorMessage({ type: "https://x/problems/insufficient-stock" }), null);
  assert.equal(rxErrorMessage({}), null);
});
