// FEF-037: the one money formatter lives in @nabd/i18n (ISO-4217 minor units, lakh grouping, clinic digit style).
// This re-export keeps the existing @nabd/domain import path; amounts may be numbers or exact decimal strings.
export { formatMoney } from "@nabd/i18n";
