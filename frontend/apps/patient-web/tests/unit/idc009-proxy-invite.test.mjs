// IDC-009: the family member's consent link calls (public, token in the path), and the catalogue keys the page needs.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { patientApi, PatientApiError } = await import("../../app/lib/patientApi.ts");

const cat = (l) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/patient.json`, import.meta.url), "utf8"));
const TOKEN = "AbC_def-0123456789xyz";

function fake(status, body) {
  const calls = [];
  const f = async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } }); };
  return { api: patientApi(f), calls };
}

test("view: GET the token path with no credentials and no storage", async () => {
  const { api, calls } = fake(200, { clinicName: "Sunrise", relation: "daughter", caregiverFirstName: "Mira", expiresAt: "2026-11-03T00:00:00Z" });
  const v = await api.proxyInvite(TOKEN);
  assert.equal(v.caregiverFirstName, "Mira");
  assert.match(calls[0].url, /\/proxy-invites\/AbC_def-0123456789xyz$/);
  assert.equal(calls[0].init.credentials, "omit");
  assert.equal(calls[0].init.cache, "no-store");
});

test("otp then accept: the scope and the code go in the body, nothing in the URL", async () => {
  const { api, calls } = fake(200, { status: "active", scope: "BOOK_ONLY" });
  await api.proxyOtp(TOKEN);
  const r = await api.proxyAccept(TOKEN, "123456", "BOOK_ONLY");
  assert.equal(r.scope, "BOOK_ONLY");
  assert.match(calls[0].url, /\/otp$/);
  assert.deepEqual(JSON.parse(calls[1].init.body), { otp: "123456", scope: "BOOK_ONLY" });
  assert.ok(!calls[1].url.includes("?"), "no query string");
});

test("an old or used link (410) and an unknown one (404) read as expired; a wrong code is its own case", async () => {
  for (const status of [410, 404]) {
    const { api } = fake(status, { code: "invite-expired" });
    await assert.rejects(api.proxyInvite(TOKEN), (e) => e instanceof PatientApiError && e.failure === "expired");
  }
  const { api } = fake(401, { code: "otp-invalid" });
  await assert.rejects(api.proxyAccept(TOKEN, "000000", "BOOK_ONLY"), (e) => e instanceof PatientApiError && e.failure === "wrong_code");
  const t = fake(429, {});
  await assert.rejects(t.api.proxyOtp(TOKEN), (e) => e.failure === "too_many");
});

test("every language has the consent page keys, in its own script", () => {
  const keys = Object.keys(cat("en")).filter((k) => k.startsWith("proxy"));
  assert.equal(keys.length, 11);
  for (const l of ["hi", "kn", "te"]) {
    for (const k of keys) assert.ok(cat(l)[k], `${l}.${k}`);
  }
  assert.match(cat("hi").proxyScopeBook, /[ऀ-ॿ]/);
  assert.match(cat("kn").proxyScopeBook, /[ಀ-೿]/);
  assert.match(cat("te").proxyScopeBook, /[ఀ-౿]/);
  assert.match(cat("en").proxyIntro, /\{relation\}/);
});
