// FEF-037: phone numbers via libphonenumber. International format when it parses, the raw text otherwise.
import { parsePhoneNumberFromString, type CountryCode } from "libphonenumber-js/min";

export function formatPhone(raw: string | null | undefined, defaultCountry?: string): string {
  if (!raw) return "—";
  const p = parsePhoneNumberFromString(raw, defaultCountry as CountryCode | undefined);
  return p?.isValid() ? p.formatInternational() : raw;
}
