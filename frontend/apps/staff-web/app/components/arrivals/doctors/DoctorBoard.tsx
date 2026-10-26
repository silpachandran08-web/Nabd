"use client";

/* eslint-disable jsx-a11y/no-noninteractive-element-interactions -- a column is a drop target for drag and drop; the keyboard path is on the cards */

// ACC-022: every doctor's queue in one call (GET /queue/board). Drag a waiting patient to another doctor's column, or press M then the
// column number; P flags priority. Controls need queue:edit and are absent without it. Token numbers are never guessed: the screen waits for the server.
import { useState } from "react";
import { useTranslations } from "next-intl";
import type { QueueReassignResponse } from "@nabd/api-client/generated";
import { useQuery } from "@tanstack/react-query";
import styles from "./doctorBoard.module.css";
import Dialog from "../../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../../ui/StateViews";
import PriorityDialog from "../dialogs/PriorityDialog";
import { api, errorText } from "../../../lib/api";
import { can } from "../../../lib/permissions";
import { getter, key, live, useReload } from "../../../lib/query";
import { usePolling } from "../../../lib/usePolling";
import { useHasMounted } from "../../../lib/useHasMounted";
import { useClinicTimeZone } from "../../../lib/useClinicTimeZone";
import { formatClinicTime } from "../../../lib/clinicTime";
import { REASONS, isMovable, keyAction, ownerOf, targetDoctor, tokenLabel, type Board, type BoardEntry } from "./doctorBoardLogic";

const POLL_MS = 15_000;
type Pending = { entryId: string; toDoctorId: string };

export default function DoctorBoard() {
  const t = useTranslations("arrivals.doctorBoard");
  const mounted = useHasMounted();
  const canEdit = mounted && can("queue:edit");
  const tz = useClinicTimeZone();
  const q = useQuery({ ...live, queryKey: key("queue", { board: true }), queryFn: getter<Board>("/queue/board"), enabled: mounted && can("queue:view") });
  const reload = useReload("queue");
  usePolling(reload, POLL_MS);

  const [moving, setMoving] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [priorityFor, setPriorityFor] = useState<string | null>(null);
  const [reason, setReason] = useState(REASONS[0].code);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState<string | null>(null);

  const board = q.data;
  const doctors = Array.isArray(board?.doctors) ? board.doctors : [];

  async function confirmMove() {
    if (!pending) return;
    const found = ownerOf(doctors, pending.entryId);
    if (!found) { setPending(null); return; }
    setBusy(true);
    try {
      const r = await api.post<QueueReassignResponse>(`/queue/${pending.entryId}/reassign`,
        { toDoctorId: pending.toDoctorId, expectedStatus: found.entry.stage, reasonCode: reason });
      setNotice(t("moved", { token: String(r.entry?.tokenNumber), previous: String(r.previousToken) }));
      setPending(null); setMoving(null);
    } catch (e) {
      setNotice(errorText(e, t("moveFail")));
      setPending(null); setMoving(null);
    } finally {
      setBusy(false);
      void reload();
    }
  }

  function onKey(e: React.KeyboardEvent, entry: BoardEntry, doctorId: string) {
    if (!canEdit) return;
    const a = keyAction(e.key, moving === entry.id);
    if (!a) return;
    if (a.type === "start" && isMovable(entry)) { e.preventDefault(); setMoving(entry.id); setNotice(t("movingNote", { token: tokenLabel(entry) })); }
    else if (a.type === "cancel") { e.preventDefault(); setMoving(null); setNotice(null); }
    else if (a.type === "priority") { e.preventDefault(); setPriorityFor(entry.id); }
    else if (a.type === "target") {
      e.preventDefault();
      const to = targetDoctor(doctors, doctorId, a.index);
      if (to) setPending({ entryId: entry.id, toDoctorId: to.doctorId });
      else setNotice(t("pickOther"));
    }
  }

  if (!mounted || q.isLoading) return <div className={styles.wrap}><Skeleton rows={6} label={t("loading")} /></div>;
  if (q.isError && !board) return <div className={styles.wrap}><ErrorState message={t("loadFail")} onRetry={() => void reload()} /></div>;
  if (doctors.length === 0) return <div className={styles.wrap}><EmptyState icon="users" title={t("emptyTitle")} hint={t("emptyHint")} /></div>;

  const pendingTo = pending ? doctors.find((d) => d.doctorId === pending.toDoctorId) : null;
  const pendingFrom = pending ? ownerOf(doctors, pending.entryId) : null;

  return (
    <div className={styles.wrap} data-testid="doctor-board">
      <div role="status" aria-live="polite" className={styles.notice} data-testid="board-notice">{notice}</div>
      {canEdit && <p className={styles.hint}>{t("hint")}</p>}
      <div className={styles.cols}>
        {doctors.map((d, i) => (
          <section key={d.doctorId} className={`${styles.col} ${dragOver === d.doctorId ? styles.colOver : ""}`} aria-label={t("colLabel", { name: d.name, number: i + 1 })} data-testid={`col-${i + 1}`}
            onDragOver={canEdit ? (e) => { e.preventDefault(); setDragOver(d.doctorId); } : undefined}
            onDragLeave={canEdit ? () => setDragOver(null) : undefined}
            onDrop={canEdit ? (e) => {
              e.preventDefault(); setDragOver(null);
              const id = e.dataTransfer.getData("text/plain");
              const from = ownerOf(doctors, id);
              if (from && from.doctor.doctorId !== d.doctorId && isMovable(from.entry)) setPending({ entryId: id, toDoctorId: d.doctorId });
            } : undefined}>
            <header className={styles.colHead}>
              <h2 className={styles.colName}><span className={styles.colNo}>{i + 1}</span> {d.name}</h2>
              <div className={styles.colMeta}>
                <span>{t(`state.${d.state === "in" || d.state === "paused" ? d.state : "out"}`)}</span>
                {d.nowServing ? <span>{t("nowServing", { token: String(d.nowServing) })}</span> : null}
                {d.delayMinutes ? <span className={styles.delay}>{t("late", { minutes: d.delayMinutes })}</span> : null}
              </div>
            </header>
            {d.entries.length === 0 ? <p className={styles.empty}>{t("noPatients")}</p> : (
              <ul className={styles.list}>
                {d.entries.map((e) => (
                  <li key={e.id}>
                    <div className={`${styles.card} ${moving === e.id ? styles.cardMoving : ""} ${e.priority ? styles.cardPriority : ""}`}
                      role="button" aria-roledescription="movable patient card" tabIndex={0} data-testid={`card-${e.id}`}
                      aria-label={t("cardLabel", { token: tokenLabel(e), name: e.displayName, stage: e.stage.replace(/_/g, " "), priority: String(!!e.priority) })}
                      draggable={canEdit && isMovable(e)}
                      onDragStart={(ev) => { ev.dataTransfer.setData("text/plain", e.id); ev.dataTransfer.effectAllowed = "move"; }}
                      onKeyDown={(ev) => onKey(ev, e, d.doctorId)}>
                      <div className={styles.cardTop}><strong>{tokenLabel(e)}</strong>{e.priority && <span className={styles.flag}>{t("priority")}</span>}{e.allergyFlag && <span className={styles.flag}>{t("allergy")}</span>}</div>
                      <div>{e.displayName}</div>
                      <div className={styles.cardSub}>
                        {e.stage.replace(/_/g, " ")}
                        {e.p50At && e.p80At ? ` · ${formatClinicTime(e.p50At, tz)}-${formatClinicTime(e.p80At, tz)}` : ""}
                        {e.mrn ? ` · ${e.mrn}` : ""}
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        ))}
      </div>

      {pending && pendingTo && pendingFrom && (
        <Dialog title={t("dialogTitle")} onClose={() => { setPending(null); setMoving(null); }}>
          <p>{t("confirm", { token: tokenLabel(pendingFrom.entry), name: pendingFrom.entry.displayName, from: pendingFrom.doctor.name, to: pendingTo.name })}</p>
          <label htmlFor="reassign-reason" className={styles.label}>{t("reason")}</label>
          <select id="reassign-reason" className={styles.select} value={reason} onChange={(e) => setReason(e.target.value)}>
            {REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
          </select>
          <div className={styles.actions}>
            <button type="button" className={styles.btn} onClick={() => { setPending(null); setMoving(null); }}>{t("cancel")}</button>
            <button type="button" className={styles.btnPrimary} disabled={busy} onClick={() => void confirmMove()}>{busy ? t("moving") : t("move")}</button>
          </div>
        </Dialog>
      )}
      {priorityFor && <PriorityDialog entryId={priorityFor} onClose={() => setPriorityFor(null)} onSaved={() => { setPriorityFor(null); void reload(); }} />}
    </div>
  );
}
