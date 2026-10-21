// ADM-002: pure helpers for the DPA step (GET /v1/setup/dpa); the component only renders what these return.
export type DpaStatus = {
  version: string;
  marketCode: string;
  html: string;
  accepted: { version: string; at: string; by: string | null; method: string; acceptanceId: string } | null;
  dataGate: "demo_only" | "live";
  blockers: ("DPA_REQUIRED" | "PLAN_REQUIRED")[];
  reacceptDueBy: string | null;
};

const BLOCKER_TEXT: Record<string, string> = {
  DPA_REQUIRED: "Sign the data processing agreement",
  PLAN_REQUIRED: "Choose a plan",
};

export const blockerText = (b: string): string => BLOCKER_TEXT[b] ?? b;

/** Controller wording per market: the plain-language heading follows the law the clinic is under. */
export const roleWord = (marketCode: string): string => (marketCode === "IN" ? "data fiduciary" : "controller");

/** Non-blocking banner for a live clinic whose accepted DPA is older than the current one; null when nothing is due. */
export function reacceptBanner(s: DpaStatus | null): string | null {
  if (!s || !s.reacceptDueBy || !s.accepted) return null;
  const due = new Date(s.reacceptDueBy);
  const day = Number.isNaN(due.getTime()) ? s.reacceptDueBy : due.toISOString().slice(0, 10);
  return `A new data processing agreement (${s.version}) is available. Please accept it by ${day}. Nothing is blocked meanwhile.`;
}

/** Only an owner-capable caller sees signing controls; everyone else gets the read-only status. */
export const showSigning = (s: DpaStatus | null, mayEdit: boolean): boolean =>
  !!s && mayEdit && (s.accepted?.version !== s.version);

export const otpValid = (v: string): boolean => /^\d{6}$/.test(v);
