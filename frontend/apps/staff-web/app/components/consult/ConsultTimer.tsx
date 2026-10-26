"use client";

import { useEffect, useState } from "react";
import Icon from "../ui/Icon";
import s from "./noteTemplates.module.css";

// DOC-028. A consult stopwatch kept in this browser tab only (sessionStorage, per queue entry). It is NOT clinical
// data: it is never sent to the server, never written to the note and never used to change anyone's waiting time.
// "Time box" is the doctor's own planned length; there is no default, so one appears only after "Extend 10 min".
type TimerState = { startedAt: number; pausedAt: number | null; pausedTotal: number; extendedMin: number; endedAt: number | null };

const key = (entryId: string) => `nabd.consultTimer.${entryId}`;

function load(entryId: string): TimerState | null {
  try {
    const raw = sessionStorage.getItem(key(entryId));
    if (!raw) return null;
    const v = JSON.parse(raw) as Partial<TimerState>;
    if (typeof v.startedAt !== "number" || typeof v.pausedTotal !== "number" || typeof v.extendedMin !== "number") return null;
    return { startedAt: v.startedAt, pausedAt: typeof v.pausedAt === "number" ? v.pausedAt : null, pausedTotal: v.pausedTotal, extendedMin: v.extendedMin, endedAt: typeof v.endedAt === "number" ? v.endedAt : null };
  } catch {
    return null;
  }
}
function save(entryId: string, st: TimerState) {
  try { sessionStorage.setItem(key(entryId), JSON.stringify(st)); } catch { /* storage unavailable: the timer still works for this page view */ }
}

export function elapsedMs(st: TimerState, now: number): number {
  const stop = st.endedAt ?? st.pausedAt ?? now;
  return Math.max(0, stop - st.startedAt - st.pausedTotal);
}
export function fmt(ms: number): string {
  const t = Math.floor(ms / 1000);
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), sec = t % 60;
  const two = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${two(m)}:${two(sec)}` : `${two(m)}:${two(sec)}`;
}

export default function ConsultTimer({ entryId, ended }: { entryId: string; ended: boolean }) {
  return <TimerInner key={entryId} entryId={entryId} ended={ended} />;
}

function TimerInner({ entryId, ended }: { entryId: string; ended: boolean }) {
  const [st, setSt] = useState<TimerState>(() => load(entryId) ?? { startedAt: Date.now(), pausedAt: null, pausedTotal: 0, extendedMin: 0, endedAt: null });
  const [now, setNow] = useState(() => Date.now());

  // Persist every change (and the first value), so a reload of the tab keeps the same timer.
  useEffect(() => { save(entryId, st); }, [entryId, st]);
  // Tick once a second while running.
  useEffect(() => {
    if (ended || st.pausedAt !== null || st.endedAt !== null) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [ended, st.pausedAt, st.endedAt]);
  // The signed note ends the timer: freeze it where it stood.
  useEffect(() => {
    if (ended && st.endedAt === null) void Promise.resolve().then(() => setSt((c) => (c.endedAt === null ? { ...c, endedAt: Date.now() } : c)));
  }, [ended, st.endedAt]);

  const paused = st.pausedAt !== null;
  const over = elapsedMs(st, now);
  const boxMs = st.extendedMin > 0 ? st.extendedMin * 60_000 : 0;
  const overBox = boxMs > 0 && over > boxMs;

  function pauseResume() {
    const t = Date.now();
    setNow(t);
    setSt((c) => (c.pausedAt === null ? { ...c, pausedAt: t } : { ...c, pausedAt: null, pausedTotal: c.pausedTotal + (t - c.pausedAt) }));
  }
  function extend() {
    // First extension creates the time box: whole minutes elapsed so far, plus 10. Later ones add 10 more.
    const t = Date.now();
    setNow(t);
    setSt((c) => ({ ...c, extendedMin: c.extendedMin > 0 ? c.extendedMin + 10 : Math.ceil(elapsedMs(c, t) / 60_000) + 10 }));
  }

  const done = ended || st.endedAt !== null;
  return (
    <div className={s.timer} role="group" aria-label="Consultation timer">
      <Icon name="clock" size={14} />
      <span className={`${s.time} ${overBox ? s.timeOver : ""}`} role="timer" aria-live="off">
        {fmt(over)}{boxMs > 0 ? ` / ${st.extendedMin} min` : ""}
      </span>
      {done ? <span className={`${s.timerNote} ${s.timerNoteInline}`}>Ended</span> : paused ? <span className={`${s.timerNote} ${s.timerNoteInline}`}>Paused</span> : overBox ? <span className={`${s.timerNote} ${s.timerNoteInline}`}>Over your time box</span> : null}
      {!done && (
        <>
          <button type="button" className={s.timerBtn} onClick={pauseResume}>{paused ? "Resume" : "Pause"}</button>
          <button type="button" className={s.timerBtn} onClick={extend}><Icon name="plus" size={12} /> Extend 10 min</button>
        </>
      )}
      <p className={s.timerNote}>Private timer for this tab. Not saved to the record; waiting times are not changed.</p>
    </div>
  );
}
