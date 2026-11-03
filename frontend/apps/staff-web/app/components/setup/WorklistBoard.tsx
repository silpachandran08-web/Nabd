"use client";

import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import StatusPill from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { usePermissions } from "../../lib/usePermissions";
import { cardTitle, COLUMNS, columnEntries, lineText, moveFocus, type WorklistEntry } from "./worklistLogic";
import { usePharmacyWorklist } from "./usePharmacyWorklist";
import styles from "./worklist.module.css";

/** PHL-012 (prototype pharmacist.dispense): the In-house worklist in three columns. A card's title opens the patient's billing
 * screen to dispense (billing:view; plain text without it); J / K move between those titles, Enter opens. Mounted only for
 * pharmacy:dispense holders in In-house mode (PharmacyPanel). */
export default function WorklistBoard() {
  const w = usePharmacyWorklist();
  const { can } = usePermissions();
  const canOpen = can("billing:view"); // dispensing happens on the visit's billing screen
  const router = useRouter();
  const titles = useRef<(HTMLButtonElement | null)[]>([]);
  const focus = useRef(-1);
  const columns = COLUMNS.map((c) => ({ ...c, items: columnEntries(w.entries ?? [], c.statuses) }));
  const ordered = columns.flatMap((c) => c.items);
  const openable = (e: WorklistEntry) => canOpen && !!e.queueEntryId;
  const keyed = ordered.filter(openable);

  const board = useRef<HTMLElement>(null);
  const keyedCount = keyed.length;
  // Enter on a focused title is the button's own click. J / K work while nothing else has focus or focus is on the board
  // (never inside a field, a dialog or the item master below).
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      const at = document.activeElement;
      if (ev.altKey || ev.ctrlKey || ev.metaKey || (at && at !== document.body && !board.current?.contains(at))) return;
      const next = moveFocus(focus.current, keyedCount, ev.key);
      if (next !== focus.current) { ev.preventDefault(); focus.current = next; titles.current[next]?.focus(); }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [keyedCount]);

  return (
    <section ref={board} className={styles.board} aria-labelledby="wl-title">
      <h2 id="wl-title" className={styles.title}>Dispensing worklist</h2>
      <p className={styles.hint}>Signed prescriptions for medicines dispensed here appear within seconds.{canOpen ? " Press J or K to move between patients, Enter to open." : ""}</p>
      {w.entries === null && !w.failed && <Skeleton rows={3} label="Loading the worklist" />}
      {w.entries === null && w.failed && <ErrorState message="Couldn't load the worklist." onRetry={() => void w.reload()} />}
      {w.entries !== null && w.failed && (
        <div className={styles.notice} role="alert">Showing the last list we loaded. <button type="button" className={styles.linkBtn} onClick={() => void w.reload()}>Retry</button></div>
      )}
      {w.entries !== null && ordered.length === 0 && <EmptyState icon="check" title="Nothing waiting to be dispensed." hint="New prescriptions show up here by themselves." />}
      {w.entries !== null && ordered.length > 0 && (
        <div className={styles.columns}>
          {columns.map((c) => (
            <div key={c.key} className={styles.column}>
              <h3 className={styles.columnTitle}>{c.title} <span className={styles.count}>{c.items.length}</span></h3>
              <ul className={styles.list} aria-label={c.title}>
                {c.items.map((e) => {
                  const i = keyed.indexOf(e);
                  return (
                    <li key={e.id} className={styles.card} data-testid={`wl-${e.id}`}>
                      <div className={styles.head}>
                        {openable(e)
                          ? <button type="button" ref={(el) => { titles.current[i] = el; }} className={styles.open} onFocus={() => { focus.current = i; }}
                              onClick={() => router.push(`/checkout/${e.queueEntryId}`)}>{cardTitle(e)}</button>
                          : <strong>{cardTitle(e)}</strong>}
                        <span className={styles.time}>{new Date(e.signedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
                      </div>
                      {e.status === "partially_dispensed" && <StatusPill tone="warn" label="Partly given" />}
                      {e.status === "in_progress" && e.claimedByName && <span className={styles.by}>With {e.claimedByName}</span>}
                      <ul className={styles.lines}>
                        {e.lines.map((l) => (
                          <li key={l.prescriptionItemId} className={styles.line}>
                            <span>{lineText(l)}</span>
                            {!l.inStock && l.qtyDispensed < l.qtyPrescribed && <StatusPill tone="danger" label="Out of stock" />}
                          </li>
                        ))}
                      </ul>
                      {w.notice?.id === e.id && <div className={`${styles.notice} ${w.notice.tone === "warn" ? styles.noticeWarn : ""}`} role="status">{w.notice.text}</div>}
                      <div className={styles.actions}>
                        {e.status === "new" && (
                          <button type="button" className={styles.primary} disabled={w.busy === e.id} onClick={() => void w.start(e)}>
                            {w.busy === e.id ? "Starting…" : "Start"}
                          </button>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
