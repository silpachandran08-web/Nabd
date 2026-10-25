// FEF-037: the one money formatter. Amounts are exact decimal strings or integer minor units; a JS float is accepted only as
// a convenience for legacy call sites. Decimals come from ISO-4217 (KWD 3, JPY 0, default 2); INR groups in lakhs.
import { getFormatContext, intlTag, type FormatContext } from "./context.ts";
import { minorDigits } from "./units.ts";

export type MoneyOpts = Partial<FormatContext> & { /** the amount is an integer count of minor units (paise, fils) */ minor?: boolean };

/** Integer minor units as an exact decimal string ("123456" INR -> "1234.56"). */
export function minorToDecimal(minor: bigint | number | string, currency: string): string {
  const d = minorDigits(currency);
  const n = BigInt(minor);
  const neg = n < BigInt(0);
  const abs = (neg ? -n : n).toString().padStart(d + 1, "0");
  return `${neg ? "-" : ""}${abs.slice(0, abs.length - d)}${d ? "." + abs.slice(abs.length - d) : ""}`;
}

/** Plain text such as "₹1,23,456.50", "KWD 1,234.567" or "-12.00 SAR" back to integer minor units (half-up beyond the minor digit). */
export function parseMoneyMinor(text: string, currency: string): bigint {
  const latin = text.replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x660)).replace(/٫/g, ".");
  const neg = /^[^0-9]*-/.test(latin) || /^\(.*\)$/.test(latin.trim());
  const [whole = "", frac = ""] = latin.replace(/[^0-9.]/g, "").split(".");
  const d = minorDigits(currency);
  const base = BigInt((whole || "0") + frac.padEnd(d, "0").slice(0, d));
  const up = BigInt(frac.length > d && frac[d] >= "5" ? 1 : 0);
  return neg ? -(base + up) : base + up;
}

export function formatMoney(amount: number | string | bigint | null | undefined, currency = "INR", opts: MoneyOpts = {}): string {
  if (amount === null || amount === undefined || (typeof amount === "number" && !Number.isFinite(amount)) || amount === "") return "—";
  const c = { ...getFormatContext(), ...opts };
  const code = currency.toUpperCase();
  const d = minorDigits(code);
  let value: string | number = typeof amount === "bigint" ? amount.toString() : amount;
  if (opts.minor) value = minorToDecimal(value, code);
  try {
    const nf = new Intl.NumberFormat(intlTag(c.locale, code, c.digitStyle), {
      style: "currency", currency: code, currencyDisplay: code === "INR" ? "symbol" : "code", minimumFractionDigits: d, maximumFractionDigits: d, signDisplay: "negative",
    });
    const out = nf.format(value as never).replace(/ /g, " ");
    return code === "SAR" && c.sarSymbol ? `${out.replace(/^SAR\s?/, "")} ﷼` : out;
  } catch {
    return `${code} ${value}`;
  }
}

/** `{ format }` bound to one currency, for screens that format many amounts of the same currency. */
export type MoneyFormatter = { format: (amount: number | string | null | undefined) => string };
export const moneyFormatter = (currency: string, opts: MoneyOpts = {}): MoneyFormatter => ({ format: (a) => formatMoney(a, currency, opts) });
