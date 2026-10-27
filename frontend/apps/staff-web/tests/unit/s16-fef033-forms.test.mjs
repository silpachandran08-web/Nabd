// FEF-033: phone to E.164 per market, schemas from the API narrowed per form, age -> estimated DOB, messages as keys.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { PatientWriteRequestSchema } from "@nabd/api-client/generated";
import { normalizePhone } from "@nabd/i18n/format";
import { registerSchema, checkInSchema, bookingSchema, discountSchema, e164 } from "../../app/lib/formSchemas.ts";
import { fieldIssues, FORM_ERRORS_EN } from "../../app/lib/formValidation.ts";
import { dobFromAge } from "../../app/components/arrivals/walk-in/ageDob.ts";

const TODAY = "2026-10-27";
const good = { name: "Layla Haddad", phone: "98450 12345", dob: "1990-05-01", gender: "female" };

test("phone: India, UAE, Saudi (SA and legacy KSA), Qatar normalise to E.164", () => {
  assert.equal(normalizePhone("98450 12345", "IN"), "+919845012345");
  assert.equal(normalizePhone("050 123 4567", "AE"), "+971501234567");
  assert.equal(normalizePhone("050 123 4567", "SA"), "+966501234567");
  assert.equal(normalizePhone("050 123 4567", "KSA"), "+966501234567");
  assert.equal(normalizePhone("5512 3456", "QA"), "+97455123456");
  assert.equal(normalizePhone("+971 50 123 4567", "IN"), "+971501234567", "an explicit country code wins over the clinic default");
});

test("phone: junk, too short and empty are refused", () => {
  for (const bad of ["12345", "abc", "", "   ", null, undefined, "98450 1234"]) assert.equal(normalizePhone(bad, "IN"), null, String(bad));
  assert.equal(e164("98450 12345", "IN"), "+919845012345");
  assert.equal(e164("12345", "IN"), "12345", "e164() never invents a number; the schema has refused it already");
});

test("registration: valid passes and is accepted by the API's own generated schema once the phone is stored as E.164", () => {
  assert.deepEqual(fieldIssues(registerSchema("IN", TODAY), good), {});
  assert.ok(PatientWriteRequestSchema.safeParse({ ...good, phone: e164(good.phone, "IN") }).success);
});

test("registration: each field reports a message KEY, one per field", () => {
  const issues = fieldIssues(registerSchema("IN", TODAY), { name: "  ", phone: "12345", dob: "", gender: "" });
  assert.deepEqual(issues, { name: "required", phone: "phoneInvalid", dob: "required", gender: "required" });
  for (const k of Object.values(issues)) assert.ok(FORM_ERRORS_EN[k], `English text exists for ${k}`);
});

test("registration: date of birth boundary is today (allowed) vs tomorrow (refused)", () => {
  assert.deepEqual(fieldIssues(registerSchema("IN", TODAY), { ...good, dob: TODAY }), {});
  assert.deepEqual(fieldIssues(registerSchema("IN", TODAY), { ...good, dob: "2026-10-28" }), { dob: "dobFuture" });
});

test("a number valid in one market is refused in another when typed without a country code", () => {
  assert.deepEqual(fieldIssues(registerSchema("AE", TODAY), { ...good, phone: "98450 12345" }), { phone: "phoneInvalid" });
  assert.deepEqual(fieldIssues(registerSchema("AE", TODAY), { ...good, phone: "050 123 4567" }), {});
});

test("age instead of DOB: 1 January of the estimated year", () => {
  assert.equal(dobFromAge("42", TODAY), "1984-01-01");
  assert.equal(dobFromAge("0", TODAY), "2026-01-01");
  assert.equal(dobFromAge("121", TODAY), "");
  assert.equal(dobFromAge("4x", TODAY), "");
});

test("check-in, booking and discount schemas", () => {
  assert.deepEqual(fieldIssues(checkInSchema, { doctorId: "" }), { doctorId: "required" });
  assert.deepEqual(fieldIssues(checkInSchema, { doctorId: "d1" }), {});
  assert.deepEqual(fieldIssues(bookingSchema, { patientId: "", doctorId: "d1", startTime: "" }), { patientId: "required", startTime: "required" });
  assert.deepEqual(fieldIssues(bookingSchema, { patientId: "p1", doctorId: "d1", startTime: "2026-10-28T09:00:00Z" }), {});
  const d = discountSchema(100);
  assert.deepEqual(fieldIssues(d, { value: 10, reasonCode: "" }), { reasonCode: "discountReason" });
  assert.deepEqual(fieldIssues(d, { value: 100, reasonCode: "staff" }), {}, "a discount equal to the subtotal is allowed");
  assert.deepEqual(fieldIssues(d, { value: 100.01, reasonCode: "staff" }), { value: "discountTooBig" });
  assert.deepEqual(fieldIssues(d, { value: -1, reasonCode: "staff" }), { value: "amountPositive" });
});

test("every key the schemas can emit exists in the English catalogue (common.formErrors) and matches the fallback text", () => {
  const cat = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/common.json", import.meta.url), "utf8")).formErrors;
  assert.deepEqual(cat, FORM_ERRORS_EN);
});

test("wiring: the four forms use the shared validation, E.164 and the unsaved-form guard", () => {
  const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
  const walk = src("components/arrivals/walk-in/WalkInDialog.tsx");
  assert.match(walk, /registerSchema/); assert.match(walk, /e164\(s\.phone/); assert.match(walk, /useUnsavedGuard/); assert.match(walk, /noValidate/);
  assert.match(src("components/arrivals/CheckInDialog.tsx"), /checkInSchema[\s\S]*useUnsavedGuard|useUnsavedGuard[\s\S]*checkInSchema/);
  const appt = src("(clinic)/schedule/NewAppointmentDialog.tsx");
  assert.match(appt, /bookingSchema/); assert.match(appt, /e164\(np\.phone/); assert.match(appt, /useUnsavedGuard/);
  const co = src("(clinic)/checkout/[queueEntryId]/page.tsx");
  assert.match(co, /discountSchema/); assert.match(co, /useUnsavedGuard/);
});
