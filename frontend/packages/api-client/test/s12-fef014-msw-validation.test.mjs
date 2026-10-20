// FEF-014 AC3: MSW handlers in tests validate request bodies against the spec (openapi-backend); a violation fails the test.
import { test, before, after, afterEach } from "node:test";
import assert from "node:assert/strict";
import { setupServer } from "msw/node";
import { createValidatingHandlers } from "../mocks/validating.ts";
import { operationCount } from "../src/generated/handlers.ts";

const BASE = "http://localhost:8080";
const DOC = "00000000-0000-7000-8000-000000000001";
const PAT = "00000000-0000-7000-8000-000000000002";
let mock, server;

before(async () => {
  mock = await createValidatingHandlers(BASE);
  server = setupServer(...mock.handlers);
  server.listen({ onUnhandledRequest: "error" });
});
after(() => server.close());
afterEach(() => { mock.violations.length = 0; });

const post = (path, body) => fetch(`${BASE}${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: typeof body === "string" ? body : JSON.stringify(body) });

test("every generated operation gets a validating handler", () => {
  assert.equal(mock.handlers.length, operationCount);
});

test("a request that matches the spec gets the mock answer and no violation", async () => {
  const res = await post("/v1/queue/check-in", { doctorId: DOC, patientId: PAT, source: "walk_in" });
  assert.equal(res.status, 200);
  assert.equal(typeof (await res.json()).tokenNumber, "number");
  mock.assertNoSpecViolations(); // does not throw
});

test("a body missing a required field is refused (400) and fails the test via assertNoSpecViolations", async () => {
  const res = await post("/v1/queue/check-in", { doctorId: DOC });
  assert.equal(res.status, 400);
  assert.equal((await res.json()).code, "mock.spec-violation");
  assert.throws(() => mock.assertNoSpecViolations(), /QueueController_checkIn.*patientId/s);
  mock.assertNoSpecViolations(); // the list was cleared by the throw above
});

test("wrong type, bad format and a non-JSON body are violations too", async () => {
  assert.equal((await post("/v1/queue/check-in", { doctorId: 42, patientId: PAT })).status, 400);
  assert.equal((await post("/v1/queue/check-in", { doctorId: "not-a-uuid", patientId: PAT })).status, 400);
  assert.equal((await post("/v1/queue/check-in", "doctorId=1")).status, 400);
  assert.equal(mock.violations.length, 3);
  assert.ok(mock.violations.every((v) => v.operationId === "QueueController_checkIn" && v.errors.length > 0), JSON.stringify(mock.violations));
});

test("a GET missing a required query parameter is a violation; with it the mock answers", async () => {
  assert.equal((await fetch(`${BASE}/v1/queue/wait-estimate`)).status, 400);
  assert.equal(mock.violations[0]?.operationId, "QueueController_waitEstimate");
  mock.violations.length = 0;
  assert.equal((await fetch(`${BASE}/v1/queue/wait-estimate?doctorId=${DOC}`)).status, 200);
  mock.assertNoSpecViolations();
});

test("3.0 bounds are enforced (discount minimum 0) and a valid checkout body passes", async () => {
  assert.equal((await post("/v1/billing/otc-checkout", { lineItems: [{ chargeCode: "CONSULT" }], discount: -1 })).status, 400);
  assert.match(mock.violations[0].errors.join(), /discount.*>= 0/);
  mock.violations.length = 0;
  assert.equal((await post("/v1/billing/otc-checkout", { lineItems: [{ chargeCode: "CONSULT" }], discount: 0 })).status, 200);
  mock.assertNoSpecViolations();
});

test("every operation's validator compiles: no handler throws (MSW would answer 500)", async () => {
  const bad = [];
  for (const h of mock.handlers) {
    const path = String(h.info.path).replace(/^\*/, BASE).replace(/:[A-Za-z]+/g, DOC);
    const res = await fetch(path, { method: h.info.method });
    if (res.status >= 500) bad.push(`${h.info.method} ${h.info.path}: ${res.status}`);
  }
  assert.deepEqual(bad, []);
  mock.violations.length = 0;
});
