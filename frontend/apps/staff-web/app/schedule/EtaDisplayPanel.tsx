"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../components/ui/Dialog";
import { ErrorState, Skeleton } from "../components/ui/StateViews";
import styles from "./schedule.module.css";
import { api, errorText } from "../lib/api";
import { ETA_MODES, SOURCE_LABEL, accuracyWarning, doctorAccuracy, modeLabel, type AccuracyRow } from "../lib/etaDisplay";

type SessionRow = { workingHoursId: string; dayOfWeek: number; startTime: string; endTime: string; etaDisplayMode: string | null };
type Settings = { etaDisplayMode: string | null; facilityDefault: string; effectiveMode: string; effectiveSource: string; sessions: SessionRow[] };

const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const FACILITY = "/queue-settings/facility";

/** ACC-018: "Time shown to patients" per doctor and per session, plus the clinic default. setup:edit only (the caller hides the button). */
export default function EtaDisplayPanel({ doctors, onClose }: { doctors: { id: string; name: string }[]; onClose: () => void }) {
  const [doctorId, setDoctorId] = useState(doctors[0]?.id ?? "");
  const [settings, setSettings] = useState<Settings | null>(null);
  const [accuracy, setAccuracy] = useState<AccuracyRow[]>([]);
  const [state, setState] = useState<"loading" | "error" | "ready">("loading");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!doctorId) { setState("ready"); return; }
    setState("loading");
    try {
      setSettings(await api.get<Settings>(`/doctors/${doctorId}/queue-settings`));
      setState("ready");
    } catch { setState("error"); }
  }, [doctorId]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useEffect(() => { // the accuracy warning is best effort: no reports:view or no data just means no warning
    api.get<{ items?: AccuracyRow[] }>("/reports/eta-accuracy").then((b) => setAccuracy(b.items ?? [])).catch(() => setAccuracy([]));
  }, []);

  async function save(body: { etaDisplayMode: string | null; workingHoursId?: string }) {
    setBusy(true); setMsg(null);
    try { await api.put(`/doctors/${doctorId}/queue-settings`, body); await load(); }
    catch (e) { setMsg(errorText(e, "Couldn't save the display mode.")); }
    finally { setBusy(false); }
  }
  async function saveFacility(value: string) {
    setBusy(true); setMsg(null);
    try { await api.put(FACILITY, { etaDisplayMode: value }); await load(); }
    catch (e) { setMsg(errorText(e, "Couldn't save the clinic default.")); }
    finally { setBusy(false); }
  }

  const warn = accuracyWarning(settings?.effectiveMode, doctorAccuracy(accuracy, doctorId));
  const pick = (v: string) => (v === "" ? null : v);

  return (
    <Dialog title="Time shown to patients" onClose={onClose}>
      <p className={styles.dialogSub}>Choose what patients see about their waiting time. A session setting beats the doctor&apos;s, which beats the clinic default.</p>
      {doctors.length === 0 ? <div className={styles.empty}>No doctors yet.</div> : (
        <>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="eta-doctor">Doctor</label>
            <select id="eta-doctor" className={styles.input} value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
              {doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>
          {state === "loading" && <Skeleton rows={3} label="Loading display settings" />}
          {state === "error" && <ErrorState message="Couldn't load the display settings." onRetry={() => void load()} />}
          {state === "ready" && settings && (
            <>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="eta-clinic">Clinic default</label>
                <select id="eta-clinic" className={styles.input} value={settings.facilityDefault} disabled={busy} onChange={(e) => void saveFacility(e.target.value)}>
                  {ETA_MODES.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
                </select>
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="eta-mode">This doctor</label>
                <select id="eta-mode" className={styles.input} value={settings.etaDisplayMode ?? ""} disabled={busy} onChange={(e) => void save({ etaDisplayMode: pick(e.target.value) })}>
                  <option value="">Use the clinic default</option>
                  {ETA_MODES.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
                </select>
              </div>
              {settings.sessions.map((s) => (
                <div className={styles.field} key={s.workingHoursId}>
                  <label className={styles.label} htmlFor={`eta-s-${s.workingHoursId}`}>{DAYS[s.dayOfWeek]} {s.startTime.slice(0, 5)}–{s.endTime.slice(0, 5)} session</label>
                  <select id={`eta-s-${s.workingHoursId}`} className={styles.input} value={s.etaDisplayMode ?? ""} disabled={busy}
                    onChange={(e) => void save({ etaDisplayMode: pick(e.target.value), workingHoursId: s.workingHoursId })}>
                    <option value="">Same as the doctor</option>
                    {ETA_MODES.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
                  </select>
                </div>
              ))}
              <p className={styles.dialogSub} data-testid="eta-effective">
                Patients now see: {modeLabel(settings.effectiveMode)} ({SOURCE_LABEL[settings.effectiveSource] ?? settings.effectiveSource}). Example: {ETA_MODES.find((m) => m.value === settings.effectiveMode)?.example}
              </p>
              {warn && <div className={styles.banner} role="alert" data-testid="eta-exact-warning">{warn}</div>}
            </>
          )}
          {msg && <div className={styles.banner} role="alert">{msg}</div>}
        </>
      )}
      <div className={styles.actions}><button type="button" className={styles.btn} onClick={onClose}>Close</button></div>
    </Dialog>
  );
}
