// FEF-062: pure rules for in-app help and first-run tours. Help content is keyed by screen id (catalogue packages/i18n
// messages/<locale>/help.json, `screens.<id>`), tours by role (`tours.<role>.s1..s5`). React-free so it is unit-tested.
import type { RoleContext } from "./navRegistry";

/** Route prefix -> screen id. Order matters: the first prefix that matches wins (a longer prefix goes before its parent). */
const SCREENS: [prefix: string, id: string][] = [
  ["/arrivals", "arrivals"], ["/schedule", "schedule"], ["/patients", "patients"], ["/consult", "consult"], ["/my-day", "myday"],
  ["/nursing", "nursing"], ["/overview", "overview"], ["/checkout", "billing"], ["/billing", "billing"], ["/pharmacy", "pharmacy"],
  ["/requests", "requests"], ["/setup", "setup"], ["/staff", "staff"], ["/reports", "reports"],
];
export const SCREEN_IDS = [...new Set(SCREENS.map(([, id]) => id)), "generic"] as const;
export type ScreenId = (typeof SCREEN_IDS)[number];

/** The help screen id for a path; "generic" when the screen has no entry of its own. */
export function screenIdFor(pathname: string | null): ScreenId {
  const p = pathname ?? "/";
  return (SCREENS.find(([prefix]) => p === prefix || p.startsWith(`${prefix}/`))?.[1] as ScreenId | undefined) ?? "generic";
}

/** Roles with a first-run tour (each walks the role's home: Arrivals, Triage, My Day, Overview). */
export const TOUR_ROLES = ["reception", "nurse", "doctor", "owner"] as const satisfies readonly RoleContext[];
export type TourRole = (typeof TOUR_ROLES)[number];
export const TOUR_STEPS = 5;
export const isTourRole = (r: string | null): r is TourRole => !!r && (TOUR_ROLES as readonly string[]).includes(r);

/** Per-screen "2-minute video" links. Placeholders: Product & Design supplies in-region URLs (FEF-062.3); a missing entry renders
 * "not available yet", never an invented link. */
export const HELP_VIDEOS: Partial<Record<ScreenId, string>> = {};

/** A tour is deferred while a consult is open: the consult workspace mirrors the open visit in ?entry= (FEF-030). */
export function consultActive(pathname: string | null, search: { get(k: string): string | null } | null): boolean {
  return !!pathname && (pathname === "/consult" || pathname.startsWith("/consult/")) && !!search?.get("entry");
}

// Completion lives in the browser like the shell's other per-viewer preferences (nabd_sidebar_collapsed, nabd_theme): ACC-035's
// staff_onboarding_progress does not exist yet. shortcut: completion is per browser, not per account; move it to ACC-035 when it lands.
const KEY = "nabd_tour_done";
type Store = { getItem(k: string): string | null; setItem(k: string, v: string): void };
const read = (store: Store): Record<string, string[]> => { try { return JSON.parse(store.getItem(KEY) ?? "{}"); } catch { return {}; } };
export function tourDone(store: Store, staffId: string, role: string): boolean {
  return (read(store)[staffId] ?? []).includes(role);
}
export function markTourDone(store: Store, staffId: string, role: string): void {
  const all = read(store);
  all[staffId] = [...new Set([...(all[staffId] ?? []), role])];
  try { store.setItem(KEY, JSON.stringify(all)); } catch { /* blocked storage: the tour shows again next load */ }
}

/** Whether the first-run tour should start now: a tour exists for the role, it is not done, and no consult is open. */
export function tourDue(input: { role: string | null; done: boolean; consult: boolean }): boolean {
  return isTourRole(input.role) && !input.done && !input.consult;
}
