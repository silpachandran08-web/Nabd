// COM-027: 'Send as SMS' appears only on a WhatsApp message we sent that failed or expired, in an India clinic.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const L = await import("../../app/components/inbox/inboxLogic.ts");

const out = (extra = {}) => ({ direction: "out", status: "failed", channel: "whatsapp", ...extra });

test("failed or expired WhatsApp messages in India can be sent as SMS", () => {
  assert.equal(L.canSendAsSms(out(), "IN"), true);
  assert.equal(L.canSendAsSms(out({ status: "expired" }), "IN"), true);
  assert.equal(L.canSendAsSms(out({ channel: undefined }), "IN"), true, "rows from before the channel field are WhatsApp");
});

test("never outside India, never for a delivered, blocked or incoming message, never for the SMS itself", () => {
  assert.equal(L.canSendAsSms(out(), "SA"), false);
  assert.equal(L.canSendAsSms(out(), null), false);
  for (const status of ["sent", "delivered", "read", "blocked", "cancelled", null]) assert.equal(L.canSendAsSms(out({ status }), "IN"), false, String(status));
  assert.equal(L.canSendAsSms(out({ direction: "in" }), "IN"), false);
  assert.equal(L.canSendAsSms(out({ channel: "sms" }), "IN"), false);
});

// S24 T1-BUG-7: a second press while the SMS is on its way is not a failure.
test("the answer to 'Send as SMS' reads as sent, in progress, refused or failed", () => {
  assert.equal(L.smsNotice("sent"), "Sent by SMS.");
  assert.equal(L.smsNotice("delivered"), "Sent by SMS.");
  assert.match(L.smsNotice("sending"), /being sent by SMS/);
  assert.match(L.smsNotice("queued"), /being sent by SMS/);
  assert.match(L.smsNotice("blocked"), /has not agreed to messages/);
  for (const status of ["failed", "expired", undefined]) assert.equal(L.smsNotice(status), "SMS could not be sent.", String(status));
  for (const status of ["sending", "queued", "blocked", "failed"]) assert.doesNotMatch(L.smsNotice(status), /Sent by SMS\./, status);
});

test("the 'Send as SMS' button cannot be pressed again while its request is pending", () => {
  const src = readFileSync(new URL("../../app/components/inbox/InboxScreen.tsx", import.meta.url), "utf8");
  assert.match(src, /async function sendAsSms\(messageId: string\) \{\s*if \(smsBusy\) return;\s*setSmsBusy\(true\);/);
  assert.match(src, /finally \{ setSmsBusy\(false\); \}/);
  assert.match(src, /disabled=\{smsBusy\} onClick=\{\(\) => void sendAsSms\(m\.id\)\}>Send as SMS</);
});
