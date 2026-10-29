"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import styles from "../../(clinic)/setup/setup.module.css";
import ph from "./pharmacy.module.css";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, errorText } from "../../lib/api";
import { formatNumber } from "@nabd/i18n";
import { LOSS_REMINDER, REASONS, byLocation, draftProblem, needsApproval, variance, type Draft, type ReasonCode } from "../../lib/stockTake";
import ApproverStep, { type Approval } from "./ApproverStep";
import type { Full } from "../../lib/gen";
import type { StockTakeLine, StockTakePosted, StockTakeSummary } from "@nabd/api-client/generated";

type Line = Full<StockTakeLine, "batchNo" | "expiryDate" | "location" | "countedQty" | "variance" | "reasonCode" | "note" | "postedDelta">;
type Summary = Full<StockTakeSummary, "scopeValue" | "postedByName" | "approvedByName" | "postedAt">;
type Sheet = { summary: Summary; lines: Line[] };
type Posted = Full<StockTakePosted, "approvedByName">;

const draftOf = (l: Line): Draft => ({ counted: l.countedQty == null ? "" : String(l.countedQty), reasonCode: (l.reasonCode ?? "") as ReasonCode | "", note: l.note ?? "" });
const FIX2 = { min: 2, max: 2 };

/** PHL-010: start a stock take (all / one location / one schedule class), count by shelf, see the variance, post all at once. */
export default function StockTakeSheet() {
  const [recent, setRecent] = useState<Summary[] | null>(null);
  const [sheet, setSheet] = useState<Sheet | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [scope, setScope] = useState<"all" | "location" | "class">("all");
  const [scopeValue, setScopeValue] = useState("");
  const [approval, setApproval] = useState<Approval>({ approverStaffId: "", approverPin: "" });
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [posted, setPosted] = useState<Posted | null>(null);

  const open = useCallback(async (id: string) => {
    const s = await api.get<Sheet>(`/pharmacy/stock-takes/${id}`);
    setSheet(s);
    setDrafts(Object.fromEntries(s.lines.map((l) => [l.batchId, draftOf(l)])));
  }, []);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const list = await api.get<Summary[]>("/pharmacy/stock-takes");
      setRecent(list);
      const cur = list.find((s) => s.status === "open");
      if (cur) await open(cur.stockTakeId); else setSheet(null);
    } catch (e) { setLoadError(errorText(e, "Couldn't load stock takes.")); }
  }, [open]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const start = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (scope !== "all" && scopeValue.trim() === "") { setProblem(scope === "location" ? "Enter the location." : "Enter the schedule class."); return; }
    setBusy(true); setProblem(null);
    try {
      const r = await api.post<{ stockTakeId: string }>("/pharmacy/stock-takes", {
        scope, ...(scope === "location" ? { location: scopeValue.trim() } : scope === "class" ? { scheduleClass: scopeValue.trim() } : {}),
      });
      await open(r.stockTakeId);
    } catch (err) { setProblem(errorText(err, "Couldn't start the stock take.")); } finally { setBusy(false); }
  };

  const changed = (): { batchId: string; counted: number; reasonCode?: string; note?: string }[] =>
    (sheet?.lines ?? []).filter((l) => {
      const d = drafts[l.batchId], o = draftOf(l);
      return d && d.counted.trim() !== "" && (d.counted !== o.counted || d.reasonCode !== o.reasonCode || d.note !== o.note);
    }).map((l) => {
      const d = drafts[l.batchId];
      return { batchId: l.batchId, counted: Number(d.counted), reasonCode: d.reasonCode || undefined, note: d.note.trim() || undefined };
    });

  const firstProblem = (): string | null => {
    for (const l of sheet?.lines ?? []) {
      const p = drafts[l.batchId] ? draftProblem(drafts[l.batchId]) : null;
      if (p) return `${l.itemName} ${l.batchNo ?? ""}: ${p}`;
    }
    return null;
  };

  /** Saves the changed counts; true when there was nothing to save or the save worked. */
  const save = async (): Promise<boolean> => {
    if (!sheet) return false;
    const p = firstProblem();
    if (p) { setProblem(p); return false; }
    const counts = changed();
    if (counts.length === 0) return true;
    try {
      await api.put(`/pharmacy/stock-takes/${sheet.summary.stockTakeId}/counts`, { counts });
      await open(sheet.summary.stockTakeId);
      return true;
    } catch (err) { setProblem(errorText(err, "Couldn't save the counts. Nothing was saved.")); return false; }
  };

  const saveCounts = async () => {
    if (busy) return;
    setBusy(true); setProblem(null); setNotice(null);
    if (await save()) setNotice("Counts saved.");
    setBusy(false);
  };

  const varOf = (l: Line) => variance(l, drafts[l.batchId]);
  const approvalNeeded = sheet ? needsApproval(sheet.lines, (l) => varOf(l as Line)) : false;

  const post = async () => {
    if (busy || !sheet) return;
    if (approvalNeeded && (!approval.approverStaffId || !approval.approverPin)) { setProblem("A controlled drug has a variance: the second signer must choose their name and enter their PIN."); return; }
    setBusy(true); setProblem(null); setNotice(null);
    try {
      if (!(await save())) return;
      const r = await api.post<Posted>(`/pharmacy/stock-takes/${sheet.summary.stockTakeId}/post`, approvalNeeded ? approval : {});
      setPosted(r); setSheet(null); setApproval({ approverStaffId: "", approverPin: "" });
      await load();
    } catch (err) {
      setProblem(errorText(err, "Couldn't post the stock take. Nothing was posted."));
      setApproval({ ...approval, approverPin: "" });
    } finally { setBusy(false); }
  };

  const cancel = async () => {
    if (busy || !sheet) return;
    setBusy(true); setProblem(null);
    try { await api.post(`/pharmacy/stock-takes/${sheet.summary.stockTakeId}/cancel`); setNotice("Stock take cancelled. Nothing was changed."); await load(); }
    catch (err) { setProblem(errorText(err, "Couldn't cancel the stock take.")); } finally { setBusy(false); }
  };

  const setDraft = (id: string, d: Partial<Draft>) => { setProblem(null); setDrafts((p) => ({ ...p, [id]: { ...p[id], ...d } })); };

  if (loadError) return <ErrorState message={loadError} onRetry={load} />;
  if (!recent) return <Skeleton rows={5} label="Loading stock take" />;

  return (
    <div>
      {posted && (
        <div className={ph.notice} role="status" data-testid="st-posted">
          Stock take posted: {posted.adjustments.length} {posted.adjustments.length === 1 ? "adjustment" : "adjustments"}, net value {formatNumber(posted.value, FIX2)}
          {posted.approvedByName ? `, approved by ${posted.approvedByName}` : ""}.
        </div>
      )}
      {notice && <div className={ph.notice} role="status">{notice}</div>}
      {!sheet ? (
        <form onSubmit={start} aria-label="Start a stock take">
          <p className={styles.subtitle}>Starting freezes the book quantity of every batch in scope. Sales may go on while you count.</p>
          <div className={ph.formRow}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="st-scope">Count</label>
              <select id="st-scope" className={styles.select} value={scope} onChange={(e) => { setScope(e.target.value as typeof scope); setScopeValue(""); }}>
                <option value="all">All stock</option>
                <option value="location">One location</option>
                <option value="class">One schedule class</option>
              </select>
            </div>
            {scope !== "all" && (
              <div className={styles.field}>
                <label className={styles.label} htmlFor="st-value">{scope === "location" ? "Location" : "Schedule class code"}</label>
                <input id="st-value" className={styles.input} maxLength={80} value={scopeValue} onChange={(e) => setScopeValue(e.target.value)} />
              </div>
            )}
          </div>
          {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
          <div className={styles.actions}>
            <Link href="/pharmacy" className={styles.btn}>Back to Pharmacy</Link>
            <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Starting…" : "Start stock take"}</button>
          </div>
          {recent.length > 0 && (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <caption className={ph.sub}>Recent stock takes</caption>
                <thead><tr><th scope="col">Started</th><th scope="col">Scope</th><th scope="col">Status</th><th scope="col">Counted</th><th scope="col">Posted by</th><th scope="col">Approved by</th></tr></thead>
                <tbody>
                  {recent.map((s) => (
                    <tr key={s.stockTakeId}>
                      <td>{new Date(s.snapshotAt).toLocaleString()}</td>
                      <td>{s.scope}{s.scopeValue ? ` · ${s.scopeValue}` : ""}</td>
                      <td>{s.status}</td>
                      <td>{s.countedCount} / {s.lineCount}</td>
                      <td>{s.postedByName ?? "—"}</td>
                      <td>{s.approvedByName ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </form>
      ) : sheet.lines.length === 0 ? (
        <EmptyState title="Nothing to count" hint="No batch with stock is on this sheet." icon="package" />
      ) : (
        <div>
          <p className={styles.subtitle}>
            Snapshot {new Date(sheet.summary.snapshotAt).toLocaleString()} · {sheet.summary.countedCount} of {sheet.summary.lineCount} counted.
            Expected = snapshot plus sales and receipts until the line was counted. Lines left blank are not adjusted.
          </p>
          {byLocation(sheet.lines).map((g) => (
            <div key={g.location} className={styles.tableWrap}>
              <table className={styles.table}>
                <caption><strong>{g.location}</strong></caption>
                <thead><tr><th scope="col">Item</th><th scope="col">Batch</th><th scope="col">Expiry</th><th scope="col">Expected</th><th scope="col">Counted</th><th scope="col">Variance</th><th scope="col">Reason</th><th scope="col">Note</th></tr></thead>
                <tbody>
                  {g.lines.map((l) => {
                    const d = drafts[l.batchId] ?? draftOf(l);
                    const v = varOf(l);
                    return (
                      <tr key={l.batchId}>
                        <td>{l.itemName}{l.controlled && <> <span className={ph.sub}>(controlled)</span></>}</td>
                        <td>{l.batchNo ?? "—"}</td>
                        <td>{l.expiryDate ?? "—"}</td>
                        <td>{l.expectedQty}</td>
                        <td><input aria-label={`Counted ${l.itemName} ${l.batchNo ?? ""}`} className={styles.input} style={{ minInlineSize: 72 }} inputMode="numeric"
                          value={d.counted} onChange={(e) => setDraft(l.batchId, { counted: e.target.value })} /></td>
                        <td data-testid={`var-${l.batchId}`}>{v == null ? "" : v > 0 ? `+${v}` : v}
                          {l.controlled && v != null && v < 0 && <div className={ph.sub} role="note">{LOSS_REMINDER}</div>}</td>
                        <td><select aria-label={`Reason ${l.itemName} ${l.batchNo ?? ""}`} className={styles.select} value={d.reasonCode}
                          onChange={(e) => setDraft(l.batchId, { reasonCode: e.target.value as ReasonCode | "" })}>
                          <option value="">{v ? "Count correction" : "—"}</option>
                          {REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
                        </select></td>
                        <td><input aria-label={`Note ${l.itemName} ${l.batchNo ?? ""}`} className={styles.input} maxLength={500} value={d.note}
                          onChange={(e) => setDraft(l.batchId, { note: e.target.value })} /></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ))}
          {approvalNeeded && <ApproverStep idPrefix="st" value={approval} onChange={setApproval} />}
          {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
          <div className={styles.actions}>
            <button type="button" className={styles.btn} onClick={() => void cancel()} disabled={busy}>Cancel stock take</button>
            <button type="button" className={styles.btn} onClick={() => void saveCounts()} disabled={busy}>Save counts</button>
            <button type="button" className={styles.btnPrimary} onClick={() => void post()} disabled={busy}>{busy ? "Working…" : "Post variances"}</button>
          </div>
        </div>
      )}
    </div>
  );
}
