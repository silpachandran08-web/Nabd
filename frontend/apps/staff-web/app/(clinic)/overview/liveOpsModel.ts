// ADM-042: pure helpers of the owner's live operations board (React-free, unit-tested in tests/unit/s27-adm042-live-ops.test.mjs).
// Matches GET /v1/reports/live (LiveOpsController -> LiveOpsResponse) and PUT /v1/reports/live/thresholds (OpsThresholds).

export type OpsThresholds = { pharmacyWaitMin: number; labTatBreach: boolean; requestWaitMin: number; doctorLateMin: number };
export type LiveDoctor = { id: string; name: string | null; status: string; waiting: number; booked: number; delayMin: number; longestWaitMin: number | null; told: number };
export type LiveStation = { open: number; longestWaitMin: number | null };
export type LiveAlert = {
  code: string; severity: string; summary: string; actions: string[]; minutes: number | null;
  doctorId: string | null; delayId: string | null; waiting: number | null; told: number | null; requestIds: string[] | null;
};
export type LiveOps = { asOf: string; thresholds: OpsThresholds; doctors: LiveDoctor[]; pharmacy: LiveStation; requests: LiveStation; lab: LiveStation | null; alerts: LiveAlert[] };

/** The SSE resource the `ops` topic makes stale (packages/api-client/sse resourcesFor). */
export const LIVE_OPS_RESOURCE = "live-ops";
/** The board's own refresh while it is on screen (prototype owner.ops): counts move without any alert. */
export const REFRESH_MS = 15_000;

const STATUS: Record<string, string> = { in: "In session", paused: "Paused", out: "Session ended", not_started: "Not started" };
export const statusLabel = (status: string): string => STATUS[status] ?? "Not started";
export const statusTone = (d: Pick<LiveDoctor, "status" | "delayMin">): "ok" | "warn" | "neutral" => (d.delayMin > 0 ? "warn" : d.status === "in" ? "ok" : "neutral");

/** "6 waiting · longest 14 min · 3 still to arrive"; a part with nothing to say is left out. */
export function doctorLine(d: LiveDoctor): string {
  const parts = [`${d.waiting} waiting`];
  if (d.waiting > 0 && d.longestWaitMin != null) parts.push(`longest ${d.longestWaitMin} min`);
  if (d.booked > 0) parts.push(`${d.booked} still to arrive`);
  if (d.delayMin > 0) parts.push(`${d.delayMin} min late`);
  if (d.told > 0) parts.push(`${d.told} told`);
  return parts.join(" · ");
}

export function stationLine(s: LiveStation, noun: string): string {
  if (s.open === 0) return `No ${noun} waiting`;
  return s.longestWaitMin == null ? `${s.open} waiting` : `${s.open} waiting · longest ${s.longestWaitMin} min`;
}

/** The booking-requests inbox filtered to the requests of an alert (ids only: never a name in a URL). */
export function requestsHref(ids: readonly string[] | null | undefined): string {
  const clean = (ids ?? []).filter((id) => /^[0-9a-f-]{36}$/i.test(id));
  return clean.length ? `/requests?ids=${clean.join(",")}` : "/requests";
}

export type ApiCall = { method: "post" | "patch"; path: string; body: Record<string, unknown> };
/**
 * 'Broadcast delay' through the existing delay broadcast (ACC-024): a delay that is already announced is changed (PATCH, which tells
 * everyone still to be seen; the 20-minute rule holds repeats), otherwise it is announced with the broadcast. Null when the alert
 * cannot be broadcast (no doctor, no minutes).
 */
export function broadcastCall(a: LiveAlert): ApiCall | null {
  if (a.code !== "doctor_late" || !a.doctorId || !a.minutes || a.minutes < 1) return null;
  const minutes = Math.min(240, Math.round(a.minutes)); // the delay endpoint takes 1..240
  return a.delayId
    ? { method: "patch", path: `/doctors/${a.doctorId}/delay/${a.delayId}`, body: { minutes } }
    : { method: "post", path: `/doctors/${a.doctorId}/delay`, body: { minutes, broadcast: true } };
}

export type AlertAction =
  | { kind: "broadcast"; label: string; call: ApiCall }
  | { kind: "link"; label: string; href: string };
/** What the viewer may do about an alert. An action the viewer has no grant for is absent, never disabled. */
export function alertActions(a: LiveAlert, can: (permission: string) => boolean): AlertAction[] {
  const out: AlertAction[] = [];
  for (const action of a.actions ?? []) {
    if (action === "broadcast_delay" && can("queue:edit")) {
      const call = broadcastCall(a);
      if (call) out.push({ kind: "broadcast", label: "Broadcast delay", call });
    } else if (action === "open_requests" && can("queue:create")) {
      out.push({ kind: "link", label: "Open requests", href: requestsHref(a.requestIds) });
    } else if (action === "open_pharmacy" && can("pharmacy:view")) {
      out.push({ kind: "link", label: "Open pharmacy", href: "/pharmacy" });
    }
  }
  return out;
}

/** What the confirm step says before patients are messaged. */
export function broadcastConfirmText(a: LiveAlert, doctorName: string | null | undefined): string {
  const who = doctorName || "The doctor";
  const told = a.told ?? 0;
  return `${who} is running ${a.minutes} min late. Everyone still to be seen is told on WhatsApp`
    + (told > 0 ? `; the ${told} already told are not messaged again within 20 minutes.` : ".");
}
export const broadcastDoneText = (recipients: number | null | undefined): string =>
  recipients ? `Delay notice sent to ${recipients} ${recipients === 1 ? "patient" : "patients"}.` : "Delay saved. Nobody new needed a message.";

export const alertTone = (a: Pick<LiveAlert, "severity">): "urgent" | "warn" => (a.severity === "critical" ? "urgent" : "warn");
export const alertLabel = (a: Pick<LiveAlert, "severity">): string => (a.severity === "critical" ? "Urgent" : "Needs attention");

export type ThresholdForm = { pharmacyWaitMin: string; requestWaitMin: string; doctorLateMin: string };
export const THRESHOLD_FIELDS: { key: keyof ThresholdForm; label: string }[] = [
  { key: "doctorLateMin", label: "Doctor late after (minutes)" },
  { key: "pharmacyWaitMin", label: "Pharmacy wait target (minutes)" },
  { key: "requestWaitMin", label: "Booking request waiting over (minutes)" },
];
export const toForm = (t: OpsThresholds): ThresholdForm =>
  ({ pharmacyWaitMin: String(t.pharmacyWaitMin), requestWaitMin: String(t.requestWaitMin), doctorLateMin: String(t.doctorLateMin) });
/** One sentence per bad field (whole minutes, 1 to 240); empty when the form can be saved. */
export function thresholdErrors(f: ThresholdForm): Partial<Record<keyof ThresholdForm, string>> {
  const out: Partial<Record<keyof ThresholdForm, string>> = {};
  for (const { key } of THRESHOLD_FIELDS) {
    const n = Number(f[key]);
    if (f[key].trim() === "" || !Number.isInteger(n) || n < 1 || n > 240) out[key] = "Enter whole minutes from 1 to 240.";
  }
  return out;
}
/** The PUT body. labTatBreach is kept as stored: the lab turnaround alert has no control until lab orders exist. */
export const toThresholds = (f: ThresholdForm, current: OpsThresholds): OpsThresholds =>
  ({ pharmacyWaitMin: Number(f.pharmacyWaitMin), labTatBreach: current.labTatBreach, requestWaitMin: Number(f.requestWaitMin), doctorLateMin: Number(f.doctorLateMin) });

/** Nothing at all to show: no doctor today, no station with work, no alert. */
export const boardEmpty = (d: LiveOps): boolean => d.doctors.length === 0 && d.alerts.length === 0 && d.pharmacy.open === 0 && d.requests.open === 0;
