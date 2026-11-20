// ADM-020: the operator's lifecycle console (GET /v1/platform/tenants/{id}/lifecycle, POST .../lifecycle/transitions).
// Pure model, unit-tested; the page only renders what these return. The server owns the transition table: the next states
// offered are the ones it lists (allowedTransitions), never a copy of the table here.
export const LIFECYCLE_STATES = ["provisioning", "trialing", "active", "overdue", "suspended", "offboarding", "offboarded"] as const;

export type LifecycleEvent = {
  fromStatus: string; toStatus: string; changedBy: string | null; reason: string; changedAt: string;
  actorType?: "operator" | "staff" | "system"; operatorName?: string | null;
};
export type TenantLifecycle = {
  tenantId: string; status: string; events: LifecycleEvent[]; allowedTransitions?: string[];
  tenantName?: string | null; tenantSlug?: string | null;
};

/** Same floor as Billing & Revenue's dunning dialog. The server refuses a blank reason; the status-changed event carries at most 500 characters. */
export const MIN_REASON = 10;
export const MAX_REASON = 500;

export const stateLabel = (s: string): string => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

/** What the move does, shown before the operator confirms. danger = the clinic loses something at once. */
export const TRANSITION_EFFECT: Record<string, { title: string; note: string; danger: boolean }> = {
  trialing: { title: "Start the trial", note: "Provisioning is done: the clinic can sign in and try Nabd on its trial clock.", danger: false },
  active: { title: "Make the clinic active", note: "Full access at once: booking, billing and patient messaging work. The payment and read-only banners go away.", danger: false },
  overdue: { title: "Mark as payment overdue", note: "Everything keeps working. Only the owner sees a payment banner with the amount due.", danger: false },
  suspended: {
    title: "Suspend this clinic",
    note: "Every signed-in session ends. Staff can sign in again but only to view and export: every change is refused. The owners are told.",
    danger: true,
  },
  offboarding: { title: "Start offboarding", note: "The clinic is leaving: this opens its export window before the data is removed. The owners are told. The only next step is offboarded.", danger: true },
  offboarded: { title: "Close the clinic (offboarded)", note: "Final. Every session ends, nobody can sign in again and no further move is possible.", danger: true },
};

/** The states to offer, in ladder order. An older server without allowedTransitions offers nothing (the control stays absent). */
export const nextStates = (l: Pick<TenantLifecycle, "allowedTransitions"> | null): string[] =>
  LIFECYCLE_STATES.filter((s) => !!l?.allowedTransitions?.includes(s));

/** Moving a tenant needs tenant_detail:edit (super admin); reading the history needs tenant_detail:view. */
export const canMove = (permissions: readonly string[] | undefined): boolean => !!permissions?.includes("tenant_detail:edit");
export const canViewLifecycle = (permissions: readonly string[] | undefined): boolean => !!permissions?.includes("tenant_detail:view");

/** Null when the reason can be sent, else the sentence to show. */
export function reasonProblem(reason: string): string | null {
  const r = reason.trim();
  if (r.length < MIN_REASON) return `Give a reason of at least ${MIN_REASON} characters. It is written to the history and the audit log.`;
  if (r.length > MAX_REASON) return `Keep the reason under ${MAX_REASON} characters.`;
  return null;
}

/** Who made the move: the operator by name, the clinic's own staff (go live) or the system (trial clock) by kind. */
export function actorLabel(e: Pick<LifecycleEvent, "actorType" | "operatorName" | "changedBy">): string {
  if (e.actorType === "staff") return "Clinic staff";
  if (e.actorType === "system" || (!e.actorType && !e.changedBy)) return "System";
  return e.operatorName?.trim() || "Nabd operator";
}
