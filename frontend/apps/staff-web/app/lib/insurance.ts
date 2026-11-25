// FIN-036: pure helpers of the insurance screens (patient file policy card, checkout cover and split). Unit-tested; no React here.
import { minorDigits } from "@nabd/i18n";
import type { Entitlements } from "./entitlementRules";

export const RELATIONSHIPS = ["self", "spouse", "child", "parent", "other"] as const;
export const PAYER_KINDS = ["insurer", "tpa", "government_scheme"] as const;
/** Sentinel option values of the payer / plan selects: "add a new one here". */
export const NEW = "__new__";

export type Plan = { id: string; name: string; copayPercent: number; global: boolean };
export type Payer = { id: string; name: string; kind: string; jurisdiction: string; identifiers: Record<string, string>; global: boolean; plans: Plan[] };
export type Policy = {
  id: string; payerId: string; payerName: string; planId: string | null; planName: string | null; copayPercent: number | null;
  memberId: string; policyNumber: string; validFrom: string; validTo: string; relationship: string; status: string; expired: boolean;
};
export type CoveragePolicy = {
  id: string; payerName: string; planName: string | null; copayPercent: number | null; memberIdMasked: string;
  validFrom: string; validTo: string; warning: string | null;
};
export type Coverage = {
  visitId: string; visitDate: string; mode: "self_pay" | "insured"; policyId: string | null; payerName: string | null;
  eligibilityRef: string | null; copayPercent: number | null; locked: boolean; policies: CoveragePolicy[];
};
// Optional fields: the same shape the generated client types give the invoice response.
export type InsuranceLine = { lineId?: string; patientShareMinor?: number; payerShareMinor?: number };
export type InvoiceInsurance = {
  payerId?: string; patientShare?: number; payerShare?: number; payerPaid?: number; pendingDecision?: number; lines?: InsuranceLine[];
};

/** The clinic's plan has the insurance module switched off (CMX-15 'no insurance'): the whole stack is absent. Unknown never hides. */
export function insuranceOff(ent: Entitlements | null): boolean {
  return !!ent && ent.modules.some((m) => m.code === "insurance" && !m.enabled);
}

/** i18n key (common.insurance.states.*) of a stored policy; expired comes from the server's clinic date. */
export function policyState(p: Pick<Policy, "status" | "expired">): "active" | "expired" | "ended" {
  return p.status !== "active" ? "ended" : p.expired ? "expired" : "active";
}

/** All but the last four characters hidden; short values are hidden whole. */
export function maskId(v: string): string {
  return v.length <= 4 ? "••••" : "•".repeat(v.length - 4) + v.slice(-4);
}

/** i18n key (common.insurance.warnings.*) of a coverage policy warning code; null when the policy can be used. */
export function warningKey(code: string | null): "expired" | "notStarted" | "ended" | "planRequired" | null {
  switch (code) {
    case "POLICY_EXPIRED": return "expired";
    case "POLICY_NOT_STARTED": return "notStarted";
    case "POLICY_ENDED": return "ended";
    case "PLAN_REQUIRED": return "planRequired";
    default: return code ? "planRequired" : null;
  }
}

export type PolicyForm = {
  payerId: string; planId: string; memberId: string; policyNumber: string; validFrom: string; validTo: string; relationship: string;
  newPayerName: string; newPayerKind: string; newPlanName: string; newPlanCopay: string;
};
export const EMPTY_FORM: PolicyForm = {
  payerId: "", planId: "", memberId: "", policyNumber: "", validFrom: "", validTo: "", relationship: "self",
  newPayerName: "", newPayerKind: "insurer", newPlanName: "", newPlanCopay: "",
};

/** A co-pay percentage as the server takes it: 0 to 100 with at most two decimals. */
export const validCopay = (s: string): boolean => /^\d{1,3}(\.\d{1,2})?$/.test(s.trim()) && Number(s) >= 0 && Number(s) <= 100;

/** i18n keys (common.insurance.form.errors.*) of what is wrong with the form; empty = can be sent. */
export function validatePolicy(f: PolicyForm): string[] {
  const out: string[] = [];
  if (!f.payerId) out.push("payer");
  else if (f.payerId === NEW && !f.newPayerName.trim()) out.push("newPayerName");
  if (f.planId === NEW) {
    if (!f.newPlanName.trim()) out.push("newPlanName");
    if (!validCopay(f.newPlanCopay)) out.push("newPlanCopay");
  }
  if (!f.memberId.trim()) out.push("memberId");
  if (!f.policyNumber.trim()) out.push("policyNumber");
  if (!f.validFrom || !f.validTo) out.push("dates");
  else if (f.validTo < f.validFrom) out.push("datesOrder");
  return out;
}

/** The POST body of /insurance/patients/{id}/policies; payerId / planId are the real ids (a new insurer or plan is created first). */
export function toPolicyRequest(f: PolicyForm, payerId: string, planId: string | null) {
  return {
    payerId, ...(planId ? { planId } : {}), memberId: f.memberId.trim(), policyNumber: f.policyNumber.trim(),
    validFrom: f.validFrom, validTo: f.validTo, relationship: f.relationship,
  };
}

/** i18n key (common.insurance.errors.*) for a failed insurance call. */
export function insuranceErrorKey(e: unknown): string {
  const status = (e as { status?: number } | null)?.status;
  const code = (e as { problem?: { code?: unknown } } | null)?.problem?.code;
  switch (code) {
    case "PAYER_UNKNOWN": return "payerUnknown";
    case "PLAN_UNKNOWN": return "planUnknown";
    case "INVALID_VALIDITY": return "invalidValidity";
    case "DUPLICATE_PAYER": return "duplicatePayer";
    case "DUPLICATE_PLAN": return "duplicatePlan";
    case "POLICY_EXPIRED": return "policyExpired";
    case "POLICY_NOT_STARTED": return "policyNotStarted";
    case "POLICY_ENDED": return "policyEnded";
    case "PLAN_REQUIRED": return "planRequired";
    case "VISIT_BILLED": return "visitBilled";
    default: return status === 403 ? "forbidden" : "generic";
  }
}

/** Minor units to a major-unit number for the currency (2 decimals for INR/AED/SAR, 3 for KWD/BHD/OMR, 0 for JPY). */
export function fromMinor(minor: number, currency: string): number {
  return minor / 10 ** minorDigits(currency);
}

/** One invoice line's two shares by line id; empty when the invoice is not insured. */
export function sharesByLine(ins: InvoiceInsurance | null | undefined): Map<string, { patientMinor: number; payerMinor: number }> {
  return new Map((ins?.lines ?? []).filter((l) => l.lineId).map((l) => [l.lineId as string, { patientMinor: l.patientShareMinor ?? 0, payerMinor: l.payerShareMinor ?? 0 }]));
}

/** Whether to show the 'waiting for a decision' row: the insurer paid part of its share and the rest is neither the patient's nor written off. */
export const hasPendingDecision = (ins: InvoiceInsurance): boolean => (ins.pendingDecision ?? 0) > 0;

/** Who pays what, as the desk reads it before collecting: the patient's part and the insurer's part of the total. */
export function splitSummary(ins: InvoiceInsurance): { patient: number; payer: number; payerOutstanding: number } {
  const payer = ins.payerShare ?? 0;
  return { patient: ins.patientShare ?? 0, payer, payerOutstanding: Math.max(0, payer - (ins.payerPaid ?? 0)) };
}

/**
 * The desk's draft of a visit's cover against what is saved. warn: the picked policy cannot cover the visit date (shown at once);
 * canSave is false for a warned policy, an unchanged draft and an insured draft without a policy: the Save button is then absent.
 */
export function coverDraft(c: Coverage, mode: "self_pay" | "insured", policyId: string, ref: string):
  { warn: ReturnType<typeof warningKey>; dirty: boolean; canSave: boolean } {
  const picked = c.policies.find((p) => p.id === policyId);
  const warn = mode === "insured" ? warningKey(picked?.warning ?? null) : null;
  const dirty = mode !== c.mode || (mode === "insured" && (policyId !== (c.policyId ?? "") || ref !== (c.eligibilityRef ?? "")));
  return { warn, dirty, canSave: dirty && !warn && (mode === "self_pay" || !!picked) };
}
