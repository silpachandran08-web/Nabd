// ADM-005/006: the onboarding team's console model (GET /v1/platform/activation/funnel|stuck, GET/POST /v1/platform/white-glove).
// Pure: unit-tested directly.
export type StepCount = { step: string; reached: number; completed: number };
export type Cohort = { week: string; signups: number; steps: StepCount[]; medianHoursToLive: number | null };
export type StuckItem = { tenantId: string; step: string; since: string; nudgesSent: number };
export type StuckPage = { items: StuckItem[]; nextCursor: string | null };
export type QueueItem = {
  id: string; tenantId: string; tenantName: string; status: "requested" | "scheduled" | "in_progress" | "done" | "cancelled";
  fee: { amountMinor: number; currency: string; status: string }; waiverReason: string | null; preferredSlots: string[]; notes: string | null;
  slotAt: string | null; operatorId: string | null; ticketId: string | null; coveredSteps: string[]; createdAt: string; closedAt: string | null;
};

export const STEP_LABELS: Record<string, string> = {
  profile: "Clinic profile", preset: "Clinic type", doctors: "Doctors", prices: "Prices", whatsapp: "WhatsApp number", tax: "Tax",
  dpa: "DPA", import: "Patient import", go_live: "Go live",
};
/** Steps Nabd may set up for a clinic (WhiteGloveService.COVERABLE): the DPA and go-live stay the owner's own. */
export const COVERABLE = ["profile", "preset", "doctors", "prices", "whatsapp", "tax", "import"];
export const MARKETS = ["IN", "SA", "AE", "QA"];

/** The one next forward step per status; cancel is offered on every open status (server: WhiteGloveService.NEXT). */
export const NEXT_ACTION: Partial<Record<QueueItem["status"], { toStatus: "scheduled" | "in_progress" | "done"; label: string }>> = {
  requested: { toStatus: "scheduled", label: "Schedule" },
  scheduled: { toStatus: "in_progress", label: "Start" },
  in_progress: { toStatus: "done", label: "Close" },
};
export const isOpen = (s: QueueItem["status"]) => s === "requested" || s === "scheduled" || s === "in_progress";

/** Bar width in percent of the cohort's sign-ups (0 when the cohort is empty), capped at 100. */
export const barPct = (n: number, signups: number) => (signups <= 0 ? 0 : Math.min(100, Math.round((n / signups) * 100)));
