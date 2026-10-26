"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./myDay.module.css";
import StatusPill from "../ui/StatusPill";
import { apiFetch } from "../../lib/apiClient";
import { EXTEND_MINUTES, problemMessage, sessionActions, sessionLabel, type SessionAction, type SessionState } from "../../lib/doctorSession";

const CALL_REASON: Record<string, string> = {
  priority: "flagged priority", booked_on_time: "booked, on time", interleave: "walk-in turn", reserved_slot: "reserved walk-in slot",
};

// ACC-015: Start / Pause / Resume / End session and "Extend" on the consult in the room. Rendered only
// for a caller with queue:edit (the page decides); the server also checks it is the doctor's own session.
export default function DoctorControls({ doctorId, inRoomId, onChanged }: { doctorId: string; inRoomId: string | null; onChanged: () => void }) {
  const [state, setState] = useState<SessionState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [called, setCalled] = useState<string | null>(null);
  const headers = () => ({ Authorization: `Bearer ${localStorage.getItem("nabd_access_token") ?? ""}` });

  const load = useCallback(async () => {
    try {
      const res = await apiFetch(`/doctors/${doctorId}/session`, { headers: headers() });
      if (!res.ok) throw new Error();
      setState(((await res.json()) as { state: SessionState }).state);
      setError(null);
    } catch {
      setError("Couldn't load your session status.");
    }
  }, [doctorId]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const post = async (path: string, body: unknown, done: (j: { state?: SessionState }) => void, fallback: string) => {
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(path, { method: "POST", body: JSON.stringify(body), headers: { ...headers(), "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() } });
      if (!res.ok) { setError(await problemMessage(res, fallback)); return; }
      done(await res.json());
    } catch {
      setError(fallback);
    } finally {
      setBusy(false);
    }
  };
  const act = (a: SessionAction) => post(`/doctors/${doctorId}/session/${a}`, {}, (j) => { if (j.state) setState(j.state); onChanged(); }, "Couldn't update your session. Try again.");
  const extend = (m: number) => inRoomId && post(`/queue/${inRoomId}/extend`, { minutes: m }, () => onChanged(), "Couldn't extend the consult. Try again.");

  // ACC-012: one button applies the clinic's call order; the answer says who and why.
  const callNext = () => post(`/queue/doctors/${doctorId}/call-next`, {}, (j) => {
    const r = j as unknown as { entry?: { tokenNumber?: number }; reason?: string };
    setCalled(`Called token #${r.entry?.tokenNumber ?? "?"}: ${CALL_REASON[r.reason ?? ""] ?? "next in order"}`);
    onChanged();
  }, "Couldn't call the next patient. Try again.");

  return (
    <section className={`${styles.card} ${styles.controls}`} aria-label="Session controls">
      <button type="button" className={styles.ctl} disabled={busy} onClick={() => void callNext()}>Call next</button>
      {called && <span role="status">{called}</span>}
      {state && <StatusPill tone={state === "in" ? "ok" : state === "paused" ? "warn" : "neutral"} label={sessionLabel(state)} />}
      {state && sessionActions(state).map(({ action, label }) => (
        <button key={action} type="button" className={styles.ctl} disabled={busy} onClick={() => void act(action)}>{label}</button>
      ))}
      {inRoomId && EXTEND_MINUTES.map((m) => (
        <button key={m} type="button" className={styles.ctl} disabled={busy} onClick={() => void extend(m)}>Extend {m} min</button>
      ))}
      {error && <div role="alert" className={styles.ctlError}>{error}</div>}
    </section>
  );
}
