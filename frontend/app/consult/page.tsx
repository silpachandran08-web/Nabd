"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./consult.module.css";

// Matches GET /v1/queue (QueueController), GET /v1/patients/{id} (PatientController),
// and /v1/clinical/notes/{queueEntryId} (NoteController).
type QueueEntry = {
  id: string; appointmentId: string | null; patientId: string; doctorId: string;
  queueDate: string; tokenNumber: number; status: string; priority: boolean; priorityReason: string | null;
};
type Row = QueueEntry & { patientName: string };
type PatientDetail = {
  id: string; mrn: string; name: string; phone: string; dob: string; gender: string; status: string;
  allergies: string[]; chronicConditions: string[]; activePackages: number; outstandingBalance?: number; lastVisitAt: string | null;
};
type Note = {
  id: string; queueEntryId: string; patientId: string; doctorId: string;
  subjective: string | null; objective: string | null; assessment: string | null; plan: string | null;
  diagnosis: string | null; status: string; signedAt: string | null; updatedAt: string;
};
type NoteDraft = { subjective: string; objective: string; assessment: string; plan: string; diagnosis: string };
type PrescriptionItem = {
  id?: string; drugName: string; dosage: string; frequency: string; duration: string;
  instructions: string; allergyOverrideReason: string; allergyWarning?: string | null;
  pregnancyWarning?: string | null; controlledSubstanceWarning?: string | null;
};
type Prescription = { id: string; status: string; signedAt: string | null; items: PrescriptionItem[] };
type Problem = { title: string; detail: string };
type FavouriteRxSet = { id: string; doctorId: string | null; name: string; items: PrescriptionItem[] };
type DoseResult = { weightKg: number; mgPerKg: number; totalDoseMg: number; ruleSource: string };
type NoteAmendment = { id: string; amendedBy: string; reason: string; diagnosis: string | null; createdAt: string };

const BLANK_RX_ITEM: PrescriptionItem = { drugName: "", dosage: "", frequency: "", duration: "", instructions: "", allergyOverrideReason: "" };

// The API returns nullable dosage/frequency/duration/instructions (a favourite set commonly omits
// duration) — every input below is a controlled component, so null must become "" before it renders.
function normalizeRxItem(i: PrescriptionItem): PrescriptionItem {
  return {
    ...i,
    dosage: i.dosage ?? "", frequency: i.frequency ?? "", duration: i.duration ?? "", instructions: i.instructions ?? "",
    allergyOverrideReason: i.allergyOverrideReason ?? "",
  };
}

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const AUTOSAVE_MS = 10_000;
const BLANK_DRAFT: NoteDraft = { subjective: "", objective: "", assessment: "", plan: "", diagnosis: "" };

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

function decodeJwt(token: string): Record<string, unknown> {
  try {
    const payload = token.split(".")[1];
    const b64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), "=");
    return JSON.parse(atob(padded));
  } catch {
    return {};
  }
}

function age(dob: string): number {
  const d = new Date(dob);
  const now = new Date();
  let a = now.getFullYear() - d.getFullYear();
  if (now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())) a--;
  return a;
}

export default function ConsultPage() {
  const router = useRouter();
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [activeEntry, setActiveEntry] = useState<Row | null>(null);
  const [activePatient, setActivePatient] = useState<PatientDetail | null>(null);
  const [note, setNote] = useState<NoteDraft>(BLANK_DRAFT);
  const [noteStatus, setNoteStatus] = useState<"draft" | "signed">("draft");
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null);
  const [completing, setCompleting] = useState(false);
  const [shellError, setShellError] = useState<string | null>(null);
  const [rxItems, setRxItems] = useState<PrescriptionItem[]>([]);
  const [rxStatus, setRxStatus] = useState<"draft" | "signed">("draft");
  const [rxSaving, setRxSaving] = useState(false);
  const [rxError, setRxError] = useState<string | null>(null);
  const [followUpDate, setFollowUpDate] = useState("");
  const [followUpBusy, setFollowUpBusy] = useState(false);
  const [followUpMessage, setFollowUpMessage] = useState<string | null>(null);
  const [previousMeds, setPreviousMeds] = useState<Prescription[]>([]);
  const [favouriteSets, setFavouriteSets] = useState<FavouriteRxSet[]>([]);
  const [newSetName, setNewSetName] = useState("");
  const [doseMgPerKg, setDoseMgPerKg] = useState("");
  const [doseResult, setDoseResult] = useState<DoseResult | null>(null);
  const [doseError, setDoseError] = useState<string | null>(null);
  const [amendments, setAmendments] = useState<NoteAmendment[]>([]);
  const [showAmendForm, setShowAmendForm] = useState(false);
  const [amendReason, setAmendReason] = useState("");
  const [amendDiagnosis, setAmendDiagnosis] = useState("");
  const [amending, setAmending] = useState(false);
  const dirtyRef = useRef(dirty);
  const noteRef = useRef(note);
  useEffect(() => {
    dirtyRef.current = dirty;
    noteRef.current = note;
  }, [dirty, note]);

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
    setLoading(true);
    setError(null);
    const token = localStorage.getItem("nabd_access_token");
    if (!token) {
      router.replace("/login");
      return;
    }
    const self = String(decodeJwt(token).sub ?? "");
    try {
      const res = await authedFetch(`/queue?doctorId=${self}`);
      if (!res) return;
      if (res.status === 403) {
        setForbidden(true);
        return;
      }
      if (!res.ok) {
        setError("Couldn't load today's queue. Try again.");
        return;
      }
      const entries: QueueEntry[] = await res.json();
      const withNames = await Promise.all(entries.map(async (e) => {
        const pRes = await authedFetch(`/patients/${e.patientId}`);
        const name = pRes?.ok ? (await pRes.json()).name : "Unknown patient";
        return { ...e, patientName: name };
      }));
      setRows(withNames);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch, router]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function saveNote(entryId: string) {
    setSaving(true);
    try {
      const res = await authedFetch(`/clinical/notes/${entryId}`, { method: "PATCH", body: JSON.stringify(noteRef.current) });
      if (res?.ok) {
        setDirty(false);
        setLastSavedAt(new Date());
      }
      return res;
    } finally {
      setSaving(false);
    }
  }

  // Autosave every 10s while there are unsaved changes — cleared when the shell closes.
  useEffect(() => {
    if (!activeEntry) return;
    const id = setInterval(() => {
      if (dirtyRef.current) saveNote(activeEntry.id);
    }, AUTOSAVE_MS);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeEntry?.id]);

  async function startConsult(row: Row) {
    const res = await authedFetch(`/queue/${row.id}/status`, { method: "PATCH", body: JSON.stringify({ status: "in_consult" }) });
    if (!res?.ok) return;
    setRows((prev) => prev.map((r) => (r.id === row.id ? { ...r, status: "in_consult" } : r)));
    openConsult({ ...row, status: "in_consult" });
  }

  async function openConsult(row: Row) {
    setActiveEntry(row);
    setActivePatient(null);
    setNote(BLANK_DRAFT);
    setNoteStatus("draft");
    setDirty(false);
    setLastSavedAt(null);
    setShellError(null);
    setRxItems([]);
    setRxStatus("draft");
    setRxError(null);
    setFollowUpDate("");
    setFollowUpMessage(null);
    setPreviousMeds([]);
    setFavouriteSets([]);
    setNewSetName("");
    setDoseMgPerKg("");
    setDoseResult(null);
    setDoseError(null);
    setAmendments([]);
    setShowAmendForm(false);
    setAmendReason("");
    setAmendDiagnosis("");

    // NB-105: previous medicines fetched right alongside everything else the pad needs, and
    // rendered inside the same Prescription card below — no separate screen to visit.
    const prevRxRes = await authedFetch(`/clinical/patients/${row.patientId}/prescriptions`);
    if (prevRxRes?.ok) setPreviousMeds(await prevRxRes.json());

    const setsRes = await authedFetch("/clinical/rx-sets");
    if (setsRes?.ok) setFavouriteSets(await setsRes.json());

    const pRes = await authedFetch(`/patients/${row.patientId}`);
    if (pRes?.ok) setActivePatient(await pRes.json());

    const nRes = await authedFetch(`/clinical/notes/${row.id}`);
    if (nRes?.status === 200) {
      const n: Note = await nRes.json();
      setNote({ subjective: n.subjective ?? "", objective: n.objective ?? "", assessment: n.assessment ?? "", plan: n.plan ?? "", diagnosis: n.diagnosis ?? "" });
      setNoteStatus(n.status as "draft" | "signed");
      if (n.status === "signed") {
        const aRes = await authedFetch(`/clinical/notes/${row.id}/amendments`);
        if (aRes?.ok) setAmendments(await aRes.json());
      }
    }

    const rRes = await authedFetch(`/clinical/prescriptions/${row.id}`);
    if (rRes?.status === 200) {
      const rx: Prescription = await rRes.json();
      setRxItems(rx.items.length > 0 ? rx.items.map(normalizeRxItem) : [{ ...BLANK_RX_ITEM }]);
      setRxStatus(rx.status as "draft" | "signed");
    } else {
      setRxItems([{ ...BLANK_RX_ITEM }]);
    }
  }

  function updateRxItem(index: number, field: keyof PrescriptionItem, value: string) {
    setRxItems((prev) => prev.map((item, i) => (i === index ? { ...item, [field]: value } : item)));
  }

  function addRxItem() {
    setRxItems((prev) => [...prev, { ...BLANK_RX_ITEM }]);
  }

  function removeRxItem(index: number) {
    setRxItems((prev) => prev.filter((_, i) => i !== index));
  }

  async function saveRx() {
    if (!activeEntry) return;
    setRxSaving(true);
    setRxError(null);
    try {
      const items = rxItems.filter((i) => i.drugName.trim() !== "");
      const res = await authedFetch(`/clinical/prescriptions/${activeEntry.id}`, { method: "PATCH", body: JSON.stringify({ items }) });
      if (!res?.ok) {
        const p: Problem = await res?.json().catch(() => ({ title: "Error", detail: "Couldn't save prescription." }));
        setRxError(p.detail || "Couldn't save prescription.");
        return false;
      }
      const rx: Prescription = await res.json();
      setRxItems(rx.items.length > 0 ? rx.items.map(normalizeRxItem) : [{ ...BLANK_RX_ITEM }]);
      return true;
    } finally {
      setRxSaving(false);
    }
  }

  async function signRx() {
    if (!activeEntry) return;
    if (!(await saveRx())) return;
    const res = await authedFetch(`/clinical/prescriptions/${activeEntry.id}/sign`, { method: "POST" });
    if (res?.ok) setRxStatus("signed");
  }

  async function calculateDose() {
    if (!activePatient || !doseMgPerKg) return;
    setDoseError(null);
    const res = await authedFetch(`/clinical/patients/${activePatient.id}/dose-calculator?mgPerKg=${doseMgPerKg}`);
    if (!res?.ok) {
      const p: Problem = await res?.json().catch(() => ({ title: "Error", detail: "Couldn't calculate dose." }));
      setDoseError(p.detail || "Couldn't calculate dose.");
      setDoseResult(null);
      return;
    }
    setDoseResult(await res.json());
  }

  async function saveCurrentAsSet() {
    if (!newSetName.trim()) return;
    const items = rxItems.filter((i) => i.drugName.trim() !== "");
    if (items.length === 0) return;
    const res = await authedFetch("/clinical/rx-sets", { method: "POST", body: JSON.stringify({ name: newSetName, items }) });
    if (res?.ok) {
      setNewSetName("");
      const setsRes = await authedFetch("/clinical/rx-sets");
      if (setsRes?.ok) setFavouriteSets(await setsRes.json());
    }
  }

  async function applySet(setId: string) {
    if (!activeEntry) return;
    const res = await authedFetch(`/clinical/prescriptions/${activeEntry.id}/apply-set/${setId}`, { method: "POST" });
    if (res?.ok) {
      const rx: Prescription = await res.json();
      setRxItems(rx.items.length > 0 ? rx.items.map(normalizeRxItem) : [{ ...BLANK_RX_ITEM }]);
      setRxError(null);
    } else {
      const p: Problem = await res?.json().catch(() => ({ title: "Error", detail: "Couldn't apply set." }));
      setRxError(p.detail || "Couldn't apply set.");
    }
  }

  async function submitAmendment() {
    if (!activeEntry || !amendReason.trim()) return;
    setAmending(true);
    try {
      const res = await authedFetch(`/clinical/notes/${activeEntry.id}/amendments`, {
        method: "POST",
        body: JSON.stringify({ reason: amendReason, diagnosis: amendDiagnosis.trim() || undefined }),
      });
      if (res?.ok) {
        setAmendReason("");
        setAmendDiagnosis("");
        setShowAmendForm(false);
        const aRes = await authedFetch(`/clinical/notes/${activeEntry.id}/amendments`);
        if (aRes?.ok) setAmendments(await aRes.json());
      }
    } finally {
      setAmending(false);
    }
  }

  async function scheduleFollowUp() {
    if (!activeEntry || !activePatient || !followUpDate) return;
    setFollowUpBusy(true);
    setFollowUpMessage(null);
    try {
      const startTime = new Date(`${followUpDate}T10:00:00`).toISOString();
      const res = await authedFetch("/appointments", {
        method: "POST",
        body: JSON.stringify({ patientId: activePatient.id, doctorId: activeEntry.doctorId, startTime, isFollowUp: true }),
      });
      if (!res?.ok) {
        const p: Problem = await res?.json().catch(() => ({ title: "Error", detail: "Couldn't schedule the follow-up." }));
        setFollowUpMessage(p.detail || "Couldn't schedule the follow-up.");
        return;
      }
      setFollowUpMessage(`Follow-up booked for ${followUpDate}.`);
    } finally {
      setFollowUpBusy(false);
    }
  }

  function updateField(field: keyof NoteDraft, value: string) {
    setNote((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
  }

  function closeShell() {
    setActiveEntry(null);
    load();
  }

  async function completeConsultation() {
    if (!activeEntry) return;
    setCompleting(true);
    setShellError(null);
    try {
      // Always flush a save first — even an untouched note needs one draft row to exist before it
      // can be signed, and this also catches the last few seconds of typing the 10s interval hasn't.
      const saveRes = await saveNote(activeEntry.id);
      if (!saveRes?.ok) {
        setShellError("Couldn't save the note. Try again before completing.");
        return;
      }
      const signRes = await authedFetch(`/clinical/notes/${activeEntry.id}/sign`, { method: "POST" });
      if (!signRes?.ok) {
        const p: Problem = await signRes?.json().catch(() => ({ title: "Error", detail: "Couldn't sign the note." }));
        setShellError(p.detail || "Couldn't sign the note.");
        return;
      }
      const statusRes = await authedFetch(`/queue/${activeEntry.id}/status`, { method: "PATCH", body: JSON.stringify({ status: "checkout_pending" }) });
      if (!statusRes?.ok) {
        setShellError("Note signed, but couldn't move the patient to checkout. Try again.");
        return;
      }
      setNoteStatus("signed");
      setActiveEntry((prev) => (prev ? { ...prev, status: "checkout_pending" } : prev));
      // Stay in the shell (don't auto-close): the signed banner below is also where the doctor
      // schedules a follow-up (NB-116) — closing here would make that control unreachable.
    } finally {
      setCompleting(false);
    }
  }

  if (activeEntry) {
    return (
      <main className={styles.page}>
        <div className={styles.strip}>
          <div className={styles.stripIdentity}>
            <button className={styles.backBtn} onClick={closeShell}>← Queue</button>
            {activePatient && (
              <>
                <span className={styles.stripName}>{activePatient.name}</span>
                <span className={styles.stripMeta}>{activePatient.mrn} · {age(activePatient.dob)} · {activePatient.gender} · Token #{activeEntry.tokenNumber}</span>
              </>
            )}
            <span className={`${styles.pill} ${STATUS_CLASS[activeEntry.status] ?? ""}`}>{activeEntry.status.replace("_", " ")}</span>
          </div>
          <div className={styles.stripActions}>
            <span className={styles.saveStatus}>
              {saving ? "Saving…" : dirty ? "Unsaved changes" : lastSavedAt ? `Saved ${lastSavedAt.toLocaleTimeString()}` : ""}
            </span>
            <button className={styles.completeBtn} onClick={completeConsultation} disabled={completing || noteStatus === "signed"}>
              {completing ? "Completing…" : "Complete consultation"}
            </button>
          </div>
        </div>

        {shellError && <div className={styles.errorState} role="alert">{shellError}</div>}

        {activePatient && (
          <div className={activePatient.allergies.length === 0 ? styles.allergyBannerNone : styles.allergyBannerSome}>
            {activePatient.allergies.length === 0 ? "No known allergies" : `⚠ Allergies: ${activePatient.allergies.join(", ")}`}
          </div>
        )}

        {noteStatus === "signed" && (
          <div className={styles.signedBanner}>
            <span>This note is signed and locked.</span>
            <span className={styles.followUpControls}>
              Schedule a follow-up:
              <input type="date" className={styles.followUpInput} value={followUpDate} onChange={(e) => setFollowUpDate(e.target.value)} />
              <button className={styles.actionBtn} onClick={scheduleFollowUp} disabled={followUpBusy || !followUpDate}>
                {followUpBusy ? "Booking…" : "Schedule follow-up"}
              </button>
              {followUpMessage && <span>{followUpMessage}</span>}
            </span>
            <span className={styles.followUpControls}>
              <button className={styles.backBtn} onClick={() => setShowAmendForm((v) => !v)}>
                {showAmendForm ? "Cancel amendment" : "Amend note"}
              </button>
            </span>
          </div>
        )}

        {showAmendForm && (
          <div className={styles.card} style={{ padding: "16px", marginTop: "8px" }}>
            <div className={styles.noteField}>
              <label className={styles.noteLabel} htmlFor="amendReason">Reason for amendment</label>
              <input id="amendReason" className={styles.input} value={amendReason} onChange={(e) => setAmendReason(e.target.value)} />
            </div>
            <div className={styles.noteField} style={{ marginTop: "8px" }}>
              <label className={styles.noteLabel} htmlFor="amendDiagnosis">Corrected diagnosis (optional)</label>
              <input id="amendDiagnosis" className={styles.input} value={amendDiagnosis} onChange={(e) => setAmendDiagnosis(e.target.value)} />
            </div>
            <button className={styles.completeBtn} style={{ marginTop: "8px" }} onClick={submitAmendment} disabled={amending || !amendReason.trim()}>
              {amending ? "Saving…" : "Save amendment"}
            </button>
          </div>
        )}

        {amendments.length > 0 && (
          <div className={styles.card} style={{ padding: "16px", marginTop: "8px" }}>
            <div className={styles.rxTitle} style={{ fontSize: "12px" }}>Amendment history</div>
            {amendments.map((a) => (
              <div key={a.id} className={styles.previousMedsRow}>
                <span className={styles.saveStatus}>{new Date(a.createdAt).toLocaleString()} — </span>
                {a.reason}{a.diagnosis ? ` (diagnosis: ${a.diagnosis})` : ""}
              </div>
            ))}
          </div>
        )}

        <div className={styles.card} style={{ padding: "24px" }}>
          <div className={styles.noteGrid}>
            {(["subjective", "objective", "assessment", "plan"] as const).map((field) => (
              <div className={styles.noteField} key={field}>
                <label className={styles.noteLabel} htmlFor={field}>{field}</label>
                <textarea
                  id={field}
                  className={styles.textarea}
                  value={note[field]}
                  disabled={noteStatus === "signed"}
                  onChange={(e) => updateField(field, e.target.value)}
                />
              </div>
            ))}
            <div className={styles.noteField}>
              <label className={styles.noteLabel} htmlFor="diagnosis">diagnosis</label>
              <input
                id="diagnosis"
                className={styles.input}
                value={note.diagnosis}
                disabled={noteStatus === "signed"}
                onChange={(e) => updateField("diagnosis", e.target.value)}
              />
            </div>
          </div>
        </div>

        <div className={styles.card} style={{ padding: "24px", marginTop: "16px" }}>
          <div className={styles.rxHeader}>
            <h3 className={styles.rxTitle}>Prescription {rxStatus === "signed" && <span className={styles.signedTag}>signed</span>}</h3>
            {rxStatus !== "signed" && (
              <div className={styles.rxHeaderActions}>
                <button className={styles.backBtn} onClick={saveRx} disabled={rxSaving}>{rxSaving ? "Saving…" : "Save"}</button>
                <button className={styles.completeBtn} onClick={signRx} disabled={rxSaving}>Sign prescription</button>
              </div>
            )}
          </div>
          {rxError && <div className={styles.errorState} role="alert">{rxError}</div>}
          {rxItems.map((item, i) => {
            const conflict = !!rxError && rxError.includes(item.drugName) && item.drugName.trim() !== "";
            return (
              <div key={i}>
                <div className={styles.rxRow}>
                  <input className={styles.input} placeholder="Drug name" value={item.drugName} disabled={rxStatus === "signed"}
                    onChange={(e) => updateRxItem(i, "drugName", e.target.value)} />
                  <input className={styles.input} placeholder="Dosage" value={item.dosage} disabled={rxStatus === "signed"}
                    onChange={(e) => updateRxItem(i, "dosage", e.target.value)} />
                  <input className={styles.input} placeholder="Frequency" value={item.frequency} disabled={rxStatus === "signed"}
                    onChange={(e) => updateRxItem(i, "frequency", e.target.value)} />
                  <input className={styles.input} placeholder="Duration" value={item.duration} disabled={rxStatus === "signed"}
                    onChange={(e) => updateRxItem(i, "duration", e.target.value)} />
                  <input className={styles.input} placeholder="Instructions" value={item.instructions} disabled={rxStatus === "signed"}
                    onChange={(e) => updateRxItem(i, "instructions", e.target.value)} />
                  {conflict && (
                    <input className={styles.input} placeholder="Allergy override reason" value={item.allergyOverrideReason}
                      onChange={(e) => updateRxItem(i, "allergyOverrideReason", e.target.value)} />
                  )}
                  {rxStatus !== "signed" && rxItems.length > 1 && (
                    <button type="button" className={styles.removeBtn} onClick={() => removeRxItem(i)} aria-label="Remove">×</button>
                  )}
                </div>
                {/* NB-107: severity-scaled — only "severe" ever blocked the save (rxError above); a
                    moderate/mild match still shows here as a passive warning. */}
                {item.allergyWarning && !conflict && (
                  <div className={styles.rxWarning}>⚠ Matches recorded allergy: {item.allergyWarning}</div>
                )}
                {item.pregnancyWarning && (
                  <div className={styles.rxWarning}>⚠ {item.pregnancyWarning}</div>
                )}
                {item.controlledSubstanceWarning && (
                  <div className={styles.rxWarning}>⚠ {item.controlledSubstanceWarning}</div>
                )}
              </div>
            );
          })}
          {rxStatus !== "signed" && (
            <button type="button" className={styles.backBtn} onClick={addRxItem}>+ Add drug</button>
          )}

          <div className={styles.previousMeds}>
            <div className={styles.rxTitle} style={{ fontSize: "12px" }}>Weight-based dose calculator</div>
            <div className={styles.rxRow}>
              <input className={styles.input} placeholder="mg/kg" value={doseMgPerKg} onChange={(e) => setDoseMgPerKg(e.target.value)} />
              <button type="button" className={styles.backBtn} onClick={calculateDose} disabled={!doseMgPerKg}>Calculate</button>
              {doseResult && (
                <span className={styles.saveStatus}>
                  {doseResult.totalDoseMg} mg total ({doseResult.weightKg} kg × {doseResult.mgPerKg} mg/kg)
                </span>
              )}
            </div>
            {doseError && <div className={styles.errorState} role="alert">{doseError}</div>}
          </div>

          <div className={styles.previousMeds}>
            <div className={styles.rxTitle} style={{ fontSize: "12px" }}>Favourite Rx sets</div>
            {favouriteSets.length === 0 ? (
              <div className={styles.saveStatus}>No saved sets yet.</div>
            ) : (
              favouriteSets.map((set) => (
                <div key={set.id} className={styles.previousMedsRow}>
                  <span>{set.name} ({set.items.map((i) => i.drugName).join(", ")})</span>{" "}
                  {rxStatus !== "signed" && (
                    <button type="button" className={styles.backBtn} onClick={() => applySet(set.id)}>Apply</button>
                  )}
                </div>
              ))
            )}
            {rxStatus !== "signed" && (
              <div className={styles.rxRow} style={{ marginTop: "8px" }}>
                <input className={styles.input} placeholder="Save current items as set…" value={newSetName} onChange={(e) => setNewSetName(e.target.value)} />
                <button type="button" className={styles.backBtn} onClick={saveCurrentAsSet} disabled={!newSetName.trim()}>Save set</button>
              </div>
            )}
          </div>

          {previousMeds.length > 0 && (
            <div className={styles.previousMeds}>
              <div className={styles.rxTitle} style={{ fontSize: "12px" }}>Previous medicines</div>
              {previousMeds.map((rx) => (
                <div key={rx.id} className={styles.previousMedsRow}>
                  {rx.signedAt && <span className={styles.saveStatus}>{new Date(rx.signedAt).toLocaleDateString()} — </span>}
                  {rx.items.map((i) => i.drugName).join(", ")}
                </div>
              ))}
            </div>
          )}
        </div>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Consultation Workspace</h1>
        <p className={styles.subtitle}>Today&apos;s queue.</p>
      </div>

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to the queue.</div>
        ) : error ? (
          <div className={styles.errorState}>{error}</div>
        ) : rows.length === 0 ? (
          <div className={styles.state}>No patients in today&apos;s queue.</div>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th>Token</th><th>Patient</th><th>Status</th><th></th></tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td className={styles.token}>{r.priority && <span className={styles.priorityDot} />}#{r.tokenNumber}</td>
                    <td>{r.patientName}</td>
                    <td><span className={`${styles.pill} ${STATUS_CLASS[r.status] ?? ""}`}>{r.status.replace("_", " ")}</span></td>
                    <td>
                      {r.status === "vitals_done" && <button className={styles.actionBtn} onClick={() => startConsult(r)}>Start consult</button>}
                      {r.status === "in_consult" && <button className={styles.actionBtn} onClick={() => openConsult(r)}>Resume</button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </main>
  );
}
