"use client";

import { moneyFormatter, type MoneyFormatter } from "@nabd/i18n";
import { useEffect, useRef, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import styles from "./billing.module.css";
import { getIdentity, getPermissions } from "../../lib/session";
import { can } from "../../lib/permissions";
import Dialog from "../../components/ui/Dialog";
import Icon, { type IconName } from "../../components/ui/Icon";
import { isKsa, paymentMethodLabel } from "@nabd/domain";
import { api, ApiError } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { useHasMounted } from "../../lib/useHasMounted";
import DeskSummary from "./DeskSummary";
import { expectedCashOf, prefillCounted } from "./deskSummaryModel";
import { ErrorSummary, FieldError, useFormErrors } from "../../lib/formErrors";
import type { Full } from "../../lib/gen";
import type { Close as GClose, DayCloseHistoryItem, DayCloseSummaryResponse, Method as GMethod, Totals as GTotals, Outstanding as GOutstanding, TaxBand as GTaxBand, Unbilled as GUnbilled } from "@nabd/api-client/generated";

// GET /v1/billing/day-close (DayCloseController → DayCloseSummaryResponse), with the always-sent fields marked present.
type Summary = Omit<Full<DayCloseSummaryResponse, "close" | "voidedCount" | "voidedTotal">, "totals" | "methods" | "outstanding" | "unbilled" | "taxBands" | "close"> & {
  totals: Full<GTotals>; methods: Full<GMethod>[]; outstanding: Full<GOutstanding>; unbilled: Full<GUnbilled, "doctorName">[]; taxBands: Full<GTaxBand>[];
  close: Full<GClose, "note" | "reopenedByName" | "reopenedAt" | "reopenReason"> | null;
};
// FIN-005: GET /v1/billing/day-close/history — every close and reopen, newest first.
type HistoryItem = Full<DayCloseHistoryItem, "expectedCash" | "countedCash" | "variance" | "note" | "actorName">;

// The wireframe's unlock reasons; "Other" takes free text.
const REOPEN_REASONS = ["Missing bill found afterwards", "Cash count entered incorrectly", "Payment method recorded wrongly"];

function formatDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

const time = (instant: string) => new Date(instant).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

export default function BillingPage() {
  const qc = useQueryClient();
  const mounted = useHasMounted(); // permissions live in the browser: role-dependent controls wait for hydration
  const [counted, setCounted] = useState(""); // cash counted, as typed
  const [dialog, setDialog] = useState<"close" | "reopen" | null>(null);
  const [day, setDay] = useState<string | null>(null); // FIN-005: picked past day; null = today
  const [todayIso, setTodayIso] = useState<string | null>(null);
  const canApprove = mounted && getPermissions().includes("billing:approve");
  const canSetup = mounted && can("setup:view");
  const canDesk = mounted && can("queue:view"); // ADM-044: the desk summary's grant
  const region = mounted ? getIdentity()?.tenantRegion : undefined;

  // PHA-010: counter sales exist only in hybrid/in_house pharmacy mode and need billing:create.
  // otc-charges needs only billing:view and returns 409 in external mode, so a 200 means counter sales
  // are available (no pharmacy:view needed). Any failure leaves the button absent.
  const otcQ = useQuery({ queryKey: key("otc-available"), queryFn: getter<unknown>("/billing/otc-charges"), enabled: mounted && can("billing:create") && can("billing:view"), staleTime: 60_000 });
  const canOtc = otcQ.isSuccess;

  const summaryKey = key("day-close", { day: day ?? "today" });
  const sumQ = useQuery({ queryKey: summaryKey, queryFn: getter<Summary>(day ? `/billing/day-close?date=${day}` : "/billing/day-close"), placeholderData: keepPreviousData });
  const s = sumQ.data ?? null;
  const histQ = useQuery({ queryKey: key("day-close-history", { date: s?.date }), queryFn: getter<{ items?: HistoryItem[] }>(`/billing/day-close/history?from=${s?.date}&to=${s?.date}`), enabled: !!s });
  const history = histQ.data ? histQ.data.items ?? [] : null;
  const historyError = histQ.isError;
  const forbidden = sumQ.error instanceof ApiError && sumQ.error.status === 403;
  const error = !sumQ.error || forbidden ? null : sumQ.error instanceof ApiError ? "Couldn't load today's billing. Try again." : "Couldn't reach the server. Check your connection and try again.";
  const sToday = s?.today ? s.date : null;
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- remembers the clinic's today (the date picker's max) once a "today" summary has loaded
    if (sToday) setTodayIso(sToday);
  }, [sToday]);
  // The owner top bar's "Start day close" lands here with ?action=close; once, when the first summary arrives.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (!s || autoOpened.current) return;
    autoOpened.current = true;
    if (new URLSearchParams(window.location.search).get("action") === "close" && canApprove && s.today && s.close?.status !== "closed") {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- a one-shot URL signal, consumed once; pre-fills the close (ADM-044)
      setCounted((c) => prefillCounted(c, expectedCashOf(s.methods)));
      setDialog("close");
    }
  }, [s, canApprove]);
  /** A close or unlock answered with the new summary: show it and re-read the history. */
  const applied = (next: Summary) => {
    qc.setQueryData(summaryKey, next);
    void qc.invalidateQueries({ queryKey: key("day-close-history", { date: next.date }) });
    setDialog(null);
  };

  if (forbidden) return <main className={styles.page}><p className={styles.state}>You don&apos;t have access to billing.</p></main>;
  if (error && !s) return <main className={styles.page}><div className={styles.error} role="alert">{error}</div></main>;
  if (!s) return <main className={styles.page}><p className={styles.state}>Loading…</p></main>;

  const money = moneyFormatter(s.currency);
  const m = Object.fromEntries(s.methods.map((x) => [x.method, x])) as Record<"cash" | "card" | "upi" | "other", Summary["methods"][number]>;
  const closed = s.close?.status === "closed";
  const reopened = s.close?.status === "reopened";
  const blocked = !closed && s.unbilled.length > 0;
  const expectedCash = expectedCashOf(s.methods);
  const countedNum = counted.trim() === "" ? null : Number(counted);
  const countValid = countedNum !== null && Number.isFinite(countedNum) && countedNum >= 0;
  const variance = closed ? Number(s.close!.variance) : countValid ? countedNum! - expectedCash : null;
  const beyond = variance !== null && Math.abs(variance) > Number(s.cashTolerance);
  const taxSum = s.taxBands.reduce((a, b) => a + Number(b.tax), 0);
  const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  const signed = (v: number) => (v > 0 ? "+" : v < 0 ? "−" : "") + money.format(Math.abs(v));

  const upiLabel = paymentMethodLabel("upi", region);
  const kpis: [string, string, string, IconName, string][] = [
    ["Total billed", money.format(Number(s.totals.billed)), plural(s.totals.bills, "bill", "bills"), "receipt", "info"],
    ["Total collected", money.format(Number(s.totals.collected)),
      Number(s.totals.billed) > 0 ? `${Math.round((Number(s.totals.collected) / Number(s.totals.billed)) * 100)}% of billed` : "nothing billed yet", "check", "ok"],
    ["Cash", money.format(expectedCash), "expected in drawer", "banknote", "accent"],
    ["Card", money.format(Number(m.card.amount)), plural(m.card.transactions, "transaction", "transactions"), "card", "info"],
    // Wireframe dcKpis: wallet for KSA's "Mada & digital", QR for India's UPI.
    [upiLabel, money.format(Number(m.upi.amount)), plural(m.upi.transactions, "transaction", "transactions"), isKsa(region) ? "wallet" : "qr", "sync"],
    ["Outstanding balances", money.format(Number(s.outstanding.amount)), plural(s.outstanding.patients, "patient", "patients"), "clock", "warn"],
    ["Refunds recorded", "—", "out of scope this pass", "refresh", "neutral"],
    [`${s.taxLabel} collected`, money.format(Number(s.totals.tax)), "reconciles to line items", "percent", "warn"],
    ["Pending checkouts", String(s.pendingCheckouts), "on the reception worklist", "hourglass", "warn"],
    ["Consultations without a bill", String(s.unbilled.length), s.unbilled.length ? "blocks day close" : "nothing blocking", s.unbilled.length ? "alert" : "check", s.unbilled.length ? "danger" : "ok"],
  ];

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Billing &amp; Day Close</h1>
          <p className={styles.subtitle}>
            {formatDate(s.date)}{s.today ? " · Today" : ""}
            <span className={closed ? styles.pillLocked : blocked ? styles.pillBad : styles.pillOk}>
              {closed ? "Day closed" : blocked ? "Close blocked" : "Ready to close"}
            </span>
          </p>
        </div>
        <div className={styles.headerActions}>
          {canOtc && <Link className={styles.btn} href="/checkout/otc">Counter sale</Link>}
          {canSetup && <Link className={styles.btn} href="/setup?tab=charges">Charges &amp; Taxes</Link>}
          <input type="date" className={styles.input} aria-label="Day to view" value={s.date} max={todayIso ?? undefined}
            onChange={(e) => { setCounted(""); setDay(e.target.value && e.target.value !== todayIso ? e.target.value : null); }} />
          {canApprove && (closed
            ? <button type="button" className={styles.btnPrimary} onClick={() => setDialog("reopen")}>Unlock day</button>
            : <button type="button" className={styles.btnPrimary} onClick={() => { setCounted(prefillCounted(counted, expectedCash)); setDialog("close"); }}>{reopened ? "Close again" : "Start day close"}</button>)}
        </div>
      </div>

      {error && <div className={styles.error} role="alert">{error}</div>}

      {canDesk && <DeskSummary day={day} />}

      <div className={styles.kpis}>
        {kpis.map(([label, value, sub, icon, tone], i) => (
          <div key={label} className={styles.kpi}>
            <div className={styles.kpiLabel}>
              <span style={{ color: `var(--nb-${tone}-500, var(--nb-text-tertiary))`, display: "inline-flex", marginInlineEnd: 6, verticalAlign: "middle" }}><Icon name={icon} size={13} /></span>
              {label}
            </div>
            <div className={styles.kpiValue} style={i === 9 && s.unbilled.length ? { color: "var(--nb-danger-500)" } : undefined}>{value}</div>
            <div className={styles.kpiSub}>{sub}</div>
          </div>
        ))}
      </div>

      {s.unbilled.length > 0 && (
        <section className={styles.alert} aria-label="Consultations without a bill">
          <div className={styles.alertHead}>
            <div className={styles.alertTitle}>{plural(s.unbilled.length, "completed consultation has", "completed consultations have")} no bill</div>
            <div className={styles.alertText}>The day cannot be closed until each of these is billed.</div>
          </div>
          {s.unbilled.map((u) => (
            <div key={u.queueEntryId} className={styles.alertRow}>
              <span className={styles.token}>T-{u.token}</span>
              <div className={styles.rowMain}>
                <div className={styles.rowName}>{u.patientName}</div>
                <div className={styles.rowSub}>{[u.doctorName, `ended ${time(u.endedAt)}`].filter(Boolean).join(" · ")}</div>
              </div>
              <Link className={styles.btnSmall} href={`/checkout/${u.queueEntryId}`}>Open checkout</Link>
            </div>
          ))}
        </section>
      )}

      <div className={styles.cards}>
        <div style={{ display: "flex", flexDirection: "column", gap: "var(--nb-space-16)" }}>
          <section className={styles.card} aria-label="Cash reconciliation">
            <div className={styles.cardHead}>
              Cash reconciliation
              <span className={variance === null ? styles.pillWarn : beyond ? styles.pillBad : styles.pillOk}>
                {variance === null ? "Count pending" : beyond ? "Variance" : "Balanced"}
              </span>
            </div>
            <div className={styles.line}><span>Expected cash</span><span className={styles.amt}>{money.format(closed ? Number(s.close!.expectedCash) : expectedCash)}</span></div>
            <div className={styles.line}>
              <span>Actual cash counted</span>
              {closed ? <span className={styles.amt}>{money.format(Number(s.close!.countedCash))}</span>
                : countValid ? <span className={styles.amt}>{money.format(countedNum!)}</span>
                  : <span className={styles.amtWarn}>Not entered</span>}
            </div>
            <div className={styles.line}>
              <span>Cash variance{beyond && <div className={styles.lineSub}>{closed ? "beyond tolerance — note recorded" : "beyond tolerance — owner note required"}</div>}</span>
              <span className={variance === null ? styles.amtMuted : variance === 0 ? styles.amtOk : beyond ? styles.amtBad : styles.amtWarn}>
                {variance === null ? "—" : signed(variance)}
              </span>
            </div>
            {closed ? (
              <div className={styles.line}>
                <span>Closed by {s.close!.closedByName} at {time(s.close!.closedAt)}{s.close!.note && <div className={styles.lineSub}>{s.close!.note}</div>}</span>
              </div>
            ) : canApprove && (
              <div className={styles.countBox}>
                <label className={styles.countLabel} htmlFor="counted-cash">Enter counted cash</label>
                <div className={styles.countRow}>
                  <input id="counted-cash" className={styles.input} type="number" inputMode="decimal" min="0" step="0.01"
                    value={counted} placeholder="0.00" onChange={(e) => setCounted(e.target.value)} />
                  <button type="button" className={styles.chip} onClick={() => setCounted(String(expectedCash))}>
                    Matches: {money.format(expectedCash)}
                  </button>
                </div>
              </div>
            )}
          </section>

          <section className={styles.card} aria-label="Outstanding and cancelled">
            <div className={styles.cardHead}>Outstanding &amp; cancelled</div>
            <div className={styles.line}>
              <span>Outstanding balances<div className={styles.lineSub}>{plural(s.outstanding.patients, "patient", "patients")} · acknowledged at close</div></span>
              <span className={Number(s.outstanding.amount) > 0 ? styles.amtWarn : styles.amt}>{money.format(Number(s.outstanding.amount))}</span>
            </div>
            <div className={styles.line}>
              {typeof s.voidedCount === "number" ? (
                <>
                  <span>Cancelled bills<div className={styles.lineSub}>{plural(s.voidedCount, "bill", "bills")} voided · retained in audit, never deleted</div></span>
                  <span className={styles.amtMuted}>{money.format(Number(s.voidedTotal ?? 0))}</span>
                </>
              ) : (
                <>
                  <span>Cancelled bills<div className={styles.lineSub}>not reported by this server yet</div></span>
                  <span className={styles.amtMuted}>—</span>
                </>
              )}
            </div>
            <div className={styles.line}>
              <span>Unbilled consultations<div className={styles.lineSub}>blocks day close</div></span>
              <span className={s.unbilled.length ? styles.amtBad : styles.amtOk}>{s.unbilled.length}</span>
            </div>
          </section>
        </div>

        <section className={styles.card} aria-label="Card and digital settlement">
          <div className={styles.cardHead}>Card &amp; digital settlement</div>
          <div className={styles.line}><span>Card settlement total</span><span className={styles.amtOk}>{money.format(Number(m.card.amount))}</span></div>
          <div className={styles.line}><span>{upiLabel}</span><span className={styles.amtOk}>{money.format(Number(m.upi.amount))}</span></div>
          {Number(m.other.amount) > 0 && (
            <div className={styles.line}><span>Other</span><span className={styles.amtOk}>{money.format(Number(m.other.amount))}</span></div>
          )}
          <div className={styles.line}>
            <span>Split payments<div className={styles.lineSub}>each tender reconciled separately</div></span>
            <span className={styles.amtInfo}>{plural(s.splitPaymentBills, "bill", "bills")}</span>
          </div>
          <div className={styles.line}>
            <span>Failed / cancelled<div className={styles.lineSub}>failed attempts aren&apos;t recorded yet</div></span>
            <span className={styles.amtMuted}>—</span>
          </div>
        </section>

        <section className={styles.card} aria-label="Close history">
          <div className={styles.cardHead}>Close history</div>
          {historyError ? <div className={styles.error} role="alert">Couldn&apos;t load the close history.</div>
            : history === null ? <div className={styles.line}><span className={styles.lineSub}>Loading…</span></div>
              : history.length === 0 ? <div className={styles.line}><span className={styles.lineSub}>This day hasn&apos;t been closed yet.</span></div>
                : <ol className={styles.history}>
                  {history.map((h) => (
                    <li key={h.id} className={styles.line} data-action={h.action}>
                      <span>{h.action === "close" ? "Closed" : "Unlocked"} by {h.actorName ?? "Unknown"} at {time(h.at)}
                        {h.note && <div className={styles.lineSub}>{h.note}</div>}</span>
                      <span className={styles.amt}>{h.action === "close" ? `${money.format(Number(h.countedCash))} / ${money.format(Number(h.expectedCash))}` : "—"}</span>
                    </li>
                  ))}
                </ol>}
        </section>

        <section className={styles.card} aria-label={`${s.taxLabel} summary`}>
          <div className={styles.cardHead}>
            {s.taxLabel} summary
            {s.taxBands.length > 0 && (
              <span className={Math.abs(taxSum - Number(s.totals.tax)) < 0.005 ? styles.pillOk : styles.pillBad}>
                {Math.abs(taxSum - Number(s.totals.tax)) < 0.005 ? "Reconciles" : "Mismatch"}
              </span>
            )}
          </div>
          {s.taxBands.length === 0 && <div className={styles.line}><span className={styles.lineSub}>No bills yet today.</span></div>}
          {s.taxBands.map((b) => (
            <div key={b.ratePercent} className={styles.line}>
              <span>{Number(b.ratePercent) === 0 ? "Exempt (0%)" : `Taxable ${Number(b.ratePercent)}%`}<div className={styles.lineSub}>{money.format(Number(b.tax))} {s.taxLabel}</div></span>
              <span className={Number(b.ratePercent) === 0 ? styles.amtInfo : styles.amt}>{money.format(Number(b.taxable))}</span>
            </div>
          ))}
          <div className={styles.line}><span>{s.taxLabel} collected</span><span className={styles.amtOk}>{money.format(Number(s.totals.tax))}</span></div>
        </section>
      </div>

      {dialog === "close" && (
        <CloseDialog summary={s} money={money} counted={counted} setCounted={setCounted} variance={variance} beyond={beyond}
          onClose={() => setDialog(null)} onDone={(next) => { applied(next); setCounted(""); }} />
      )}
      {dialog === "reopen" && (
        <ReopenDialog date={s.date} onClose={() => setDialog(null)} onDone={applied} />
      )}
    </main>
  );
}

/** The wireframe's three checks — pending issues, cash count, variance — then close. The server re-checks all of them. */
function CloseDialog({ summary, money, counted, setCounted, variance, beyond, onClose, onDone }: {
  summary: Summary; money: MoneyFormatter; counted: string; setCounted: (v: string) => void;
  variance: number | null; beyond: boolean;
  onClose: () => void; onDone: (s: Summary) => void;
}) {
  const [note, setNote] = useState("");
  const fe = useFormErrors("close", { countedCash: "Counted cash", note: "Note", date: "Day" });
  const [saving, setSaving] = useState(false);
  const blocked = summary.unbilled.length > 0;
  const canSubmit = !blocked && variance !== null && (!beyond || note.trim() !== "");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    fe.clear();
    try {
      onDone(await api.post<Summary>("/billing/day-close", { countedCash: Number(counted), note: note.trim() || null, date: summary.date }));
    } catch (err) {
      fe.fail(err, "Couldn't close the day.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog title={summary.close?.status === "reopened" ? "Close the day again" : "Day close"} onClose={onClose}>
      <form onSubmit={submit}>
        <p className={styles.dialogSub}>{formatDate(summary.date)} · billing locks once the day is closed</p>
        {/* ADM-044: opens pre-filled with the expected totals; the counted cash starts at the expected cash and stays editable */}
        <section aria-label="Expected totals">
          {summary.methods.map((x) => (
            <div key={x.method} className={styles.step}><span>Expected {paymentMethodLabel(x.method, getIdentity()?.tenantRegion)}</span><span className={styles.amt}>{money.format(Number(x.amount))}</span></div>
          ))}
        </section>
        <div className={styles.step}><span>1. Pending issues</span>
          <span className={blocked ? styles.amtBad : styles.amtOk}>{blocked ? `${summary.unbilled.length} blocking` : "None"}</span></div>
        <div className={styles.step}><span>2. Cash count</span>
          <span className={variance === null ? styles.amtWarn : styles.amt}>{variance === null ? "Required" : money.format(Number(counted))}</span></div>
        <FieldError fe={fe} name="countedCash" className={styles.lineSub} />
        <div className={styles.step}><span>3. Variance</span>
          <span className={variance === null ? styles.amtMuted : beyond ? styles.amtBad : styles.amtOk}>
            {variance === null ? "—" : (variance > 0 ? "+" : variance < 0 ? "−" : "") + money.format(Math.abs(variance))}
          </span></div>
        {blocked && <p className={styles.dialogSub} style={{ marginTop: 12 }}>Bill each consultation listed on the page first — the close is blocked until then.</p>}
        {!blocked && (
          <div className={styles.field}>
            <label className={styles.countLabel} htmlFor="close-counted">Counted cash</label>
            <input id="close-counted" className={styles.input} type="number" min="0" step="0.01" value={counted} autoFocus {...fe.attrs("countedCash")}
              onChange={(e) => { fe.clearField("countedCash"); setCounted(e.target.value); }} />
          </div>
        )}
        {!blocked && variance !== null && (
          <div className={styles.field}>
            <label className={styles.countLabel} htmlFor="close-note">
              Note{beyond ? ` (required — off by more than ${money.format(Number(summary.cashTolerance))})` : " (optional)"}
            </label>
            <textarea id="close-note" className={styles.textarea} maxLength={1000} value={note} {...fe.attrs("note")} onChange={(e) => { fe.clearField("note"); setNote(e.target.value); }} />
            <FieldError fe={fe} name="note" className={styles.lineSub} />
          </div>
        )}
        <ErrorSummary fe={fe} className={styles.error} />
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={!canSubmit || saving}>{saving ? "Closing…" : "Close the day"}</button>
        </div>
      </form>
    </Dialog>
  );
}

function ReopenDialog({ date, onClose, onDone }: {
  date: string;
  onClose: () => void; onDone: (s: Summary) => void;
}) {
  const [choice, setChoice] = useState<string>("");
  const [other, setOther] = useState("");
  const fe = useFormErrors("reopen", { reason: "Reason", date: "Day" });
  const [saving, setSaving] = useState(false);
  const reason = choice === "other" ? other.trim() : choice;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    fe.clear();
    try {
      onDone(await api.post<Summary>("/billing/day-close/reopen", { reason, date }));
    } catch (err) {
      fe.fail(err, "Couldn't unlock the day.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog title="Unlock a closed day" onClose={onClose}>
      <form onSubmit={submit}>
        <p className={styles.dialogSub}>Unlocking reopens {formatDate(date)} so it can be corrected and closed again. It is recorded in the audit log with your name, the time and your reason.</p>
        <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className={styles.countLabel}>Reason (required)</legend>
          {[...REOPEN_REASONS, "other"].map((r) => (
            <label key={r} className={styles.radio}>
              <input type="radio" name="reopen-reason" value={r} checked={choice === r} {...fe.attrs("reason")} onChange={() => { fe.clearField("reason"); setChoice(r); }} />
              {r === "other" ? "Other" : r}
            </label>
          ))}
        </fieldset>
        {choice === "other" && (
          <input aria-label="Other reason" maxLength={500} className={styles.input} style={{ width: "100%", marginTop: 8 }} value={other} autoFocus
            onChange={(e) => setOther(e.target.value)} placeholder="What needs correcting?" />
        )}
        <FieldError fe={fe} name="reason" className={styles.lineSub} />
        <ErrorSummary fe={fe} className={styles.error} />
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={!reason || saving}>{saving ? "Unlocking…" : "Unlock day"}</button>
        </div>
      </form>
    </Dialog>
  );
}
