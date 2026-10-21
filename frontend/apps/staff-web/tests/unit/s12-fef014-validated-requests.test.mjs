// FEF-014 AC3 in use: staff-web's queue-move body (lib/queueMove moveBody, used by the consult and patients screens)
// sent through the shared api client against the generated MSW handlers that validate every request against
// api/openapi/core.yaml. afterEach fails the test on any spec violation, so a body the spec refuses cannot pass silently.
import { test, before, after, afterEach } from "node:test";
import assert from "node:assert/strict";
import { setupServer } from "msw/node";
import { createValidatingHandlers } from "@nabd/api-client/mocks";
import { createClient, ApiError } from "@nabd/api-client";
import { moveBody } from "../../app/lib/queueMove.ts";

const ORIGIN = "http://localhost:8080";
const ID = "00000000-0000-7000-8000-00000000000a";
let mock, server;
const api = createClient({ baseUrl: `${ORIGIN}/v1`, getToken: () => null, onAuthFailure: () => {}, retryDelayMs: 1 });

before(async () => {
  mock = await createValidatingHandlers(ORIGIN);
  server = setupServer(...mock.handlers);
  server.listen({ onUnhandledRequest: "error" });
});
afterEach(() => mock.assertNoSpecViolations());
after(() => server.close());

test("moveBody (compare-and-set queue move) is a valid PATCH /queue/{id}/status body", async () => {
  await api.patch(`/queue/${ID}/status`, JSON.parse(moveBody("in_consult", "waiting")));
  await api.patch(`/queue/${ID}/status`, JSON.parse(moveBody("checkout_pending", "in_consult")));
});

test("a body the spec refuses gets the mock's 400 and is recorded as a violation (the gate bites)", async () => {
  await assert.rejects(api.patch(`/queue/${ID}/status`, { expectedStatus: "waiting" }), (e) => e instanceof ApiError && e.status === 400);
  assert.throws(() => mock.assertNoSpecViolations(), /QueueController_updateStatus/);
});
