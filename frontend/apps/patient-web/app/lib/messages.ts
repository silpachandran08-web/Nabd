// FEF-061: patient catalogue (packages/i18n/messages/<locale>/patient.json), picked on the server so only one language reaches the phone.
import { DEFAULT_LOCALE, LOCALE_HEADER, isLocale, type Locale } from "@nabd/i18n";
import { headers } from "next/headers";
import en from "@nabd/i18n/messages/en/patient.json";
import hi from "@nabd/i18n/messages/hi/patient.json";
import kn from "@nabd/i18n/messages/kn/patient.json";
import te from "@nabd/i18n/messages/te/patient.json";

export type Messages = Record<keyof typeof en, string>;
const CATALOGUES: Partial<Record<Locale, Messages>> = { en, hi, kn, te };
/** Languages this app ships a full catalogue for (the picker lists exactly these). */
export const PATIENT_LOCALES = ["en", "hi", "kn", "te"] as const;

export async function currentLocale(): Promise<Locale> {
  const v = (await headers()).get(LOCALE_HEADER);
  return isLocale(v) && CATALOGUES[v] ? v : DEFAULT_LOCALE;
}

export const messagesFor = (locale: Locale): Messages => CATALOGUES[locale] ?? en;
