import { test } from "vitest";
import assert from "node:assert/strict";
import { isAbhaAddress, isAbhaId, isAbhaNumber, maskAbha, parseAbhaQr } from "../src/abha.ts";

test("number accepts 14 digits with spaces or dashes only", () => {
  assert.ok(isAbhaNumber("12-3456-7890-1234"));
  assert.ok(isAbhaNumber("12345678901234"));
  assert.ok(!isAbhaNumber("1234567890123"));
  assert.ok(!isAbhaNumber("1234567890123a"));
});
test("address needs @abdm or @sbx", () => {
  assert.ok(isAbhaAddress("asha.k@abdm"));
  assert.ok(!isAbhaAddress("asha@gmail.com"));
  assert.ok(isAbhaId("asha.k@sbx") && !isAbhaId("x"));
});
test("mask shows last 4 only", () => assert.equal(maskAbha("1234"), "XX-XXXX-XXXX-1234"));
test("QR prefills name, dob, gender, mobile", () => {
  const p = parseAbhaQr(JSON.stringify({ name: "Test Person", dob: "02-01-1990", gender: "F", mobile: "9000000000", hidn: "12-3456-7890-1234", hid: "tp.person@abdm" }));
  assert.deepEqual(p, { name: "Test Person", dob: "1990-01-02", gender: "female", phone: "+919000000000", abhaNumber: "12345678901234", abhaAddress: "tp.person@abdm" });
});
test("unreadable or empty QR gives null", () => {
  assert.equal(parseAbhaQr("not json"), null);
  assert.equal(parseAbhaQr("{}"), null);
  assert.equal(parseAbhaQr("[1]"), null);
});
