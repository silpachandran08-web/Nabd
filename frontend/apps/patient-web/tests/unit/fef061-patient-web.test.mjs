// FEF-061: pure logic of patient-web (CSP/headers, failure mapping, API calls, catalogues, formatting).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { buildCsp, cspHeaderName, NO_STORE, STATIC_HEADERS, LINK_ID, apiOrigin, newNonce } = await import("../../app/lib/security.ts");
const { failureOf, patientApi, PatientApiError, labelOf, DOC_INLINE } = await import("../../app/lib/patientApi.ts");
const { makeT, formatWhen, newKey } = await import("../../app/components/util.ts");

const cat = (l) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/patient.json`, import.meta.url), "utf8"));

test("CSP is nonce-based, no inline script, no third party, api origin only in connect-src", () => {
  const csp = buildCsp("abc", { connect: "https://api.example.test" });
  assert.equal(csp.split("; ").find((d) => d.startsWith("script-src ")), "script-src 'self' 'nonce-abc' 'strict-dynamic'");
  assert.match(csp, /connect-src 'self' https:\/\/api\.example\.test/);
  assert.match(csp, /frame-ancestors 'none'/);
  assert.doesNotMatch(csp, /unsafe-eval/);
  assert.match(buildCsp("x", { dev: true }), /unsafe-eval/);
  assert.equal(cspHeaderName(undefined), "Content-Security-Policy");
  assert.equal(cspHeaderName("report-only"), "Content-Security-Policy-Report-Only");
  assert.notEqual(newNonce(), newNonce());
  assert.equal(apiOrigin("https://x.test/v1"), "https://x.test");
});
test("no-store and no referrer", () => {
  assert.match(NO_STORE, /no-store/);
  assert.equal(STATIC_HEADERS["Referrer-Policy"], "no-referrer");
});
test("link ids are opaque url-safe tokens only", () => {
  assert.ok(LINK_ID.test("AbC_def-0123456789xyz"));
  assert.ok(!LINK_ID.test("short"));
  assert.ok(!LINK_ID.test("../../etc/passwd/................"));
  assert.ok(!LINK_ID.test("a".repeat(16) + "?x=1"));
});
test("failure mapping: 404 and 410 both read as expired, 409 ARTEFACT_CHANGED is its own case", () => {
  assert.equal(failureOf(410, null), "expired");
  assert.equal(failureOf(404, null), "expired");
  assert.equal(failureOf(409, "ARTEFACT_CHANGED"), "changed");
  assert.equal(failureOf(409, "OTHER"), "network");
  assert.equal(failureOf(401, "INVALID_CODE"), "wrong_code");
  assert.equal(failureOf(429, null), "too_many");
  assert.equal(failureOf(503, null), "network");
});
function fakeFetch(answer, seen) {
  return async (url, init) => { seen.push({ url: String(url), init }); return answer(url, init); };
}
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" } });

test("decision goes to the exchange endpoint with Bearer, Idempotency-Key and the artefact hash, id only in the path", async () => {
  const seen = [];
  const api = patientApi(fakeFetch(() => json(200, { status: "approved" }), seen));
  await api.decide("c-1", "tok", "approve", "h-1", "key-1");
  const r = seen[0];
  assert.match(r.url, /\/exchange\/consents\/c-1\/patient-decision$/);
  assert.equal(r.init.method, "POST");
  assert.equal(r.init.headers.get("authorization"), "Bearer tok");
  assert.equal(r.init.headers.get("idempotency-key"), "key-1");
  assert.deepEqual(JSON.parse(r.init.body), { decision: "approve", artefactHash: "h-1" });
  assert.equal(r.init.cache, "no-store");
  assert.equal(r.init.credentials, "omit");
  assert.ok(!r.url.includes("tok"), "the token is never in a URL");
});
test("api maps 409 ARTEFACT_CHANGED (code in title, as Core writes it) and 410", async () => {
  const mk = (res) => patientApi(fakeFetch(() => res, []));
  await assert.rejects(mk(json(409, { title: "ARTEFACT_CHANGED" })).decide("c", "t", "approve", "h", "k"), (e) => e instanceof PatientApiError && e.failure === "changed");
  await assert.rejects(mk(json(410, { title: "EXPIRED" })).content("l", "t"), (e) => e.failure === "expired");
  await assert.rejects(mk(json(401, { code: "INVALID_CODE" })).verifyOtp("l", "000000"), (e) => e.failure === "wrong_code");
});
test("network failure is a failure, not a throw of the raw error", async () => {
  const api = patientApi(async () => { throw new TypeError("offline"); });
  await assert.rejects(api.linkInfo("l"), (e) => e instanceof PatientApiError && e.failure === "network");
});
test("labels come from the catalogue, unknown codes are shown readable", () => {
  const m = cat("en");
  assert.equal(labelOf("cat", "lab_reports", m), "Lab reports");
  assert.equal(labelOf("cat", "dental_xray", m), "dental xray");
  assert.equal(labelOf("purpose", "treatment", cat("te")), cat("te").purpose_treatment);
  assert.equal(DOC_INLINE("application/pdf"), "pdf");
  assert.equal(DOC_INLINE("image/png"), "image");
  assert.equal(DOC_INLINE("application/zip"), "other");
});
test("all four catalogues have exactly the English keys; kn/te/hi are written in their own script", () => {
  const en = Object.keys(cat("en")).sort();
  for (const l of ["hi", "kn", "te"]) assert.deepEqual(Object.keys(cat(l)).sort(), en, l);
  assert.match(cat("te").consentTitle, /[ఀ-౿]/);
  assert.match(cat("kn").consentTitle, /[ಀ-೿]/);
  assert.match(cat("hi").consentTitle, /[ऀ-ॿ]/);
  // the expiry text must not name the document type
  for (const l of ["en", "hi", "kn", "te"]) assert.doesNotMatch(JSON.stringify([cat(l).expiredTitle, cat(l).expiredHint, cat(l).expiredFull]).toLowerCase(), /prescription|report|invoice|consent|appointment|पर्चा|ಚೀಟಿ|ప్రిస్క్రిప్షన్/);
  assert.equal(cat("en").expiredFull, "This link has expired — ask the clinic to resend");
});
test("t() fills placeholders and formatWhen respects locale and zone", () => {
  assert.equal(makeT(cat("en"))("otpHint", { clinic: "Sunrise" }), "We will send a 6-digit code to your phone number on file at Sunrise.");
  assert.equal(makeT({})("missing"), "missing");
  const a = formatWhen("2026-03-01T20:30:00Z", "en", { time: true, timeZone: "Asia/Kolkata" });
  assert.match(a, /Mar 2, 2026, 2:00/); // 20:30 UTC is 02:00 next day in Kolkata
  assert.equal(formatWhen("not a date", "en"), "");
  assert.match(newKey(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});
