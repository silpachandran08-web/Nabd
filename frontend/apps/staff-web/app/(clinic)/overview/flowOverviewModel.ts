// ADM-041: pure helpers of the owner's flow overview (GET /v1/reports/overview/flow), unit-tested without React.
// shortcut: English strings live here, like the rest of the overview; move to packages/i18n with the whole screen.

export type FlowRange = "today" | "d7" | "d30";
export type FlowTile = {
  code: string; value: number | null; unit: "min" | "pct" | "count" | "money"; delta: number | null; target: number | null;
  lowerIsBetter: boolean | null; n: number; trend: (number | null)[];
};
export type FlowOverview = {
  range: FlowRange; facilityId: string; from: string; to: string; previousFrom: string; previousTo: string; refreshedAt: string | null; currency: string | null;
  tiles: FlowTile[];
  waitByHour: { hour: number; minutes: number }[];
  etaAccuracy14d: { day: string; pct: number | null; n: number }[];
  timeSplit: { checkinToVitals: number | null; waiting: number | null; consult: number | null; checkout: number | null };
  punctuality: { doctorId: string; doctorName: string | null; sessions: number; onTimePct: number }[];
};
export type Delta = { text: string; direction: "up" | "down" | "flat"; tone: "better" | "worse" | "neutral" };

export const RANGES: [FlowRange, string][] = [["today", "Today"], ["d7", "7 days"], ["d30", "30 days"]];
export const TIME_TILES = ["wait", "door_to_doctor", "consult", "eta_accuracy", "booking_response", "no_show_rate"];
export const BUSINESS_TILES = ["visits", "revenue"];
export const TILE_LABELS: Record<string, string> = {
  wait: "Median wait", door_to_doctor: "Door to doctor", consult: "Consult time", eta_accuracy: "ETA accuracy",
  booking_response: "Booking response", no_show_rate: "No-show rate", visits: "Visits", revenue: "Revenue",
};
export const TILE_HINTS: Record<string, string> = {
  wait: "Ready for the doctor to consult start", door_to_doctor: "Check-in to consult start", consult: "Median consult length",
  eta_accuracy: "Called within 10 min of the time we showed", booking_response: "Online request to decision",
  no_show_rate: "Booked, never arrived", visits: "Checked-in visits", revenue: "Billed, before tax",
};

// Report keys the tiles open (ADM-047 library, GET /v1/reports/{reportId}); the route is /reports?report=<key>&from&to&facility.
export const REPORT_KEYS: Record<string, string> = {
  wait: "visit-times", door_to_doctor: "visit-times", consult: "visit-times", eta_accuracy: "eta-accuracy", booking_response: "booking-response",
  no_show_rate: "no-show-risk", visits: "sources", revenue: "daily-money", punctuality: "doctor-punctuality",
};

/** The report behind a tile, on the same facility and dates the tile was computed for. */
export function reportHref(code: string, o: Pick<FlowOverview, "from" | "to" | "facilityId">): string {
  const q = new URLSearchParams({ report: REPORT_KEYS[code] ?? code, from: o.from, to: o.to, facility: o.facilityId });
  return `/reports?${q.toString()}`;
}

const COMPARED: Record<FlowRange, string> = { today: "the same day last week", d7: "the previous 7 days", d30: "the previous 30 days" };
const num = (v: number) => (Math.round(v * 10) / 10).toString();

export function valueText(t: FlowTile, money: (n: number) => string): string {
  if (t.value === null) return "No data";
  return t.unit === "min" ? `${num(t.value)} min` : t.unit === "pct" ? `${num(t.value)}%` : t.unit === "money" ? money(t.value) : Math.round(t.value).toLocaleString("en-GB");
}

/** The change in words, the arrow, and whether it is good: tone follows lowerIsBetter, never the sign (a falling wait is down + better). */
export function deltaOf(t: FlowTile, range: FlowRange): Delta | undefined {
  if (t.delta === null || t.value === null) return undefined;
  const d = Math.round(t.delta * 10) / 10;
  const direction = d > 0 ? "up" : d < 0 ? "down" : "flat";
  const tone = d === 0 || t.lowerIsBetter === null ? "neutral" : (d < 0) === t.lowerIsBetter ? "better" : "worse";
  const unit = t.unit === "min" ? " min" : t.unit === "pct" ? " pts" : "";
  const words = t.unit === "money" || t.unit === "count" ? `${d > 0 ? "up" : "down"} ${Math.abs(Math.round(t.delta)).toLocaleString("en-GB")}`
    : `${num(Math.abs(d))}${unit} ${d < 0 ? (t.lowerIsBetter ? "faster" : "lower") : t.lowerIsBetter ? "slower" : "higher"}`;
  return { direction, tone, text: d === 0 ? `No change vs ${COMPARED[range]}` : `${words} vs ${COMPARED[range]}` };
}

/** A tile that misses its target (above for lower-is-better, below otherwise); null when there is no target or no value. */
export function targetMissed(t: FlowTile): boolean | null {
  if (t.target === null || t.value === null || t.lowerIsBetter === null) return null;
  return t.lowerIsBetter ? t.value > t.target : t.value < t.target;
}

export function targetText(t: FlowTile): string | undefined {
  return t.target === null ? undefined : `Target ${t.lowerIsBetter ? "under" : "at least"} ${num(t.target)}${t.unit === "pct" ? "%" : " min"}`;
}

export function trendText(t: FlowTile, range: FlowRange): string {
  const known = t.trend.filter((v): v is number => v !== null);
  if (known.length < 2) return `${TILE_LABELS[t.code] ?? t.code}: not enough days for a trend`;
  const first = known[0], last = known[known.length - 1];
  return `${TILE_LABELS[t.code] ?? t.code} ${last > first ? "rising" : last < first ? "falling" : "flat"} over ${range === "today" ? 7 : range === "d7" ? 7 : 30} days`;
}

/** Chart input: hour labels like "09:00" and the minutes; hours with no visits are absent (the server omits them). */
export function waitByHourSeries(rows: FlowOverview["waitByHour"]) {
  return { categories: rows.map((r) => `${String(r.hour).padStart(2, "0")}:00`), values: rows.map((r) => r.minutes) };
}

export function etaSeries(rows: FlowOverview["etaAccuracy14d"]) {
  return { categories: rows.map((r) => r.day.slice(5)), values: rows.map((r) => r.pct) };
}

/** Stages in order with their median minutes; a stage that was not measured is left out rather than shown as 0. */
export function splitRows(s: FlowOverview["timeSplit"]): { label: string; minutes: number }[] {
  return ([["Check-in to vitals", s.checkinToVitals], ["Waiting for the doctor", s.waiting], ["Consultation", s.consult], ["Checkout", s.checkout]] as [string, number | null][])
    .filter((r): r is [string, number] => r[1] !== null).map(([label, minutes]) => ({ label, minutes }));
}
