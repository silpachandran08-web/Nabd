// CLN-023: the printed QR is now the public verify link; the pharmacy scanner and its offline check read the token after the last
// slash. The revoke reason rule matches the server's. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { headline, parseRxQr, PREFIX, verifyOffline } from "../../app/components/setup/rxScan.ts";
import { revokeReasonOk, REVOKE_REASON_MAX } from "../../app/components/consult/rxRevokeRules.ts";

const b64 = (bytes) => Buffer.from(bytes).toString("base64url");
const uuidBytes = (u) => Buffer.from(u.replace(/-/g, ""), "hex");
const TENANT = "0193b2c4-1111-7000-8000-000000000001", RX = "0193b2c4-2222-7000-8000-000000000002";

async function signed() {
  const pair = await webcrypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const jwk = { ...(await webcrypto.subtle.exportKey("jwk", pair.publicKey)), kid: "k1" };
  const head = [PREFIX, b64(uuidBytes(TENANT)), b64(uuidBytes(RX)), "1762000000", "", b64(Buffer.from("k1")), b64(Buffer.from("KMC 1"))].join(".");
  const sig = await webcrypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, pair.privateKey, new TextEncoder().encode(head));
  return { payload: `${head}.${b64(new Uint8Array(sig))}`, jwks: { keys: [jwk] } };
}

test("the scanned verify link parses and verifies like the bare payload", async () => {
  const { payload, jwks } = await signed();
  const link = `https://patient.example.com/rx/${payload}`;
  assert.equal(parseRxQr(link).claims.prescriptionId, RX);
  assert.equal((await verifyOffline(link, jwks, TENANT, new Date(), webcrypto.subtle)).status, "valid");
  assert.equal(parseRxQr("https://patient.example.com/rx/"), null);
});

test("a revoked answer tells the pharmacist not to dispense", () => {
  assert.match(headline("invalid", "revoked", null), /Revoked by the prescriber/);
});

test("the revoke reason needs 5 to 500 characters after trimming", () => {
  assert.equal(revokeReasonOk("  no  "), false);
  assert.equal(revokeReasonOk("Wrong dose"), true);
  assert.equal(revokeReasonOk("x".repeat(REVOKE_REASON_MAX + 1)), false);
});
