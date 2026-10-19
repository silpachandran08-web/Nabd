// FEF-011: the one place staff-web turns an instant into a clinic date or time. Every helper formats in the
// clinic's IANA zone (tenants.timezone, loaded once at shell boot), never the device's zone or locale.
// eslint bans toLocale*String and new Intl.DateTimeFormat in migrated files; they come here instead.
// Moves into packages/i18n in FEF-037.

export const CLINIC_LOCALE = "en-GB";

/** Used only when tenants.timezone is null or unusable. Backend regions are IN and KSA; AE/SA/QA are the Gulf codes. */
export const REGION_DEFAULT_TZ: Record<string, string> = {
  IN: "Asia/Kolkata", AE: "Asia/Dubai", SA: "Asia/Riyadh", KSA: "Asia/Riyadh", QA: "Asia/Qatar",
};

let zone: string | null = null;
let nowMs: () => number = () => Date.now();
const listeners = new Set<() => void>();
let ready: Promise<string> | null = null;
let readyFor: string | null = null;
let warned = false;

export function getClinicTimeZone(): string | null {
  return zone;
}

export function setClinicTimeZone(tz: string): void {
  zone = tz;
  listeners.forEach((l) => l());
}

export function subscribeClinicTimeZone(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** Tests freeze "now" here; pass nothing to go back to the real clock. */
export function setClinicNow(fn?: () => number): void {
  nowMs = fn ?? (() => Date.now());
}

/** Test reset: forget the loaded zone and the one-time warning. */
export function resetClinicTimeZone(): void {
  zone = null;
  ready = null;
  readyFor = null;
  warned = false;
}

export function isValidTimeZone(tz: unknown): tz is string {
  if (typeof tz !== "string" || !(tz.includes("/") || tz === "UTC")) return false; // IANA ids only, same rule as ClinicClock
  try {
    new Intl.DateTimeFormat(CLINIC_LOCALE, { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/** The configured zone when usable, else the region default (fallback: true). Unknown region: UTC, the backend's own fallback. */
export function resolveClinicTimeZone(configured: unknown, region: string | null | undefined): { tz: string; fallback: boolean } {
  if (isValidTimeZone(configured)) return { tz: configured, fallback: false };
  return { tz: REGION_DEFAULT_TZ[region ?? ""] ?? "UTC", fallback: true };
}

const fmtCache = new Map<string, Intl.DateTimeFormat>();
function formatter(tz: string, opts: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = tz + JSON.stringify(opts);
  let f = fmtCache.get(key);
  if (!f) { f = new Intl.DateTimeFormat(CLINIC_LOCALE, { ...opts, timeZone: tz }); fmtCache.set(key, f); }
  return f;
}

const tzOr = (tz?: string | null) => tz ?? zone ?? "UTC";
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/** A calendar date ("2026-03-10") is not an instant: it is shown as-is, never shifted by any zone. */
function toDate(value: string | number | Date): { d: Date; dateOnly: boolean } {
  if (typeof value === "string" && DATE_ONLY.test(value)) return { d: new Date(`${value}T00:00:00Z`), dateOnly: true };
  return { d: value instanceof Date ? value : new Date(value), dateOnly: false };
}

/** {date: "YYYY-MM-DD", time: "HH:mm"} of an instant on the clinic's wall clock. */
export function toClinicDateTime(iso: string | number | Date, tz?: string | null): { date: string; time: string } {
  const parts = formatter(tzOr(tz), { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
    .formatToParts(toDate(iso).d);
  const p = (t: string) => parts.find((x) => x.type === t)?.value ?? "";
  return { date: `${p("year")}-${p("month")}-${p("day")}`, time: `${p("hour")}:${p("minute")}` };
}

/** Today's date ("YYYY-MM-DD") in the clinic, whatever the device clock's zone. */
export function clinicToday(tz?: string | null): string {
  return toClinicDateTime(nowMs(), tz).date;
}

/** An instant or a calendar date, formatted in the clinic zone (default: "10 Mar 2026"). Unparseable input is returned as-is. */
export function formatClinicDate(value: string | number | Date, opts: Intl.DateTimeFormatOptions = { day: "2-digit", month: "short", year: "numeric" },
  tz?: string | null): string {
  const { d, dateOnly } = toDate(value);
  if (Number.isNaN(d.getTime())) return String(value);
  return formatter(dateOnly ? "UTC" : tzOr(tz), opts).format(d);
}

/** "HH:mm" (24h) of an instant on the clinic's wall clock. */
export function formatClinicTime(iso: string | number | Date, tz?: string | null): string {
  const { d } = toDate(iso);
  return Number.isNaN(d.getTime()) ? String(iso) : toClinicDateTime(d, tz).time;
}

type Fetcher = (path: string) => Promise<Response>;

async function configuredZone(fetcher: Fetcher): Promise<unknown> {
  try {
    const profile = await fetcher("/setup/profile");
    if (profile.ok) return ((await profile.json()) as { timezone?: unknown }).timezone ?? null;
    if (profile.status !== 403) return null;
    // No setup:view (most doctors): the schedule board carries the same clinic zone under queue:view.
    const day = await fetcher("/schedule/day");
    return day.ok ? ((await day.json()) as { timezone?: unknown }).timezone ?? null : null;
  } catch {
    return null;
  }
}

/** Shell boot: load the clinic zone once per tenant (later callers share the same promise; a different tenantKey,
 * e.g. an owner switching clinics, reloads). Null or unusable tenants.timezone falls back to the region default
 * and warns once per page load. */
export function loadClinicTimeZone(fetcher: Fetcher, region: string | null | undefined, tenantKey = ""): Promise<string> {
  if (ready && readyFor === tenantKey) return ready;
  readyFor = tenantKey;
  ready = configuredZone(fetcher).then((configured) => {
    const { tz, fallback } = resolveClinicTimeZone(configured, region);
    if (fallback && !warned) {
      warned = true;
      console.warn("[clinic-timezone-fallback]", { region: region ?? null, timezone: tz });
    }
    setClinicTimeZone(tz);
    return tz;
  });
  return ready;
}

/** Resolves with the clinic zone once shell boot has loaded it (immediately if already loaded). */
export function clinicTimeZoneReady(): Promise<string> {
  if (zone) return Promise.resolve(zone);
  return ready ?? new Promise((resolve) => {
    const off = subscribeClinicTimeZone(() => { if (zone) { off(); resolve(zone); } });
  });
}
