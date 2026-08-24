"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./nursing.module.css";

// E13 Nursing, Orders & Triage. NB-142's vitals worklist plus NB-143/145/146/148 as tabs on the
// same page — NB-144/149 (clinical triage inbox, task handoff) aren't built, both need NB-197's
// WhatsApp inbox, which doesn't exist. NB-147 (package sessions) is a link to /packages, which
// already has the redeem action — no new UI needed for it.
type QueueEntry = {
  id: string; patientId: string; doctorId: string; tokenNumber: number; status: string; createdAt: string;
  priority: boolean; priorityReason: string | null; priorityFlaggedBy: string | null; priorityFlaggedAt: string | null;
  priorityAcknowledgedBy: string | null; priorityAcknowledgedAt: string | null;
};
type Row = QueueEntry & { patientName: string; patientMrn: string; doctorName: string };
type StaffOption = { id: string; name: string };
type AdministrationOrder = {
  id: string; queueEntryId: string; patientId: string; patientName: string; orderedByName: string; drugName: string;
  dose: string; route: string; site: string | null; status: string; recordedByName: string | null;
  witnessedByName: string | null; refuseReason: string | null; recordedAt: string | null; createdAt: string;
};
type ProcedureOrder = {
  id: string; queueEntryId: string; patientId: string; patientName: string; orderedByName: string; chargeCode: string;
  chargeName: string; baseAmount: number; taxRatePercent: number; prepNotes: string | null; consentNote: string | null;
  status: string; billed: boolean; completedByName: string | null; completedAt: string | null; createdAt: string;
  consentSignedName: string | null; consentRecordedByName: string | null; consentSignedAt: string | null;
};
type ActivityEntry = { kind: string; activity: string; patientName: string; occurredAt: string };
type Problem = { title: string; detail: string };
type Tab = "vitals" | "priority" | "administration" | "procedures" | "activity";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const POLL_MS = 15_000;
const TABS: { key: Tab; label: string }[] = [
  { key: "vitals", label: "Arrivals & Vitals" },
  { key: "priority", label: "Priority Patients" },
  { key: "administration", label: "Administration Orders" },
  { key: "procedures", label: "Today's Procedures" },
  { key: "activity", label: "Completed Activity" },
];

function waitMinutes(createdAt: string): number {
  return Math.max(0, Math.round((Date.now() - new Date(createdAt).getTime()) / 60000));
}

export default function NursingPage() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("vitals");
  const [rows, setRows] = useState<Row[]>([]);
  const [priorityRows, setPriorityRows] = useState<Row[]>([]);
  const [administrationOrders, setAdministrationOrders] = useState<AdministrationOrder[]>([]);
  const [procedureOrders, setProcedureOrders] = useState<ProcedureOrder[]>([]);
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [staff, setStaff] = useState<StaffOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [, setNow] = useState(() => Date.now());

  const [vitalsEntryId, setVitalsEntryId] = useState<string | null>(null);
  const [vitalsForm, setVitalsForm] = useState({ heightCm: "", weightKg: "", bpSystolic: "", bpDiastolic: "", pulseBpm: "", tempCelsius: "", spo2Percent: "" });
  const [vitalsError, setVitalsError] = useState<string | null>(null);
  const [vitalsFlags, setVitalsFlags] = useState<string[] | null>(null);
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
      const [queueRes, priorityRes, staffRes, adminRes, procRes, activityRes] = await Promise.all([
        authedFetch("/queue"), authedFetch("/queue?priority=true"), authedFetch("/staff?limit=100"),
        authedFetch("/nursing/administration-orders/today"), authedFetch("/nursing/procedure-orders/today"),
        authedFetch("/nursing/activity/today"),
      ]);
      if (!queueRes) return;
      if (queueRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!queueRes.ok) {
        setError("Couldn't load the nursing worklist. Try again.");
        return;
      }
      const entries: QueueEntry[] = await queueRes.json();
      const staffMap = new Map<string, string>();
      if (staffRes?.ok) {
        const staffBody = await staffRes.json();
        const options: StaffOption[] = staffBody.data.map((s: { id: string; name: string }) => ({ id: s.id, name: s.name }));
        setStaff(options);
        options.forEach((s) => staffMap.set(s.id, s.name));
      }
      const withNames = async (list: QueueEntry[]) => Promise.all(list.map(async (e) => {
        const pRes = await authedFetch(`/patients/${e.patientId}`);
        const p = pRes?.ok ? await pRes.json() : null;
        return { ...e, patientName: p?.name ?? "Unknown patient", patientMrn: p?.mrn ?? "", doctorName: staffMap.get(e.doctorId) ?? "—" };
      }));
      setRows(await withNames(entries.filter((e) => e.status === "vitals_pending")));
      if (priorityRes?.ok) setPriorityRows(await withNames(await priorityRes.json()));
      if (adminRes?.ok) setAdministrationOrders(await adminRes.json());
      if (procRes?.ok) setProcedureOrders(await procRes.json());
      if (activityRes?.ok) setActivity(await activityRes.json());
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
    setVitalsFlags(null);
  }

  function closeVitalsModal() {
    setVitalsEntryId(null);
    setVitalsFlags(null);
    load();
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
      const saved: { abnormalFlags: string[] } = await res.json();
      if (saved.abnormalFlags.length > 0) {
        setVitalsFlags(saved.abnormalFlags);
      } else {
        closeVitalsModal();
      }
    } finally {
      setVitalsSubmitting(false);
    }
  }

  // ── NB-143: priority ────────────────────────────────────────────────────
  async function acknowledgePriority(id: string) {
    setActionError(null);
    const res = await authedFetch(`/queue/${id}/priority/acknowledge`, { method: "POST" });
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't acknowledge." }));
      setActionError(p.detail || "Couldn't acknowledge.");
    }
    load();
  }

  // ── NB-145: administration orders ────────────────────────────────────────
  const [showAdminForm, setShowAdminForm] = useState(false);
  const [adminForm, setAdminForm] = useState({ queueEntryId: "", drugName: "", dose: "", route: "IM", site: "" });
  const [adminFormError, setAdminFormError] = useState<string | null>(null);
  const [witnessModal, setWitnessModal] = useState<{ orderId: string; witnessId: string } | null>(null);
  const [refuseModal, setRefuseModal] = useState<{ orderId: string; reason: string } | null>(null);

  async function submitAdminOrder(e: React.FormEvent) {
    e.preventDefault();
    setAdminFormError(null);
    const res = await authedFetch("/nursing/administration-orders", { method: "POST", body: JSON.stringify(adminForm) });
    if (!res) return;
    if (!res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't create the order." }));
      setAdminFormError(p.detail || "Couldn't create the order.");
      return;
    }
    setShowAdminForm(false);
    setAdminForm({ queueEntryId: "", drugName: "", dose: "", route: "IM", site: "" });
    load();
  }

  async function submitAdminister() {
    if (!witnessModal) return;
    setActionError(null);
    const res = await authedFetch(`/nursing/administration-orders/${witnessModal.orderId}/administer`, {
      method: "POST", body: JSON.stringify({ witnessedByStaffId: witnessModal.witnessId }),
    });
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't record administration." }));
      setActionError(p.detail || "Couldn't record administration.");
    }
    setWitnessModal(null);
    load();
  }

  async function submitRefuse() {
    if (!refuseModal) return;
    setActionError(null);
    const res = await authedFetch(`/nursing/administration-orders/${refuseModal.orderId}/refuse`, {
      method: "POST", body: JSON.stringify({ reason: refuseModal.reason }),
    });
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't record refusal." }));
      setActionError(p.detail || "Couldn't record refusal.");
    }
    setRefuseModal(null);
    load();
  }

  // ── NB-146: procedure orders ─────────────────────────────────────────────
  const [showProcForm, setShowProcForm] = useState(false);
  const [procForm, setProcForm] = useState({ queueEntryId: "", chargeCode: "", prepNotes: "", consentNote: "" });
  const [procFormError, setProcFormError] = useState<string | null>(null);

  async function submitProcOrder(e: React.FormEvent) {
    e.preventDefault();
    setProcFormError(null);
    const res = await authedFetch("/nursing/procedure-orders", { method: "POST", body: JSON.stringify(procForm) });
    if (!res) return;
    if (!res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't create the order." }));
      setProcFormError(p.detail || "Couldn't create the order.");
      return;
    }
    setShowProcForm(false);
    setProcForm({ queueEntryId: "", chargeCode: "", prepNotes: "", consentNote: "" });
    load();
  }

  async function setProcedureStatus(id: string, status: string) {
    setActionError(null);
    const res = await authedFetch(`/nursing/procedure-orders/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) });
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't update the procedure." }));
      setActionError(p.detail || "Couldn't update the procedure.");
    }
    load();
  }

  // ── NB-119: signed consent gates starting the procedure ────────────────────
  const [consentModal, setConsentModal] = useState<{ orderId: string; signedName: string } | null>(null);

  async function submitConsent() {
    if (!consentModal || !consentModal.signedName.trim()) return;
    setActionError(null);
    const res = await authedFetch(`/nursing/procedure-orders/${consentModal.orderId}/consent`, {
      method: "POST", body: JSON.stringify({ signedName: consentModal.signedName }),
    });
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't record consent." }));
      setActionError(p.detail || "Couldn't record consent.");
    }
    setConsentModal(null);
    load();
  }

  // ── NB-148: export ────────────────────────────────────────────────────────
  function exportActivityCsv() {
    const header = "Kind,Activity,Patient,Occurred At\n";
    const body = activity.map((a) => [a.kind, a.activity, a.patientName, a.occurredAt].map((v) => `"${v}"`).join(",")).join("\n");
    const blob = new Blob([header + body], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `shift-handover-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  if (loading) return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to the nursing worklist.</div></main>;
  if (error) return <main className={styles.page}><div className={styles.errorState}>{error}</div></main>;

  return (
    <main className={styles.page}>
      <div className={styles.headerRow}>
        <div>
          <h1 className={styles.title}>Nursing Worklist</h1>
          <p className={styles.subtitle}>Vitals, priority triage, administration, procedures and today&apos;s activity.</p>
        </div>
        <button className={styles.smallBtn} onClick={() => router.push("/packages")}>Package Sessions →</button>
      </div>

      <div className={styles.tabs}>
        {TABS.map((t) => (
          <button key={t.key} className={tab === t.key ? styles.tabActive : styles.tab} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>

      {actionError && <div className={styles.errorState} style={{ padding: "8px 0" }}>{actionError}</div>}

      {tab === "vitals" && (
        <div className={styles.card}>
          {rows.length === 0 ? (
            <div className={styles.state}>Nobody is waiting on vitals right now.</div>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Token</th><th>Patient</th><th>Doctor</th><th>Wait</th><th>Status</th><th></th></tr></thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} className={r.priority ? styles.rowFlagged : undefined}>
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
      )}

      {tab === "priority" && (
        <div className={styles.card}>
          {priorityRows.length === 0 ? (
            <div className={styles.state}>No priority patients flagged right now.</div>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Patient</th><th>Flagged</th><th>Reason</th><th>Acknowledged</th><th>Status</th><th></th></tr></thead>
                <tbody>
                  {priorityRows.map((r) => (
                    <tr key={r.id} className={styles.rowFlagged}>
                      <td><span className={styles.patientName}>{r.patientName}</span><span className={styles.mrn}>{r.patientMrn}</span></td>
                      <td className={styles.muted}>{r.priorityFlaggedAt ? new Date(r.priorityFlaggedAt).toLocaleTimeString() : "—"}</td>
                      <td>{r.priorityReason}</td>
                      <td className={styles.muted}>{r.priorityAcknowledgedAt ? new Date(r.priorityAcknowledgedAt).toLocaleTimeString() : "Not yet"}</td>
                      <td><span className={styles.pillNeutral}>{r.status.replace("_", " ")}</span></td>
                      <td>{!r.priorityAcknowledgedAt && <button className={styles.actionBtn} onClick={() => acknowledgePriority(r.id)}>Acknowledge</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {tab === "administration" && (
        <>
          <div className={styles.headerActions} style={{ marginBottom: "12px" }}>
            <button className={styles.actionBtn} onClick={() => setShowAdminForm(true)}>+ New order</button>
          </div>
          <div className={styles.card}>
            {administrationOrders.length === 0 ? (
              <div className={styles.state}>No administration orders today.</div>
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th>Patient</th><th>Ordered item</th><th>Ordered by</th><th>Route &amp; site</th><th>State</th><th></th></tr></thead>
                  <tbody>
                    {administrationOrders.map((o) => (
                      <tr key={o.id}>
                        <td>{o.patientName}</td>
                        <td>{o.drugName} · {o.dose}</td>
                        <td>{o.orderedByName}</td>
                        <td>{o.route}{o.site ? ` · ${o.site}` : ""}</td>
                        <td>
                          {o.status === "not_started" && <span className={styles.pillNeutral}>Not started</span>}
                          {o.status === "administered" && <span className={styles.pillDone}>Administered · witness {o.witnessedByName}</span>}
                          {o.status === "refused" && <span className={styles.pillDanger}>Refused: {o.refuseReason}</span>}
                        </td>
                        <td>
                          {o.status === "not_started" && (
                            <>
                              <button className={styles.smallBtn} onClick={() => setWitnessModal({ orderId: o.id, witnessId: "" })}>Administer</button>{" "}
                              <button className={styles.smallBtn} onClick={() => setRefuseModal({ orderId: o.id, reason: "" })}>Refuse</button>
                            </>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {tab === "procedures" && (
        <>
          <div className={styles.headerActions} style={{ marginBottom: "12px" }}>
            <button className={styles.actionBtn} onClick={() => setShowProcForm(true)}>+ New procedure</button>
          </div>
          <div className={styles.card}>
            {procedureOrders.length === 0 ? (
              <div className={styles.state}>No procedures scheduled today.</div>
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th>Patient</th><th>Procedure</th><th>Consent</th><th>Preparation</th><th>State</th><th></th></tr></thead>
                  <tbody>
                    {procedureOrders.map((p) => (
                      <tr key={p.id}>
                        <td>{p.patientName}</td>
                        <td>{p.chargeName}</td>
                        <td className={styles.muted}>
                          {p.consentSignedName ? `✓ signed by ${p.consentSignedName}` : "Not signed"}
                          {!p.consentSignedName && (
                            <>{" "}<button className={styles.smallBtn} onClick={() => setConsentModal({ orderId: p.id, signedName: "" })}>Record consent</button></>
                          )}
                        </td>
                        <td className={styles.muted}>{p.prepNotes ?? "—"}</td>
                        <td>
                          {p.status === "ordered" && <span className={styles.pillNeutral}>Not started</span>}
                          {p.status === "prepped" && <span className={styles.pillVitals}>In progress</span>}
                          {p.status === "completed" && <span className={styles.pillDone}>Completed{p.billed ? " · billed" : " · pending bill"}</span>}
                        </td>
                        <td>
                          {p.status === "ordered" && <button className={styles.smallBtn} onClick={() => setProcedureStatus(p.id, "prepped")}>Mark prepped</button>}
                          {(p.status === "ordered" || p.status === "prepped") && (
                            <>{" "}<button className={styles.smallBtn} onClick={() => setProcedureStatus(p.id, "completed")}>Complete</button></>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {tab === "activity" && (
        <>
          <div className={styles.headerActions} style={{ marginBottom: "12px" }}>
            <button className={styles.smallBtn} onClick={exportActivityCsv} disabled={activity.length === 0}>Export for handover</button>
          </div>
          <div className={styles.card}>
            {activity.length === 0 ? (
              <div className={styles.state}>Nothing completed yet today.</div>
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th>Patient</th><th>Activity</th><th>Time</th></tr></thead>
                  <tbody>
                    {activity.map((a, i) => (
                      <tr key={i}>
                        <td>{a.patientName}</td>
                        <td>{a.activity}</td>
                        <td className={styles.muted}>{new Date(a.occurredAt).toLocaleTimeString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {vitalsEntryId && vitalsFlags && (
        <div className={styles.overlay} onClick={closeVitalsModal}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Vitals saved</h2>
            <div className={styles.formError} role="alert">
              {vitalsFlags.map((f) => <div key={f}>⚠ {f}</div>)}
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.submitBtn} onClick={closeVitalsModal}>Done</button>
            </div>
          </div>
        </div>
      )}

      {vitalsEntryId && !vitalsFlags && (
        <div className={styles.overlay} onClick={closeVitalsModal}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitVitals}>
            <h2 className={styles.modalTitle}>Record vitals</h2>
            {([
              ["heightCm", "Height (cm)"], ["weightKg", "Weight (kg)"],
              ["bpSystolic", "BP systolic"], ["bpDiastolic", "BP diastolic"],
              ["pulseBpm", "Pulse (bpm)"], ["tempCelsius", "Temp (°C)"], ["spo2Percent", "SpO2 (%)"],
            ] as const).map(([key, label]) => (
              <div className={styles.field} key={key}>
                <label className={styles.label} htmlFor={key}>{label}</label>
                <input id={key} type="number" step="any" className={styles.input} value={vitalsForm[key]}
                  onChange={(e) => setVitalsForm((prev) => ({ ...prev, [key]: e.target.value }))} />
              </div>
            ))}
            {vitalsError && <div className={styles.formError} role="alert">{vitalsError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={closeVitalsModal}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={vitalsSubmitting}>{vitalsSubmitting ? "Saving…" : "Save vitals"}</button>
            </div>
          </form>
        </div>
      )}

      {showAdminForm && (
        <div className={styles.overlay} onClick={() => setShowAdminForm(false)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitAdminOrder}>
            <h2 className={styles.modalTitle}>New administration order</h2>
            <div className={styles.field}>
              <label className={styles.label}>Queue entry ID</label>
              <input className={styles.input} value={adminForm.queueEntryId} onChange={(e) => setAdminForm((p) => ({ ...p, queueEntryId: e.target.value }))} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Drug</label>
              <input className={styles.input} value={adminForm.drugName} onChange={(e) => setAdminForm((p) => ({ ...p, drugName: e.target.value }))} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Dose</label>
              <input className={styles.input} value={adminForm.dose} onChange={(e) => setAdminForm((p) => ({ ...p, dose: e.target.value }))} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Route</label>
              <select className={styles.select} value={adminForm.route} onChange={(e) => setAdminForm((p) => ({ ...p, route: e.target.value }))}>
                {["IM", "IV", "SC", "infusion", "oral", "topical"].map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Site (optional)</label>
              <input className={styles.input} value={adminForm.site} onChange={(e) => setAdminForm((p) => ({ ...p, site: e.target.value }))} placeholder="left deltoid" />
            </div>
            {adminFormError && <div className={styles.formError} role="alert">{adminFormError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setShowAdminForm(false)}>Cancel</button>
              <button type="submit" className={styles.submitBtn}>Create order</button>
            </div>
          </form>
        </div>
      )}

      {witnessModal && (
        <div className={styles.overlay} onClick={() => setWitnessModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Confirm the five rights, then witness</h2>
            <p className={styles.muted} style={{ marginBottom: "12px" }}>Right patient, right drug, right dose, right route, right time — confirmed before administering.</p>
            <div className={styles.field}>
              <label className={styles.label}>Witnessed by</label>
              <select className={styles.select} value={witnessModal.witnessId} onChange={(e) => setWitnessModal({ ...witnessModal, witnessId: e.target.value })}>
                <option value="">Select a witness…</option>
                {staff.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setWitnessModal(null)}>Cancel</button>
              <button type="button" className={styles.submitBtn} disabled={!witnessModal.witnessId} onClick={submitAdminister}>Administer</button>
            </div>
          </div>
        </div>
      )}

      {consentModal && (
        <div className={styles.overlay} onClick={() => setConsentModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Record consent</h2>
            <div className={styles.field}>
              <label className={styles.label}>Patient&apos;s typed name (stands in for a signature)</label>
              <input className={styles.input} value={consentModal.signedName}
                onChange={(e) => setConsentModal({ ...consentModal, signedName: e.target.value })} />
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setConsentModal(null)}>Cancel</button>
              <button type="button" className={styles.submitBtn} disabled={!consentModal.signedName.trim()} onClick={submitConsent}>Record consent</button>
            </div>
          </div>
        </div>
      )}

      {refuseModal && (
        <div className={styles.overlay} onClick={() => setRefuseModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Refuse administration</h2>
            <div className={styles.field}>
              <label className={styles.label}>Reason</label>
              <textarea className={styles.textarea} value={refuseModal.reason} onChange={(e) => setRefuseModal({ ...refuseModal, reason: e.target.value })} />
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setRefuseModal(null)}>Cancel</button>
              <button type="button" className={styles.submitBtn} disabled={!refuseModal.reason.trim()} onClick={submitRefuse}>Record refusal</button>
            </div>
          </div>
        </div>
      )}

      {showProcForm && (
        <div className={styles.overlay} onClick={() => setShowProcForm(false)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitProcOrder}>
            <h2 className={styles.modalTitle}>New procedure order</h2>
            <div className={styles.field}>
              <label className={styles.label}>Queue entry ID</label>
              <input className={styles.input} value={procForm.queueEntryId} onChange={(e) => setProcForm((p) => ({ ...p, queueEntryId: e.target.value }))} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Charge code</label>
              <input className={styles.input} value={procForm.chargeCode} onChange={(e) => setProcForm((p) => ({ ...p, chargeCode: e.target.value }))} required placeholder="from Clinic Setup → Charges" />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Prep notes</label>
              <textarea className={styles.textarea} value={procForm.prepNotes} onChange={(e) => setProcForm((p) => ({ ...p, prepNotes: e.target.value }))} placeholder="Chair 1 ready" />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Consent note</label>
              <input className={styles.input} value={procForm.consentNote} onChange={(e) => setProcForm((p) => ({ ...p, consentNote: e.target.value }))} placeholder="Signed v2.1 / Consent missing" />
            </div>
            {procFormError && <div className={styles.formError} role="alert">{procFormError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setShowProcForm(false)}>Cancel</button>
              <button type="submit" className={styles.submitBtn}>Create order</button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}
