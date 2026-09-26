"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import styles from "./billing.module.css";
import { getPermissions } from "../lib/session";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

// Matches GET /v1/billing/day-close (DayCloseController → DayCloseSummaryResponse).
type Summary = {
  date: string; today: boolean; currency: string; taxLabel: string; cashTolerance: number;
  totals: { billed: number; bills: number; collected: number; payments: number; tax: number };
  methods: { method: "cash" | "card" | "upi" | "other"; amount: number; transactions: number }[];
  outstanding: { amount: number; patients: number; bills: number };
  pendingCheckouts: number;
  unbilled: { queueEntryId: string; token: number; patientName: string; doctorName: string | null; endedAt: string }[];
  taxBands: { ratePercent: number; taxable: number; tax: number }[];
  splitPaymentBills: number;
  close: {
    status: "closed" | "reopened"; expectedCash: number; countedCash: number; variance: number; note: string | null;
    closedByName: string; closedAt: string; reopenedByName: string | null; reopenedAt: string | null; reopenReason: string | null;
  } | null;
};
type Problem = { title: string; detail: string };

// The wireframe's unlock reasons; "Other" takes free text.
const REOPEN_REASONS = ["Missing bill found afterwards", "Cash count entered incorrectly", "Payment method recorded wrongly"];

function formatDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

const time = (instant: string) => new Date(instant).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

export default function BillingPage() {
  const router = useRouter();
  const [s, setS] = useState<Summary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [canApprove, setCanApprove] = useState(false);
  const [counted, setCounted] = useState(""); // cash counted, as typed
  const [dialog, setDialog] = useState<"close" | "reopen" | null>(null);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(async () => {
    setError(null);
    try {
      setCanApprove(getPermissions().includes("billing:approve"));
      const res = await authedFetch("/billing/day-close");
      if (!res) return;
      if (res.status === 403) {
        setForbidden(true);
        return;
      }
      if (!res.ok) {
        setError("Couldn't load today's billing. Try again.");
        return;
      }
      setS(await res.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    }
  }, [authedFetch]);

  useEffect(() => {
    // Deferred to a microtask so the effect body never calls setState synchronously
    // (react-hooks/set-state-in-effect) — same as the other clinic pages.
    void Promise.resolve().then(load);
  }, [load]);

  if (forbidden) return <main className={styles.page}><p className={styles.state}>You don&apos;t have access to billing.</p></main>;
  if (error && !s) return <main className={styles.page}><div className={styles.error} role="alert">{error}</div></main>;
  if (!s) return <main className={styles.page}><p className={styles.state}>Loading…</p></main>;

  const money = new Intl.NumberFormat(s.currency === "SAR" ? "en-SA" : "en-IN", { style: "currency", currency: s.currency, minimumFractionDigits: 2 });
  const m = Object.fromEntries(s.methods.map((x) => [x.method, x])) as Record<Summary["methods"][number]["method"], Summary["methods"][number]>;
  const closed = s.close?.status === "closed";
  const blocked = !closed && s.unbilled.length > 0;
  const expectedCash = Number(m.cash.amount);
  const countedNum = counted.trim() === "" ? null : Number(counted);
  const countValid = countedNum !== null && Number.isFinite(countedNum) && countedNum >= 0;
  const variance = closed ? Number(s.close!.variance) : countValid ? countedNum! - expectedCash : null;
  const beyond = variance !== null && Math.abs(variance) > Number(s.cashTolerance);
  const taxSum = s.taxBands.reduce((a, b) => a + Number(b.tax), 0);
  const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  const signed = (v: number) => (v > 0 ? "+" : v < 0 ? "−" : "") + money.format(Math.abs(v));

  const kpis: [string, string, string][] = [
    ["Total billed", money.format(Number(s.totals.billed)), plural(s.totals.bills, "bill", "bills")],
    ["Total collected", money.format(Number(s.totals.collected)),
      Number(s.totals.billed) > 0 ? `${Math.round((Number(s.totals.collected) / Number(s.totals.billed)) * 100)}% of billed` : "nothing billed yet"],
    ["Cash", money.format(expectedCash), "expected in drawer"],
    ["Card", money.format(Number(m.card.amount)), plural(m.card.transactions, "transaction", "transactions")],
    ["UPI", money.format(Number(m.upi.amount)), plural(m.upi.transactions, "transaction", "transactions")],
    ["Outstanding balances", money.format(Number(s.outstanding.amount)), plural(s.outstanding.patients, "patient", "patients")],
    ["Refunds recorded", "—", "not tracked yet"],
    [`${s.taxLabel} collected`, money.format(Number(s.totals.tax)), "reconciles to line items"],
    ["Pending checkouts", String(s.pendingCheckouts), "on the reception worklist"],
    ["Consultations without a bill", String(s.unbilled.length), s.unbilled.length ? "blocks day close" : "nothing blocking"],
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
          <Link className={styles.btn} href="/setup?tab=charges">Charges &amp; Taxes</Link>
          {canApprove && s.today && (closed
            ? <button type="button" className={styles.btnPrimary} onClick={() => setDialog("reopen")}>Unlock day</button>
            : <button type="button" className={styles.btnPrimary} onClick={() => setDialog("close")}>Start day close</button>)}
        </div>
      </div>

      {error && <div className={styles.error} role="alert">{error}</div>}

      <div className={styles.kpis}>
        {kpis.map(([label, value, sub], i) => (
          <div key={label} className={styles.kpi}>
            <div className={styles.kpiLabel}>{label}</div>
            <div className={styles.kpiValue} style={i === 9 && s.unbilled.length ? { color: "var(--bl-bad)" } : undefined}>{value}</div>
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
            ) : canApprove && s.today && (
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

          <section className={styles.card} aria-label="Outstanding and unbilled">
            <div className={styles.cardHead}>Outstanding &amp; unbilled</div>
            <div className={styles.line}>
              <span>Outstanding balances<div className={styles.lineSub}>{plural(s.outstanding.patients, "patient", "patients")} · acknowledged at close</div></span>
              <span className={Number(s.outstanding.amount) > 0 ? styles.amtWarn : styles.amt}>{money.format(Number(s.outstanding.amount))}</span>
            </div>
            <div className={styles.line}>
              <span>Cancelled bills<div className={styles.lineSub}>bills can&apos;t be cancelled in Nabd yet</div></span>
              <span className={styles.amtMuted}>—</span>
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
          <div className={styles.line}><span>UPI</span><span className={styles.amtOk}>{money.format(Number(m.upi.amount))}</span></div>
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
          authedFetch={authedFetch} onClose={() => setDialog(null)} onDone={(next) => { setS(next); setDialog(null); setCounted(""); }} />
      )}
      {dialog === "reopen" && (
        <ReopenDialog authedFetch={authedFetch} onClose={() => setDialog(null)} onDone={(next) => { setS(next); setDialog(null); }} />
      )}
    </main>
  );
}

function useEscape(onClose: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
}

async function problemDetail(res: Response, fallback: string): Promise<string> {
  const p: Problem = await res.json().catch(() => ({ title: "Error", detail: fallback }));
  return p.detail || p.title || fallback;
}

/** The wireframe's three checks — pending issues, cash count, variance — then close. The server re-checks all of them. */
function CloseDialog({ summary, money, counted, setCounted, variance, beyond, authedFetch, onClose, onDone }: {
  summary: Summary; money: Intl.NumberFormat; counted: string; setCounted: (v: string) => void;
  variance: number | null; beyond: boolean;
  authedFetch: (path: string, init?: RequestInit) => Promise<Response | null>;
  onClose: () => void; onDone: (s: Summary) => void;
}) {
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  useEscape(onClose);
  const blocked = summary.unbilled.length > 0;
  const canSubmit = !blocked && variance !== null && (!beyond || note.trim() !== "");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const res = await authedFetch("/billing/day-close", {
        method: "POST", body: JSON.stringify({ countedCash: Number(counted), note: note.trim() || null }),
      });
      if (!res) return;
      if (!res.ok) {
        setError(await problemDetail(res, "Couldn't close the day."));
        return;
      }
      onDone(await res.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={styles.overlay} onClick={onClose}>
      <form className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="close-title" onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <h2 id="close-title" className={styles.dialogTitle}>Day close</h2>
        <p className={styles.dialogSub}>{formatDate(summary.date)} · billing locks once the day is closed</p>
        <div className={styles.step}><span>1. Pending issues</span>
          <span className={blocked ? styles.amtBad : styles.amtOk}>{blocked ? `${summary.unbilled.length} blocking` : "None"}</span></div>
        <div className={styles.step}><span>2. Cash count</span>
          <span className={variance === null ? styles.amtWarn : styles.amt}>{variance === null ? "Required" : money.format(Number(counted))}</span></div>
        <div className={styles.step}><span>3. Variance</span>
          <span className={variance === null ? styles.amtMuted : beyond ? styles.amtBad : styles.amtOk}>
            {variance === null ? "—" : (variance > 0 ? "+" : variance < 0 ? "−" : "") + money.format(Math.abs(variance))}
          </span></div>
        {blocked && <p className={styles.dialogSub} style={{ marginTop: 12 }}>Bill each consultation listed on the page first — the close is blocked until then.</p>}
        {!blocked && variance === null && (
          <div className={styles.field}>
            <label className={styles.countLabel} htmlFor="close-counted">Counted cash</label>
            <input id="close-counted" className={styles.input} type="number" min="0" step="0.01" value={counted} autoFocus
              onChange={(e) => setCounted(e.target.value)} />
          </div>
        )}
        {!blocked && variance !== null && (
          <div className={styles.field}>
            <label className={styles.countLabel} htmlFor="close-note">
              Note{beyond ? ` (required — off by more than ${money.format(Number(summary.cashTolerance))})` : " (optional)"}
            </label>
            <textarea id="close-note" className={styles.textarea} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
        )}
        {error && <div className={styles.error} role="alert" style={{ marginTop: 12 }}>{error}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={!canSubmit || saving}>{saving ? "Closing…" : "Close the day"}</button>
        </div>
      </form>
    </div>
  );
}

function ReopenDialog({ authedFetch, onClose, onDone }: {
  authedFetch: (path: string, init?: RequestInit) => Promise<Response | null>;
  onClose: () => void; onDone: (s: Summary) => void;
}) {
  const [choice, setChoice] = useState<string>("");
  const [other, setOther] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  useEscape(onClose);
  const reason = choice === "other" ? other.trim() : choice;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const res = await authedFetch("/billing/day-close/reopen", { method: "POST", body: JSON.stringify({ reason }) });
      if (!res) return;
      if (!res.ok) {
        setError(await problemDetail(res, "Couldn't unlock the day."));
        return;
      }
      onDone(await res.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={styles.overlay} onClick={onClose}>
      <form className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="reopen-title" onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <h2 id="reopen-title" className={styles.dialogTitle}>Unlock a closed day</h2>
        <p className={styles.dialogSub}>Unlocking reopens billing for today and is recorded in the audit log with your name, the time and your reason.</p>
        <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className={styles.countLabel}>Reason (required)</legend>
          {[...REOPEN_REASONS, "other"].map((r) => (
            <label key={r} className={styles.radio}>
              <input type="radio" name="reopen-reason" value={r} checked={choice === r} onChange={() => setChoice(r)} />
              {r === "other" ? "Other" : r}
            </label>
          ))}
        </fieldset>
        {choice === "other" && (
          <input aria-label="Other reason" className={styles.input} style={{ width: "100%", marginTop: 8 }} value={other} autoFocus
            onChange={(e) => setOther(e.target.value)} placeholder="What needs correcting?" />
        )}
        {error && <div className={styles.error} role="alert" style={{ marginTop: 12 }}>{error}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={!reason || saving}>{saving ? "Unlocking…" : "Unlock day"}</button>
        </div>
      </form>
    </div>
  );
}
