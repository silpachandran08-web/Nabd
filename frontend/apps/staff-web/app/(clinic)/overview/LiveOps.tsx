"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import type { AppPermission } from "@nabd/api-client/types";
import styles from "./liveOps.module.css";
import Dialog from "../../components/ui/Dialog";
import StatusPill from "../../components/ui/StatusPill";
import { api, ApiError, errorText } from "../../lib/api";
import { useLiveRefresh } from "../../lib/live";
import { can } from "../../lib/permissions";
import {
  LIVE_OPS_RESOURCE, REFRESH_MS, THRESHOLD_FIELDS, alertActions, alertLabel, alertTone, boardEmpty, broadcastConfirmText, broadcastDoneText,
  doctorLine, stationLine, statusLabel, statusTone, thresholdErrors, toForm, toThresholds,
  type ApiCall, type LiveAlert, type LiveOps, type OpsThresholds, type ThresholdForm,
} from "./liveOpsModel";

type State = { kind: "loading" } | { kind: "error" } | { kind: "forbidden" } | { kind: "ready"; data: LiveOps; stale: boolean };
type Notice = { tone: "ok" | "warn"; text: string } | null;

/**
 * ADM-042: live operations on the owner overview. Doctors in session and who is late, waits at the pharmacy and in the booking
 * requests, and alerts with their actions. Alerts arrive on the SSE topic `ops` (a delta re-reads the board); the board also
 * re-reads every 15 s while the tab is visible. reports:view only; an action or the settings the viewer has no grant for is absent.
 */
export default function LiveOpsSection() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [notice, setNotice] = useState<Notice>(null);
  const [confirm, setConfirm] = useState<{ alert: LiveAlert; call: ApiCall } | null>(null);
  const [settings, setSettings] = useState(false);
  const seq = useRef(0);

  const load = useCallback(async () => {
    const my = ++seq.current;
    try {
      const data = await api.get<LiveOps>("/reports/live");
      if (my === seq.current) setState({ kind: "ready", data, stale: false });
    } catch (e) {
      if (my !== seq.current || (e instanceof ApiError && e.status === 401)) return;
      // a failed re-read keeps the last board on screen, marked as not current
      setState((s) => (e instanceof ApiError && e.status === 403 ? { kind: "forbidden" } : s.kind === "ready" ? { ...s, stale: true } : { kind: "error" }));
    }
  }, []);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useLiveRefresh([LIVE_OPS_RESOURCE], load);
  useEffect(() => {
    const id = setInterval(() => { if (document.visibilityState === "visible") void load(); }, REFRESH_MS);
    const onVisible = () => { if (document.visibilityState === "visible") void load(); };
    document.addEventListener("visibilitychange", onVisible);
    return () => { clearInterval(id); document.removeEventListener("visibilitychange", onVisible); };
  }, [load]);

  if (!can("reports:view") || state.kind === "forbidden") return null; // absent, not disabled

  const data = state.kind === "ready" ? state.data : null;
  const allowed = (p: string) => can(p as AppPermission);
  const nameOf = (id: string | null) => data?.doctors.find((d) => d.id === id)?.name ?? null;

  return (
    <section className={styles.section} aria-label="Live operations" aria-busy={state.kind === "loading"}>
      <div className={styles.head}>
        <h2 className={styles.heading}>Live now</h2>
        {data && can("setup:edit") && <button type="button" className={styles.secondary} onClick={() => setSettings(true)}>Alert settings</button>}
      </div>
      {state.kind === "loading" && <div className={styles.skel} role="status" aria-label="Loading live operations" />}
      {state.kind === "error" && (
        <div className={styles.error} role="alert">Couldn&apos;t load live operations. <button type="button" className={styles.retry} onClick={() => void load()}>Try again</button></div>
      )}
      {state.kind === "ready" && state.stale && (
        <div className={styles.error} role="alert">These figures may be out of date: the last refresh failed. <button type="button" className={styles.retry} onClick={() => void load()}>Try again</button></div>
      )}
      {notice && <div className={styles.notice} role="status">{notice.text}</div>}

      {data && (
        <>
          <ul className={styles.alerts} aria-label="Alerts" aria-live="polite">
            {data.alerts.map((a) => (
              <li key={`${a.code}:${a.doctorId ?? ""}`} className={styles.alert} data-testid={`ops-alert-${a.code}`}>
                <div className={styles.alertBody}>
                  <StatusPill tone={alertTone(a)} label={alertLabel(a)} />
                  <span>{a.summary}</span>
                </div>
                <div className={styles.actions}>
                  {alertActions(a, allowed).map((act) => (act.kind === "link"
                    ? <Link key={act.label} href={act.href} className={styles.secondary}>{act.label}</Link>
                    : <button key={act.label} type="button" className={styles.primary} onClick={() => { setNotice(null); setConfirm({ alert: a, call: act.call }); }}>{act.label}</button>))}
                </div>
              </li>
            ))}
          </ul>
          {boardEmpty(data) ? <p className={styles.meta}>Nothing is waiting right now. Doctors, the pharmacy and booking requests appear here as the day starts.</p> : (
            <div className={styles.grid}>
              <section className={styles.panel} aria-label="Doctors today">
                <h3 className={styles.panelTitle}>Doctors today</h3>
                {data.doctors.length === 0 ? <p className={styles.meta}>No doctor has patients or a session today.</p> : (
                  <ul className={styles.rows}>
                    {data.doctors.map((d) => (
                      <li key={d.id} className={styles.row}>
                        <span className={styles.rowMain}><bdi className={styles.name}>{d.name ?? "Doctor"}</bdi><span className={styles.meta}>{doctorLine(d)}</span></span>
                        <StatusPill tone={statusTone(d)} label={statusLabel(d.status)} />
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <section className={styles.panel} aria-label="Waits by station">
                <h3 className={styles.panelTitle}>Waits by station</h3>
                <div className={styles.row}><span className={styles.name}>Pharmacy</span><span className={styles.meta}>{stationLine(data.pharmacy, "prescriptions")} · target {data.thresholds.pharmacyWaitMin} min</span></div>
                <div className={styles.row}><span className={styles.name}>Booking requests</span><span className={styles.meta}>{stationLine(data.requests, "requests")} · alert over {data.thresholds.requestWaitMin} min</span></div>
              </section>
            </div>
          )}
        </>
      )}

      {confirm && (
        <BroadcastDialog text={broadcastConfirmText(confirm.alert, nameOf(confirm.alert.doctorId))} call={confirm.call} onClose={() => setConfirm(null)}
          onDone={(n) => { setConfirm(null); setNotice(n); void load(); }} />
      )}
      {settings && data && (
        <ThresholdsDialog current={data.thresholds} onClose={() => setSettings(false)}
          onSaved={() => { setSettings(false); setNotice({ tone: "ok", text: "Alert settings saved. They apply from the next check." }); void load(); }} />
      )}
    </section>
  );
}

function BroadcastDialog({ text, call, onClose, onDone }: { text: string; call: ApiCall; onClose: () => void; onDone: (n: Notice) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function send() {
    setBusy(true); setError(null);
    try {
      const res = call.method === "patch" ? await api.patch<{ recipients?: number }>(call.path, call.body) : await api.post<{ recipients?: number }>(call.path, call.body);
      onDone({ tone: "ok", text: broadcastDoneText(res?.recipients) });
    } catch (e) {
      // someone announced or cleared the delay in between: the board is re-read, nothing is sent twice
      if (e instanceof ApiError && e.status === 409) onDone({ tone: "warn", text: "This doctor's delay was just changed by someone else. The board is refreshed; check it before sending again." });
      else { setError(errorText(e, "Couldn't send the delay notice. Nothing was sent.")); setBusy(false); }
    }
  }
  return (
    <Dialog title="Broadcast delay" onClose={onClose}>
      <p className={styles.meta}>{text}</p>
      {error && <div className={styles.error} role="alert">{error}</div>}
      <div className={styles.dialogActions}>
        <button type="button" className={styles.secondary} onClick={onClose}>Cancel</button>
        <button type="button" className={styles.primary} disabled={busy} onClick={() => void send()}>{busy ? "Sending…" : "Send delay notice"}</button>
      </div>
    </Dialog>
  );
}

function ThresholdsDialog({ current, onClose, onSaved }: { current: OpsThresholds; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<ThresholdForm>(() => toForm(current));
  const [shown, setShown] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const errors = thresholdErrors(form);
  async function save(e: React.FormEvent) {
    e.preventDefault();
    setShown(true);
    if (Object.keys(errors).length) return;
    setBusy(true); setError(null);
    try {
      await api.put("/reports/live/thresholds", toThresholds(form, current));
      onSaved();
    } catch (err) {
      setError(err instanceof ApiError && err.status === 400 ? "Enter whole minutes from 1 to 240 in every field." : errorText(err, "Couldn't save the alert settings. Nothing changed."));
      setBusy(false);
    }
  }
  return (
    <Dialog title="Alert settings" onClose={onClose}>
      <form onSubmit={save} noValidate>
        <p className={styles.meta}>When the live board raises an alert. Changes apply from the next check, within seconds.</p>
        {THRESHOLD_FIELDS.map(({ key, label }) => (
          <label key={key} className={styles.field}>{label}
            <input type="number" inputMode="numeric" min={1} max={240} step={1} value={form[key]} aria-invalid={shown && errors[key] ? true : undefined}
              aria-describedby={shown && errors[key] ? `ops-${key}-error` : undefined} onChange={(e) => setForm((f) => ({ ...f, [key]: e.target.value }))} />
            {shown && errors[key] && <span id={`ops-${key}-error`} className={styles.error} role="alert">{errors[key]}</span>}
          </label>
        ))}
        {error && <div className={styles.error} role="alert">{error}</div>}
        <div className={styles.dialogActions}>
          <button type="button" className={styles.secondary} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.primary} disabled={busy}>{busy ? "Saving…" : "Save"}</button>
        </div>
      </form>
    </Dialog>
  );
}
