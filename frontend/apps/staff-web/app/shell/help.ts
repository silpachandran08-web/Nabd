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

// ACC-035: completion lives at Core per account (GET/PUT /v1/me/onboarding, table staff_onboarding_progress), so a tour runs
// once per person and role whatever the browser; it replaced FEF-062's per-browser nabd_tour_done flag.
export type Onboarding = { completed: string[]; competencyPassedAt: string | null };
/** The answer of GET/PUT /me/onboarding if it has the expected shape, else undefined: a wrong body (an array, an error page,
 * a proxy's HTML) must read as "no tour state", never take the shell down (QA S22 T1-BUG-5). */
export function asOnboarding(body: unknown): Onboarding | undefined {
  if (!body || typeof body !== "object" || Array.isArray(body)) return undefined;
  const { completed, competencyPassedAt } = body as Record<string, unknown>;
  if (!Array.isArray(completed) || !completed.every((k) => typeof k === "string")) return undefined;
  return { completed, competencyPassedAt: typeof competencyPassedAt === "string" ? competencyPassedAt : null };
}
export const tourKey = (role: TourRole): string => `tour.${role}`;
export const tourDone = (p: Onboarding | undefined, role: TourRole): boolean => !!asOnboarding(p)?.completed.includes(tourKey(role));

/** ACC-035: the ten reception competency tasks (docs/training/reception-train-the-trainer.md; Core's allow-list, keys "task.<id>"). */
export const COMPETENCY_TASKS = ["walkin", "checkin", "queue", "requests", "delay", "checkout", "reschedule", "noshow", "search", "offline"] as const;
export type CompetencyTask = (typeof COMPETENCY_TASKS)[number];
export const taskKey = (t: CompetencyTask): string => `task.${t}`;
export const tasksDone = (p: Onboarding | undefined): number => {
  const ok = asOnboarding(p);
  return COMPETENCY_TASKS.filter((t) => ok?.completed.includes(taskKey(t))).length;
};

/** ACC-035: the top five desk tasks each get a video under 2 minutes per language. Placeholders: Product & Design record and host
 * them (external content); an empty slot renders "not available yet", never an invented link. */
export const DESK_VIDEO_TASKS = ["checkin", "walkin", "requests", "delay", "checkout"] as const satisfies readonly CompetencyTask[];
export const VIDEO_LANGS = ["en", "hi"] as const;
export const DESK_VIDEOS: Record<(typeof DESK_VIDEO_TASKS)[number], Partial<Record<(typeof VIDEO_LANGS)[number], string>>> = {
  checkin: {}, walkin: {}, requests: {}, delay: {}, checkout: {},
};

/** Whether the first-run tour should start now: a tour exists for the role, it is not done, and no consult is open. */
export function tourDue(input: { role: string | null; done: boolean; consult: boolean }): boolean {
  return isTourRole(input.role) && !input.done && !input.consult;
}
