"use client";

import { useEffect, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { formatClinicDate, formatClinicTime } from "../../lib/clinicTime";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { DECLINE_REASONS, instantToZoned, keyAction, reasonLabel, slaState, zonedToInstant } from "./requestsLogic";
import { useBookingRequests, type RequestItem } from "./useBookingRequests";
import styles from "./requests.module.css";

function useNow(ms: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), ms); return () => clearInterval(id); }, [ms]);
  return now;
}

/** Reception's requests inbox (prototype reception.requests): why it needs review, SLA countdown, a suggested alternative, and one-key decisions (J/K move, A accept, S suggest, D decline). */
export default function RequestsInbox() {
  const r = useBookingRequests();
  const tz = useClinicTimeZone();
  const now = useNow(15_000);
  const [selRaw, setSel] = useState(0);
  const [dialog, setDialog] = useState<{ kind: "suggest" | "decline"; item: RequestItem } | null>(null);
  const rows = useRef<(HTMLLIElement | null)[]>([]);
  const items = r.items ?? [];
  const sel = Math.min(selRaw, Math.max(0, items.length - 1)); // the list can shrink under the cursor
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const act = keyAction(e as unknown as Parameters<typeof keyAction>[0], dialog !== null);
      if (!act) return;
      const cur = items[sel];
      if (act === "next") setSel(Math.min(items.length - 1, sel + 1));
      else if (act === "prev") setSel(Math.max(0, sel - 1));
      else if (!cur) return;
      else if (act === "accept") void r.accept(cur.appointmentId);
      else setDialog({ kind: act, item: cur });
      e.preventDefault();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  });
  useEffect(() => { rows.current[sel]?.scrollIntoView?.({ block: "nearest" }); }, [sel]);

  const when = (iso: string) => `${formatClinicDate(iso, undefined, tz)} ${formatClinicTime(iso, tz)}`;
  const median = r.stats?.medianReplySeconds;

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>Booking requests</h1>
          <p className={styles.subtitle}>Bookings held for your decision. The slot stays held until you decide.</p>
        </div>
        {r.stats && (
          <dl className={styles.stats} aria-label="Today">
            <div><dt>Auto-accepted today</dt><dd>{r.stats.autoAccepted ?? 0}</dd></div>
            <div><dt>Median reply</dt><dd>{median == null ? "—" : `${Math.max(1, Math.round(median / 60))} min`}</dd></div>
          </dl>
        )}
      </header>
      <p className={styles.keys}>Keys: <kbd>J</kbd>/<kbd>K</kbd> move, <kbd>A</kbd> accept, <kbd>S</kbd> suggest other times, <kbd>D</kbd> decline.</p>
      {r.notice && (
        <div className={`${styles.notice} ${r.notice.tone === "warn" ? styles.noticeWarn : ""}`} role="status">
          {r.notice.text}
          <button type="button" className={styles.linkBtn} onClick={() => r.setNotice(null)} aria-label="Dismiss message"><Icon name="x" size={14} /></button>
        </div>
      )}
      {r.items === null && !r.failed ? <Skeleton rows={4} label="Loading booking requests" />
        : r.failed && r.items === null ? <ErrorState message="Couldn't load the booking requests. Check your connection and try again." onRetry={() => void r.reload()} />
        : items.length === 0 ? <EmptyState icon="inbox" title="No requests waiting." hint="New bookings that need a decision appear here by themselves." />
        : (
          <ul className={styles.list} aria-label="Pending booking requests">
            {items.map((it, i) => {
              const sla = slaState(it.slaDueAt, now);
              const flagged = (it.triageFlags ?? []).length > 0;
              return (
                <li key={it.appointmentId} ref={(el) => { rows.current[i] = el; }} className={`${styles.card} ${i === sel ? styles.selected : ""}`} aria-current={i === sel ? "true" : undefined} onClick={() => setSel(i)}>
                  <div className={styles.main}>
                    <div className={styles.who}>
                      <span className={styles.name}>{it.patient?.displayName}</span>
                      {it.patient?.isNew && <StatusPill tone="info" label="New" />}
                      {flagged && <StatusPill tone="urgent" label="Triage" />}
                    </div>
                    <div className={styles.meta}>{when(it.start ?? "")} · {it.channel}</div>
                    <div className={styles.why}>{reasonLabel(it.reviewReason)}</div>
                    {it.suggestedAlternative && <div className={styles.meta}>Next free time: {when(it.suggestedAlternative)}</div>}
                  </div>
                  <div className={`${styles.sla} ${sla.overdue ? styles.overdue : ""}`} aria-label={`Reply time: ${sla.label}`}><Icon name="clock" size={14} /> {sla.label}</div>
                  <div className={styles.actions}>
                    <button type="button" className={styles.primary} onClick={(e) => { e.stopPropagation(); void r.accept(it.appointmentId); }}>Accept</button>
                    <button type="button" className={styles.secondary} onClick={(e) => { e.stopPropagation(); setDialog({ kind: "suggest", item: it }); }}>Suggest times</button>
                    <button type="button" className={styles.secondary} onClick={(e) => { e.stopPropagation(); setDialog({ kind: "decline", item: it }); }}>Decline</button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      {dialog?.kind === "suggest" && <SuggestDialog item={dialog.item} tz={tz} onClose={() => setDialog(null)}
        onSend={async (slots) => { const res = await r.suggest(dialog.item.appointmentId, slots); if (res.ok) setDialog(null); return res.ok; }} />}
      {dialog?.kind === "decline" && <DeclineDialog onClose={() => setDialog(null)}
        onSend={async (code) => { const res = await r.decline(dialog.item.appointmentId, code); setDialog(null); return res.ok; }} />}
    </main>
  );
}

function SuggestDialog({ item, tz, onClose, onSend }: { item: RequestItem; tz: string | null; onClose: () => void; onSend: (slots: string[]) => Promise<boolean> }) {
  const zone = tz ?? "UTC";
  const [times, setTimes] = useState<string[]>([item.suggestedAlternative ? instantToZoned(item.suggestedAlternative, zone) : ""]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function send() {
    const slots = times.filter(Boolean).map((t) => zonedToInstant(t, zone));
    if (slots.length === 0 || slots.some((s) => s === null)) { setError("Pick at least one time."); return; }
    setBusy(true); setError(null);
    const ok = await onSend(slots as string[]);
    setBusy(false);
    if (!ok) setError("That time can't be offered. Pick another.");
  }
  return (
    <Dialog title="Suggest other times" onClose={onClose}>
      <p className={styles.meta}>The patient gets up to three buttons plus &ldquo;none of these&rdquo;. Their tap confirms the booking.</p>
      {times.map((t, i) => (
        <label key={i} className={styles.field}>Time {i + 1}
          <input type="datetime-local" step={900} value={t} onChange={(e) => setTimes((cur) => cur.map((x, j) => (j === i ? e.target.value : x)))} />
        </label>
      ))}
      {times.length < 3 && <button type="button" className={styles.secondary} onClick={() => setTimes((cur) => [...cur, ""])}>Add another time</button>}
      {error && <div className={styles.error} role="alert">{error}</div>}
      <div className={styles.dialogActions}>
        <button type="button" className={styles.secondary} onClick={onClose}>Cancel</button>
        <button type="button" className={styles.primary} disabled={busy} onClick={() => void send()}>{busy ? "Sending…" : "Send to patient"}</button>
      </div>
    </Dialog>
  );
}

function DeclineDialog({ onClose, onSend }: { onClose: () => void; onSend: (code: string) => Promise<boolean> }) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  async function send() {
    if (!code) { setError("Choose a reason."); return; }
    await onSend(code);
  }
  return (
    <Dialog title="Decline this request" onClose={onClose}>
      <p className={styles.meta}>The patient gets a neutral message with a button to book another time. The reason stays in your records.</p>
      <label className={styles.field}>Reason
        <select value={code} onChange={(e) => { setCode(e.target.value); setError(null); }} aria-invalid={error ? true : undefined}>
          <option value="">Choose a reason</option>
          {DECLINE_REASONS.map((d) => <option key={d.code} value={d.code}>{d.label}</option>)}
        </select>
      </label>
      {error && <div className={styles.error} role="alert">{error}</div>}
      <div className={styles.dialogActions}>
        <button type="button" className={styles.secondary} onClick={onClose}>Cancel</button>
        <button type="button" className={styles.primary} onClick={() => void send()}>Decline</button>
      </div>
    </Dialog>
  );
}
