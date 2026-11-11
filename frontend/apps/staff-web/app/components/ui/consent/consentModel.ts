// CLN-014: the pure parts of the tablet consent: signature strokes to the SVG the API takes, the form's HTML shown as plain
// text (never injected as HTML), and which language the form opens in.
export type Point = [number, number];
export type ConsentTemplate = { id: string; version: number; lang: string; chargeCode: string; title: string; bodyHtml: string; placeholder: boolean };
export const PAD_W = 600;
export const PAD_H = 200;

/** Strokes (pad coordinates, 600 x 200) as `<svg><path d="M x y L x y ..."/></svg>`; empty strokes are dropped. */
export function strokesToSvg(strokes: Point[][]): string {
  const r = (n: number) => Math.round(Math.min(Math.max(n, 0), 9999) * 10) / 10;
  const paths = strokes.filter((s) => s.length > 0)
    .map((s) => `<path d="${s.map(([x, y], i) => `${i === 0 ? "M" : "L"}${r(x)} ${r(y)}`).join(" ")}"/>`);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${PAD_W} ${PAD_H}">${paths.join("")}</svg>`;
}

/** Same reading as the server's PDF: block ends become line breaks, tags dropped, common entities decoded. */
export function plainText(html: string): string {
  return html.replace(/<br\s*\/?>|<\/p>|<\/li>|<\/h[1-6]>|<\/div>/gi, "\n").replace(/<li[^>]*>/gi, "- ").replace(/<[^>]*>/g, "")
    .replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, "\"").replace(/&#39;/g, "'").replace(/&amp;/g, "&")
    .replace(/\n{3,}/g, "\n\n").trim();
}

/** Only the clinic's own forms can be signed; the patient's language first, then English, then any. */
export function signable(items: ConsentTemplate[]): ConsentTemplate[] {
  return items.filter((t) => !t.placeholder);
}
export function pickLang(items: ConsentTemplate[], patientLang: string | null | undefined): string | null {
  const langs = signable(items).map((t) => t.lang);
  const want = (patientLang ?? "").toLowerCase().split("-")[0];
  return langs.includes(want) ? want : langs.includes("en") ? "en" : langs[0] ?? null;
}
