// PHL-007: pure rules of the supplier-bill grid (unit-tested). The server owns the truth (expiry check, duplicate bill,
// stock); these only keep a keyboard-first entry fast and catch the obvious slips before the Save.

export type ReceiptRow = { itemId: string; batchNo: string; expiry: string; mrp: string; rate: string; qty: string; freeQty: string; taxRate: string };

export const emptyRow = (): ReceiptRow => ({ itemId: "", batchNo: "", expiry: "", mrp: "", rate: "", qty: "", freeQty: "", taxRate: "" });

/** A row nobody has typed into (the trailing blank row of the grid) is ignored, not an error. */
export const isBlank = (r: ReceiptRow): boolean => Object.values(r).every((v) => v.trim() === "");

/** "MM/YY" or "MM/YYYY" (a "-" or "." works too, and "0426" without a separator) -> the LAST day of that month as YYYY-MM-DD, else null. */
export function parseExpiry(text: string): string | null {
  const t = text.trim();
  const m = /^(\d{1,2})\s*[/.\-]?\s*(\d{2}|\d{4})$/.exec(t);
  if (!m) return null;
  const month = Number(m[1]);
  if (month < 1 || month > 12) return null;
  const year = m[2].length === 2 ? 2000 + Number(m[2]) : Number(m[2]);
  const last = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return `${year}-${String(month).padStart(2, "0")}-${String(last).padStart(2, "0")}`;
}

const num = (s: string): number | null => (s.trim() !== "" && Number.isFinite(Number(s)) ? Number(s) : null);

/** Rate x paid / (paid + free), 2 places, half up on the minor unit: 10 paid + 2 free at 50 = 41.67 (the server computes the stored figure). */
export function effectiveRate(rate: number, qty: number, free: number): number {
  if (qty <= 0) return rate;
  return Math.round((rate * qty * 100) / (qty + free)) / 100;
}

export const lineTotal = (r: ReceiptRow): number => Math.round((num(r.rate) ?? 0) * (num(r.qty) ?? 0) * 100) / 100;

export const billTotal = (rows: ReceiptRow[]): number => Math.round(rows.filter((r) => !isBlank(r)).reduce((s, r) => s + lineTotal(r), 0) * 100) / 100;

/** Message for the first problem of a (non-blank) row, or null when it can be sent. `today` is the clinic's date, YYYY-MM-DD. */
export function rowProblem(r: ReceiptRow, today: string): string | null {
  if (!r.itemId) return "Pick the item.";
  if (r.batchNo.trim() === "") return "Enter the batch number.";
  const exp = parseExpiry(r.expiry);
  if (!exp) return "Enter the expiry as MM/YY.";
  if (exp < today) return "This batch has already expired.";
  for (const [k, label] of [["mrp", "MRP"], ["rate", "Rate"]] as const) {
    const v = num(r[k]);
    if (v == null || v < 0) return `${label} must be a number, 0 or more.`;
  }
  const q = num(r.qty);
  if (q == null || !Number.isInteger(q) || q < 1) return "Quantity must be a whole number, at least 1.";
  const f = num(r.freeQty);
  if (r.freeQty.trim() !== "" && (f == null || !Number.isInteger(f) || f < 0)) return "Free quantity must be a whole number, 0 or more.";
  if (r.taxRate.trim() !== "" && (num(r.taxRate) ?? -1) < 0) return "Tax rate must be 0 or more.";
  return null;
}

export type ReceiptBody = {
  supplierName: string; billNumber: string; billDate: string;
  lines: { itemId: string; batchNo: string; expiryDate: string; mrp: number; rate: number; qty: number; freeQty: number; taxRate: number }[];
};

/** The POST /pharmacy/receipts body from the filled rows; the caller has already checked every row with {@link rowProblem}. */
export function toBody(supplierName: string, billNumber: string, billDate: string, rows: ReceiptRow[]): ReceiptBody {
  return {
    supplierName: supplierName.trim(), billNumber: billNumber.trim(), billDate,
    lines: rows.filter((r) => !isBlank(r)).map((r) => ({
      itemId: r.itemId, batchNo: r.batchNo.trim(), expiryDate: parseExpiry(r.expiry) as string, mrp: Number(r.mrp), rate: Number(r.rate),
      qty: Number(r.qty), freeQty: num(r.freeQty) ?? 0, taxRate: num(r.taxRate) ?? 0,
    })),
  };
}
