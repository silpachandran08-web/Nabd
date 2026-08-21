"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./nursing.module.css";

// NB-142: nurse landing screen — the pending-vitals subset of GET /v1/queue (QueueController),
// same data /arrivals shows but scoped to just this one worklist.
type QueueEntry = {
  id: string; patientId: string; doctorId: string; tokenNumber: number; status: string; createdAt: string;
};
type Row = QueueEntry & { patientName: string; patientMrn: string; doctorName: string };
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const POLL_MS = 15_000;

function waitMinutes(createdAt: string): number {
  return Math.max(0, Math.round((Date.now() - new Date(createdAt).getTime()) / 60000));
}

export default function NursingPage() {
  const router = useRouter();
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [, setNow] = useState(() => Date.now());

  const [vitalsEntryId, setVitalsEntryId] = useState<string | null>(null);
  const [vitalsForm, setVitalsForm] = useState({ heightCm: "", weightKg: "", bpSystolic: "", bpDiastolic: "", pulseBpm: "", tempCelsius: "", spo2Percent: "" });
  const [vitalsError, setVitalsError] = useState<string | null>(null);
  const [vitalsSubmitting, setVitalsSubmitting] = useState(false);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(async () => {
    setError(null);
    try {
      const [queueRes, staffRes] = await Promise.all([authedFetch("/queue"), authedFetch("/staff?limit=100")]);
      if (!queueRes) return;
      if (queueRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!queueRes.ok) {
        setError("Couldn't load the vitals worklist. Try again.");
        return;
      }
      const entries: QueueEntry[] = await queueRes.json();
      const staffMap = new Map<string, string>();
      if (staffRes?.ok) {
        const staffBody = await staffRes.json();
        staffBody.data.forEach((s: { id: string; name: string }) => staffMap.set(s.id, s.name));
      }
      const pending = entries.filter((e) => e.status === "vitals_pending");
      const withNames = await Promise.all(pending.map(async (e) => {
        const pRes = await authedFetch(`/patients/${e.patientId}`);
        const p = pRes?.ok ? await pRes.json() : null;
        return { ...e, patientName: p?.name ?? "Unknown patient", patientMrn: p?.mrn ?? "", doctorName: staffMap.get(e.doctorId) ?? "—" };
      }));
      setRows(withNames);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  // "updates live with queue state" (NB-142's acceptance bar) — polling, same as /arrivals (NB-095);
  // push-based sync is NB-096, a separate ticket.
  useEffect(() => {
    const id = setInterval(load, POLL_MS);
    return () => clearInterval(id);
  }, [load]);

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);

  function openVitalsModal(entryId: string) {
    setVitalsEntryId(entryId);
    setVitalsForm({ heightCm: "", weightKg: "", bpSystolic: "", bpDiastolic: "", pulseBpm: "", tempCelsius: "", spo2Percent: "" });
    setVitalsError(null);
  }

  async function submitVitals(e: React.FormEvent) {
    e.preventDefault();
    if (!vitalsEntryId) return;
    setVitalsError(null);
    setVitalsSubmitting(true);
    try {
      const num = (v: string) => (v.trim() === "" ? null : Number(v));
      const res = await authedFetch(`/clinical/vitals/${vitalsEntryId}`, {
        method: "PATCH",
        body: JSON.stringify({
          heightCm: num(vitalsForm.heightCm), weightKg: num(vitalsForm.weightKg),
          bpSystolic: num(vitalsForm.bpSystolic), bpDiastolic: num(vitalsForm.bpDiastolic),
          pulseBpm: num(vitalsForm.pulseBpm), tempCelsius: num(vitalsForm.tempCelsius), spo2Percent: num(vitalsForm.spo2Percent),
        }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't save vitals." }));
        setVitalsError(p.detail || "Couldn't save vitals.");
        return;
      }
      setVitalsEntryId(null);
      load();
    } finally {
      setVitalsSubmitting(false);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Nursing Worklist</h1>
        <p className={styles.subtitle}>Patients waiting on vitals, across every doctor today.</p>
      </div>

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to the queue.</div>
        ) : error ? (
          <div className={styles.errorState}>{error}</div>
        ) : rows.length === 0 ? (
          <div className={styles.state}>Nobody is waiting on vitals right now.</div>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th>Token</th><th>Patient</th><th>Doctor</th><th>Wait</th><th>Status</th><th></th></tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td>#{r.tokenNumber}</td>
                    <td><span className={styles.patientName}>{r.patientName}</span><span className={styles.mrn}>{r.patientMrn}</span></td>
                    <td>{r.doctorName}</td>
                    <td className={styles.muted}>{waitMinutes(r.createdAt)}m</td>
                    <td><span className={styles.pillVitals}>vitals pending</span></td>
                    <td><button className={styles.actionBtn} onClick={() => openVitalsModal(r.id)}>Record vitals</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {vitalsEntryId && (
        <div className={styles.overlay} onClick={() => setVitalsEntryId(null)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitVitals}>
            <h2 className={styles.modalTitle}>Record vitals</h2>
            {([
              ["heightCm", "Height (cm)"], ["weightKg", "Weight (kg)"],
              ["bpSystolic", "BP systolic"], ["bpDiastolic", "BP diastolic"],
              ["pulseBpm", "Pulse (bpm)"], ["tempCelsius", "Temp (°C)"], ["spo2Percent", "SpO2 (%)"],
            ] as const).map(([key, label]) => (
              <div className={styles.field} key={key}>
                <label className={styles.label} htmlFor={key}>{label}</label>
                <input
                  id={key}
                  type="number"
                  step="any"
                  className={styles.input}
                  value={vitalsForm[key]}
                  onChange={(e) => setVitalsForm((prev) => ({ ...prev, [key]: e.target.value }))}
                />
              </div>
            ))}
            {vitalsError && <div className={styles.formError} role="alert">{vitalsError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setVitalsEntryId(null)}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={vitalsSubmitting}>{vitalsSubmitting ? "Saving…" : "Save vitals"}</button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}
