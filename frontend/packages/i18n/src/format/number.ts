// FEF-037: plain numbers and the digit style. Clinical values (vitals, doses, lab results) are always Latin digits.
import { getFormatContext, intlTag, type FormatContext } from "./context.ts";

export type NumberOpts = Partial<FormatContext> & { min?: number; max?: number; /** vitals, doses: never localise the digits */ clinical?: boolean; percent?: boolean };

export function formatNumber(value: number | string | null | undefined, opts: NumberOpts = {}): string {
  if (value === null || value === undefined || value === "" || (typeof value === "number" && !Number.isFinite(value))) return "—";
  const c = { ...getFormatContext(), ...opts };
  const nf = new Intl.NumberFormat(intlTag(c.locale, undefined, opts.clinical ? "latn" : c.digitStyle), {
    style: opts.percent ? "percent" : "decimal", minimumFractionDigits: opts.min ?? 0, maximumFractionDigits: opts.max ?? Math.max(opts.min ?? 0, 2),
  });
  return nf.format(value as never);
}

/** Re-render the Latin digits of an already formatted string in the user's digit style (no-op for latn). */
export function localizeDigits(text: string, style = getFormatContext().digitStyle): string {
  return style === "arab" ? text.replace(/[0-9]/g, (d) => String.fromCharCode(0x660 + Number(d))) : text;
}
