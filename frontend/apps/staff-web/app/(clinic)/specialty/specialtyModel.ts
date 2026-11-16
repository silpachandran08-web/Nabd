// CLN-042: the specialty workspace is resolved from data (GET /v1/specialty/me), never from per-specialty code. A new profile
// code with new widgets and quick actions renders here unchanged: known widget ids get a built tool, unknown ones a card.

export type Localized = Record<string, string>;
export type QuickAction = { id: string; href: string; label: Localized; writes?: boolean };
export type Widget = { id: string; label: Localized };
export type SpecialtyConfig = { module?: string; widgets: Widget[]; quickActions: QuickAction[]; fieldSets: unknown[]; templatePack: string | null };
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
export const BUILT_WIDGETS: Record<string, string> = { toothChart: "/patients" };

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
    actions: (me.config.quickActions ?? []).map((a) => ({ id: a.id, label: localized(a.label, locale, a.id), href: a.href, disabled: readOnly && a.writes === true })),
    widgets: (me.config.widgets ?? []).map((w) => ({ id: w.id, label: localized(w.label, locale, w.id), href: BUILT_WIDGETS[w.id] ?? null })),
  };
}

/** Owner's picker: only profiles the clinic's plan unlocks; the others are absent (the server refuses them with 422 NOT_ENTITLED). */
export const pickable = (items: ProfileItem[]): ProfileItem[] => items.filter((p) => p.entitled);
