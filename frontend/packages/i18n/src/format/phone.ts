// FEF-037: phone numbers via libphonenumber. International format when it parses, the raw text otherwise.
import { parsePhoneNumberFromString, type CountryCode } from "libphonenumber-js/min";

export function formatPhone(raw: string | null | undefined, defaultCountry?: string): string {
  if (!raw) return "—";
  const p = parsePhoneNumberFromString(raw, defaultCountry as CountryCode | undefined);
  return p?.isValid() ? p.formatInternational() : raw;
}

/** FEF-033: E.164 for a typed number in the clinic's country (IN, AE, SA, QA; legacy KSA = SA), or null when it is not a valid number. */
export function normalizePhone(raw: string | null | undefined, defaultCountry?: string): string | null {
  if (!raw || !raw.trim()) return null;
  const p = parsePhoneNumberFromString(raw.trim(), (defaultCountry === "KSA" ? "SA" : defaultCountry) as CountryCode | undefined);
  return p?.isValid() ? p.number : null;
}
