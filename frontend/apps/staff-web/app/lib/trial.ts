// ADM-004 / ADM-011: pure helpers for the trial chip, the "Start for real" dialog and the go-live step. Components only render what these return.
export type TrialStatus = {
  status: string;
  endsAt: string | null;
  daysLeft: number | null;
  blockers: ("dpa" | "plan")[];
  conversion: { jobId: string; status: "queued" | "running" | "done" | "failed"; syntheticRowsDeleted: number } | null;
};
export type GoLiveChecks = {
  blockers: { code: string; ok: boolean; fixUrl: string | null }[];
  niceToHave: { code: string; ok: boolean; note: string | null }[];
};
export type GoLiveResult = { liveAt: string; bookingUrl: string; qrPosterUrl: string };

/** Only a clinic that is on its trial sees the chip. */
export const showTrialChip = (s: TrialStatus | null): boolean => !!s && s.status === "trialing";

export function trialChipText(s: TrialStatus): string {
  const d = s.daysLeft;
  if (d == null) return "Trial";
  if (d <= 0) return "Trial ends today";
  return d === 1 ? "Trial: 1 day left" : `Trial: ${d} days left`;
}

/** The dialog states plainly what a conversion keeps and what it deletes (the server does the same: demo patients only). */
export const CONVERT_KEEPS = ["Clinic profile, departments, doctors and staff", "Prices, templates and every other setting"];
export const CONVERT_DELETES = ["Demo patients and everything attached to them (visits, bills, queue entries, notes)"];

export const conversionDone = (s: TrialStatus | null): boolean => !!s && s.status !== "trialing" && s.conversion?.status === "done";
export const conversionFailed = (s: TrialStatus | null): boolean => s?.conversion?.status === "failed";
export const conversionRunning = (s: TrialStatus | null): boolean => s?.conversion?.status === "queued" || s?.conversion?.status === "running";

const CHECK_TEXT: Record<string, string> = {
  doctor_schedule: "At least one doctor has working hours",
  price_list: "A price list is set up",
  whatsapp_sender: "A WhatsApp number is on file",
  dpa_signed: "Data processing agreement signed",
  waiting_room_tv: "Waiting-room screen paired",
  patients_imported: "Patients imported",
  staff_invited: "Staff invited",
  tax_id: "Tax registration number entered",
  einvoice_phase2: "E-invoicing (phase 2) connected",
};
export const checkText = (code: string): string => CHECK_TEXT[code] ?? code;

/** Go live needs every blocker ok; an empty or missing list never counts as ready. */
export const canGoLive = (c: GoLiveChecks | null): boolean => !!c && c.blockers.length > 0 && c.blockers.every((b) => b.ok);
export const openBlockers = (c: GoLiveChecks | null) => (c ? c.blockers.filter((b) => !b.ok) : []);
