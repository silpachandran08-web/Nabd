// COM-025: the patient file's message preferences (GET/PUT /v1/patients/{id}/notification-preferences). Pure helpers, unit-tested.

export const LANGUAGES = ["en", "hi", "ar", "kn", "te"] as const;
/** Shown in the language itself, like the WhatsApp picker. */
export const LANGUAGE_NAMES: Record<(typeof LANGUAGES)[number], string> = { en: "English", hi: "हिन्दी", ar: "العربية", kn: "ಕನ್ನಡ", te: "తెలుగు" };
export const CHANNELS = ["whatsapp", "sms", "email"] as const;
export const CATEGORY_KEYS = ["careReminders", "recalls", "feedback", "reviews"] as const;
/** A caregiver gets reminders and recalls only; review and feedback requests are the patient's own. */
export const CAREGIVER_CATEGORIES = ["careReminders", "recalls"] as const;
export const DEFAULT_QUIET = { start: "21:00", end: "08:00" };

export type Category = (typeof CATEGORY_KEYS)[number];
export type CaregiverPref = { personRef: string; categories: string[] };
export type EligibleCaregiver = { personRef: string; firstName?: string | null; relation: string };
export type Prefs = {
  language?: string | null;
  channel: string;
  categories: Record<Category, boolean>;
  quietHours?: { start: string; end: string } | null;
  caregivers: CaregiverPref[];
  eligibleCaregivers: EligibleCaregiver[];
  version: number;
};

/** The PUT body: the whole preference set (PUT replaces); caregivers left with no category are dropped. */
export function toRequest(p: Prefs) {
  return {
    language: p.language ?? null,
    channel: p.channel,
    categories: p.categories,
    quietHours: p.quietHours ?? null,
    caregivers: p.caregivers.filter((c) => c.categories.length > 0),
  };
}

/** Switches one category on or off for one caregiver, adding or removing the caregiver as needed. */
export function toggleCaregiver(list: CaregiverPref[], personRef: string, category: string, on: boolean): CaregiverPref[] {
  const current = list.find((c) => c.personRef === personRef);
  const cats = new Set(current?.categories ?? []);
  if (on) cats.add(category); else cats.delete(category);
  const others = list.filter((c) => c.personRef !== personRef);
  return cats.size === 0 ? others : [...others, { personRef, categories: CAREGIVER_CATEGORIES.filter((k) => cats.has(k)) }];
}

export const caregiverHas = (list: CaregiverPref[], personRef: string, category: string) =>
  list.some((c) => c.personRef === personRef && c.categories.includes(category));

/** i18n key (common.notifPrefs.errors.*) for a failed save. */
export function prefsErrorKey(e: unknown): "notAuthorised" | "forbidden" | "generic" {
  const status = (e as { status?: number } | null)?.status;
  const code = (e as { problem?: { code?: unknown } } | null)?.problem?.code;
  if (status === 422 && code === "CAREGIVER_NOT_AUTHORISED") return "notAuthorised";
  if (status === 403) return "forbidden";
  return "generic";
}
