"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./reports.module.css";

// E20 Owner Insights, scoped to live queries (see ReportsController) — NB-229/230/231/232/233/234/235/236/237.
type DailyMoney = { billedToday: number; collectedToday: number; outstandingTotal: number; invoiceCountToday: number; paymentCountToday: number };
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
type DoctorPunctuality = {
  accessNote: string;
  entries: { doctorId: string; doctorName: string; delayCount: number; avgDelayMinutes: number; sameDayRepeatDays: number }[];
};

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export default function ReportsPage() {
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [dailyMoney, setDailyMoney] = useState<DailyMoney | null>(null);
  const [sources, setSources] = useState<SourceRow[]>([]);
  const [staffPerf, setStaffPerf] = useState<StaffPerformanceReport | null>(null);
  const [retention, setRetention] = useState<Retention | null>(null);
  const [noShowRisk, setNoShowRisk] = useState<NoShowRisk | null>(null);
  const [liability, setLiability] = useState<Liability | null>(null);
  const [leakage, setLeakage] = useState<BillingLeakage | null>(null);
  const [leakageThreshold, setLeakageThreshold] = useState("0");
  const [punctuality, setPunctuality] = useState<DoctorPunctuality | null>(null);

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
    setLoading(true);
    setError(null);
    try {
      const [moneyRes, sourcesRes, staffRes, retentionRes, riskRes, liabilityRes, leakageRes, punctualityRes] = await Promise.all([
        authedFetch("/reports/daily-money"), authedFetch("/reports/sources"),
        authedFetch("/reports/staff-performance"), authedFetch("/reports/retention"),
        authedFetch("/reports/no-show-risk"), authedFetch("/packages/liability"),
        authedFetch("/reports/billing-leakage?thresholdAmount=0"), authedFetch("/reports/doctor-punctuality"),
      ]);
      if (!moneyRes) return;
      if (moneyRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!moneyRes.ok) {
        setError("Couldn't load reports. Try again.");
        return;
      }
      setDailyMoney(await moneyRes.json());
      if (sourcesRes?.ok) setSources(await sourcesRes.json());
      if (staffRes?.ok) setStaffPerf(await staffRes.json());
      if (retentionRes?.ok) setRetention(await retentionRes.json());
      if (riskRes?.ok) setNoShowRisk(await riskRes.json());
      if (liabilityRes?.ok) setLiability(await liabilityRes.json());
      if (leakageRes?.ok) setLeakage(await leakageRes.json());
      if (punctualityRes?.ok) setPunctuality(await punctualityRes.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

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
    const token = localStorage.getItem("nabd_access_token");
    if (!token) return;
    const res = await fetch(`${API_BASE}/reports/export?reportType=${reportType}`, { headers: { Authorization: `Bearer ${token}` } });
    if (!res.ok) return;
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
    return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  }
  if (forbidden) {
    return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to reports.</div></main>;
  }
  if (error) {
    return <main className={styles.page}><div className={styles.errorState}>{error}</div></main>;
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Owner Insights</h1>
        <p className={styles.subtitle}>Live view over today&apos;s clinic data.</p>
      </div>

      {dailyMoney && (
        <div className={styles.grid}>
          <div className={styles.statTile}>
            <div className={styles.statLabel}>Billed today</div>
            <div className={styles.statValue}>{money(dailyMoney.billedToday)}</div>
            <div className={styles.statSub}>{dailyMoney.invoiceCountToday} invoice{dailyMoney.invoiceCountToday === 1 ? "" : "s"}</div>
          </div>
          <div className={styles.statTile}>
            <div className={styles.statLabel}>Collected today</div>
            <div className={styles.statValue}>{money(dailyMoney.collectedToday)}</div>
            <div className={styles.statSub}>{dailyMoney.paymentCountToday} payment{dailyMoney.paymentCountToday === 1 ? "" : "s"}</div>
          </div>
          <div className={styles.statTile}>
            <div className={styles.statLabel}>Outstanding</div>
            <div className={styles.statValue}>{money(dailyMoney.outstandingTotal)}</div>
            <div className={styles.statSub}>unpaid + partial invoices</div>
          </div>
        </div>
      )}

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

      <div className={styles.card + " " + styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>Where patients come from (last 30 days)</h3>
          <button className={styles.exportBtn} onClick={() => exportCsv("sources")}>Export CSV</button>
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

      {staffPerf && (
        <div className={styles.card + " " + styles.section}>
          <div className={styles.sectionHeader}>
            <h3 className={styles.sectionTitle}>Staff collection & performance (last 30 days)</h3>
            <button className={styles.exportBtn} onClick={() => exportCsv("staff-performance")}>Export CSV</button>
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

      {punctuality && (
        <div className={styles.card + " " + styles.section}>
          <div className={styles.sectionHeader}>
            <h3 className={styles.sectionTitle}>Doctor punctuality (last 90 days)</h3>
          </div>
          <div className={styles.rule}>{punctuality.accessNote}</div>
          {punctuality.entries.length === 0 ? (
            <div className={styles.muted}>No delays announced.</div>
          ) : (
            <table className={styles.table}>
              <thead><tr><th>Doctor</th><th>Delays</th><th>Avg minutes</th><th>Same-day repeats</th></tr></thead>
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
    </main>
  );
}
