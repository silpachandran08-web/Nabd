// FIN-036: the insurance screens' logic (policy card, checkout cover, patient / insurer split). Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const m = await import("../../app/lib/insurance.ts");

const read = (p) => readFileSync(new URL(p, import.meta.url), "utf8");
const cover = (over = {}) => ({
  visitId: "v1", visitDate: "2026-11-20", mode: "self_pay", policyId: null, payerName: null, eligibilityRef: null, copayPercent: null, locked: false,
  policies: [
    { id: "ok", payerName: "A", planName: "Gold", copayPercent: 20, memberIdMasked: "••••1234", validFrom: "2026-01-01", validTo: "2026-12-31", warning: null },
    { id: "old", payerName: "A", planName: "Gold", copayPercent: 20, memberIdMasked: "••••1234", validFrom: "2025-01-01", validTo: "2025-12-31", warning: "POLICY_EXPIRED" },
    { id: "noplan", payerName: "B", planName: null, copayPercent: null, memberIdMasked: "••••9", validFrom: "2026-01-01", validTo: "2026-12-31", warning: "PLAN_REQUIRED" },
  ], ...over,
});

test("a policy expired on the visit date is warned at once and cannot be saved (acceptance: warned before marking insured)", () => {
  const d = m.coverDraft(cover(), "insured", "old", "");
  assert.equal(d.warn, "expired");
  assert.equal(d.canSave, false);
  assert.equal(m.coverDraft(cover(), "insured", "noplan", "").warn, "planRequired");
  const ok = m.coverDraft(cover(), "insured", "ok", "ELIG-1");
  assert.deepEqual([ok.warn, ok.dirty, ok.canSave], [null, true, true]);
});

test("Save is absent for an unchanged draft, an insured draft with no policy and for self-pay -> self-pay", () => {
  assert.equal(m.coverDraft(cover(), "self_pay", "", "").canSave, false);
  assert.equal(m.coverDraft(cover(), "insured", "", "").canSave, false);
  const saved = cover({ mode: "insured", policyId: "ok", eligibilityRef: "E1" });
  assert.equal(m.coverDraft(saved, "insured", "ok", "E1").canSave, false);
  assert.equal(m.coverDraft(saved, "insured", "ok", "E2").canSave, true);
  assert.equal(m.coverDraft(saved, "self_pay", "", "").canSave, true);
});

test("the clinic without the insurance module sees nothing; unknown entitlements never hide", () => {
  const ent = (enabled) => ({ modules: [{ code: "insurance", enabled }], killedFeatures: [], seatLimit: null });
  assert.equal(m.insuranceOff(ent(false)), true);
  assert.equal(m.insuranceOff(ent(true)), false);
  assert.equal(m.insuranceOff(null), false);
});

test("the split reads the line shares by id, in minor units of the invoice currency", () => {
  const ins = { payerId: "p", patientShare: 160, payerShare: 640, payerPaid: 600, pendingDecision: 40,
    lines: [{ lineId: "a", patientShareMinor: 10000, payerShareMinor: 40000 }, { lineId: "b", patientShareMinor: 6000, payerShareMinor: 24000 }] };
  const by = m.sharesByLine(ins);
  assert.deepEqual(by.get("a"), { patientMinor: 10000, payerMinor: 40000 });
  assert.equal(by.size, 2);
  assert.equal(m.sharesByLine(null).size, 0);
  assert.equal(m.fromMinor(10050, "INR"), 100.5);
  assert.equal(m.fromMinor(12345, "KWD"), 12.345);
  assert.equal(m.fromMinor(500, "JPY"), 500);
  assert.equal(m.hasPendingDecision(ins), true);
  assert.deepEqual(m.splitSummary(ins), { patient: 160, payer: 640, payerOutstanding: 40 });
  assert.equal(m.hasPendingDecision({ ...ins, pendingDecision: 0 }), false);
});

test("the policy form validates before anything is sent", () => {
  const f = { ...m.EMPTY_FORM };
  assert.deepEqual(m.validatePolicy(f), ["payer", "memberId", "policyNumber", "dates"]);
  const good = { ...f, payerId: "p", memberId: " M1 ", policyNumber: "P1", validFrom: "2026-01-01", validTo: "2026-12-31" };
  assert.deepEqual(m.validatePolicy(good), []);
  assert.deepEqual(m.validatePolicy({ ...good, validTo: "2025-12-31" }), ["datesOrder"]);
  assert.deepEqual(m.validatePolicy({ ...good, payerId: m.NEW }), ["newPayerName"]);
  assert.deepEqual(m.validatePolicy({ ...good, planId: m.NEW, newPlanName: "G", newPlanCopay: "100.5" }), ["newPlanCopay"]);
  assert.deepEqual(m.validatePolicy({ ...good, planId: m.NEW, newPlanName: "G", newPlanCopay: "12.5" }), []);
  assert.deepEqual(m.toPolicyRequest(good, "p", null), { payerId: "p", memberId: "M1", policyNumber: "P1", validFrom: "2026-01-01", validTo: "2026-12-31", relationship: "self" });
  assert.equal(m.toPolicyRequest(good, "p", "pl").planId, "pl");
});

test("policy state, masking and error keys", () => {
  assert.equal(m.policyState({ status: "active", expired: false }), "active");
  assert.equal(m.policyState({ status: "active", expired: true }), "expired");
  assert.equal(m.policyState({ status: "ended", expired: false }), "ended");
  assert.equal(m.maskId("ABCD1234"), "••••1234");
  assert.equal(m.maskId("12"), "••••");
  const e = (status, code) => ({ status, problem: { code } });
  assert.equal(m.insuranceErrorKey(e(422, "POLICY_EXPIRED")), "policyExpired");
  assert.equal(m.insuranceErrorKey(e(422, "PAYER_UNKNOWN")), "payerUnknown");
  assert.equal(m.insuranceErrorKey(e(409, "VISIT_BILLED")), "visitBilled");
  assert.equal(m.insuranceErrorKey({ status: 403 }), "forbidden");
  assert.equal(m.insuranceErrorKey(new TypeError("fetch failed")), "generic");
});

test("restricted controls are absent, screens are mounted behind insurance:view, and every string exists in en and hi", () => {
  const card = read("../../app/(clinic)/patients/PolicyCard.tsx");
  const bar = read("../../app/components/checkout/CoverageBar.tsx");
  const split = read("../../app/components/checkout/InsuranceSplit.tsx");
  assert.match(card, /canEdit && !open/, "Add policy only with insurance:edit");
  assert.match(card, /canEdit && open/);
  assert.doesNotMatch(split, /<input|<select|<button/, "the split is read-only");
  const locked = bar.slice(bar.indexOf("if (c.locked || !canEdit)"), bar.indexOf("return (\n    <section className={styles.cover} aria-label={t(\"cover.title\")} aria-busy"));
  assert.ok(locked.length > 0);
  assert.doesNotMatch(locked, /<input|<select|<button/, "a billed visit or a view-only role gets text, not controls");
  assert.match(read("../../app/(clinic)/patients/drawer/OverviewTab.tsx"), /can\("insurance:view"\) && <PolicyCard/);
  assert.match(read("../../app/(clinic)/checkout/[queueEntryId]/page.tsx"), /can\("insurance:view"\) && <CoverageBar/);
  const ns = (l) => JSON.parse(read(`../../../../packages/i18n/messages/${l}/common.json`)).insurance;
  const keys = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`]));
  assert.deepEqual(keys(ns("hi")).sort(), keys(ns("en")).sort());
  const en = new Set(keys(ns("en")));
  for (const [src, base] of [[card, ""], [bar, ""], [split, "split."]]) {
    for (const k of src.matchAll(/\bt\("([a-zA-Z.]+)"/g)) assert.ok(en.has(base + k[1]), `missing ${base}${k[1]}`);
  }
  for (const k of ["states.active", "states.expired", "states.ended", ...["expired", "notStarted", "ended", "planRequired"].map((w) => `warnings.${w}`),
    ...["generic", "forbidden", "payerUnknown", "planUnknown", "invalidValidity", "duplicatePayer", "duplicatePlan", "policyExpired", "policyNotStarted", "policyEnded", "planRequired", "visitBilled"].map((w) => `errors.${w}`),
    ...m.RELATIONSHIPS.map((r) => `relationships.${r}`), ...m.PAYER_KINDS.map((r) => `kinds.${r}`),
    ...["payer", "memberId", "policyNumber", "dates", "datesOrder", "newPayerName", "newPlanName", "newPlanCopay"].map((w) => `form.errors.${w}`)]) assert.ok(en.has(k), `missing ${k}`);
});
