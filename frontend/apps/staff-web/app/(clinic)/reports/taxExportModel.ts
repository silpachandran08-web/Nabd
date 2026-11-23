// FIN-011: pure helpers of the Tax exports card (no React), unit-tested in s27-fin011-gst-export.test.mjs.

export type TaxFormat = "xlsx" | "json";
export const TAX_FORMATS: TaxFormat[] = ["xlsx", "json"];

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** The latest finished month: the month before the clinic's today ("YYYY-MM-DD"). */
export function defaultPeriod(today: string): string {
  const [y, m] = today.split("-").map(Number);
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, "0")}`;
}

/** null when the month can be exported; "open" while the month is still running (the server answers 422 PERIOD_OPEN). */
export function periodProblem(period: string, today: string): "invalid" | "open" | null {
  if (!MONTH.test(period)) return "invalid";
  return period >= today.slice(0, 7) ? "open" : null;
}

export const exportPath = (period: string, format: TaxFormat) => `/billing/exports/gstr1?${new URLSearchParams({ period, format })}`;

export type Gstr1Answer = { period: string; format: string; fileName: string; contentType: string; reconciled: boolean; invoices: number; creditNotes: number; contentBase64: string };

export type EinvoiceStatus = "pending" | "submitted" | "accepted" | "cleared" | "reported" | "rejected" | "cancelled";
export type EinvoiceItem = { invoiceId: string; invoiceNumber: string; status: EinvoiceStatus; irn?: string | null; ackNo?: string | null; attempts: number; lastError?: string | null };

/** The chip's tone and text key: a pending bill reads "IRN pending" and may be retried, a refused one needs the desk's attention. */
export function chip(status: EinvoiceStatus): { tone: "ok" | "wait" | "bad" | "off"; key: "chipIssued" | "chipPending" | "chipRejected" | "chipCancelled" } {
  if (status === "accepted" || status === "cleared" || status === "reported") return { tone: "ok", key: "chipIssued" };
  if (status === "rejected") return { tone: "bad", key: "chipRejected" };
  if (status === "cancelled") return { tone: "off", key: "chipCancelled" };
  return { tone: "wait", key: "chipPending" };
}

/** Retry is offered for a waiting or refused submission only; an issued IRN is never sent again. */
export const canRetry = (status: EinvoiceStatus) => status === "pending" || status === "submitted" || status === "rejected";
