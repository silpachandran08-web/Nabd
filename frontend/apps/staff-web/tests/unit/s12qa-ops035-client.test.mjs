// Independent QA (S12 OPS-035 AC3): the generated Zod schemas really validate (accept a good body, refuse a bad one),
// the generated mocks are importable without msw, and the staff-web app uses the generated client.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { LoginRequestSchema, PrescriptionResponseSchema, operationSchemas } from "../../../../packages/api-client/src/generated/index.ts";
import { createHandlers } from "../../../../packages/api-client/src/generated/handlers.ts";

test("LoginRequest schema accepts a complete body and refuses one without pin or with a wrong type", () => {
  assert.equal(LoginRequestSchema.safeParse({ email: "a@b.test", pin: "1234", tenantSlug: "t1" }).success, true);
  assert.equal(LoginRequestSchema.safeParse({ email: "a@b.test", tenantSlug: "t1" }).success, false);
  assert.equal(LoginRequestSchema.safeParse({ email: 5, pin: "1234", tenantSlug: "t1" }).success, false);
});

test("prescription sign response schema refuses a non-integer unresolvedWarnings and a non-array items", () => {
  const sign = operationSchemas.PrescriptionController_sign.response;
  assert.equal(sign, PrescriptionResponseSchema);
  assert.equal(sign.safeParse({ status: "signed", unresolvedWarnings: 0, items: [] }).success, true);
  assert.equal(sign.safeParse({ unresolvedWarnings: 1.5 }).success, false);
  assert.equal(sign.safeParse({ items: "x" }).success, false);
});

test("handlers module needs no msw import and every handler answers a documented 2xx", () => {
  const src = readFileSync(new URL("../../../../packages/api-client/src/generated/handlers.ts", import.meta.url), "utf8");
  assert.doesNotMatch(src, /from\s+["']msw["']/);
  const statuses = [];
  const route = () => (_p, resolver) => statuses.push(resolver().status ?? 200);
  class HttpResponse { constructor(_b, init) { this.status = init?.status; } static json(_b, init) { return { status: init?.status ?? 200 }; } }
  createHandlers({ http: { get: route(), post: route(), put: route(), patch: route(), delete: route() }, HttpResponse });
  assert.ok(statuses.length >= 264);
  assert.ok(statuses.every((s) => s >= 200 && s < 300), "a mock answers a non-2xx status");
});

function files(dir) {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? files(p) : /\.(ts|tsx)$/.test(n) ? [p] : [];
  });
}

// OPS-035 AC3 "the FE build uses them": was a todo until FEF-014 (wave 2) wired the app to the generated client.
test("staff-web app imports the generated client", () => {
  const app = fileURLToPath(new URL("../../app", import.meta.url));
  const users = files(app).filter((f) => /api-client\/src\/generated|@nabd\/api-client\/generated/.test(readFileSync(f, "utf8")));
  assert.ok(users.length > 0, "no app module imports the generated client");
});
