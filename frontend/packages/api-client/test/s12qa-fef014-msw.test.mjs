// S12 QA-6 (independent) FEF-014 AC3: the request-validating MSW handlers refuse spec-violating bodies on the endpoints
// the clinic screens write (queue status move, patient registration), and a violation fails the test.
import { test, before, after, afterEach } from "node:test";
import assert from "node:assert/strict";
import { setupServer } from "msw/node";
import { createValidatingHandlers } from "../mocks/validating.ts";

const BASE = "http://localhost:8080";
const Q = "00000000-0000-7000-8000-00000000000a";
let mock, server;

before(async () => {
  mock = await createValidatingHandlers(BASE);
  server = setupServer(...mock.handlers);
  server.listen({ onUnhandledRequest: "error" });
});
after(() => server.close());
afterEach(() => { mock.violations.length = 0; });

const send = (method, path, body) => fetch(`${BASE}${path}`, { method, headers: { "Content-Type": "application/json" }, body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body) });
const patchStatus = (body, id = Q) => send("PATCH", `/v1/queue/${id}/status`, body);
const PATIENT = { name: "Layla Haddad", dob: "1990-04-01", gender: "female", phone: "+919800000000" };

test("valid PATCH /queue/{id}/status (Arrivals 'send for vitals' body) passes with no violation", async () => {
  const r = await patchStatus({ status: "vitals_pending", expectedStatus: "waiting" });
  assert.ok(r.status < 300, `status ${r.status}`);
  mock.assertNoSpecViolations();
});

test("PATCH /queue/{id}/status without the required status is refused and fails the test", async () => {
  const r = await patchStatus({ expectedStatus: "waiting" });
  assert.equal(r.status, 400);
  const p = await r.json();
  assert.equal(p.code, "mock.spec-violation");
  assert.match(p.detail, /status/);
  assert.throws(() => mock.assertNoSpecViolations(), /QueueController_updateStatus/);
});

test("PATCH /queue/{id}/status with wrong types (number, null, array, object) is refused each time", async () => {
  for (const bad of [{ status: 3 }, { status: null }, { status: ["vitals_pending"] }, { status: "vitals_pending", expectedStatus: { s: "waiting" } }]) {
    assert.equal((await patchStatus(bad)).status, 400, JSON.stringify(bad));
  }
  assert.equal(mock.violations.length, 4);
});

test("PATCH /queue/{id}/status with a non-uuid path id is refused", async () => {
  assert.equal((await patchStatus({ status: "waiting" }, "q1")).status, 400);
  assert.equal(mock.violations.length, 1);
});

test("an empty body and a non-JSON body are refused", async () => {
  assert.equal((await patchStatus("")).status, 400, "empty body on a required requestBody");
  assert.equal((await patchStatus("status=waiting")).status, 400);
  assert.equal(mock.violations.length, 2);
});

test("POST /patients: valid registration passes; each missing required field and a bad date are refused", async () => {
  assert.ok((await send("POST", "/v1/patients", PATIENT)).status < 300);
  mock.assertNoSpecViolations();
  for (const k of ["name", "dob", "gender", "phone"]) {
    const { [k]: _drop, ...body } = PATIENT;
    assert.equal((await send("POST", "/v1/patients", body)).status, 400, `missing ${k}`);
  }
  assert.equal((await send("POST", "/v1/patients", { ...PATIENT, dob: "01/04/1990" })).status, 400, "dob format date");
  assert.equal((await send("POST", "/v1/patients", { ...PATIENT, guardianId: "nope" })).status, 400, "guardianId uuid");
  assert.equal(mock.violations.length, 6);
  assert.throws(() => mock.assertNoSpecViolations(), /PatientController_register/);
});

test("boundary: a violation is recorded even when the test ignores the 400 (assertNoSpecViolations fails it later)", async () => {
  await patchStatus({});
  assert.throws(() => mock.assertNoSpecViolations(), /request\(s\) violate/);
  mock.assertNoSpecViolations(); // cleared
});

test("a request to an operation not in the spec is not silently answered (unhandled -> error)", async () => {
  await assert.rejects(send("POST", "/v1/queue/not-a-real-endpoint/teleport", { x: 1 }));
});
