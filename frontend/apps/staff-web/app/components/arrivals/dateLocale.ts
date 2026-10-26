// FEF-038: dates and times follow the UI language; English keeps the formats the desk always showed (en-GB clock/date where it was fixed, the browser default elsewhere).
export const dateLocale = (locale: string, enFormat?: string): string | undefined => (locale === "en" ? enFormat : locale);
