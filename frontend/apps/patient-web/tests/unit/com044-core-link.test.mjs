// COM-044 (S24 T1-BUG-2): a link Core minted (IDC-015) is opened on the two public calls Core serves, with the same OTP step.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { patientApi, isCoreLink, PatientApiError } = await import("../../app/lib/patientApi.ts");
const { LINK_ID } = await import("../../app/lib/security.ts");

const TOKEN = Buffer.alloc(48, 7).toString("base64url"); // Core's token: 16 bytes of clinic id + 32 random bytes
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "content-type": status >= 400 ? "application/problem+json" : "application/json" } });
const api = (answer, seen = []) => patientApi(async (url, init) => { seen.push({ url: String(url), init }); return answer(String(url)); });
const CARD = { packageName: "Laser 6", status: "active", items: [{ name: "Laser", used: 1, total: 6 }], sessionsUsed: 1, sessionsTotal: 6,
  validUntil: "2027-01-31", graceUntil: null, paid: 2400, due: 600, saved: 1200, currency: "INR" };

test("only a 48-byte base64url token is a Core link; the page route accepts it", () => {
  assert.equal(TOKEN.length, 64);
  assert.ok(isCoreLink(TOKEN) && LINK_ID.test(TOKEN));
  for (const id of ["docLink_0123456789ab", "consentLink_0123456789", TOKEN.slice(1), TOKEN + "A", TOKEN.slice(1) + "="]) assert.ok(!isCoreLink(id), id);
});

test("the code is asked for on Core's otp call: POST, no body, no token, and the empty 202 is fine", async () => {
  const seen = [];
  await api(() => new Response(null, { status: 202 }), seen).coreLinkOtp(TOKEN);
  assert.equal(seen.length, 1);
  assert.ok(seen[0].url.endsWith(`/v1/public/links/${TOKEN}/otp`));
  assert.equal(seen[0].init.method, "POST");
  assert.equal(seen[0].init.body, undefined);
  assert.equal(seen[0].init.headers.get("authorization"), null);
  assert.equal(seen[0].init.cache, "no-store");
  assert.equal(seen[0].init.credentials, "omit");
});

test("the code opens the link on Core's open call and the package card comes back as the page's content", async () => {
  const seen = [];
  const r = await api(() => json(200, { fileUrl: null, mime: null, title: "Package card", packageCard: CARD }), seen).coreLinkOpen(TOKEN, "123456");
  assert.deepEqual(r, { kind: "package", card: CARD });
  assert.ok(seen[0].url.endsWith(`/v1/public/links/${TOKEN}/open`));
  assert.equal(seen[0].init.method, "POST");
  assert.deepEqual(JSON.parse(seen[0].init.body), { otp: "123456" });
  assert.ok(!seen.some((s) => s.url.includes("/patient-links/")), "nothing in this tree serves /patient-links");
  assert.ok(!seen[0].url.includes("123456"), "the code is never in a URL");
});

test("a file link is not shown as a card and its signed URL is not kept", async () => {
  const r = await api(() => json(200, { fileUrl: "/files-blob/object?s=x", mime: "application/pdf", title: "Report", packageCard: null })).coreLinkOpen(TOKEN, "123456");
  assert.deepEqual(r, { kind: "unsupported" });
});

test("Core's refusals read as the page's own states", async () => {
  const fails = async (res, call) => { try { await call(api(() => res)); } catch (e) { assert.ok(e instanceof PatientApiError); return e.failure; } assert.fail("no refusal"); };
  const open = (a) => a.coreLinkOpen(TOKEN, "000000");
  assert.equal(await fails(json(410, { title: "Link expired" }), (a) => a.coreLinkOtp(TOKEN)), "expired");
  assert.equal(await fails(json(410, { title: "Link expired" }), open), "expired");
  assert.equal(await fails(json(401, { title: "Code not accepted" }), open), "wrong_code");
  assert.equal(await fails(json(423, { title: "Link locked" }), open), "too_many");
  assert.equal(await fails(json(429, { title: "Too many codes requested" }), (a) => a.coreLinkOtp(TOKEN)), "too_many");
  assert.equal(await fails(json(423, { title: "Number not verified" }), (a) => a.coreLinkOtp(TOKEN)), "network");
});

test("every language has the two strings the Core link page needs", () => {
  for (const l of ["en", "hi", "kn", "te"]) {
    const c = JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/patient.json`, import.meta.url), "utf8"));
    assert.ok(c.otpHintPlain && !c.otpHintPlain.includes("{"), `${l} otpHintPlain`);
    assert.ok(c.docNotHere && !c.docNotHere.includes("{"), `${l} docNotHere`);
  }
});
