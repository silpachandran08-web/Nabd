// COM-027: 'Send as SMS' appears only on a WhatsApp message we sent that failed or expired, in an India clinic.
import { test } from "node:test";
import assert from "node:assert/strict";
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
