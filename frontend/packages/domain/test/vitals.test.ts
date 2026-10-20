// NUR-023: the vitals capture form (/nursing Capture vitals, /arrivals Record vitals) must never let a blank
// or implausible save through, since a save moves the visit from vitals_pending to vitals_done.
// FEF-013: moved from staff-web tests/unit (node:test) to Vitest in packages/domain.
// The limits asserted here are the ones already in src/vitals.ts (mirroring VitalsWriteRequest);
// they await clinical sign-off (CLINICAL_SIGNOFF_PACK D14-D15) and this test does not add any.
import { test } from "vitest";
import assert from "node:assert/strict";
import { VITAL_FIELDS, EMPTY_VITALS, vitalsErrors, vitalsPayload, type VitalsExtras, type VitalsForm } from "../src/vitals.ts";

const form = (patch: Partial<VitalsForm>): VitalsForm => ({ ...EMPTY_VITALS, ...patch });

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

// NUR-021: combined BP box, blood sugar, pain score.
import { parseBp, nurseVitalsErrors, nurseVitalsPayload, EMPTY_EXTRAS } from "../src/vitals.ts";

test("parseBp accepts 120/80 and 120 / 80, blank, and rejects junk", () => {
  assert.deepEqual(parseBp("120/80"), { sys: "120", dia: "80" });
  assert.deepEqual(parseBp(" 120 / 80 "), { sys: "120", dia: "80" });
  assert.deepEqual(parseBp(""), { sys: "", dia: "" });
  assert.ok(parseBp("120").error);
  assert.ok(parseBp("a/b").error);
});

test("nurse form: BP errors land on the combined bp key; systolic must exceed diastolic", () => {
  assert.ok(nurseVitalsErrors(EMPTY_VITALS, "120", EMPTY_EXTRAS).bp);
  assert.ok(nurseVitalsErrors(EMPTY_VITALS, "80/120", EMPTY_EXTRAS).bp);
  assert.deepEqual(nurseVitalsErrors(EMPTY_VITALS, "120/80", EMPTY_EXTRAS), {});
});

test("nurse form: pain 0-10 whole numbers; a lone blood sugar or pain value is enough to save; blank is refused", () => {
  const x = (o: Partial<VitalsExtras>): VitalsExtras => ({ ...EMPTY_EXTRAS, ...o });
  assert.ok(nurseVitalsErrors(EMPTY_VITALS, "", x({ painScore: "11" })).painScore);
  assert.ok(nurseVitalsErrors(EMPTY_VITALS, "", x({ painScore: "2.5" })).painScore);
  assert.deepEqual(nurseVitalsErrors(EMPTY_VITALS, "", x({ painScore: "0" })), {});
  assert.ok(nurseVitalsErrors(EMPTY_VITALS, "", x({ bloodSugar: "0" })).bloodSugar);
  assert.deepEqual(nurseVitalsErrors(EMPTY_VITALS, "", x({ bloodSugar: "5.4" })), {});
  assert.ok(nurseVitalsErrors(EMPTY_VITALS, "", EMPTY_EXTRAS).form);
});

test("nurse payload splits BP and sends nulls for blanks", () => {
  const p: Record<string, number | null> = nurseVitalsPayload(EMPTY_VITALS, "120/80", { bloodSugar: "", painScore: "3" });
  assert.equal(p.bpSystolic, 120); assert.equal(p.bpDiastolic, 80);
  assert.equal(p.bloodSugar, null); assert.equal(p.painScore, 3); assert.equal(p.pulseBpm, null);
});
