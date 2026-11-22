// PHL-020: the "Paper Rx" dialog's rules, upload flow and wiring. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { paperRxProblem, photoProblem, paperRxErrorKey, uploadPaperRxPhoto } from "../../app/components/checkout/paperRx.ts";
import { unlinkedRx, rxErrorMessage } from "../../app/components/checkout/rxLink.ts";

const app = new URL("../../", import.meta.url).pathname;
const form = { prescriberName: "Dr A", prescriberRegNo: "REG-1", rxDate: "2026-11-20", qty: 10, hasPhoto: false, duplicateCopyRetained: false };

test("AC1: doctor name, registration no, date and quantity are all needed; the date cannot be in the future", () => {
  assert.equal(paperRxProblem(form, "2026-11-20", 2, false), null);
  assert.equal(paperRxProblem({ ...form, prescriberName: "  " }, "2026-11-20", 2, false), "needName");
  assert.equal(paperRxProblem({ ...form, prescriberRegNo: "" }, "2026-11-20", 2, false), "needRegNo");
  assert.equal(paperRxProblem({ ...form, rxDate: "" }, "2026-11-20", 2, false), "needDate");
  assert.equal(paperRxProblem({ ...form, rxDate: "2026-11-21" }, "2026-11-20", 2, false), "futureDate");
  assert.equal(paperRxProblem({ ...form, qty: 0 }, "2026-11-20", 2, false), "needQty");
  assert.equal(paperRxProblem({ ...form, qty: 1.5 }, "2026-11-20", 1, false), "needQty");
});

test("AC2: a prescription for fewer units than the bill line is caught before the sale (boundary: equal passes)", () => {
  assert.equal(paperRxProblem({ ...form, qty: 4 }, "2026-11-20", 5, false), "qtyBelowCart");
  assert.equal(paperRxProblem({ ...form, qty: 5 }, "2026-11-20", 5, false), null);
});

test("AC3: once the item is known to keep a duplicate copy, the tick and the photo are both mandatory", () => {
  assert.equal(paperRxProblem({ ...form, hasPhoto: true }, "2026-11-20", 2, true), "copyNeeded");
  assert.equal(paperRxProblem({ ...form, duplicateCopyRetained: true }, "2026-11-20", 2, true), "copyNeeded");
  assert.equal(paperRxProblem({ ...form, hasPhoto: true, duplicateCopyRetained: true }, "2026-11-20", 2, true), null);
  assert.equal(paperRxErrorKey({ type: "https://x/problems/rx-copy-required" }), "copyNeeded");
});

test("photo type and size are checked before anything is sent", () => {
  assert.equal(photoProblem({ type: "image/jpeg", size: 1000 }), null);
  assert.equal(photoProblem({ type: "image/heic", size: 1000 }), "photoType");
  assert.equal(photoProblem({ type: "image/png", size: 0 }), "photoSize");
  assert.equal(photoProblem({ type: "image/png", size: 20 * 1024 * 1024 + 1 }), "photoSize");
});

/** A fake client that records calls and answers the scan poll with the given statuses in turn. */
function fake(statuses, { failPut = false } = {}) {
  const calls = [];
  return {
    calls,
    post: async (path, body) => { calls.push(["POST", path, body]); return path.endsWith("/complete") ? { fileId: "f1" } : { uploadId: "u1", putUrl: "/files-blob/object?m=PUT" }; },
    put: async (path, _b, opts) => { calls.push(["PUT", path, opts.headers["Content-Type"]]); if (failPut) throw new Error("net"); },
    get: async (path) => { calls.push(["GET", path]); return { status: statuses.shift() ?? "pending_scan" }; },
  };
}
const noWait = async () => {};

test("AC1: the photo goes through the pharmacy upload endpoints (not /files) and resolves to the file id once the scan passes", async () => {
  const c = fake(["pending_scan", "available"]);
  assert.equal(await uploadPaperRxPhoto(c, { type: "image/jpeg", size: 2048 }, noWait), "f1");
  assert.deepEqual(c.calls.map((x) => `${x[0]} ${x[1]}`), [
    "POST /pharmacy/paper-prescriptions/uploads", "PUT /../files-blob/object?m=PUT", "POST /pharmacy/paper-prescriptions/uploads/u1/complete",
    "GET /pharmacy/paper-prescriptions/uploads/f1", "GET /pharmacy/paper-prescriptions/uploads/f1"]);
  assert.deepEqual(c.calls[0][2], { contentType: "image/jpeg", sizeBytes: 2048 });
  assert.equal(c.calls[1][2], "application/octet-stream");
});

test("AC4: an infected or wrong-type photo never yields a document id, and the reason is named", async () => {
  await assert.rejects(uploadPaperRxPhoto(fake(["infected"]), { type: "image/jpeg", size: 10 }, noWait), /^Error: photoInfected$/);
  await assert.rejects(uploadPaperRxPhoto(fake(["rejected_type"]), { type: "image/png", size: 10 }, noWait), /^Error: photoRejected$/);
  await assert.rejects(uploadPaperRxPhoto(fake([]), { type: "image/png", size: 10 }, noWait), /^Error: photoStillScanning$/);
  await assert.rejects(uploadPaperRxPhoto(fake([], { failPut: true }), { type: "image/png", size: 10 }, noWait), /^Error: photoUploadFailed$/);
  const untouched = fake([]);
  await assert.rejects(uploadPaperRxPhoto(untouched, { type: "text/plain", size: 10 }, noWait), /^Error: photoType$/);
  assert.equal(untouched.calls.length, 0);
  assert.equal(paperRxErrorKey({ type: "https://x/problems/document-not-clean", scanStatus: "infected" }), "photoInfected");
  assert.equal(paperRxErrorKey({ type: "https://x/problems/document-not-clean", scanStatus: "pending_scan" }), "photoStillScanning");
  assert.equal(paperRxErrorKey({ type: "https://x/problems/document-not-clean", scanStatus: "missing" }), "photoUnavailable");
  assert.equal(paperRxErrorKey({ type: "https://x/problems/insufficient-stock" }), null);
});

test("a paper link unblocks an Rx line like a signed prescription does, and the new refusals are named at checkout", () => {
  const charges = [{ code: "RX1", isRx: true }];
  assert.deepEqual(unlinkedRx([{ chargeCode: "RX1" }], charges), ["RX1"]);
  assert.deepEqual(unlinkedRx([{ chargeCode: "RX1", paperRxLineId: "l1" }], charges), []);
  assert.match(rxErrorMessage({ type: "https://x/problems/rx-quantity-exhausted", detail: "has 0 left" }), /no quantity left.*0 left/);
  assert.match(rxErrorMessage({ type: "https://x/problems/document-not-clean", detail: "failed the virus scan" }), /photo cannot be used.*virus scan/);
  assert.match(rxErrorMessage({ type: "https://x/problems/rx-copy-required" }), /duplicate copy/);
  assert.match(rxErrorMessage({ type: "https://x/problems/rx-item-mismatch" }), /different item/);
});

test("every message key the dialog can show exists in en and hi, with the same placeholders", () => {
  const msg = (l) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/checkout.json`, import.meta.url), "utf8"));
  const en = msg("en"), hi = msg("hi");
  const keys = ["needName", "needRegNo", "needDate", "futureDate", "needQty", "qtyBelowCart", "copyNeeded", "photoType", "photoSize", "photoUploadFailed",
    "photoInfected", "photoRejected", "photoStillScanning", "photoUnavailable", "photoUsed", "expired", "itemMismatch", "uploading", "saving", "saveFailed"];
  for (const k of keys) assert.ok(en.paperRx[k] && hi.paperRx[k], k);
  assert.deepEqual(Object.keys(hi.paperRx).sort(), Object.keys(en.paperRx).sort());
  const slots = (s) => (s.match(/\{[a-z]+\}/g) ?? []).sort();
  for (const k of Object.keys(en.paperRx)) assert.deepEqual(slots(hi.paperRx[k]), slots(en.paperRx[k]), k);
  for (const ns of ["otc", "pos"]) for (const k of ["paperRx", "paperRxLinked"]) { assert.ok(en[ns][k] && hi[ns][k], `${ns}.${k}`); assert.deepEqual(slots(hi[ns][k]), slots(en[ns][k]), `${ns}.${k}`); }
});

test("AC5 / wiring: the Paper Rx control is absent without pharmacy:dispense and the sale sends paperRxLineId", () => {
  const page = readFileSync(`${app}app/(clinic)/checkout/otc/page.tsx`, "utf8");
  assert.match(page, /const canPaperRx = can\("pharmacy:dispense"\)/);
  assert.match(page, /\{canPaperRx && waitingForRx\.includes\(i\.chargeCode\)/); // rendered only with the grant: absent, not disabled
  assert.match(page, /paperRxLineId: i\.paperRxLineId/);
  const dialog = readFileSync(`${app}app/components/checkout/PaperRxDialog.tsx`, "utf8");
  assert.ok(!/\/files\/uploads/.test(dialog), "the dialog must not call the clinical upload endpoints");
  assert.match(dialog, /capture="environment"/);
});
