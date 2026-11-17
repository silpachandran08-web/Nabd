// CLN-042: the specialty workspace is resolved from data (GET /v1/specialty/me), never from per-specialty code. A new profile
// code with new widgets and quick actions renders here unchanged: known widget ids get a built tool, unknown ones a card.

export type Localized = Record<string, string>;
export type QuickAction = { id: string; href: string; label: Localized; writes?: boolean };
export type Widget = { id: string; label: Localized };
/** CLN-043: my-day widget ids (FRD home widgets) and the Today-list options of the profile. */
export type MyDayConfig = { widgets: string[]; visitTypeChips?: boolean; ageInMonthsUnder2?: boolean };
export type SpecialtyConfig = { module?: string; widgets: Widget[]; quickActions: QuickAction[]; fieldSets: unknown[]; templatePack: string | null; myDay?: MyDayConfig };
export type MySpecialty = {
  primary: string | null;
  secondary: string | null;
  name: Localized | null;
  configVersion: number | null;
  writable: boolean;
  config: SpecialtyConfig | null;
};
export type ProfileItem = { code: string; name: Localized; entitled: boolean; configVersion: number };

/** Widgets with a built tool in staff-web (the dental chart lives in the patient drawer). Everything else is a plain card. */
export const BUILT_WIDGETS: Record<string, string> = { toothChart: "/patients", photoTimeline: "/patients" }; // CLN-047: Photos tab of the patient drawer

/** The label in the UI language, else English, else the raw id. */
export const localized = (l: Localized | null | undefined, locale: string, fallback = ""): string =>
  l?.[locale] || l?.en || fallback;

export type WorkspaceView = {
  title: string;
  readOnly: boolean;
  actions: { id: string; label: string; href: string; disabled: boolean }[];
  widgets: { id: string; label: string; href: string | null }[];
};

/** null = no profile assigned. An expired plan entitlement keeps every read and disables only the actions that write. */
export function workspaceView(me: MySpecialty, locale: string): WorkspaceView | null {
  if (!me.primary || !me.config) return null;
  const readOnly = !me.writable;
  return {
    title: localized(me.name, locale, me.primary),
    readOnly,
    actions: (validQuickActions(me.config) ?? []).map((a) => ({ id: a.id, label: localized(a.label, locale, a.id), href: a.href, disabled: readOnly && a.writes === true })),
    widgets: (me.config.widgets ?? []).map((w) => ({ id: w.id, label: localized(w.label, locale, w.id), href: BUILT_WIDGETS[w.id] ?? null })),
  };
}

/** Owner's picker: only profiles the clinic's plan unlocks; the others are absent (the server refuses them with 422 NOT_ENTITLED). */
export const pickable = (items: ProfileItem[]): ProfileItem[] => items.filter((p) => p.entitled);

// ---- CLN-043: quick-action bar and my-day options ----

/** The bar holds at most 6 actions. The registry's DB check refuses a 7th; a config that still carries one is refused here too
 * (no bar at all), never silently cut to 6. Every action must be an in-app route with an English label. */
export const MAX_QUICK_ACTIONS = 6;
const IN_APP_HREF = /^\/(?!\/)[\w\-/?=&.]*$/;
export function validQuickActions(config: Partial<SpecialtyConfig> | null | undefined): QuickAction[] | null {
  const list: unknown = config?.quickActions;
  if (!Array.isArray(list) || list.length > MAX_QUICK_ACTIONS) return null;
  const ok = list.every((a) => !!a && typeof a.id === "string" && typeof a.href === "string" && IN_APP_HREF.test(a.href) && typeof a.label?.en === "string");
  return ok ? (list as QuickAction[]) : null;
}

/** What the consult bar shows: exactly the configured actions, minus the ones that write once the plan has lapsed (absent). */
export function quickBar(me: MySpecialty | null | undefined, locale: string): { id: string; label: string; href: string }[] {
  const v = me ? workspaceView(me, locale) : null;
  return v ? v.actions.filter((a) => !a.disabled).map(({ id, label, href }) => ({ id, label, href })) : [];
}

export type MyDayOptions = { widgets: string[]; visitTypeChips: boolean; ageInMonthsUnder2: boolean };
export const NO_MY_DAY: MyDayOptions = { widgets: [], visitTypeChips: false, ageInMonthsUnder2: false };

/** No profile, no config or a malformed myDay: plain My Day. */
export function myDayOptions(me: MySpecialty | null | undefined): MyDayOptions {
  const d = me?.primary ? me.config?.myDay : undefined;
  if (!d || !Array.isArray(d.widgets)) return NO_MY_DAY;
  return { widgets: d.widgets.filter((w) => typeof w === "string"), visitTypeChips: d.visitTypeChips === true, ageInMonthsUnder2: d.ageInMonthsUnder2 === true };
}

/** A widget whose data endpoint answers 403 (not entitled / no permission) or 404 (not deployed yet) is hidden, silently. */
export const hidesWidget = (status: number | undefined): boolean => status === 403 || status === 404;

/** Age from an ISO dob on the clinic date: "14 mo" under 2 years when the profile asks (PED), else whole years ("3y"). */
export function ageText(dob: string | null | undefined, today: string, monthsUnder2: boolean): string {
  const b = /^(\d{4})-(\d{2})-(\d{2})/.exec(dob ?? "");
  const t = /^(\d{4})-(\d{2})-(\d{2})/.exec(today);
  if (!b || !t) return "";
  const months = (+t[1] - +b[1]) * 12 + (+t[2] - +b[2]) - (+t[3] < +b[3] ? 1 : 0);
  if (months < 0) return "";
  return monthsUnder2 && months < 24 ? `${months} mo` : `${Math.floor(months / 12)}y`;
}

export type VisitChip = "new" | "followUp";
/** Visit-type chip from what the server already sends: the queue's visitType (follow_up | appointment | walk_in | transfer) or
 * an appointment's isFollowUp. Not a follow-up = a new visit (the split ServiceTimeRepository uses); transfers get none.
 * Package session and patch-test reading have no data source yet (nothing links a visit to a package session or a patch
 * test), so those chips never show rather than being guessed. */
export function visitChip(v: { visitType?: string | null; isFollowUp?: boolean | null }): VisitChip | null {
  if (v.visitType === "follow_up" || v.isFollowUp === true) return "followUp";
  if (v.visitType === "walk_in" || v.visitType === "appointment" || v.isFollowUp === false) return "new";
  return null;
}
