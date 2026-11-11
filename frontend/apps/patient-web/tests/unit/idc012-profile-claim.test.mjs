// IDC-012: the young adult's claim link calls (public, token in the path) and the catalogue keys the page needs.
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

test("view, otp and claim use the token path with no credentials; the choice and code go in the body", async () => {
  const { api, calls } = fake(200, { claimed: true, keptScope: "BOOK_ONLY" });
  await api.profileClaim(TOKEN);
  await api.profileClaimOtp(TOKEN);
  const r = await api.claimProfile(TOKEN, "123456", "BOOK_ONLY");
  assert.equal(r.claimed, true);
  assert.match(calls[0].url, /\/me\/profile-claims\/AbC_def-0123456789xyz$/);
  assert.match(calls[1].url, /\/otp$/);
  assert.deepEqual(JSON.parse(calls[2].init.body), { otp: "123456", keepGuardianScope: "BOOK_ONLY" });
  for (const c of calls) { assert.equal(c.init.credentials, "omit"); assert.ok(!c.url.includes("?")); }
});

test("before the birthday, without an own number, and an old link each read as their own case", async () => {
  await assert.rejects(fake(409, { code: "claim-not-open" }).api.profileClaimOtp(TOKEN), (e) => e instanceof PatientApiError && e.failure === "not_open");
  await assert.rejects(fake(422, { code: "own-phone-required" }).api.profileClaimOtp(TOKEN), (e) => e.failure === "own_phone");
  await assert.rejects(fake(410, { code: "claim-expired" }).api.profileClaim(TOKEN), (e) => e.failure === "expired");
  await assert.rejects(fake(401, { code: "otp-invalid" }).api.claimProfile(TOKEN, "000000", "NONE"), (e) => e.failure === "wrong_code");
});

test("every language has the claim page keys, in its own script", () => {
  const keys = Object.keys(cat("en")).filter((k) => k.startsWith("claim"));
  assert.equal(keys.length, 8);
  for (const l of ["hi", "kn", "te"]) for (const k of keys) assert.ok(cat(l)[k], `${l}.${k}`);
  assert.match(cat("hi").claimSubmit, /[ऀ-ॿ]/);
  assert.match(cat("en").claimIntro, /\{guardian\}/);
});
