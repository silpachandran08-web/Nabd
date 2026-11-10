// ADM-037: the owner's plan page model (GET /v1/setup/subscription, change preview/change, Nabd invoices). Pure: unit-tested directly.
export type PlanOption = { code: string; name: string; version: number; monthlyMinor: number; pricingBasis: string; modules: string[]; seatLimit: number | null; branchLimit: number | null };
export type Usage = { seatsUsed: number; seatsLimit: number | null; branchesUsed: number; branchesLimit: number; whatsappUtilityMessages: number; whatsappPassThroughMinor: number | null; aiChecks: number | null };
export type PendingChange = { id: string; toPlanCode: string; direction: "upgrade" | "downgrade"; effectiveAt: string };
export type PlanSummary = {
  plan: string | null; status: string; planCode: string | null; version: number | null; renewalDate: string | null; currency: string | null;
  nextInvoice: { amountMinor: number; currency: string } | null; paymentMethod: string | null; usage: Usage | null;
  availablePlans: PlanOption[]; pendingChange: PendingChange | null;
};
export type Preview = { targetPlanCode: string; direction: "upgrade" | "downgrade"; effective: "now" | "renewal"; prorationMinor: number; currency: string;
  effectiveAt: string; modulesTurningReadOnly: string[]; branchesExceedTarget: boolean; requiresClinicName: boolean };
export type Invoice = { id: string; number: string; kind: string; period: string; periodStart: string; periodEnd: string; amountMinor: number; currency: string; status: string; issuedAt: string; pdfUrl: string | null };

/** Must equal OwnerPlanService.stepUpTarget: the MFA confirmation is good for this plan and timing only. */
export const stepUpTarget = (planCode: string, effective: "now" | "renewal") => `plan-change:${planCode}:${effective}`;

/** Same rule as the server: trimmed, case-insensitive. */
export const nameMatches = (typed: string, clinic: string) => typed.trim().toLocaleLowerCase() === clinic.trim().toLocaleLowerCase();

/** A meter's fill in percent; null when there is no limit. Over the limit stays 100. */
export const meterPct = (used: number, limit: number | null | undefined): number | null =>
  limit == null || limit <= 0 ? null : Math.min(100, Math.round((used / limit) * 100));

/** Upgrades apply now (pro rata); a downgrade waits for the renewal unless the owner chooses now. */
export const defaultEffective = (currentMonthly: number, target: PlanOption): "now" | "renewal" => (target.monthlyMinor > currentMonthly ? "now" : "renewal");
