// CLN-023: the public Rx verify page's calls and its offline check (WebCrypto against the published key, no network). Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { webcrypto } from "node:crypto";
const { patientApi, PatientApiError } = await import("../../app/lib/patientApi.ts");
const { isToken, tone, verifyOffline } = await import("../../app/lib/rxVerify.ts");

const cat = (l) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/patient.json`, import.meta.url), "utf8"));
const b64 = (bytes) => Buffer.from(bytes).toString("base64url");
const uuidBytes = (u) => Buffer.from(u.replace(/-/g, ""), "hex");

async function signed({ validUntil = "" } = {}) {
  const pair = await webcrypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const jwk = { ...(await webcrypto.subtle.exportKey("jwk", pair.publicKey)), kid: "k9" };
  const head = ["NRX1", b64(uuidBytes("0193b2c4-1111-7000-8000-000000000001")), b64(uuidBytes("0193b2c4-2222-7000-8000-000000000002")),
    "1762000000", validUntil, b64(Buffer.from("k9")), b64(Buffer.from("KMC-12345"))].join(".");
  const sig = await webcrypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, pair.privateKey, new TextEncoder().encode(head));
  return { token: `${head}.${b64(new Uint8Array(sig))}`, jwks: { keys: [jwk] } };
}

function fake(status, body) {
  const calls = [];
  const f = async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } }); };
  return { api: patientApi(f), calls };
}

test("offline: the published key alone verifies the token; a tampered token or another key does not", async () => {
  const { token, jwks } = await signed({ validUntil: "1762432000" });
  const ok = await verifyOffline(token, jwks, new Date("2025-11-03T00:00:00Z"), webcrypto.subtle);
  assert.deepEqual([ok.ok, ok.expired, ok.registrationNo], [true, false, "KMC-12345"]);
  assert.equal((await verifyOffline(token, jwks, new Date("2025-11-07T00:00:00Z"), webcrypto.subtle)).expired, true);
  const f = token.split(".");
  f[4] = "1862432000";
  assert.equal((await verifyOffline(f.join("."), jwks, new Date(), webcrypto.subtle)).ok, false);
  const other = await signed();
  assert.equal((await verifyOffline(token, other.jwks, new Date(), webcrypto.subtle)).ok, false);
  assert.equal((await verifyOffline(token, null, new Date(), webcrypto.subtle)).ok, false);
});

test("only an NRX1 token is looked up", () => {
  assert.equal(isToken("NRX1.a.b.1.2.c.d.e"), true);
  assert.equal(isToken("NRX1.a.b"), false);
  assert.equal(isToken("<script>"), false);
  assert.equal(isToken("NRX1." + "a.".repeat(600) + "a"), false);
});

test("verify and keys are public calls on the token path; 404 and 429 read as their own cases", async () => {
  const { api, calls } = fake(200, { status: "valid", doctor: { name: "Dr Rao", registrationNo: "KMC-1", council: null }, issuedAt: "2026-11-16T05:00:00Z", validUntil: null, lineCount: 2, dispensedAt: null });
  const v = await api.rxVerify("NRX1.a.b.1.2.c.d.e");
  assert.equal(v.lineCount, 2);
  await api.rxKeys();
  assert.match(calls[0].url, /\/public\/rx\/verify\/NRX1\.a\.b\.1\.2\.c\.d\.e$/);
  assert.match(calls[1].url, /\/public\/rx\/keys$/);
  for (const c of calls) { assert.equal(c.init.credentials, "omit"); assert.equal(new Headers(c.init.headers).get("Authorization"), null); }
  await assert.rejects(fake(404, { code: "UNKNOWN_TOKEN" }).api.rxVerify("NRX1.a.b.1.2.c.d.e"), (e) => e instanceof PatientApiError && e.failure !== "network");
  await assert.rejects(fake(429, {}).api.rxVerify("NRX1.a.b.1.2.c.d.e"), (e) => e.failure === "too_many");
  assert.deepEqual(["valid", "expired", "dispensed", "revoked"].map(tone), ["ok", "warn", "warn", "alert"]);
});

test("every language has the verify page keys", () => {
  const keys = Object.keys(cat("en")).filter((k) => k.startsWith("rx"));
  assert.equal(keys.length, 20);
  for (const l of ["hi", "kn", "te"]) for (const k of keys) assert.ok(cat(l)[k], `${l}.${k}`);
  assert.match(cat("en").rxDispensed, /\{date\}/);
});
