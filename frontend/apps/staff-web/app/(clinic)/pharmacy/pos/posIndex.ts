// PHL-019: the counter sale's item index, searched in the browser so no keystroke waits on the server.
// GET /pharmacy/items/index (ETag: the browser's HTTP cache revalidates it, an unchanged index costs a 304) -> buildIndex once,
// then searchIndex per keystroke and byBarcode per scan. Pure (unit-tested with 5,000 items).
import type { PharmacyItemIndexEntry } from "@nabd/api-client/generated";

export type PosItem = { id: string; code: string; name: string; generic: string | null; gtin: string | null; price: number; scheduleClass: string; isRx: boolean; onHand: number };
/** `words[n]`: the lower-cased words of item n, brand name first; `nameWords[n]` says how many of them are the brand name. */
export type PosIndex = { items: PosItem[]; words: string[][]; nameWords: number[]; byGtin: Map<string, PosItem> };

const split = (s: string) => s.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);
/** UPC-A, EAN-13 and GTIN-14 are the same number padded with zeros: compare without the padding. */
const gtinKey = (s: string) => s.replace(/\D/g, "").replace(/^0+/, "");

/** Rows without an id, charge code or name cannot be sold and are dropped. The server's order (by name) is kept. */
export function buildIndex(rows: readonly PharmacyItemIndexEntry[]): PosIndex {
  const items: PosItem[] = [];
  for (const r of rows) {
    if (!r.id || !r.code || !r.name) continue;
    items.push({ id: r.id, code: r.code, name: r.name, generic: r.generic ?? null, gtin: r.gtin ?? null, price: r.price ?? 0, scheduleClass: r.class ?? "OTC", isRx: !!r.isRx, onHand: r.onHand ?? 0 });
  }
  const byGtin = new Map<string, PosItem>();
  for (const i of items) if (i.gtin && gtinKey(i.gtin)) byGtin.set(gtinKey(i.gtin), i);
  const names = items.map((i) => split(i.name));
  return { items, words: items.map((i, n) => [...names[n], ...split(i.generic ?? "")]), nameWords: names.map((w) => w.length), byGtin };
}

/** The item a scanned code belongs to, or null. */
export function byBarcode(ix: PosIndex, code: string): PosItem | null {
  const k = gtinKey(code);
  return k.length >= 7 ? ix.byGtin.get(k) ?? null : null;
}

/**
 * Brand, generic or barcode. Every typed word must start a word of the brand or generic name; a run of digits also matches the
 * start of a barcode. Order: the exact barcode, then items whose brand name alone matches, then the rest (generic, barcode
 * prefix), each in index order.
 */
export function searchIndex(ix: PosIndex, query: string, limit = 8): PosItem[] {
  const q = query.trim().toLowerCase();
  const words = split(q);
  if (words.length === 0) return [];
  const digits = /^\d{3,}$/.test(q) ? q : null;
  const exact = digits ? byBarcode(ix, digits) : null;
  const first: PosItem[] = [], rest: PosItem[] = [];
  for (let n = 0; n < ix.items.length && first.length < limit; n++) {
    const item = ix.items[n];
    if (item === exact) continue;
    const hay = ix.words[n];
    const named = words.every((w) => hay.some((h) => h.startsWith(w)));
    if (!named && !(digits && item.gtin?.startsWith(digits))) continue;
    if (named && words.every((w) => hay.some((h, k) => k < ix.nameWords[n] && h.startsWith(w)))) first.push(item);
    else if (rest.length < limit) rest.push(item);
  }
  return [...(exact ? [exact] : []), ...first, ...rest].slice(0, limit);
}
