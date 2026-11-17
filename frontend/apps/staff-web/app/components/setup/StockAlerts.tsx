"use client";

import { useCallback, useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import styles from "../../(clinic)/setup/setup.module.css";
import ph from "./pharmacy.module.css";
import sa from "./stockAlerts.module.css";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, ApiError, errorText } from "../../lib/api";
import { can } from "../../lib/permissions";
import { stockAlertsQuery } from "../../lib/query";
import { formatNumber } from "@nabd/i18n";
import { ACTIONS, SUGGESTED_LABEL, TABS, TYPE_LABEL, WINDOWS, daysText, remaining, type AckAction, type AlertCounts, type AlertTab } from "../../lib/stockAlerts";

type Alert = {
  id: string; type: string; itemId: string; itemName: string; batchId?: string | null; batchNo?: string | null; expiryDate?: string | null;
  daysToExpiry?: number | null; onHand: number; reorderLevel?: number | null; valueAtRisk?: number | null; status: string; action?: string | null; createdOn: string;
};
type AlertPage = { items: Alert[]; nextCursor?: string | null; counts: AlertCounts };
type ExpiryLine = {
  batchId: string; itemName: string; batchNo: string; expiryDate: string; daysToExpiry: number; qtyOnHand: number;
  purchaseRate?: number | null; valueAtRisk: number; suggestedAction: string;
};
type ExpiryReport = { asOf: string; withinDays: number; totalValueAtRisk: number; lines: ExpiryLine[] };

const FIX2 = { min: 2, max: 2 };

/** PHL-008: open stock alerts in three tabs (bulk acknowledge for pharmacy:edit) and the expiry report with CSV export. */
export default function StockAlerts() {
  return (
    <>
      <AlertTabs />
      <ExpiryReportView />
    </>
  );
}

function AlertTabs() {
  const canAck = can("pharmacy:edit"); // acknowledge controls are absent without it
  const qc = useQueryClient();
  const [tab, setTab] = useState<AlertTab>("expiry");
  const [page, setPage] = useState<AlertPage | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [action, setAction] = useState<AckAction | "">("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async (t: AlertTab) => {
    setLoadError(null);
    try { setPage(await api.get<AlertPage>(`/pharmacy/alerts?type=${t}&limit=50`)); } catch (e) { setLoadError(errorText(e, "Couldn't load the stock alerts.")); }
  }, []);
  useEffect(() => { void Promise.resolve().then(() => load(tab)); }, [load, tab]);

  const more = async () => {
    if (!page?.nextCursor || busy) return;
    setBusy(true);
    try {
      const next = await api.get<AlertPage>(`/pharmacy/alerts?type=${tab}&limit=50&cursor=${encodeURIComponent(page.nextCursor)}`);
      setPage({ ...next, items: [...page.items, ...next.items] });
    } catch (e) { setProblem(errorText(e, "Couldn't load more alerts.")); } finally { setBusy(false); }
  };

  const switchTab = (t: AlertTab) => { setTab(t); setPage(null); setSelected([]); setProblem(null); setNotice(null); };
  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  const acknowledge = async () => {
    if (busy) return;
    if (!action) { setProblem("Choose the action you took."); return; }
    setBusy(true); setProblem(null); setNotice(null);
    const done: string[] = [];
    for (const id of selected) {
      try { await api.post(`/pharmacy/alerts/${id}/acknowledge`, { action }); done.push(id); } catch (e) {
        setProblem(errorText(e, "Couldn't acknowledge every alert. The ones still ticked were not saved."));
        if (e instanceof ApiError && e.status === 403) break;
      }
    }
    setSelected(remaining(selected, done));
    if (done.length > 0) setNotice(`${done.length} ${done.length === 1 ? "alert" : "alerts"} acknowledged.`);
    await load(tab);
    void qc.invalidateQueries({ queryKey: stockAlertsQuery().queryKey }); // the nav badge follows
    setBusy(false);
  };

  const counts = page?.counts;
  const items = page?.items ?? [];
  return (
    <section aria-labelledby="sa-title">
      <h2 id="sa-title" className={styles.cardTitle}>Open alerts</h2>
      <div className={sa.tabs} role="tablist" aria-label="Alert type">
        {TABS.map((t) => (
          <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} className={tab === t.id ? sa.tabOn : sa.tab} onClick={() => switchTab(t.id)}>
            {t.label}{counts ? ` (${counts[t.id]})` : ""}
          </button>
        ))}
      </div>
      {notice && <div className={ph.notice} role="status">{notice}</div>}
      {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
      {loadError ? <ErrorState message={loadError} onRetry={() => load(tab)} />
        : !page ? <Skeleton rows={4} label="Loading stock alerts" />
        : items.length === 0 ? <EmptyState icon="package" title="No open alerts here." hint="New alerts are raised every morning and after each sale." />
        : (
          <>
            {canAck && (
              <div className={sa.bar}>
                <label className={sa.target}>
                  <input type="checkbox" checked={selected.length === items.length} onChange={(e) => setSelected(e.target.checked ? items.map((i) => i.id) : [])} /> Select all
                </label>
                <label className={styles.label} htmlFor="sa-action">Action taken</label>
                <select id="sa-action" className={`${styles.select} ${sa.target}`} value={action} onChange={(e) => { setProblem(null); setAction(e.target.value as AckAction | ""); }}>
                  <option value="">Choose…</option>
                  {ACTIONS.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
                </select>
                <button type="button" className={`${styles.btnPrimary} ${sa.target}`} disabled={busy || selected.length === 0} onClick={acknowledge}>
                  Acknowledge {selected.length > 0 ? `(${selected.length})` : ""}
                </button>
              </div>
            )}
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {canAck && <th scope="col"><span className="srOnly">Select</span></th>}
                    <th scope="col">Item</th>
                    {tab === "expiry" && <><th scope="col">Batch</th><th scope="col">Expiry</th></>}
                    <th scope="col">Alert</th>
                    <th scope="col" className={sa.num}>On hand</th>
                    {tab === "low" && <th scope="col" className={sa.num}>Reorder level</th>}
                    {tab !== "low" && <th scope="col" className={sa.num}>Value at risk</th>}
                    <th scope="col">Raised</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((a) => (
                    <tr key={a.id}>
                      {canAck && <td><input className={sa.target} type="checkbox" aria-label={`Select ${a.itemName}`} checked={selected.includes(a.id)} onChange={() => toggle(a.id)} /></td>}
                      <td>{a.itemName}</td>
                      {tab === "expiry" && <><td>{a.batchNo}</td><td>{a.expiryDate} <span className={ph.sub}>{daysText(a.daysToExpiry)}</span></td></>}
                      <td>{TYPE_LABEL[a.type] ?? a.type}</td>
                      <td className={sa.num}>{formatNumber(a.onHand)}</td>
                      {tab === "low" && <td className={sa.num}>{a.reorderLevel == null ? "" : formatNumber(a.reorderLevel)}</td>}
                      {tab !== "low" && <td className={sa.num}>{a.valueAtRisk == null ? "" : formatNumber(a.valueAtRisk, FIX2)}</td>}
                      <td>{a.createdOn}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {page.nextCursor && <button type="button" className={`${styles.btn} ${sa.target}`} disabled={busy} onClick={more}>Load more</button>}
          </>
        )}
    </section>
  );
}

function ExpiryReportView() {
  const [within, setWithin] = useState<number>(60);
  const [report, setReport] = useState<ExpiryReport | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  const load = useCallback(async (days: number) => {
    setLoadError(null);
    try { setReport(await api.get<ExpiryReport>(`/pharmacy/reports/expiry?withinDays=${days}`)); } catch (e) { setLoadError(errorText(e, "Couldn't load the expiry report.")); }
  }, []);
  useEffect(() => { void Promise.resolve().then(() => load(within)); }, [load, within]);

  const exportCsv = async () => {
    if (exporting) return;
    setExporting(true); setExportError(null);
    try {
      const csv = await api.get<string>(`/pharmacy/reports/expiry?withinDays=${within}&format=csv`, { as: "text" });
      const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
      const a = document.createElement("a");
      a.href = url; a.download = `expiry-report-${within}-days.csv`; a.click();
      URL.revokeObjectURL(url);
    } catch (e) { setExportError(errorText(e, "Couldn't export the report. Try again.")); } finally { setExporting(false); }
  };

  return (
    <section className={sa.section} aria-labelledby="er-title">
      <h2 id="er-title" className={styles.cardTitle}>Expiry report</h2>
      <div className={sa.bar}>
        <label className={styles.label} htmlFor="er-window">Expiring within</label>
        <select id="er-window" className={`${styles.select} ${sa.target}`} value={within} onChange={(e) => { setReport(null); setWithin(Number(e.target.value)); }}>
          {WINDOWS.map((d) => <option key={d} value={d}>Next {d} days</option>)}
        </select>
        <button type="button" className={`${styles.btn} ${sa.target}`} disabled={exporting || !report || report.lines.length === 0} onClick={exportCsv}>Export CSV</button>
      </div>
      {exportError && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{exportError}</div>}
      {loadError ? <ErrorState message={loadError} onRetry={() => load(within)} />
        : !report ? <Skeleton rows={4} label="Loading the expiry report" />
        : report.lines.length === 0 ? <EmptyState icon="package" title={`Nothing expires in the next ${within} days.`} />
        : (
          <>
            <p className={styles.subtitle} data-testid="er-total">
              {report.lines.length} {report.lines.length === 1 ? "batch" : "batches"}, value at risk {formatNumber(report.totalValueAtRisk, FIX2)} (quantity x purchase rate).
            </p>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th scope="col">Item</th><th scope="col">Batch</th><th scope="col">Expiry</th><th scope="col" className={sa.num}>Qty</th>
                    <th scope="col" className={sa.num}>Value at risk</th><th scope="col">Suggested action</th>
                  </tr>
                </thead>
                <tbody>
                  {report.lines.map((l) => (
                    <tr key={l.batchId}>
                      <td>{l.itemName}</td><td>{l.batchNo}</td>
                      <td>{l.expiryDate} <span className={ph.sub}>{daysText(l.daysToExpiry)}</span></td>
                      <td className={sa.num}>{formatNumber(l.qtyOnHand)}</td>
                      <td className={sa.num}>{formatNumber(l.valueAtRisk, FIX2)}</td>
                      <td>{SUGGESTED_LABEL[l.suggestedAction] ?? l.suggestedAction}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
    </section>
  );
}
