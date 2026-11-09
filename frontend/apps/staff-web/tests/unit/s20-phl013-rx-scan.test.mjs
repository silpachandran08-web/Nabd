// PHL-013: the Rx QR parser, the offline WebCrypto check against a cached key, and the result card words. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { headline, isOffline, parseRxQr, PREFIX, tone, verifyOffline } from "../../app/components/setup/rxScan.ts";

const b64 = (bytes) => Buffer.from(bytes).toString("base64url");
const uuidBytes = (u) => Buffer.from(u.replace(/-/g, ""), "hex");
const TENANT = "0193b2c4-1111-7000-8000-000000000001", RX = "0193b2c4-2222-7000-8000-000000000002";

async function signed({ validUntil = "", regNo = "KMC 12345", kid = "k1", signedAt = 1_762_000_000 } = {}) {
  const pair = await webcrypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const jwk = { ...(await webcrypto.subtle.exportKey("jwk", pair.publicKey)), kid };
  delete jwk.key_ops; delete jwk.ext;
  const head = [PREFIX, b64(uuidBytes(TENANT)), b64(uuidBytes(RX)), String(signedAt), validUntil, b64(Buffer.from(kid)), b64(Buffer.from(regNo))].join(".");
  const sig = await webcrypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, pair.privateKey, new TextEncoder().encode(head));
  return { payload: `${head}.${b64(new Uint8Array(sig))}`, jwks: { keys: [jwk] } };
}

test("the payload parses to its claims; anything else is not an Rx QR", async () => {
  const { payload } = await signed({ validUntil: "1762432000" });
  const p = parseRxQr(" " + payload + "\n");
  assert.equal(p.claims.tenantId, TENANT);
  assert.equal(p.claims.prescriptionId, RX);
  assert.equal(p.claims.signedAt, "2025-11-01T12:26:40.000Z");
  assert.equal(p.claims.validUntil, "2025-11-06T12:26:40.000Z");
  assert.deepEqual([p.claims.kid, p.claims.regNo], ["k1", "KMC 12345"]);
  assert.equal(p.head, payload.slice(0, payload.lastIndexOf(".")));
  assert.equal(parseRxQr("https://example.com/x"), null);
  assert.equal(parseRxQr("NRX1.a.b"), null);
  assert.equal(parseRxQr(payload.replace("NRX1", "NRX2")), null);
});

test("offline: a genuine QR verifies against the cached key, expiry comes from the payload", async () => {
  const { payload, jwks } = await signed({ validUntil: "1762432000" }); // 2025-11-06T12:26:40Z
  const ok = await verifyOffline(payload, jwks, TENANT, new Date("2025-11-03T00:00:00Z"), webcrypto.subtle);
  assert.equal(ok.status, "valid");
  assert.equal(ok.claims.regNo, "KMC 12345");
  const late = await verifyOffline(payload, jwks, TENANT, new Date("2025-11-06T12:26:40Z"), webcrypto.subtle);
  assert.equal(late.status, "expired");
  const never = await signed();
  assert.equal((await verifyOffline(never.payload, never.jwks, TENANT.toUpperCase(), new Date("2030-01-01T00:00:00Z"), webcrypto.subtle)).status, "valid");
});

test("offline: an altered payload, an unknown key or no cache never verifies", async () => {
  const { payload, jwks } = await signed({ validUntil: "1762432000" });
  const f = payload.split(".");
  f[4] = "1999999999"; // a later expiry than the doctor signed
  assert.deepEqual(await verifyOffline(f.join("."), jwks, TENANT, new Date(), webcrypto.subtle), { status: "invalid", reason: "signature" });
  const other = await signed();
  assert.deepEqual(await verifyOffline(payload, other.jwks, TENANT, new Date(), webcrypto.subtle), { status: "invalid", reason: "signature" }); // same kid, another clinic's key
  assert.deepEqual(await verifyOffline(payload, { keys: [{ ...jwks.keys[0], kid: "old" }] }, TENANT, new Date(), webcrypto.subtle), { status: "invalid", reason: "unknown-key" });
  assert.deepEqual(await verifyOffline(payload, null, TENANT, new Date(), webcrypto.subtle), { status: "invalid", reason: "unknown-key" });
  assert.deepEqual(await verifyOffline("garbage", jwks, TENANT, new Date(), webcrypto.subtle), { status: "invalid", reason: "unreadable" });
});

test("offline (T20-BUG-1): another clinic's correctly signed QR is 'not from this clinic', never verified", async () => {
  const { payload, jwks } = await signed({ validUntil: "1999999999" });
  const other = "0193b2c4-9999-7000-8000-000000000009";
  assert.deepEqual(await verifyOffline(payload, jwks, other, new Date(), webcrypto.subtle), { status: "invalid", reason: "other-tenant" });
  assert.deepEqual(await verifyOffline(payload, jwks, null, new Date(), webcrypto.subtle), { status: "invalid", reason: "other-tenant" }); // no session tenant: never ours
  assert.equal((await verifyOffline(payload, jwks, TENANT, new Date(), webcrypto.subtle)).status, "valid");
  assert.equal(headline("invalid", "other-tenant", null), "Not from this clinic");
});

test("card colour and headline per outcome", () => {
  assert.deepEqual(["valid", "expired", "dispensed", "invalid"].map(tone), ["ok", "warn", "warn", "danger"]);
  assert.equal(headline("valid", null, null), "Verified prescription");
  assert.match(headline("expired", null, "2025-11-06T12:26:40Z"), /^Expired on /);
  assert.match(headline("dispensed", null, null, "2025-11-04T10:00:00Z"), /^Already fully dispensed on /);
  assert.equal(headline("invalid", "signature", null), "Signature invalid");
  assert.equal(headline("invalid", "other-tenant", null), "Not from this clinic");
  assert.equal(headline("invalid", "unreadable", null), "Not a Nabd prescription QR");
});

test("only a call that never reached the server falls back to the offline check", () => {
  assert.equal(isOffline(new TypeError("fetch failed")), true);
  assert.equal(isOffline(Object.assign(new Error("t"), { name: "TimeoutError" })), true);
  assert.equal(isOffline(Object.assign(new Error("403"), { status: 403 })), false);
});
