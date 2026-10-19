// FEF-008: one Idempotency-Key per money/registration intent, kept in sessionStorage (never localStorage, ADR-12:
// payments are never queued offline) so a retry or a page reload re-sends the SAME key and the server collapses it.
// React-free so it is unit-tested (tests/unit/fef008-idempotency.test.mjs). Cleared on sign-out (session.ts).
const PREFIX = "nabd.pay.";
export const POST_TIMEOUT_MS = 15_000;
export const OFFLINE_MESSAGE = "You are offline — payments cannot be queued";

export class TimeoutError extends Error {
  constructor() { super("request timed out"); this.name = "TimeoutError"; }
}
export type Fetcher = (path: string, init?: RequestInit) => Promise<Response | null>;
type Payment = { id?: string; idempotencyKey?: string | null };
type PayInvoice = { payments?: Payment[] } & Record<string, unknown>;
export type Intent = { key: string; method: string; amountMinor: number; scope?: string };

const store = (): Storage | null => { try { return typeof sessionStorage === "undefined" ? null : sessionStorage; } catch { return null; } };

/** Short stable fingerprint of a request body, for scopes that have no natural id. */
export function fingerprint(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}
/** The persisted key for this scope; created once, then returned unchanged until dropKey. */
export function keyFor(scope: string, extra: Omit<Intent, "key"> = { method: "", amountMinor: 0 }): string {
  const s = store();
  try { const cur = s?.getItem(PREFIX + scope); if (cur) return (JSON.parse(cur) as Intent).key; } catch { /* corrupt entry: replace */ }
  const key = crypto.randomUUID();
  try { s?.setItem(PREFIX + scope, JSON.stringify({ key, ...extra })); } catch { /* storage full/blocked: key lives for this attempt only */ }
  return key;
}
export function dropKey(scope: string): void { try { store()?.removeItem(PREFIX + scope); } catch { /* ignore */ } }
/** Sign-out: no payment intent survives the session. */
export function clearIntents(): void {
  const s = store();
  if (!s) return;
  try { for (const k of Object.keys(s)) if (k.startsWith(PREFIX)) s.removeItem(k); } catch { /* ignore */ }
}
/** Intents left behind for an invoice (reload mid-timeout). */
export function pendingFor(invoiceId: string): Intent[] {
  const s = store();
  const out: Intent[] = [];
  if (!s) return out;
  try {
    for (const k of Object.keys(s)) if (k.startsWith(`${PREFIX}pay.${invoiceId}.`)) out.push({ ...JSON.parse(s.getItem(k) ?? "null"), scope: k.slice(PREFIX.length) });
  } catch { /* ignore */ }
  return out.filter((i) => i && i.key);
}

/** POST with an Idempotency-Key and a hard timeout. Rejects with TimeoutError, or the network error. */
export async function keyedPost(f: Fetcher, path: string, body: unknown, key: string, timeoutMs = POST_TIMEOUT_MS): Promise<Response | null> {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeoutMs);
  try {
    return await f(path, { method: "POST", body: JSON.stringify(body), headers: { "Idempotency-Key": key }, signal: ac.signal });
  } catch (e) {
    throw ac.signal.aborted ? new TimeoutError() : e;
  } finally { clearTimeout(t); }
}

/** Generic keyed submit (checkout, OTC, package sale, check-in, registration): the key is dropped once the
 * server has answered definitively, kept on timeout/5xx/in-progress so the retry reuses it. */
export async function submitKeyed(f: Fetcher, path: string, body: unknown, scope: string): Promise<Response | null> {
  const res = await keyedPost(f, path, body, keyFor(scope));
  if (res && (res.ok || (res.status < 500 && res.status !== 409))) dropKey(scope); // 409 may be IN_PROGRESS: keep
  return res;
}

export type PayOutcome =
  | { kind: "recorded"; invoice: PayInvoice }
  | { kind: "rejected"; message: string }
  | { kind: "notRecorded"; invoice: PayInvoice; message?: string }  // status check ran: nothing landed; retry reuses the key
  | { kind: "unknown" }                            // status check itself failed
  | { kind: "offline" }
  | { kind: "gone" };                              // session ended

/** Which payment (if any) carries this key. */
export const landed = (inv: PayInvoice | null | undefined, key: string): boolean => !!inv?.payments?.some((p) => p.idempotencyKey === key);

/** GET the invoice and look for the key. */
export async function checkStatus(f: Fetcher, invoiceId: string, key: string, scope?: string): Promise<PayOutcome> {
  try {
    const r = await f(`/billing/invoices/${invoiceId}`);
    if (!r) return { kind: "gone" };
    if (!r.ok) return { kind: "unknown" };
    const invoice: PayInvoice = await r.json();
    if (landed(invoice, key)) { if (scope) dropKey(scope); return { kind: "recorded", invoice }; }
    return { kind: "notRecorded", invoice };
  } catch { return { kind: "unknown" }; }
}

/** Records one payment safely. `slot` = payments already on the invoice, so two identical rows get two keys. */
export async function recordPayment(
  f: Fetcher, p: { invoiceId: string; method: string; amountMinor: number; slot: number }, onChecking?: () => void,
): Promise<PayOutcome> {
  if (typeof navigator !== "undefined" && navigator.onLine === false) return { kind: "offline" }; // nothing stored
  const scope = `pay.${p.invoiceId}.${p.method}.${p.amountMinor}.${p.slot}`;
  const key = keyFor(scope, { method: p.method, amountMinor: p.amountMinor });
  let res: Response | null;
  try {
    res = await keyedPost(f, `/billing/invoices/${p.invoiceId}/payments`, { method: p.method, amount: p.amountMinor / 100 }, key);
  } catch {
    onChecking?.();
    return checkStatus(f, p.invoiceId, key, scope);
  }
  if (!res) return { kind: "gone" };
  if (res.ok) {
    dropKey(scope);
    const body: PayInvoice = await res.json().catch(() => ({}));
    if (Array.isArray(body.payments)) return { kind: "recorded", invoice: body };
    onChecking?.(); // 201 summary only: read the invoice back
    const c = await checkStatus(f, p.invoiceId, key);
    return c.kind === "recorded" || c.kind === "notRecorded" ? { kind: "recorded", invoice: c.invoice } : c;
  }
  const prob: { code?: string; type?: string; detail?: string } = await res.json().catch(() => ({}));
  const tag = `${prob.code ?? ""} ${prob.type ?? ""}`;
  if (res.status >= 500 || (res.status === 409 && /IN_PROGRESS/i.test(tag))) {
    onChecking?.();
    const c = await checkStatus(f, p.invoiceId, key, scope);
    return c.kind === "notRecorded" ? { ...c, message: prob.detail } : c;
  }
  dropKey(scope); // a definitive refusal: the next attempt is a new intent
  if (res.status === 422 && /KEY_REUSED/i.test(tag)) {
    return { kind: "rejected", message: "This payment was already started with a different amount. Reload the page and enter the payment again." };
  }
  return { kind: "rejected", message: prob.detail || "Couldn't record payment." };
}
