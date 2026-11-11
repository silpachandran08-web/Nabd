// ADM-015: the owner's export link calls (public, token in the path, OTP in the body) and the page strings.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { patientApi, PatientApiError } = await import("../../app/lib/patientApi.ts");

const cat = (l) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/patient.json`, import.meta.url), "utf8"));
const TOKEN = "AbC_def-0123456789xyz";

function fake(status, body, type = "application/json") {
  const calls = [];
  const f = async (url, init) => { calls.push({ url, init }); return new Response(typeof body === "string" ? body : JSON.stringify(body), { status, headers: { "content-type": type } }); };
  return { api: patientApi(f), calls };
}

test("view, code and download go to the token path with no credentials; the code is in the body", async () => {
  const { api, calls } = fake(200, "PK-zip-bytes", "application/zip");
  await api.exportOtp(TOKEN);
  const blob = await api.exportDownload(TOKEN, "123456");
  assert.equal(await blob.text(), "PK-zip-bytes");
  assert.match(calls[0].url, /\/public\/exports\/AbC_def-0123456789xyz\/otp$/);
  assert.match(calls[1].url, /\/download$/);
  assert.deepEqual(JSON.parse(calls[1].init.body), { otp: "123456" });
  for (const c of calls) { assert.equal(c.init.credentials, "omit"); assert.ok(!c.url.includes("?")); }
});

test("an expired or used-up link and a wrong code each read as their own case", async () => {
  await assert.rejects(fake(410, { code: "export-link-expired" }).api.exportLink(TOKEN), (e) => e instanceof PatientApiError && e.failure === "expired");
  await assert.rejects(fake(401, { code: "otp-invalid" }).api.exportDownload(TOKEN, "000000"), (e) => e.failure === "wrong_code");
});

test("every language has the export page keys", () => {
  const keys = Object.keys(cat("en")).filter((k) => k.startsWith("export"));
  assert.equal(keys.length, 5);
  for (const l of ["hi", "kn", "te"]) for (const k of keys) assert.ok(cat(l)[k], `${l}.${k}`);
  assert.match(cat("en").exportIntro, /\{left\}/);
});
