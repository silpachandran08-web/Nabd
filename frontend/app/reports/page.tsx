"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./reports.module.css";
import Link from "next/link";
import { getIdentity } from "../lib/session";
import { can } from "../lib/permissions";
import StatusPill from "../components/ui/StatusPill";
import { paymentMethodLabel } from "../components/checkout/paymentMethods";
import { ErrorState, Skeleton } from "../components/ui/StateViews";

// E20 Owner Insights, scoped to live queries (see ReportsController) — NB-229/230/231/232/233/234/235/236/237.
type SourceRow = { source: string; visitCount: number };
type StaffRow = { staffId: string; staffName: string; collected: number; paymentCount: number };
type StaffPerformanceReport = { scopeNote: string; rows: StaffRow[] };
type Retention = { totalPatients: number; repeatPatients: number; repeatRatePercent: number; avgVisitsPerPatient: number };
type NoShowRisk = { rule: string; entries: { patientId: string; patientName: string; priorNoShowCount: number }[] };
type Liability = {
  activePatientPackages: number; sessionsOwed: number; remainingListValue: number; remainingAllocatedValue: number;
  inGracePeriod: number; expiringIn30Days: number; potentialExpiryLoss: number; refundsAwaitingApproval: number;
};
type BillingLeakage = {
  rule: string; thresholdAmount: number;
  entries: { procedureOrderId: string; patientName: string; chargeCode: string; chargeName: string; amount: number; completedAt: string }[];
};
// GET /v1/reports/money (ReportsController.money → MoneyResponse)
type Money = {
  from: string; to: string; includesToday: boolean; currency: string; taxLabel: string;
  collected: number; billsRaised: number; taxCollected: number; noShows: number; bookings: number; waitingNow: number | null;
  // count: payments per tender (added with OWN-023); optional so an older backend still renders.
  tender: { method: string; amount: number; count?: number }[]; topServices: { name: string; revenue: number }[];
};
// GET /v1/billing/day-close — only the fields the Day close card reads.
type DayClose = {
  date: string; today: boolean; pendingCheckouts: number; unbilled: unknown[];
  close: { status: "closed" | "reopened"; closedAt: string } | null;
};
type DoctorPunctuality = {
  accessNote: string;
  entries: { doctorId: string; doctorName: string; delayCount: number; avgDelayMinutes: number; sameDayRepeatDays: number }[];
};

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

// DESIGN.md owner "Reports": period switch, then seven tabs.
type Period = "today" | "yesterday" | "week" | "month" | "custom";
const PERIODS: [Period, string][] = [["today", "Today"], ["yesterday", "Yesterday"], ["week", "This week"], ["month", "This month"], ["custom", "Custom"]];
type Tab = "money" | "sources" | "staff" | "packages" | "leakage" | "retention" | "punctuality";
const TABS: [Tab, string][] = [["money", "Today's money"], ["sources", "Sources"], ["staff", "Staff"], ["packages", "Packages"], ["leakage", "Leakage"], ["retention", "Retention"], ["punctuality", "Punctuality"]];
// Chart series colours (DESIGN §8.22); each column also carries its text label.
const TENDER_COLORS: Record<string, string> = { cash: "var(--rp-series-1)", upi: "var(--rp-series-2)", card: "var(--rp-series-3)", other: "var(--nb-text-tertiary)" };

/** The browser's local calendar date as YYYY-MM-DD (staff browsers run on the clinic's clock). */
const localIso = (d: Date) => d.toLocaleDateString("en-CA");

function periodRange(p: Period, custom: { from: string; to: string }): { from: string; to: string } {
  const now = new Date();
  const today = localIso(now);
  if (p === "today") return { from: today, to: today };
  if (p === "yesterday") {
    const y = new Date(now);
    y.setDate(y.getDate() - 1);
    return { from: localIso(y), to: localIso(y) };
  }
  if (p === "week") {
    const m = new Date(now);
    m.setDate(m.getDate() - ((m.getDay() + 6) % 7)); // back to Monday
    return { from: localIso(m), to: today };
  }
  if (p === "month") return { from: localIso(new Date(now.getFullYear(), now.getMonth(), 1)), to: today };
  return custom;
}

export default function ReportsPage() {
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [sources, setSources] = useState<SourceRow[]>([]);
  const [staffPerf, setStaffPerf] = useState<StaffPerformanceReport | null>(null);
  const [retention, setRetention] = useState<Retention | null>(null);
  const [noShowRisk, setNoShowRisk] = useState<NoShowRisk | null>(null);
  const [liability, setLiability] = useState<Liability | null>(null);
  const [leakage, setLeakage] = useState<BillingLeakage | null>(null);
  const [leakageThreshold, setLeakageThreshold] = useState("0");
  const [punctuality, setPunctuality] = useState<DoctorPunctuality | null>(null);
  const [period, setPeriod] = useState<Period>("today");
  const [custom, setCustom] = useState(() => {
    const d = new Date();
    const to = localIso(d);
    d.setDate(d.getDate() - 6);
    return { from: localIso(d), to };
  });
  const [tab, setTab] = useState<Tab>("money");
  const [moneyReport, setMoneyReport] = useState<Money | null>(null);
  const [dayClose, setDayClose] = useState<DayClose | null>(null);
  const [clinicName, setClinicName] = useState("");
  const [region, setRegion] = useState<string | undefined>(undefined);
  const [exportError, setExportError] = useState<string | null>(null);
  const range = periodRange(period, custom);
  const qs = `from=${range.from}&to=${range.to}`;

  const authedFetch = useCallback(
    async (path: string) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` } });
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
    setError(null); // `loading` is only the first load — switching period keeps the page on screen
    try {
      const [moneyRes, sourcesRes, staffRes, retentionRes, riskRes, liabilityRes, leakageRes, punctualityRes, dayCloseRes] = await Promise.all([
        authedFetch(`/reports/money?${qs}`), authedFetch(`/reports/sources?${qs}`),
        authedFetch(`/reports/staff-performance?${qs}`), authedFetch("/reports/retention"),
        authedFetch("/reports/no-show-risk"), authedFetch("/packages/liability"),
        authedFetch("/reports/billing-leakage?thresholdAmount=0"), authedFetch("/reports/doctor-punctuality"),
        authedFetch("/billing/day-close"),
      ]);
      if (!moneyRes) return;
      if (moneyRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!moneyRes.ok) {
        const p = await moneyRes.json().catch(() => null);
        setError(p?.detail || "Couldn't load reports. Try again.");
        return;
      }
      setMoneyReport(await moneyRes.json());
      setClinicName(getIdentity()?.tenantName ?? "");
      setRegion(getIdentity()?.tenantRegion);
      if (sourcesRes?.ok) setSources(await sourcesRes.json());
      if (staffRes?.ok) setStaffPerf(await staffRes.json());
      if (retentionRes?.ok) setRetention(await retentionRes.json());
      if (riskRes?.ok) setNoShowRisk(await riskRes.json());
      if (liabilityRes?.ok) setLiability(await liabilityRes.json());
      if (leakageRes?.ok) setLeakage(await leakageRes.json());
      if (punctualityRes?.ok) setPunctuality(await punctualityRes.json());
      // Roles without billing access simply don't get the Day close card.
      setDayClose(dayCloseRes?.ok ? await dayCloseRes.json() : null);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch, qs]);

  const reloadLeakage = useCallback(
    async (threshold: string) => {
      const res = await authedFetch(`/reports/billing-leakage?thresholdAmount=${encodeURIComponent(threshold || "0")}`);
      if (res?.ok) setLeakage(await res.json());
    },
    [authedFetch]
  );

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function exportCsv(reportType: string) {
    setExportError(null);
    const token = localStorage.getItem("nabd_access_token");
    if (!token) return;
    let res: Response;
    try {
      res = await fetch(`${API_BASE}/reports/export?reportType=${reportType}&${qs}`, { headers: { Authorization: `Bearer ${token}` } });
    } catch {
      setExportError("Couldn't reach the server. The export wasn't downloaded.");
      return;
    }
    if (!res.ok) {
      setExportError(res.status === 403 ? "Your role isn't allowed to export reports." : "Couldn't export this report. Try again.");
      return;
    }
    const csv = await res.text();
    const blob = new Blob([csv], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${reportType}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  if (loading) {
    return <main className={styles.page}><Skeleton rows={6} label="Loading reports" /></main>;
  }
  if (forbidden) {
    return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to reports.</div></main>;
  }
  if (error) {
    return <main className={styles.page}><ErrorState message={error} onRetry={() => void load()} /></main>;
  }

  // Export is its own grant (RBAC: Reports > Export); absent, not disabled, without it.
  const canExport = can("reports:export");

  const m = moneyReport;
  const cur = m ? new Intl.NumberFormat(m.currency === "SAR" ? "en-SA" : "en-IN", { style: "currency", currency: m.currency, minimumFractionDigits: 2 }) : null;
  const tenderMax = m ? Math.max(0, ...m.tender.map((t) => Number(t.amount))) : 0;
  const serviceMax = m ? Math.max(0, ...m.topServices.map((t) => Number(t.revenue))) : 0;

  return (
    <main className={`${styles.page} ${styles.wide}`}>
      <div className={styles.header}>
        <h1 className={styles.title}>Reports</h1>
        {clinicName && <p className={styles.subtitle}>{clinicName}</p>}
      </div>

      <div className={styles.segmented} role="tablist" aria-label="Period">
        {PERIODS.map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={period === key}
            className={period === key ? styles.segOn : styles.seg} onClick={() => setPeriod(key)}>{label}</button>
        ))}
      </div>
      {period === "custom" && (
        <div className={styles.customRange}>
          <label>From <input type="date" className={styles.dateInput} value={custom.from} max={custom.to}
            onChange={(e) => e.target.value && setCustom({ ...custom, from: e.target.value })} /></label>
          <label>to <input type="date" className={styles.dateInput} value={custom.to} min={custom.from}
            onChange={(e) => e.target.value && setCustom({ ...custom, to: e.target.value })} /></label>
        </div>
      )}
      {exportError && <div className={styles.infoNote} role="alert">{exportError}</div>}
      {m?.includesToday && <div className={styles.infoNote} role="note">ⓘ Today is still in progress — figures are provisional</div>}

      <div className={styles.segmented} role="tablist" aria-label="Report">
        {TABS.filter(([key]) => key !== "packages" || can("packages:view")).map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={tab === key}
            className={tab === key ? styles.segOn : styles.seg} onClick={() => setTab(key)}>{label}</button>
        ))}
      </div>

      {tab === "money" && m && cur && (
        <>
          <div className={styles.hero}>
            <div className={styles.heroLabel}>Collections — selected period</div>
            <div className={styles.heroValue}>{cur.format(Number(m.collected))}</div>
          </div>
          <div className={styles.stats4}>
            <div className={styles.tileInfo}>
              <div className={styles.statLabel}>Bills raised</div>
              <div className={`${styles.statValue} ${styles.statInfo}`}>{m.billsRaised}</div>
            </div>
            <div className={styles.tileWarn}>
              <div className={styles.statLabel}>No-shows</div>
              <div className={`${styles.statValue} ${styles.statWarn}`}>
                {m.noShows}{m.bookings > 0 ? ` · ${Math.round((m.noShows / m.bookings) * 100)}%` : ""}
              </div>
              <div className={styles.statSub}>from bookings vs check-ins</div>
            </div>
            <div className={styles.tileInfo}>
              <div className={styles.statLabel}>Waiting now</div>
              <div className={`${styles.statValue} ${styles.statAcc}`}>{m.waitingNow ?? "—"}</div>
              {m.waitingNow === null && <div className={styles.statSub}>only while the period includes today</div>}
            </div>
            <div className={styles.tileInfo}>
              <div className={styles.statLabel}>{m.taxLabel} collected</div>
              <div className={`${styles.statValue} ${styles.statInfo}`}>{cur.format(Number(m.taxCollected))}</div>
            </div>
          </div>

          {dayClose && (() => {
            const closed = dayClose.close?.status === "closed";
            const blocked = !closed && dayClose.unbilled.length > 0;
            return (
              <div className={styles.card + " " + styles.section} role="group" aria-label="Day close">
                <div className={styles.cardLabel}>Day close</div>
                <div className={styles.dayCloseRow}>
                  <div>
                    {closed ? <StatusPill tone="info" label="Closed & locked" icon="lock" />
                      : blocked ? <StatusPill tone="danger" label="Close blocked" />
                      : <StatusPill tone="ok" label="Ready to close" />}
                    <div className={styles.statSub}>
                      {closed ? "Today is closed. Billing is locked until the day is unlocked."
                        : blocked ? `${dayClose.unbilled.length} completed ${dayClose.unbilled.length === 1 ? "consultation has" : "consultations have"} no bill.`
                        : `Nothing blocks the close.${dayClose.pendingCheckouts > 0 ? ` ${dayClose.pendingCheckouts} checkout${dayClose.pendingCheckouts === 1 ? "" : "s"} still pending.` : ""}`}
                    </div>
                  </div>
                  <Link className={styles.exportBtn} href="/billing">{closed ? "View day close" : "Open day close"}</Link>
                </div>
              </div>
            );
          })()}

          <div className={styles.card + " " + styles.section}>
            <div className={styles.cardLabel}>Tender split</div>
            <div className={styles.infoNoteSmall}>ⓘ Payments recorded in the period — the same figures Billing &amp; Day Close uses</div>
            {m.tender.length === 0 ? (
              <div className={styles.muted}>No payments in this period.</div>
            ) : (
              <div className={styles.columns}>
                {m.tender.map((t) => (
                  <div key={t.method} className={styles.column}>
                    <span className={styles.columnValue}>{cur.format(Number(t.amount))}</span>
                    <span className={styles.columnBar} style={{
                      height: `${tenderMax > 0 ? Math.max(2, (Number(t.amount) / tenderMax) * 100) : 2}px`,
                      background: TENDER_COLORS[t.method] ?? TENDER_COLORS.other,
                    }} />
                    <span className={styles.columnLabel}>{paymentMethodLabel(t.method, region)}</span>
                    {typeof t.count === "number" && (
                      <span className={styles.columnSub}>{t.count} {t.count === 1 ? "transaction" : "transactions"}</span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className={styles.card + " " + styles.section}>
            <div className={styles.cardLabel}>Top 5 services by revenue</div>
            {m.topServices.length === 0 ? (
              <div className={styles.muted}>No bills in this period.</div>
            ) : (
              m.topServices.map((sv) => (
                <div key={sv.name} className={styles.hbarRow}>
                  <span className={styles.hbarName} title={sv.name}>{sv.name}</span>
                  <span className={styles.hbarTrack}>
                    <span className={styles.hbar} style={{ width: `${serviceMax > 0 ? (Number(sv.revenue) / serviceMax) * 60 : 0}%` }} />
                    <span className={styles.hbarValue}>{cur.format(Number(sv.revenue))}</span>
                  </span>
                </div>
              ))
            )}
          </div>
        </>
      )}

      {tab === "sources" && (
        <>
      <div className={styles.card + " " + styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>Where patients come from (selected period)</h3>
          {canExport && <button className={styles.exportBtn} onClick={() => exportCsv("sources")}>Export CSV</button>}
        </div>
        {sources.length === 0 ? (
          <div className={styles.muted}>No visits recorded yet.</div>
        ) : (
          <table className={styles.table}>
            <thead><tr><th>Source</th><th>Visits</th></tr></thead>
            <tbody>
              {sources.map((s) => (
                <tr key={s.source}><td>{s.source.replace("_", " ")}</td><td>{s.visitCount}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

        </>
      )}

      {tab === "staff" && (
        <>
      {staffPerf && (
        <div className={styles.card + " " + styles.section}>
          <div className={styles.sectionHeader}>
            <h3 className={styles.sectionTitle}>Staff collection & performance (selected period)</h3>
            {canExport && <button className={styles.exportBtn} onClick={() => exportCsv("staff-performance")}>Export CSV</button>}
          </div>
          <div className={styles.rule}>{staffPerf.scopeNote}</div>
          {staffPerf.rows.length === 0 ? (
            <div className={styles.muted}>No payments recorded yet.</div>
          ) : (
            <table className={styles.table}>
              <thead><tr><th>Staff</th><th>Collected</th><th>Payments</th></tr></thead>
              <tbody>
                {staffPerf.rows.map((s) => (
                  <tr key={s.staffId}><td>{s.staffName}</td><td>{money(s.collected)}</td><td>{s.paymentCount}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

        </>
      )}

      {tab === "packages" && (
        <>
          <div className={styles.rule}>Current position — not affected by the period.</div>
      {liability && (
        <div className={styles.card + " " + styles.section}>
          <div className={styles.sectionHeader}>
            <h3 className={styles.sectionTitle}>Package liability</h3>
          </div>
          <div className={styles.grid}>
            <div className={styles.statTile}>
              <div className={styles.statLabel}>Active packages</div>
              <div className={styles.statValue}>{liability.activePatientPackages}</div>
              <div className={styles.statSub}>{liability.sessionsOwed} sessions owed</div>
            </div>
            <div className={styles.statTile}>
              <div className={styles.statLabel}>Remaining value</div>
              <div className={styles.statValue}>{money(liability.remainingAllocatedValue)}</div>
              <div className={styles.statSub}>list price {money(liability.remainingListValue)}</div>
            </div>
            <div className={styles.statTile}>
              <div className={styles.statLabel}>Expiring in 30 days</div>
              <div className={styles.statValue}>{liability.expiringIn30Days}</div>
              <div className={styles.statSub}>potential loss {money(liability.potentialExpiryLoss)}</div>
            </div>
            <div className={styles.statTile}>
              <div className={styles.statLabel}>In grace period</div>
              <div className={styles.statValue}>{liability.inGracePeriod}</div>
              <div className={styles.statSub}>{liability.refundsAwaitingApproval} refunds awaiting approval</div>
            </div>
          </div>
        </div>
      )}

        </>
      )}

      {tab === "leakage" && (
        <>
      {leakage && (
        <div className={styles.card + " " + styles.section}>
          <div className={styles.sectionHeader}>
            <h3 className={styles.sectionTitle}>Billing leakage alerts</h3>
            <span>
              Min amount:{" "}
              <input
                type="number"
                min="0"
                value={leakageThreshold}
                onChange={(e) => setLeakageThreshold(e.target.value)}
                onBlur={() => void reloadLeakage(leakageThreshold)}
                style={{ width: "5rem" }}
              />
            </span>
          </div>
          <div className={styles.rule}>{leakage.rule}</div>
          {leakage.entries.length === 0 ? (
            <div className={styles.muted}>No billing leakage found.</div>
          ) : (
            <table className={styles.table}>
              <thead><tr><th>Patient</th><th>Charge</th><th>Amount</th><th>Completed</th></tr></thead>
              <tbody>
                {leakage.entries.map((e) => (
                  <tr key={e.procedureOrderId}>
                    <td>{e.patientName}</td><td>{e.chargeName}</td><td>{money(e.amount)}</td>
                    <td>{new Date(e.completedAt).toLocaleDateString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

        </>
      )}

      {tab === "retention" && (
        <>
          <div className={styles.rule}>Across all patients — not affected by the period.</div>
      {retention && (
        <div className={styles.grid}>
          <div className={styles.statTile}>
            <div className={styles.statLabel}>Active patients</div>
            <div className={styles.statValue}>{retention.totalPatients}</div>
          </div>
          <div className={styles.statTile}>
            <div className={styles.statLabel}>Repeat rate</div>
            <div className={styles.statValue}>{retention.repeatRatePercent}%</div>
            <div className={styles.statSub}>{retention.repeatPatients} repeat patients</div>
          </div>
          <div className={styles.statTile}>
            <div className={styles.statLabel}>Avg visits / patient</div>
            <div className={styles.statValue}>{retention.avgVisitsPerPatient}</div>
          </div>
        </div>
      )}

      {noShowRisk && (
        <div className={styles.card + " " + styles.section}>
          <div className={styles.sectionHeader}>
            <h3 className={styles.sectionTitle}>No-show risk today</h3>
          </div>
          <div className={styles.rule}>Rule: {noShowRisk.rule}</div>
          {noShowRisk.entries.length === 0 ? (
            <div className={styles.muted}>Nobody flagged today.</div>
          ) : (
            <table className={styles.table}>
              <thead><tr><th>Patient</th><th>Prior no-shows</th></tr></thead>
              <tbody>
                {noShowRisk.entries.map((e) => (
                  <tr key={e.patientId}><td>{e.patientName}</td><td>{e.priorNoShowCount}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
        </>
      )}
      {tab === "punctuality" && (
        <>
          <div className={styles.rule}>Last 90 days — not affected by the period.</div>
          {punctuality ? (
            <div className={styles.card + " " + styles.section}>
              <div className={styles.sectionHeader}>
                <h3 className={styles.sectionTitle}>Doctor punctuality</h3>
              </div>
              <div className={styles.rule}>{punctuality.accessNote}</div>
              {punctuality.entries.length === 0 ? (
                <div className={styles.muted}>No delays in the last 90 days.</div>
              ) : (
                <table className={styles.table}>
                  <thead><tr><th>Doctor</th><th>Delay events</th><th>Avg minutes</th><th>Same-day repeat</th></tr></thead>
                  <tbody>
                    {punctuality.entries.map((e) => (
                      <tr key={e.doctorId}>
                        <td>{e.doctorName}</td><td>{e.delayCount}</td><td>{e.avgDelayMinutes}</td><td>{e.sameDayRepeatDays}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ) : (
            <div className={styles.muted}>Punctuality isn&apos;t available for your role.</div>
          )}
        </>
      )}
    </main>
  );
}
