// FIN-017: specialty starter price lists in the setup wizard. React-free so the grid rules and the billing-screen preview are
// unit-tested (tests/unit/fin017-price-template.test.mjs). The preview is derived from the grid state on every render: no API call per edit.

export type TemplateHead = {
  code: string; name: string; category: string; baseAmount: number; taxCategory: string; taxRatePercent: number; sacHsn: string | null; displayOrder: number;
};
export type PriceTemplate = {
  templateId: string; specialty: string; jurisdiction: string; version: number; reviewed: boolean; reviewNote: string; currency: string; heads: TemplateHead[];
};
/** A head already in the clinic's price master (GET /setup/charges). */
export type ExistingCharge = { code: string; name: string; category: string; baseAmount: number; taxRatePercent: number | null; active: boolean; displayOrder: number };
/** One grid row: the template head as the owner edits it (amount as typed). */
export type Row = TemplateHead & { amount: string; include: boolean };
export type PreviewButton = { code: string; name: string; category: string; amount: number | null; badge: string; isNew: boolean };

export const SPECIALTIES: [string, string][] = [
  ["dental", "Dental"], ["derma", "Dermatology"], ["gp", "General practice"], ["paediatric", "Paediatrics"], ["physio", "Physiotherapy"], ["other", "Other"],
];

const upper = (s: string) => s.trim().toUpperCase();

export function rowsOf(t: PriceTemplate): Row[] {
  return [...t.heads].sort((a, b) => a.displayOrder - b.displayOrder).map((h) => ({ ...h, amount: String(h.baseAmount), include: true }));
}

/** null when the typed amount is a valid price (0 or more, up to two decimals). */
export function amountError(v: string): string | null {
  return /^\d{1,10}(\.\d{1,2})?$/.test(v.trim()) ? null : "Use 0 or more, with up to two decimals.";
}

export function nameError(v: string): string | null {
  return v.trim() === "" ? "Enter a name." : v.trim().length > 120 ? "Up to 120 characters." : null;
}

/** The badge the billing screen shows: GST in India, VAT in the Gulf markets. */
export function taxBadge(taxCategory: string | null, rate: number | null, jurisdiction: string): string {
  const regime = jurisdiction === "IN" ? "GST" : "VAT";
  if (taxCategory === "exempt") return "Exempt";
  if (taxCategory === "zero_rated") return `${regime} 0%`;
  if (taxCategory === "nil") return "Nil";
  return rate != null && rate > 0 ? `${regime} ${rate}%` : "";
}

/** Moves row i one place up (-1) or down (+1); display order follows the new positions. */
export function move(rows: Row[], i: number, dir: -1 | 1): Row[] {
  const j = i + dir;
  if (j < 0 || j >= rows.length) return rows;
  const next = [...rows];
  [next[i], next[j]] = [next[j], next[i]];
  return next.map((r, k) => ({ ...r, displayOrder: (k + 1) * 10 }));
}

export function existingCodes(existing: ExistingCharge[]): Set<string> {
  return new Set(existing.map((c) => upper(c.code)));
}

/** The receptionist's charge buttons after Save: the active heads the clinic has plus the included new ones, in display order (then name). */
export function preview(rows: Row[], existing: ExistingCharge[], jurisdiction: string): { categories: string[]; buttons: PreviewButton[] } {
  const have = existingCodes(existing);
  const items = [
    ...existing.filter((c) => c.active).map((c) => ({ order: c.displayOrder, b: { code: c.code, name: c.name, category: c.category, amount: c.baseAmount,
      badge: taxBadge(null, c.taxRatePercent, jurisdiction), isNew: false } })),
    ...rows.filter((r) => r.include && !have.has(upper(r.code))).map((r) => ({ order: r.displayOrder, b: { code: r.code, name: r.name.trim() || r.code,
      category: r.category, amount: amountError(r.amount) ? null : Number(r.amount), badge: taxBadge(r.taxCategory, r.taxRatePercent, jurisdiction), isNew: true } })),
  ].sort((x, y) => x.order - y.order || x.b.name.localeCompare(y.b.name));
  const buttons = items.map((x) => x.b);
  return { categories: ["All", ...Array.from(new Set(buttons.map((b) => b.category)))], buttons };
}

/** First problem with the included rows that would be saved, or null. */
export function gridError(rows: Row[], existing: ExistingCharge[]): string | null {
  const have = existingCodes(existing);
  const toSave = rows.filter((r) => r.include && !have.has(upper(r.code)));
  if (toSave.length === 0) return "Every included head is already in your price list.";
  for (const r of toSave) {
    const e = nameError(r.name) ?? amountError(r.amount);
    if (e) return `${r.code}: ${e}`;
  }
  return null;
}

/** POST /setup/charges/bulk body: the included rows as edited (the server skips codes the clinic already has). */
export function bulkBody(t: PriceTemplate, rows: Row[]) {
  return {
    templateId: t.templateId,
    heads: rows.filter((r) => r.include).map((r) => ({ code: r.code, name: r.name.trim(), category: r.category, baseAmount: Number(r.amount),
      taxCategory: r.taxCategory, taxRatePercent: r.taxRatePercent, sacHsn: r.sacHsn, displayOrder: r.displayOrder })),
  };
}
