// FEF-046: page presets and the print stylesheet. Pure (no React) so it is unit-tested directly.
export const PAPERS = ["a4", "a5", "80mm"] as const;
export type Paper = (typeof PAPERS)[number];
export const DOC_TYPES = ["token_slip", "rx", "document", "invoice", "receipt"] as const;
export type DocType = (typeof DOC_TYPES)[number];

/** Built-in default per document type, used until the clinic sets print.default_paper.<type>. */
export const BUILTIN_PAPER: Record<DocType, Paper> = { token_slip: "80mm", rx: "a4", document: "a4", invoice: "a4", receipt: "80mm" };

export const isPaper = (v: unknown): v is Paper => typeof v === "string" && (PAPERS as readonly string[]).includes(v);
export const policyKey = (t: DocType) => `print.default_paper.${t}`;

/** The clinic's value when it is a known paper, else the built-in default (a bad or missing value never blocks printing). */
export const resolvePaper = (t: DocType, value?: string | null): Paper => (isPaper(value) ? value : BUILTIN_PAPER[t]);

/** Printable inline size of the sheet in mm (page width minus margins). */
export const SHEET_MM: Record<Paper, number> = { a4: 182, a5: 128, "80mm": 72 };
const PAGE: Record<Paper, string> = { a4: "size: A4; margin: 14mm;", a5: "size: A5; margin: 10mm;", "80mm": "margin: 4mm;" };
const NUMBERED = `@bottom-center { content: "Page " counter(page) " of " counter(pages); font: 9pt sans-serif; color: #000; }`;

/**
 * The stylesheet a mounted sheet injects. Print: only the sheet and its ancestors stay; the paper is black on white
 * whatever the app theme; page x of y comes from the @page margin box (not on a roll, which is one page).
 */
export function printCss(paper: Paper, rollHeightMm = 297 /* page height of a roll in mm, measured by PrintSheet */): string {
  return `
.nbp-sheet{box-sizing:border-box;background:#fff;color:#000;inline-size:${SHEET_MM[paper]}mm;max-inline-size:100%;margin-inline:auto;padding:4mm;font:${paper === "80mm" ? "9pt" : "10pt"}/1.35 sans-serif;border:1px solid #888}
.nbp-sheet[data-bare]{border:0;padding:0;background:none;inline-size:auto}
.nbp-sheet *{box-sizing:border-box}
.nbp-row{display:flex;justify-content:space-between;gap:2mm;break-inside:avoid}
.nbp-h{margin:0 0 1mm;font-size:1.2em}
.nbp-cols{display:grid;grid-template-columns:1fr 1fr;gap:4mm}
.nbp-totals{inline-size:100%;border-collapse:collapse}
.nbp-totals td{padding:.5mm 0;vertical-align:top}.nbp-totals td:last-child{text-align:end;font-variant-numeric:tabular-nums}
.nbp-totals tr.nbp-strong td{border-block-start:1px solid #000;font-weight:700}
.nbp-qr{display:flex;flex-direction:column;align-items:center;gap:1mm;break-inside:avoid}.nbp-qr img{inline-size:28mm;block-size:28mm}
.nbp-foot{margin-block-start:2mm;font-size:.85em}
@media print{
  @page{${paper === "80mm" ? `size: 80mm ${rollHeightMm}mm; ` : ""}${PAGE[paper]}${paper === "80mm" ? "" : NUMBERED}}
  html,body{background:#fff!important;color:#000!important;block-size:auto!important;overflow:visible!important}
  body:has(.nbp-sheet) *:not(:has(.nbp-sheet)):not(.nbp-sheet):not(.nbp-sheet *){display:none!important}
  body:has(.nbp-sheet) *:has(.nbp-sheet){display:block!important;position:static!important;padding:0!important;margin:0!important;border:0!important;background:none!important;box-shadow:none!important;overflow:visible!important;block-size:auto!important;min-block-size:0!important;max-block-size:none!important;inline-size:auto!important;max-inline-size:none!important}
  .nbp-sheet{border:0;padding:0;inline-size:100%;margin:0}
  .nbp-sheet[data-bare]>*{border:0!important;padding:0!important;border-radius:0!important}
  .nbp-sheet,.nbp-sheet *{background:transparent!important;color:#000!important;box-shadow:none!important;text-shadow:none!important}
}`;
}
