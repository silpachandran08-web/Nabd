"use client";

import { moneyFormatter, type MoneyFormatter } from "@nabd/i18n";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import styles from "./billing.module.css";
import { getIdentity, getPermissions, isOwner } from "../../lib/session";
import { can } from "../../lib/permissions";
import Dialog from "../../components/ui/Dialog";
import Icon, { type IconName } from "../../components/ui/Icon";
import { isKsa } from "@nabd/domain";
import { usePaymentMethodLabel } from "../../lib/paymentMethodText";
import { api, ApiError } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { useHasMounted } from "../../lib/useHasMounted";
import DeskSummary from "./DeskSummary";
import OwnerFinanceTabs from "./OwnerFinanceTabs";
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

// The wireframe's unlock reasons; "Other" takes free text. The English text is what the server records (FEF-040: only the label is translated).
const REOPEN_REASONS = { missingBill: "Missing bill found afterwards", cashCount: "Cash count entered incorrectly", method: "Payment method recorded wrongly" } as const;

function formatDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

const time = (instant: string) => new Date(instant).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

export default function BillingPage() {
  const t = useTranslations("billing.dayClose");
  const paymentMethodLabel = usePaymentMethodLabel();
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
  const error = !sumQ.error || forbidden ? null : sumQ.error instanceof ApiError ? t("loadFailed") : t("unreachable");
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

  if (forbidden) return <main className={styles.page}><p className={styles.state}>{t("noAccess")}</p></main>;
  if (error && !s) return <main className={styles.page}><div className={styles.error} role="alert">{error}</div></main>;
  if (!s) return <main className={styles.page}><p className={styles.state}>{t("loading")}</p></main>;

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
  const signed = (v: number) => (v > 0 ? "+" : v < 0 ? "−" : "") + money.format(Math.abs(v));

  const upiLabel = paymentMethodLabel("upi", region);
  const kpis: [string, string, string, IconName, string][] = [
    [t("kpi.totalBilled"), money.format(Number(s.totals.billed)), t("kpi.bills", { count: s.totals.bills }), "receipt", "info"],
    [t("kpi.totalCollected"), money.format(Number(s.totals.collected)),
      Number(s.totals.billed) > 0 ? t("kpi.pctOfBilled", { pct: Math.round((Number(s.totals.collected) / Number(s.totals.billed)) * 100) }) : t("kpi.nothingBilled"), "check", "ok"],
    [t("kpi.cash"), money.format(expectedCash), t("kpi.expectedInDrawer"), "banknote", "accent"],
    [t("kpi.card"), money.format(Number(m.card.amount)), t("kpi.transactions", { count: m.card.transactions }), "card", "info"],
    // Wireframe dcKpis: wallet for KSA's "Mada & digital", QR for India's UPI.
    [upiLabel, money.format(Number(m.upi.amount)), t("kpi.transactions", { count: m.upi.transactions }), isKsa(region) ? "wallet" : "qr", "sync"],
    [t("kpi.outstanding"), money.format(Number(s.outstanding.amount)), t("kpi.patients", { count: s.outstanding.patients }), "clock", "warn"],
    [t("kpi.refunds"), "—", t("kpi.refundsOutOfScope"), "refresh", "neutral"],
    [t("kpi.taxCollected", { tax: s.taxLabel }), money.format(Number(s.totals.tax)), t("kpi.reconcilesToLines"), "percent", "warn"],
    [t("kpi.pendingCheckouts"), String(s.pendingCheckouts), t("kpi.onWorklist"), "hourglass", "warn"],
    [t("kpi.unbilled"), String(s.unbilled.length), s.unbilled.length ? t("kpi.blocksClose") : t("kpi.nothingBlocking"), s.unbilled.length ? "alert" : "check", s.unbilled.length ? "danger" : "ok"],
  ];

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>{t("title")}</h1>
          <p className={styles.subtitle}>
            {formatDate(s.date)}{s.today ? t("todaySuffix") : ""}
            <span className={closed ? styles.pillLocked : blocked ? styles.pillBad : styles.pillOk}>
              {closed ? t("dayClosed") : blocked ? t("closeBlocked") : t("readyToClose")}
            </span>
          </p>
        </div>
        <div className={styles.headerActions}>
          {canOtc && <Link className={styles.btn} href="/checkout/otc">{t("counterSale")}</Link>}
          {canSetup && <Link className={styles.btn} href="/setup?tab=charges">{t("chargesTaxes")}</Link>}
          <input type="date" className={styles.input} aria-label={t("dayToView")} value={s.date} max={todayIso ?? undefined}
            onChange={(e) => { setCounted(""); setDay(e.target.value && e.target.value !== todayIso ? e.target.value : null); }} />
          {canApprove && (closed
            ? <button type="button" className={styles.btnPrimary} onClick={() => setDialog("reopen")}>{t("unlockDay")}</button>
            : <button type="button" className={styles.btnPrimary} onClick={() => { setCounted(prefillCounted(counted, expectedCash)); setDialog("close"); }}>{reopened ? t("closeAgain") : t("startDayClose")}</button>)}
        </div>
      </div>

      {error && <div className={styles.error} role="alert">{error}</div>}

      {canDesk && <DeskSummary day={day} />}

      {mounted && isOwner() && <OwnerFinanceTabs today={s.today ? s.date : todayIso ?? s.date} />}

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
        <section className={styles.alert} aria-label={t("kpi.unbilled")}>
          <div className={styles.alertHead}>
            <div className={styles.alertTitle}>{t("unbilledTitle", { count: s.unbilled.length })}</div>
            <div className={styles.alertText}>{t("unbilledText")}</div>
          </div>
          {s.unbilled.map((u) => (
            <div key={u.queueEntryId} className={styles.alertRow}>
              <span className={styles.token}>{t("tokenNo", { token: u.token })}</span>
              <div className={styles.rowMain}>
                <div className={styles.rowName}>{u.patientName}</div>
                <div className={styles.rowSub}>{[u.doctorName, t("endedAt", { time: time(u.endedAt) })].filter(Boolean).join(" · ")}</div>
              </div>
              <Link className={styles.btnSmall} href={`/checkout/${u.queueEntryId}`}>{t("openCheckout")}</Link>
            </div>
          ))}
        </section>
      )}

      <div className={styles.cards}>
        <div style={{ display: "flex", flexDirection: "column", gap: "var(--nb-space-16)" }}>
          <section className={styles.card} aria-label={t("cashRecon")}>
            <div className={styles.cardHead}>
              {t("cashRecon")}
              <span className={variance === null ? styles.pillWarn : beyond ? styles.pillBad : styles.pillOk}>
                {variance === null ? t("countPending") : beyond ? t("variance") : t("balanced")}
              </span>
            </div>
            <div className={styles.line}><span>{t("expectedCash")}</span><span className={styles.amt}>{money.format(closed ? Number(s.close!.expectedCash) : expectedCash)}</span></div>
            <div className={styles.line}>
              <span>{t("actualCounted")}</span>
              {closed ? <span className={styles.amt}>{money.format(Number(s.close!.countedCash))}</span>
                : countValid ? <span className={styles.amt}>{money.format(countedNum!)}</span>
                  : <span className={styles.amtWarn}>{t("notEntered")}</span>}
            </div>
            <div className={styles.line}>
              <span>{t("cashVariance")}{beyond && <div className={styles.lineSub}>{closed ? t("beyondNoted") : t("beyondNoteRequired")}</div>}</span>
              <span className={variance === null ? styles.amtMuted : variance === 0 ? styles.amtOk : beyond ? styles.amtBad : styles.amtWarn}>
                {variance === null ? "—" : signed(variance)}
              </span>
            </div>
            {closed ? (
              <div className={styles.line}>
                <span>{t("closedByAt", { name: s.close!.closedByName, time: time(s.close!.closedAt) })}{s.close!.note && <div className={styles.lineSub}>{s.close!.note}</div>}</span>
              </div>
            ) : canApprove && (
              <div className={styles.countBox}>
                <label className={styles.countLabel} htmlFor="counted-cash">{t("enterCounted")}</label>
                <div className={styles.countRow}>
                  <input id="counted-cash" className={styles.input} type="number" inputMode="decimal" min="0" step="0.01"
                    value={counted} placeholder="0.00" onChange={(e) => setCounted(e.target.value)} />
                  <button type="button" className={styles.chip} onClick={() => setCounted(String(expectedCash))}>
                    {t("matches", { amount: money.format(expectedCash) })}
                  </button>
                </div>
              </div>
            )}
          </section>

          <section className={styles.card} aria-label={t("outstandingCancelled")}>
            <div className={styles.cardHead}>{t("outstandingCancelledHead")}</div>
            <div className={styles.line}>
              <span>{t("kpi.outstanding")}<div className={styles.lineSub}>{t("outstandingAck", { patients: t("kpi.patients", { count: s.outstanding.patients }) })}</div></span>
              <span className={Number(s.outstanding.amount) > 0 ? styles.amtWarn : styles.amt}>{money.format(Number(s.outstanding.amount))}</span>
            </div>
            <div className={styles.line}>
              {typeof s.voidedCount === "number" ? (
                <>
                  <span>{t("cancelledBills")}<div className={styles.lineSub}>{t("voidedRetained", { bills: t("kpi.bills", { count: s.voidedCount }) })}</div></span>
                  <span className={styles.amtMuted}>{money.format(Number(s.voidedTotal ?? 0))}</span>
                </>
              ) : (
                <>
                  <span>{t("cancelledBills")}<div className={styles.lineSub}>{t("notReported")}</div></span>
                  <span className={styles.amtMuted}>—</span>
                </>
              )}
            </div>
            <div className={styles.line}>
              <span>{t("unbilledConsults")}<div className={styles.lineSub}>{t("kpi.blocksClose")}</div></span>
              <span className={s.unbilled.length ? styles.amtBad : styles.amtOk}>{s.unbilled.length}</span>
            </div>
          </section>
        </div>

        <section className={styles.card} aria-label={t("cardDigital")}>
          <div className={styles.cardHead}>{t("cardDigitalHead")}</div>
          <div className={styles.line}><span>{t("cardTotal")}</span><span className={styles.amtOk}>{money.format(Number(m.card.amount))}</span></div>
          <div className={styles.line}><span>{upiLabel}</span><span className={styles.amtOk}>{money.format(Number(m.upi.amount))}</span></div>
          {Number(m.other.amount) > 0 && (
            <div className={styles.line}><span>{t("other")}</span><span className={styles.amtOk}>{money.format(Number(m.other.amount))}</span></div>
          )}
          <div className={styles.line}>
            <span>{t("splitPayments")}<div className={styles.lineSub}>{t("splitHint")}</div></span>
            <span className={styles.amtInfo}>{t("kpi.bills", { count: s.splitPaymentBills })}</span>
          </div>
          <div className={styles.line}>
            <span>{t("failedCancelled")}<div className={styles.lineSub}>{t("failedHint")}</div></span>
            <span className={styles.amtMuted}>—</span>
          </div>
        </section>

        <section className={styles.card} aria-label={t("closeHistory")}>
          <div className={styles.cardHead}>{t("closeHistory")}</div>
          {historyError ? <div className={styles.error} role="alert">{t("historyFailed")}</div>
            : history === null ? <div className={styles.line}><span className={styles.lineSub}>{t("loading")}</span></div>
              : history.length === 0 ? <div className={styles.line}><span className={styles.lineSub}>{t("notClosedYet")}</span></div>
                : <ol className={styles.history}>
                  {history.map((h) => (
                    <li key={h.id} className={styles.line} data-action={h.action}>
                      <span>{t(h.action === "close" ? "historyClosed" : "historyUnlocked", { name: h.actorName ?? t("unknownActor"), time: time(h.at) })}
                        {h.note && <div className={styles.lineSub}>{h.note}</div>}</span>
                      <span className={styles.amt}>{h.action === "close" ? `${money.format(Number(h.countedCash))} / ${money.format(Number(h.expectedCash))}` : "—"}</span>
                    </li>
                  ))}
                </ol>}
        </section>

        <section className={styles.card} aria-label={t("taxSummary", { tax: s.taxLabel })}>
          <div className={styles.cardHead}>
            {t("taxSummary", { tax: s.taxLabel })}
            {s.taxBands.length > 0 && (
              <span className={Math.abs(taxSum - Number(s.totals.tax)) < 0.005 ? styles.pillOk : styles.pillBad}>
                {Math.abs(taxSum - Number(s.totals.tax)) < 0.005 ? t("reconciles") : t("mismatch")}
              </span>
            )}
          </div>
          {s.taxBands.length === 0 && <div className={styles.line}><span className={styles.lineSub}>{t("noBillsToday")}</span></div>}
          {s.taxBands.map((b) => (
            <div key={b.ratePercent} className={styles.line}>
              <span>{Number(b.ratePercent) === 0 ? t("exempt") : t("taxable", { rate: Number(b.ratePercent) })}<div className={styles.lineSub}>{money.format(Number(b.tax))} {s.taxLabel}</div></span>
              <span className={Number(b.ratePercent) === 0 ? styles.amtInfo : styles.amt}>{money.format(Number(b.taxable))}</span>
            </div>
          ))}
          <div className={styles.line}><span>{t("kpi.taxCollected", { tax: s.taxLabel })}</span><span className={styles.amtOk}>{money.format(Number(s.totals.tax))}</span></div>
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
  const t = useTranslations("billing.dayClose");
  const paymentMethodLabel = usePaymentMethodLabel();
  const [note, setNote] = useState("");
  const fe = useFormErrors("close", { countedCash: t("close.fieldCounted"), note: t("close.fieldNote"), date: t("close.fieldDay") });
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
      fe.fail(err, t("close.failed"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog title={summary.close?.status === "reopened" ? t("close.titleAgain") : t("close.title")} onClose={onClose}>
      <form onSubmit={submit}>
        <p className={styles.dialogSub}>{t("close.locksNote", { date: formatDate(summary.date) })}</p>
        {/* ADM-044: opens pre-filled with the expected totals; the counted cash starts at the expected cash and stays editable */}
        <section aria-label={t("close.expectedTotals")}>
          {summary.methods.map((x) => (
            <div key={x.method} className={styles.step}><span>{t("close.expectedMethod", { method: paymentMethodLabel(x.method, getIdentity()?.tenantRegion) })}</span><span className={styles.amt}>{money.format(Number(x.amount))}</span></div>
          ))}
        </section>
        <div className={styles.step}><span>{t("close.step1")}</span>
          <span className={blocked ? styles.amtBad : styles.amtOk}>{blocked ? t("close.blocking", { count: summary.unbilled.length }) : t("close.none")}</span></div>
        <div className={styles.step}><span>{t("close.step2")}</span>
          <span className={variance === null ? styles.amtWarn : styles.amt}>{variance === null ? t("close.required") : money.format(Number(counted))}</span></div>
        <FieldError fe={fe} name="countedCash" className={styles.lineSub} />
        <div className={styles.step}><span>{t("close.step3")}</span>
          <span className={variance === null ? styles.amtMuted : beyond ? styles.amtBad : styles.amtOk}>
            {variance === null ? "—" : (variance > 0 ? "+" : variance < 0 ? "−" : "") + money.format(Math.abs(variance))}
          </span></div>
        {blocked && <p className={styles.dialogSub} style={{ marginTop: 12 }}>{t("close.blockedHint")}</p>}
        {!blocked && (
          <div className={styles.field}>
            <label className={styles.countLabel} htmlFor="close-counted">{t("close.countedCash")}</label>
            <input id="close-counted" className={styles.input} type="number" min="0" step="0.01" value={counted} autoFocus {...fe.attrs("countedCash")}
              onChange={(e) => { fe.clearField("countedCash"); setCounted(e.target.value); }} />
          </div>
        )}
        {!blocked && variance !== null && (
          <div className={styles.field}>
            <label className={styles.countLabel} htmlFor="close-note">
              {beyond ? t("close.noteRequired", { amount: money.format(Number(summary.cashTolerance)) }) : t("close.noteOptional")}
            </label>
            <textarea id="close-note" className={styles.textarea} maxLength={1000} value={note} {...fe.attrs("note")} onChange={(e) => { fe.clearField("note"); setNote(e.target.value); }} />
            <FieldError fe={fe} name="note" className={styles.lineSub} />
          </div>
        )}
        <ErrorSummary fe={fe} className={styles.error} />
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>{t("cancel")}</button>
          <button type="submit" className={styles.btnPrimary} disabled={!canSubmit || saving}>{saving ? t("close.closing") : t("close.submit")}</button>
        </div>
      </form>
    </Dialog>
  );
}

function ReopenDialog({ date, onClose, onDone }: {
  date: string;
  onClose: () => void; onDone: (s: Summary) => void;
}) {
  const t = useTranslations("billing.dayClose");
  const [choice, setChoice] = useState<string>("");
  const [other, setOther] = useState("");
  const fe = useFormErrors("reopen", { reason: t("reopen.fieldReason"), date: t("close.fieldDay") });
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
      fe.fail(err, t("reopen.failed"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog title={t("reopen.title")} onClose={onClose}>
      <form onSubmit={submit}>
        <p className={styles.dialogSub}>{t("reopen.explain", { date: formatDate(date) })}</p>
        <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className={styles.countLabel}>{t("reopen.reasonLegend")}</legend>
          {[...Object.entries(REOPEN_REASONS), ["other", "other"]].map(([k, r]) => (
            <label key={r} className={styles.radio}>
              <input type="radio" name="reopen-reason" value={r} checked={choice === r} {...fe.attrs("reason")} onChange={() => { fe.clearField("reason"); setChoice(r); }} />
              {k === "other" ? t("reopen.other") : t(`reopen.reasons.${k}`)}
            </label>
          ))}
        </fieldset>
        {choice === "other" && (
          <input aria-label={t("reopen.otherReason")} maxLength={500} className={styles.input} style={{ width: "100%", marginTop: 8 }} value={other} autoFocus
            onChange={(e) => setOther(e.target.value)} placeholder={t("reopen.otherPlaceholder")} />
        )}
        <FieldError fe={fe} name="reason" className={styles.lineSub} />
        <ErrorSummary fe={fe} className={styles.error} />
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>{t("cancel")}</button>
          <button type="submit" className={styles.btnPrimary} disabled={!reason || saving}>{saving ? t("reopen.unlocking") : t("unlockDay")}</button>
        </div>
      </form>
    </Dialog>
  );
}
