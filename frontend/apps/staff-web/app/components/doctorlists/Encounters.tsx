"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import styles from "./doctorLists.module.css";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { fmtDate, getJson, isoDate, type PageMeta } from "./api";

// DTO mirrors clinical/EncounterListModels.EncounterListItem exactly (GET /clinical/encounters).
type Encounter = {
  queueEntryId: string; patientId: string; patientName: string; patientMrn: string; queueDate: string;
  occurredAt: string; visitStatus: "in_consult" | "checkout_pending" | "completed";
  noteState: "none" | "draft" | "signed"; noteSignedAt: string | null; amendmentCount: number;
  rxStatus: "none" | "draft" | "signed"; rxItemCount: number; documentsDraft: number; documentsIssued: number;
};
type EncounterPage = { data: Encounter[]; page: PageMeta };

const STATES: { key: string; label: string }[] = [
  { key: "", label: "All" },
  { key: "unsigned", label: "Unsigned" },
  { key: "draft", label: "Draft note" },
  { key: "none", label: "No note" },
  { key: "signed", label: "Signed" },
];
const VISIT_LABEL: Record<Encounter["visitStatus"], string> = { in_consult: "In consultation", checkout_pending: "At checkout", completed: "Completed" };
const PAGE = 20;

function noteTone(s: Encounter["noteState"]) {
  return s === "signed" ? { tone: "ok" as const, label: "Note signed" }
    : s === "draft" ? { tone: "warn" as const, label: "Note unsigned (draft)" }
    : { tone: "warn" as const, label: "No note yet" };
}

function Outputs({ e }: { e: Encounter }) {
  const parts: { tone: "ok" | "warn" | "neutral"; label: string; icon?: "check" | "clock" | "list" }[] = [];
  if (e.rxStatus === "signed") parts.push({ tone: "ok", label: `Rx signed · ${e.rxItemCount} item${e.rxItemCount === 1 ? "" : "s"}` });
  else if (e.rxStatus === "draft") parts.push({ tone: "warn", label: `Rx draft · ${e.rxItemCount} item${e.rxItemCount === 1 ? "" : "s"}` });
  if (e.documentsIssued > 0) parts.push({ tone: "ok", label: `${e.documentsIssued} document${e.documentsIssued === 1 ? "" : "s"} issued` });
  if (e.documentsDraft > 0) parts.push({ tone: "warn", label: `${e.documentsDraft} document draft${e.documentsDraft === 1 ? "" : "s"}` });
  if (parts.length === 0) return <span className={styles.na}>No outputs recorded</span>;
  return <div className={styles.pills}>{parts.map((p) => <StatusPill key={p.label} tone={p.tone} label={p.label} />)}</div>;
}

// Today's visits open the consultation (the consult page resolves ?entry= from today's queue);
// older visits open the patient record, found by MRN (never the name) when the role may view patients.
function hrefFor(e: Encounter): string | null {
  if (e.queueDate === isoDate(new Date())) return `/consult?entry=${encodeURIComponent(e.queueEntryId)}`;
  if (can("patients:view")) return `/patients?q=${encodeURIComponent(e.patientMrn)}`;
  return null;
}

export default function Encounters() {
  const [state, setState] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [rows, setRows] = useState<Encounter[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [moreBusy, setMoreBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState(false);
  const seq = useRef(0);

  const query = useCallback((cursor: string | null) => {
    const p = new URLSearchParams({ limit: String(PAGE) });
    if (state) p.set("state", state);
    if (from) p.set("from", from);
    if (to) p.set("to", to);
    if (cursor) p.set("cursor", cursor);
    return `/clinical/encounters?${p.toString()}`;
  }, [state, from, to]);

  const load = useCallback(async () => {
    const my = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const page = await getJson<EncounterPage>(query(null));
      if (my !== seq.current) return;
      setRows(page.data);
      setNext(page.page.nextCursor);
      setMoreError(false);
    } catch (e) {
      if (my !== seq.current || (e as Error).message === "auth") return;
      setError("Couldn't load your encounters. Check your connection and try again.");
    } finally {
      if (my === seq.current) setLoading(false);
    }
  }, [query]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function loadMore() {
    if (!next) return;
    const my = seq.current;
    setMoreBusy(true);
    setMoreError(false);
    try {
      const page = await getJson<EncounterPage>(query(next));
      if (my !== seq.current) return;
      setRows((r) => [...r, ...page.data]);
      setNext(page.page.nextCursor);
    } catch { if (my === seq.current) setMoreError(true); }
    finally { setMoreBusy(false); }
  }

  const rangeInvalid = !!from && !!to && from > to;
  const filtered = !!state || !!from || !!to;

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>Encounters</h1>
          <p className={styles.subtitle}>Your visits that reached the consultation, newest first. Read-only.</p>
        </div>
        <button type="button" className={styles.btn} onClick={() => void load()} aria-label="Refresh encounters">
          <Icon name="refresh" size={14} /> Refresh
        </button>
      </header>

      <div className={styles.filters}>
        <div className={styles.chips} role="group" aria-label="Note state">
          {STATES.map((s) => (
            <button key={s.key} type="button" className={styles.chip} aria-pressed={state === s.key} onClick={() => setState(s.key)}>{s.label}</button>
          ))}
        </div>
        <label className={styles.label}>From
          <input type="date" className={styles.field} value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className={styles.label}>To
          <input type="date" className={styles.field} value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
        </label>
        {filtered && <button type="button" className={styles.btn} onClick={() => { setState(""); setFrom(""); setTo(""); }}>Clear filters</button>}
      </div>

      <section className={styles.card} aria-label="Encounters">
        {rangeInvalid ? <EmptyState icon="alert" title="The start date is after the end date." hint="Adjust the date range to see encounters." />
          : loading && rows.length === 0 ? <Skeleton rows={6} label="Loading encounters" />
          : error ? <ErrorState message={error} onRetry={() => void load()} />
          : rows.length === 0 ? <EmptyState icon="inbox" title={filtered ? "No encounters match these filters." : "No encounters yet."} hint={filtered ? undefined : "Visits appear here once you start a consultation."} />
          : (
            <>
              <div className={`${styles.head} ${styles.cols5}`} aria-hidden="true">
                <span>Patient</span><span>Date</span><span>Outputs</span><span>Note</span><span />
              </div>
              <ul className={styles.list} aria-busy={loading}>
                {rows.map((e) => {
                  const href = hrefFor(e);
                  const n = noteTone(e.noteState);
                  const body = (
                    <>
                      <span className={styles.cell}><span className={styles.name}>{e.patientName}</span><span className={styles.meta}>{e.patientMrn} · {VISIT_LABEL[e.visitStatus]}</span></span>
                      <span className={styles.cell}><span className={styles.mlabel}>Date</span>{fmtDate(e.queueDate)}</span>
                      <span className={styles.cell}><span className={styles.mlabel}>Outputs</span><Outputs e={e} /></span>
                      <span className={styles.cell}>
                        <span className={styles.mlabel}>Note</span>
                        <span className={styles.pills}>
                          <StatusPill tone={n.tone} label={n.label} />
                          {e.amendmentCount > 0 && <StatusPill tone="info" icon="info" label={`Amended ×${e.amendmentCount}`} />}
                        </span>
                      </span>
                      {href ? <Icon name="chevron" size={14} /> : <span />}
                    </>
                  );
                  return (
                    <li key={e.queueEntryId}>
                      {href ? <Link href={href} className={`${styles.row} ${styles.cols5}`}>{body}</Link>
                        : <div className={`${styles.row} ${styles.cols5}`}>{body}</div>}
                    </li>
                  );
                })}
              </ul>
              {moreError && <div className={styles.inlineErr} role="alert">Couldn&apos;t load more. <button type="button" className={styles.btn} onClick={() => void loadMore()}>Retry</button></div>}
              {next && !moreError && (
                <div className={styles.more}>
                  <button type="button" className={styles.btn} onClick={() => void loadMore()} disabled={moreBusy}>{moreBusy ? "Loading…" : "Load more"}</button>
                </div>
              )}
            </>
          )}
      </section>
    </main>
  );
}
