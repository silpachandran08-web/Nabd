// NUR-023: the vitals capture form (/nursing Capture vitals, /arrivals Record vitals) must never let a blank
// or implausible save through, since a save moves the visit from vitals_pending to vitals_done.
// Runs with plain Node (type stripping imports the .ts module directly): `npm run -s test:unit`.
// The limits asserted here are the ones already in app/lib/vitals.ts (mirroring VitalsWriteRequest);
// they await clinical sign-off (CLINICAL_SIGNOFF_PACK D14-D15) and this test does not add any.
import { test } from "node:test";
import assert from "node:assert/strict";
import { VITAL_FIELDS, EMPTY_VITALS, vitalsErrors, vitalsPayload } from "../../app/lib/vitals.ts";

const form = (patch) => ({ ...EMPTY_VITALS, ...patch });

test("a completely blank form is blocked with a form-level error", () => {
  const errs = vitalsErrors(form({}));
  assert.ok(errs.form, "blank save must be refused");
});

test("whitespace-only entries count as blank", () => {
  assert.ok(vitalsErrors(form({ pulseBpm: "   " })).form);
});

test("one plausible value is enough", () => {
  assert.deepEqual(vitalsErrors(form({ pulseBpm: "72" })), {});
});

test("every field rejects values outside its own hard limits and accepts the limits themselves", () => {
  for (const d of VITAL_FIELDS) {
    if (d.key === "bpSystolic" || d.key === "bpDiastolic") continue; // BP is a pair, covered below
    const below = d.step === "1" ? d.min - 1 : +(d.min - 0.1).toFixed(1);
    const above = d.step === "1" ? d.max + 1 : +(d.max + 0.1).toFixed(1);
    assert.ok(vitalsErrors(form({ [d.key]: String(below) }))[d.key], `${d.key} ${below} should be refused`);
    assert.ok(vitalsErrors(form({ [d.key]: String(above) }))[d.key], `${d.key} ${above} should be refused`);
    assert.equal(vitalsErrors(form({ [d.key]: String(d.min) }))[d.key], undefined, `${d.key} min accepted`);
    assert.equal(vitalsErrors(form({ [d.key]: String(d.max) }))[d.key], undefined, `${d.key} max accepted`);
  }
});

test("the classic typo temp 370 is blocked", () => {
  assert.ok(vitalsErrors(form({ tempCelsius: "370" })).tempCelsius);
});

test("non-numbers and fractional whole-number fields are refused", () => {
  assert.ok(vitalsErrors(form({ weightKg: "abc" })).weightKg);
  assert.ok(vitalsErrors(form({ pulseBpm: "72.5" })).pulseBpm);
  assert.ok(vitalsErrors(form({ spo2Percent: "97.5" })).spo2Percent);
});

test("blood pressure needs both values", () => {
  assert.ok(vitalsErrors(form({ bpSystolic: "120" })).bpDiastolic);
  assert.ok(vitalsErrors(form({ bpDiastolic: "80" })).bpSystolic);
});

test("systolic must be higher than diastolic", () => {
  assert.ok(vitalsErrors(form({ bpSystolic: "80", bpDiastolic: "120" })).bpSystolic);
  assert.ok(vitalsErrors(form({ bpSystolic: "90", bpDiastolic: "90" })).bpSystolic);
  assert.deepEqual(vitalsErrors(form({ bpSystolic: "120", bpDiastolic: "80" })), {});
});

test("payload sends blanks as null (server keeps the stored value) and numbers as numbers", () => {
  const p = vitalsPayload(form({ pulseBpm: "72", tempCelsius: "36.8" }));
  assert.equal(p.pulseBpm, 72);
  assert.equal(p.tempCelsius, 36.8);
  assert.equal(p.bpSystolic, null);
  assert.equal(Object.keys(p).length, VITAL_FIELDS.length);
});
