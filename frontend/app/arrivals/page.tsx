"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./arrivals.module.css";

// Matches GET /v1/queue (QueueController), GET /v1/patients (PatientController),
// GET /v1/staff (StaffController), POST /v1/queue/check-in, POST /v1/queue/{id}/reorder.
type QueueEntry = {
  id: string; appointmentId: string | null; patientId: string; doctorId: string;
  queueDate: string; tokenNumber: number; status: string; priority: boolean; priorityReason: string | null;
  createdAt: string;
};
type Row = QueueEntry & { patientName: string; patientMrn: string };
type PatientOption = { id: string; name: string; phone: string; mrn: string };
type StaffOption = { id: string; name: string };
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const POLL_MS = 15_000;
// NB-143: a small fixed reason-code catalogue for this one call site — not NB-011's generic
// shared reason-code service (doesn't exist), same "skip the generic service" trick used all
// session for NB-008/NB-010.
const PRIORITY_REASON_CODES = [
  "Chest pain / cardiac symptoms", "Breathing difficulty", "Severe bleeding",
  "High fever in infant", "Post-operative complication", "Severe pain", "Other",
] as const;

const STATUS_CLASS: Record<string, string> = {
  checked_in: styles.pillWaiting,
  waiting: styles.pillWaiting,
  vitals_pending: styles.pillVitals,
  vitals_done: styles.pillReady,
  in_consult: styles.pillConsult,
  checkout_pending: styles.pillDone,
  completed: styles.pillDone,
  no_show: styles.pillNoShow,
};

type Bucket = "waiting" | "in_consult" | "checkout_pending" | "completed" | "other";
function bucketOf(status: string): Bucket {
  if (["checked_in", "waiting", "vitals_pending", "vitals_done"].includes(status)) return "waiting";
  if (status === "in_consult") return "in_consult";
  if (status === "checkout_pending") return "checkout_pending";
  if (status === "completed") return "completed";
  return "other";
}

const TABS: { key: "all" | Bucket; label: string }[] = [
  { key: "all", label: "All" },
  { key: "waiting", label: "Waiting" },
  { key: "in_consult", label: "In consultation" },
  { key: "checkout_pending", label: "Checkout pending" },
  { key: "completed", label: "Completed" },
];

function waitMinutes(createdAt: string): number {
  return Math.max(0, Math.round((Date.now() - new Date(createdAt).getTime()) / 60000));
}

export default function ArrivalsPage() {
  const router = useRouter();
  const [rows, setRows] = useState<Row[]>([]);
  const [staff, setStaff] = useState<StaffOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [tab, setTab] = useState<"all" | Bucket>("all");
  const [, setNow] = useState(() => Date.now());
  // Computed client-side only — server and browser locale defaults for toLocaleDateString can
  // differ, and doing this during the initial render causes a hydration mismatch.
  const [dateLabel, setDateLabel] = useState("");

  const [vitalsEntryId, setVitalsEntryId] = useState<string | null>(null);
  const [vitalsForm, setVitalsForm] = useState({ heightCm: "", weightKg: "", bpSystolic: "", bpDiastolic: "", pulseBpm: "", tempCelsius: "", spo2Percent: "" });
  const [vitalsError, setVitalsError] = useState<string | null>(null);
  const [vitalsSubmitting, setVitalsSubmitting] = useState(false);

  const [priorityEntryId, setPriorityEntryId] = useState<string | null>(null);
  const [priorityReasonCode, setPriorityReasonCode] = useState<string>(PRIORITY_REASON_CODES[0]);
  const [priorityOtherReason, setPriorityOtherReason] = useState("");
  const [prioritySubmitting, setPrioritySubmitting] = useState(false);

  const [showModal, setShowModal] = useState(false);
  const [patientQuery, setPatientQuery] = useState("");
  const [searchResults, setSearchResults] = useState<PatientOption[]>([]);
  const [selectedPatient, setSelectedPatient] = useState<PatientOption | null>(null);
  const [newPatientMode, setNewPatientMode] = useState(false);
  const [newName, setNewName] = useState("");
  const [newPhone, setNewPhone] = useState("");
  const [newDob, setNewDob] = useState("");
  const [newGender, setNewGender] = useState("male");
  const [doctorId, setDoctorId] = useState("");
  const [modalError, setModalError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const [waitEstimates, setWaitEstimates] = useState<Record<string, number>>({});
  const [delays, setDelays] = useState<Record<string, { delayMinutes: number; reason: string | null } | null>>({});
  const [delayFormFor, setDelayFormFor] = useState<string | null>(null);
  const [delayMinutesInput, setDelayMinutesInput] = useState("15");
  const [delayReasonInput, setDelayReasonInput] = useState("");
  const [delayBusy, setDelayBusy] = useState(false);

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
        setError("Couldn't load today's arrivals. Try again.");
        return;
      }
      const entries: QueueEntry[] = await queueRes.json();
      const staffMap = new Map<string, string>();
      let doctorIds: string[] = [];
      if (staffRes?.ok) {
        const staffBody = await staffRes.json();
        setStaff(staffBody.data.map((s: { id: string; name: string }) => ({ id: s.id, name: s.name })));
        staffBody.data.forEach((s: { id: string; name: string }) => staffMap.set(s.id, s.name));
        doctorIds = staffBody.data.map((s: { id: string }) => s.id);
      }
      const withNames = await Promise.all(entries.map(async (e) => {
        const pRes = await authedFetch(`/patients/${e.patientId}`);
        const p = pRes?.ok ? await pRes.json() : null;
        return { ...e, patientName: p?.name ?? "Unknown patient", patientMrn: p?.mrn ?? "" };
      }));
      setRows(withNames);

      // NB-101/NB-100: per-doctor wait estimate and any active delay, for the sidebar.
      const estimateEntries = await Promise.all(doctorIds.map(async (id: string) => {
        const res = await authedFetch(`/queue/wait-estimate?doctorId=${id}`);
        return [id, res?.ok ? (await res.json()).estimatedMinutes : null] as const;
      }));
      setWaitEstimates(Object.fromEntries(estimateEntries.filter(([, v]) => v !== null)));

      const delayEntries = await Promise.all(doctorIds.map(async (id: string) => {
        const res = await authedFetch(`/doctors/${id}/delay`);
        if (!res?.ok) return [id, null] as const;
        const history: { active: boolean; delayMinutes: number; reason: string | null }[] = await res.json();
        const active = history.find((h) => h.active);
        return [id, active ? { delayMinutes: active.delayMinutes, reason: active.reason } : null] as const;
      }));
      setDelays(Object.fromEntries(delayEntries));
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  // NB-095: "live-updates without refresh" via polling — full push-based sync is NB-096, separate.
  useEffect(() => {
    const id = setInterval(load, POLL_MS);
    return () => clearInterval(id);
  }, [load]);

  // Re-render the "Wait" column once a minute without waiting on the full poll.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    // Deferred to a microtask so the effect body itself never calls setState synchronously.
    void Promise.resolve().then(() => {
      setDateLabel(new Date().toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" }));
    });
  }, []);

  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  function onPatientQueryChange(value: string) {
    setPatientQuery(value);
    setSelectedPatient(null);
    setNewPatientMode(false);
    if (searchTimer.current) clearTimeout(searchTimer.current);
    if (value.trim().length < 2) {
      setSearchResults([]);
      return;
    }
    searchTimer.current = setTimeout(async () => {
      const res = await authedFetch(`/patients?q=${encodeURIComponent(value.trim())}`);
      if (res?.ok) setSearchResults((await res.json()).data);
    }, 300);
  }

  function openModal() {
    setShowModal(true);
    setPatientQuery("");
    setSearchResults([]);
    setSelectedPatient(null);
    setNewPatientMode(false);
    setNewName("");
    setNewPhone("");
    setNewDob("");
    setNewGender("male");
    setDoctorId("");
    setModalError(null);
  }

  async function submitCheckIn(e: React.FormEvent) {
    e.preventDefault();
    setModalError(null);
    if (!doctorId) {
      setModalError("Pick a doctor.");
      return;
    }
    setSubmitting(true);
    try {
      let patientId = selectedPatient?.id ?? null;
      if (!patientId) {
        if (!newName || !newPhone || !newDob) {
          setModalError("Fill in the new patient's name, phone and date of birth.");
          return;
        }
        const res = await authedFetch("/patients", {
          method: "POST",
          body: JSON.stringify({ name: newName, phone: newPhone, dob: newDob, gender: newGender }),
        });
        if (!res) return;
        if (res.status === 409) {
          setModalError("A similar patient already exists — search for them above instead of registering again.");
          return;
        }
        if (!res.ok) {
          const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't register patient." }));
          setModalError(p.detail || "Couldn't register patient.");
          return;
        }
        patientId = (await res.json()).id;
      }

      const ciRes = await authedFetch("/queue/check-in", {
        method: "POST",
        body: JSON.stringify({ patientId, doctorId }),
      });
      if (!ciRes) return;
      if (!ciRes.ok) {
        const p: Problem = await ciRes.json().catch(() => ({ title: "Error", detail: "Couldn't check in." }));
        setModalError(p.detail || "Couldn't check in.");
        return;
      }
      setShowModal(false);
      load();
    } finally {
      setSubmitting(false);
    }
  }

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

  function openPriorityModal(id: string) {
    setPriorityEntryId(id);
    setPriorityReasonCode(PRIORITY_REASON_CODES[0]);
    setPriorityOtherReason("");
  }

  async function submitPriority(e: React.FormEvent) {
    e.preventDefault();
    if (!priorityEntryId) return;
    const reason = priorityReasonCode === "Other" ? priorityOtherReason.trim() : priorityReasonCode;
    if (!reason) return;
    setPrioritySubmitting(true);
    try {
      const res = await authedFetch(`/queue/${priorityEntryId}/reorder`, { method: "POST", body: JSON.stringify({ priority: true, reason }) });
      if (res?.ok) {
        setPriorityEntryId(null);
        load();
      }
    } finally {
      setPrioritySubmitting(false);
    }
  }

  async function submitDelay(e: React.FormEvent) {
    e.preventDefault();
    if (!delayFormFor) return;
    setDelayBusy(true);
    try {
      const res = await authedFetch(`/doctors/${delayFormFor}/delay`, {
        method: "POST",
        body: JSON.stringify({ delayMinutes: Number(delayMinutesInput), reason: delayReasonInput || null }),
      });
      if (res?.ok) {
        setDelayFormFor(null);
        setDelayReasonInput("");
        load();
      }
    } finally {
      setDelayBusy(false);
    }
  }

  async function clearDelay(doctorId: string) {
    const res = await authedFetch(`/doctors/${doctorId}/delay/clear`, { method: "POST" });
    if (res?.ok) load();
  }

  const filtered = tab === "all" ? rows : rows.filter((r) => bucketOf(r.status) === tab);
  const counts: Record<string, number> = { all: rows.length };
  (["waiting", "in_consult", "checkout_pending", "completed"] as Bucket[]).forEach((b) => {
    counts[b] = rows.filter((r) => bucketOf(r.status) === b).length;
  });

  const doctorQueues = staff
    .map((s) => ({
      ...s,
      waiting: rows.filter((r) => r.doctorId === s.id && bucketOf(r.status) === "waiting").length,
      estimatedMinutes: waitEstimates[s.id],
      delay: delays[s.id] ?? null,
    }))
    .filter((s) => s.waiting > 0 || s.delay)
    .sort((a, b) => b.waiting - a.waiting);

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Today · Arrivals</h1>
          <p className={styles.subtitle}>{dateLabel} · {rows.length} on the board</p>
        </div>
        {!forbidden && (
          <div style={{ display: "flex", gap: "var(--nb-space-8)" }}>
            <button className={styles.registerBtn} style={{ background: "transparent", color: "var(--nb-text-primary)", border: "1px solid var(--nb-border-default)" }}
              onClick={() => router.push("/checkout/otc")}>
              Counter sale
            </button>
            <button className={styles.registerBtn} onClick={openModal}>Register walk-in</button>
          </div>
        )}
      </div>

      {!forbidden && (
        <div className={styles.tabs}>
          {TABS.map((t) => (
            <button key={t.key} className={tab === t.key ? styles.tabActive : styles.tab} onClick={() => setTab(t.key)}>
              {t.label} {counts[t.key] !== undefined ? `(${counts[t.key]})` : ""}
            </button>
          ))}
        </div>
      )}

      {loading ? (
        <div className={styles.card}><div className={styles.state}>Loading…</div></div>
      ) : forbidden ? (
        <div className={styles.card}><div className={styles.state}>Your role doesn&apos;t have access to the arrivals board.</div></div>
      ) : error ? (
        <div className={styles.card}><div className={styles.errorState}>{error}</div></div>
      ) : (
        <div className={styles.layout}>
          <div className={styles.card}>
            {filtered.length === 0 ? (
              <div className={styles.state}>No patients here yet.</div>
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr><th>Token</th><th>Patient</th><th>Doctor</th><th>Wait</th><th>Status</th><th></th></tr>
                  </thead>
                  <tbody>
                    {filtered.map((r) => {
                      const b = bucketOf(r.status);
                      return (
                        <tr key={r.id}>
                          <td className={styles.token}>{r.priority && <span className={styles.priorityDot} />}#{r.tokenNumber}</td>
                          <td><span className={styles.patientName}>{r.patientName}</span><span className={styles.mrn}>{r.patientMrn}</span></td>
                          <td>{staff.find((s) => s.id === r.doctorId)?.name ?? "—"}</td>
                          <td className={styles.muted}>{b === "waiting" || b === "in_consult" ? `${waitMinutes(r.createdAt)}m` : "—"}</td>
                          <td><span className={`${styles.pill} ${STATUS_CLASS[r.status] ?? ""}`}>{r.status.replace("_", " ")}</span></td>
                          <td>
                            {!r.priority && b === "waiting" && (
                              <button className={styles.actionBtn} onClick={() => openPriorityModal(r.id)}>Mark priority</button>
                            )}
                            {r.status === "vitals_pending" && (
                              <button className={styles.actionBtn} onClick={() => openVitalsModal(r.id)}>Record vitals</button>
                            )}
                            {b === "checkout_pending" && (
                              <button className={styles.actionBtn} onClick={() => router.push(`/checkout/${r.id}`)}>Checkout</button>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className={styles.card}>
            <div className={styles.sideTitle}>Doctor queues</div>
            {doctorQueues.length === 0 ? (
              <div className={styles.state}>No one waiting.</div>
            ) : (
              doctorQueues.map((d) => (
                <div key={d.id} className={styles.doctorRow} style={{ flexDirection: "column", alignItems: "stretch", gap: "4px" }}>
                  <div style={{ display: "flex", justifyContent: "space-between", width: "100%" }}>
                    <span className={styles.doctorName}>{d.name}</span>
                    <span className={styles.doctorCount}>
                      {d.waiting} waiting{d.estimatedMinutes !== undefined ? ` · ~${d.estimatedMinutes}m wait` : ""}
                    </span>
                  </div>
                  {d.delay ? (
                    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", fontSize: "12px", color: "var(--nb-danger-500)" }}>
                      <span>Running {d.delay.delayMinutes}m late{d.delay.reason ? ` — ${d.delay.reason}` : ""}</span>
                      <button className={styles.actionBtn} onClick={() => clearDelay(d.id)}>Clear delay</button>
                    </div>
                  ) : (
                    <button className={styles.actionBtn} style={{ alignSelf: "flex-start" }}
                      onClick={() => { setDelayFormFor(d.id); setDelayMinutesInput("15"); setDelayReasonInput(""); }}>
                      Announce delay
                    </button>
                  )}
                </div>
              ))
            )}
          </div>
        </div>
      )}

      {showModal && (
        <div className={styles.overlay} onClick={() => setShowModal(false)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitCheckIn}>
            <h2 className={styles.modalTitle}>Register walk-in</h2>

            <div className={styles.field}>
              <label className={styles.label} htmlFor="patientSearch">Patient</label>
              <input
                id="patientSearch"
                className={styles.input}
                placeholder="Search by name or phone…"
                value={patientQuery}
                onChange={(e) => onPatientQueryChange(e.target.value)}
                disabled={!!selectedPatient}
              />
              {selectedPatient && (
                <div className={styles.selectedPatient}>
                  <span>{selectedPatient.name} · {selectedPatient.phone}</span>
                  <button type="button" className={styles.linkBtn} onClick={() => { setSelectedPatient(null); setPatientQuery(""); }}>Change</button>
                </div>
              )}
              {!selectedPatient && searchResults.length > 0 && (
                <div className={styles.searchResults}>
                  {searchResults.map((p) => (
                    <div key={p.id} className={styles.searchResultRow} onClick={() => { setSelectedPatient(p); setSearchResults([]); }}>
                      {p.name} · {p.phone} · {p.mrn}
                    </div>
                  ))}
                </div>
              )}
              {!selectedPatient && patientQuery.trim().length >= 2 && searchResults.length === 0 && !newPatientMode && (
                <button type="button" className={styles.linkBtn} style={{ marginTop: "8px" }} onClick={() => setNewPatientMode(true)}>
                  No match — register a new patient
                </button>
              )}
            </div>

            {newPatientMode && !selectedPatient && (
              <>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="newName">Name</label>
                  <input id="newName" className={styles.input} value={newName} onChange={(e) => setNewName(e.target.value)} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="newPhone">Phone</label>
                  <input id="newPhone" className={styles.input} value={newPhone} onChange={(e) => setNewPhone(e.target.value)} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="newDob">Date of birth</label>
                  <input id="newDob" type="date" className={styles.input} value={newDob} onChange={(e) => setNewDob(e.target.value)} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="newGender">Gender</label>
                  <select id="newGender" className={styles.select} value={newGender} onChange={(e) => setNewGender(e.target.value)}>
                    <option value="male">Male</option>
                    <option value="female">Female</option>
                    <option value="other">Other</option>
                  </select>
                </div>
              </>
            )}

            <div className={styles.field}>
              <label className={styles.label} htmlFor="doctor">Doctor</label>
              <select id="doctor" className={styles.select} value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
                <option value="">Select a doctor…</option>
                {staff.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>

            {modalError && <div className={styles.formError} role="alert">{modalError}</div>}

            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setShowModal(false)}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={submitting}>{submitting ? "Checking in…" : "Check in"}</button>
            </div>
          </form>
        </div>
      )}

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

      {priorityEntryId && (
        <div className={styles.overlay} onClick={() => setPriorityEntryId(null)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitPriority}>
            <h2 className={styles.modalTitle}>Mark priority</h2>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="priorityReason">Reason</label>
              <select id="priorityReason" className={styles.select} value={priorityReasonCode}
                onChange={(e) => setPriorityReasonCode(e.target.value)}>
                {PRIORITY_REASON_CODES.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            {priorityReasonCode === "Other" && (
              <div className={styles.field}>
                <label className={styles.label} htmlFor="priorityOther">Specify</label>
                <input id="priorityOther" className={styles.input} value={priorityOtherReason}
                  onChange={(e) => setPriorityOtherReason(e.target.value)} />
              </div>
            )}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setPriorityEntryId(null)}>Cancel</button>
              <button type="submit" className={styles.submitBtn}
                disabled={prioritySubmitting || (priorityReasonCode === "Other" && !priorityOtherReason.trim())}>
                {prioritySubmitting ? "Saving…" : "Mark priority"}
              </button>
            </div>
          </form>
        </div>
      )}

      {delayFormFor && (
        <div className={styles.overlay} onClick={() => setDelayFormFor(null)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitDelay}>
            <h2 className={styles.modalTitle}>Announce delay</h2>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="delayMinutes">Running late by (minutes)</label>
              <input id="delayMinutes" type="number" min="1" className={styles.input}
                value={delayMinutesInput} onChange={(e) => setDelayMinutesInput(e.target.value)} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="delayReason">Reason (optional)</label>
              <input id="delayReason" className={styles.input} value={delayReasonInput}
                onChange={(e) => setDelayReasonInput(e.target.value)} placeholder="Emergency case running long" />
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setDelayFormFor(null)}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={delayBusy || !delayMinutesInput}>
                {delayBusy ? "Announcing…" : "Announce delay"}
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}
