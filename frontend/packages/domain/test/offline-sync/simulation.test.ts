// PLT-031: the replay simulation behind docs/architecture/offline-sync.md. 1,000 reconnects with lost, repeated, late
// and out-of-order requests must end with 0 duplicate bills, 0 lost payments and every conflict surfaced.
import { expect, test, vi } from "vitest";
import { simulate, violations } from "./prototype.ts";

vi.setConfig({ testTimeout: 60_000 }); // CPU-bound: about half a second each, but a busy machine once stalled one past the 5 s default

const RECONNECTS = 1000;
const SEED = 31;

test("1,000 reconnects: 0 duplicate bills, 0 lost payments, every conflict surfaced", () => {
  const run = simulate(SEED, RECONNECTS);
  expect(violations(run)).toEqual([]);
  // the run must really have been hostile, otherwise a clean result proves nothing
  const s = run.stats;
  for (const k of ["timeouts", "lateDeliveries", "stalePages", "idempotentReplays", "durableHits", "keysExpired", "inProgress", "dependencyRetries",
    "failedBeforeCommit", "lostBookkeeping", "billsStoppedByBlock", "duplicateCheckIns", "duplicateBills", "paymentsHeld", "oversold"]) {
    expect(s[k], k).toBeGreaterThan(0);
  }
  // the figures quoted in the design document; they change only if the model or the seed changes
  expect(s).toMatchObject({ checkIns: 1261, bills: 2753, payments: 3596, paidMinor: 1517270, duplicateBills: 800, paymentsHeld: 1059, durableHits: 164 });
});

test("the same seed gives the same run", () => {
  expect(simulate(SEED, 200).stats).toEqual(simulate(SEED, 200).stats);
});

test.each([1, 2, 3, 5, 8, 13, 21, 34, 55, 89])("seed %i is clean too", (seed) => {
  expect(violations(simulate(seed, RECONNECTS))).toEqual([]);
});

// Each guard switched off: the checks must notice. This is what shows the clean runs above mean something.
test("without the durable op id, a re-run after the idempotency row is gone makes duplicate bills", () => {
  expect(violations(simulate(SEED, RECONNECTS, { durableOpId: false })).join("\n")).toMatch(/duplicate bill/);
});

test("a device that forgets a command once it is sent loses payments", () => {
  expect(violations(simulate(SEED, RECONNECTS, { ackOnSend: true })).join("\n")).toMatch(/lost payment/);
});

test("conflicts carried only in one HTTP answer are not all surfaced", () => {
  expect(violations(simulate(SEED, RECONNECTS, { durableConflicts: false })).join("\n")).toMatch(/conflict not surfaced/);
});

test("a delta feed without the safe horizon leaves a device's copy behind", () => {
  expect(violations(simulate(SEED, RECONNECTS, { safeHorizon: false })).join("\n")).toMatch(/differ from the server/);
});
