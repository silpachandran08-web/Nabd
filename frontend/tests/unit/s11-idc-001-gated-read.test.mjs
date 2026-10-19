// IDC-001: a gated clinical 404 (patient-not-found) is told apart from "nothing recorded yet" (plain 404).
import { test } from "node:test";
import assert from "node:assert/strict";
import { isPatientNotFound } from "../../app/components/consult/clinical.ts";

const res = (status, body) => new Response(body === undefined ? "" : JSON.stringify(body), { status, headers: { "content-type": "application/problem+json" } });

test("patient-not-found problem is a gated read", async () => {
  assert.equal(await isPatientNotFound(res(404, { type: "https://api.nabd.health/errors/patient-not-found" })), true);
});

test("plain not-found, other statuses and unreadable bodies are not", async () => {
  assert.equal(await isPatientNotFound(res(404, { type: "https://api.nabd.health/errors/not-found" })), false);
  assert.equal(await isPatientNotFound(res(403, { type: "https://api.nabd.health/errors/patient-not-found" })), false);
  assert.equal(await isPatientNotFound(res(404)), false);
  assert.equal(await isPatientNotFound(res(404, { type: 42 })), false);
});

test("the body stays readable for the caller after the check", async () => {
  const r = res(404, { type: "x/patient-not-found", title: "t" });
  await isPatientNotFound(r);
  assert.equal((await r.json()).title, "t");
});
