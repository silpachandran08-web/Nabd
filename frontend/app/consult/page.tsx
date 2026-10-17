"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import Dialog from "../components/ui/Dialog";
import Icon, { type IconName } from "../components/ui/Icon";
import StatusPill, { type PillTone } from "../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { can } from "../lib/permissions";
import { getIdentity } from "../lib/session";
import { useClinicalContext } from "../components/consult/clinical";
import AllergyRegister from "../components/consult/AllergyRegister";
import ConflictDialog from "../components/consult/ConflictDialog";
import ConsultTimer from "../components/consult/ConsultTimer";
import DiagnosisField from "../components/consult/DiagnosisField";
import NoteTemplates from "../components/consult/NoteTemplates";
import DoseCalculator from "../components/consult/DoseCalculator";
import DentalDrawer from "../components/consult/DentalDrawer";
import DoctorOrders from "../components/consult/DoctorOrders";
import { deleteDraft, readDraft, writeDraft, type NoteFields } from "../components/consult/draftStore";
import FollowUpPanel from "../components/consult/FollowUpPanel";
import MedicinesList from "../components/consult/MedicinesList";
import PackageSessionCard from "../components/consult/PackageSessionCard";
import PreConsultDialog from "../components/consult/PreConsultDialog";
import RxWarnings, { OverrideDialog, WARNING_TITLE } from "../components/consult/RxWarnings";
import RxPad from "../components/consult/RxPad";
import FavouriteRxSetsPanel from "../components/consult/FavouriteRxSetsPanel";
import SafetyStrip from "../components/consult/SafetyStrip";
import VisitOutputs from "../components/consult/VisitOutputs";
import VisitTimeline from "../components/consult/VisitTimeline";
import {
  buildTimeline, DEFAULT_START_STATUS, NOTE_FIELD_LABEL, NOTE_TITLE, nextPatient, queueStatusLabel, statusBeforeConsult, statusSequence,
} from "../components/consult/consultFlow";
import VitalsCard from "../components/consult/VitalsCard";
import {
  age, DONE_STATUSES, initials, minutesSince, rxItemLine, WAITING_STATUSES,
  type PatientDetail, type Prescription, type PrescriptionItem, type Problem, type QueueEntry, type Row, type RxWarning,
} from "../components/consult/shared";
import p from "../components/consult/consultParts.module.css";
import styles from "./consult.module.css";

// Matches GET /v1/queue (QueueController), GET /v1/patients/{id} (PatientController),
// and /v1/clinical/notes/{queueEntryId} (NoteController).
type TransferTarget = { departmentId: string; departmentName: string; doctors: { id: string; name: string }[] };
type Note = {
  id: string; queueEntryId: string; patientId: string; doctorId: string;
  subjective: string | null; objective: string | null; assessment: string | null; plan: string | null;
  diagnosis: string | null; status: string; signedAt: string | null; updatedAt: string;
};
type NoteDraft = NoteFields;
type NoteAmendment = { id: string; amendedBy: string; reason: string; diagnosis: string | null; createdAt: string };
type SaveState = "idle" | "saving" | "saved" | "error" | "offline";
type Step = "reason" | "examine" | "assess" | "plan" | "close";

// Step rail and the one-line note for the current step (wireframe cwSteps / cwStepNote).
const STEPS: { id: Step; label: string; hint: string; icon: IconName }[] = [
  { id: "reason", label: "Reason", hint: "Why they came — read, don't type.", icon: "inbox" },
  { id: "examine", label: "Examine", hint: "Specialty tools live here, inside the consult — no second screen.", icon: "stethoscope" },
  { id: "assess", label: "Assess", hint: "Two or three lines. Quick phrases cover most of it.", icon: "list" },
  { id: "plan", label: "Plan", hint: "Prescribe, order, follow up — actions only.", icon: "pulse" },
  { id: "close", label: "Close", hint: "What stays open after this visit.", icon: "check" },
];

const BLANK_RX_ITEM: PrescriptionItem = { drugName: "", dosage: "", frequency: "", duration: "", instructions: "" };

// The API returns nullable dosage/frequency/duration/instructions (a favourite set commonly omits
// duration) — every input below is a controlled component, so null must become "" before it renders.
function normalizeRxItem(i: PrescriptionItem): PrescriptionItem {
  return {
    ...i,
    dosage: i.dosage ?? "", frequency: i.frequency ?? "", duration: i.duration ?? "", instructions: i.instructions ?? "",
  };
}

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const AUTOSAVE_MS = 10_000;
const LOCAL_DRAFT_MS = 800;
const BLANK_DRAFT: NoteDraft = { subjective: "", objective: "", assessment: "", plan: "", diagnosis: "" };
const VISIT_LABEL: Record<string, string> = {
  walk_in: "Walk-in", referral: "Referral", online: "Online", social_media: "Social media",
  returning: "Returning", other: "Other", internal_transfer: "Internal transfer",
};

const STATUS_TONE: Record<string, { tone: PillTone; icon?: Parameters<typeof StatusPill>[0]["icon"] }> = {
  checked_in: { tone: "neutral", icon: "clock" },
  waiting: { tone: "neutral", icon: "clock" },
  vitals_pending: { tone: "warn", icon: "pulse" },
  vitals_done: { tone: "info", icon: "check" },
  in_consult: { tone: "sync", icon: "stethoscope" },
  checkout_pending: { tone: "ok", icon: "receipt" },
  completed: { tone: "ok", icon: "check" },
  no_show: { tone: "danger", icon: "x" },
};
function StatusBadge({ status }: { status: string }) {
  const t = STATUS_TONE[status] ?? { tone: "neutral" as PillTone };
  return <StatusPill tone={t.tone} icon={t.icon} label={queueStatusLabel(status)} />;
}

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

function toFields(n: Note): NoteFields {
  return { subjective: n.subjective ?? "", objective: n.objective ?? "", assessment: n.assessment ?? "", plan: n.plan ?? "", diagnosis: n.diagnosis ?? "" };
}
const sameFields = (a: NoteFields, b: NoteFields) => (Object.keys(a) as (keyof NoteFields)[]).every((k) => a[k] === b[k]);
const isStep = (v: string | null): v is Step => STEPS.some((s) => s.id === v);

type StatusFilter = "all" | "waiting" | "in_consult" | "done";

export default function ConsultPage() {
  const router = useRouter();
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [scope, setScope] = useState<"mine" | "all">("mine");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [selfId, setSelfId] = useState("");
  const [now, setNow] = useState(0);
  // departmentId -> the status a visit must be in before it can move to in_consult (that
  // department's configured pipeline; a department without vitals starts from "waiting").
  const [startStatus, setStartStatus] = useState<Record<string, string | null>>({});
  // The URL mirrors the open visit, but only once a ?entry= deep link has been read; otherwise the
  // mirror effect would strip the params on mount, before the deep link is consumed.
  const [urlReady, setUrlReady] = useState(false);

  const [activeEntry, setActiveEntry] = useState<Row | null>(null);
  const [activePatient, setActivePatient] = useState<PatientDetail | null>(null);
  const [step, setStep] = useState<Step>("reason");
  const [note, setNote] = useState<NoteDraft>(BLANK_DRAFT);
  const [noteStatus, setNoteStatus] = useState<"draft" | "signed">("draft");
  const [signedAt, setSignedAt] = useState<string | null>(null);
  // Editing and saving wait until the server note is known; a failed load must never let a save
  // replace the server's draft with blank or partial fields (T1-BUG-3).
  const [noteLoad, setNoteLoad] = useState<"loading" | "ok" | "error">("loading");
  const noteLoadRef = useRef<"loading" | "ok" | "error">("loading");
  const [orphanText, setOrphanText] = useState<NoteFields | null>(null); // local text not applied because the note was signed elsewhere
  const [dirty, setDirty] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null);
  const [online, setOnline] = useState(true);
  const [conflict, setConflict] = useState<{ mine: NoteFields; server: NoteFields; serverUpdatedAt: string | null } | null>(null);
  const [conflictOpen, setConflictOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [completing, setCompleting] = useState(false);
  const [closeAck, setCloseAck] = useState(false);
  const [shellError, setShellError] = useState<string | null>(null);
  // undefined = not known (still checking, or the lookup failed): never shown as "Not set" (T1-BUG-2).
  const [followUpLabel, setFollowUpLabel] = useState<string | null | undefined>(undefined);
  const [preview, setPreview] = useState<Row | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [showDental, setShowDental] = useState(false);
  const [showTransfer, setShowTransfer] = useState(false);
  const [transferTargets, setTransferTargets] = useState<TransferTarget[] | null>(null);
  const [transferDepartmentId, setTransferDepartmentId] = useState("");
  const [transferDoctorId, setTransferDoctorId] = useState("");
  const [transferReason, setTransferReason] = useState(""); // REC-021: server requires a reason for a department move
  const [transferNote, setTransferNote] = useState("");
  const [transferring, setTransferring] = useState(false);
  const [transferError, setTransferError] = useState<string | null>(null);
  const [rxItems, setRxItems] = useState<PrescriptionItem[]>([]);
  const [rxStatus, setRxStatus] = useState<"draft" | "signed">("draft");
  const [rxSaving, setRxSaving] = useState(false);
  const [rxError, setRxError] = useState<string | null>(null);
  const [showSignConfirm, setShowSignConfirm] = useState(false);
  const [signAck, setSignAck] = useState(false);
  const [rxDirty, setRxDirty] = useState(false); // edits since the last save: the warnings shown are then out of date
  const [override, setOverride] = useState<{ index: number; warning: RxWarning } | null>(null);
  const [overrideError, setOverrideError] = useState<string | null>(null);
  const [showAllergies, setShowAllergies] = useState(false);
  const [signing, setSigning] = useState(false);
  const [signError, setSignError] = useState<string | null>(null);
  const [amendments, setAmendments] = useState<NoteAmendment[]>([]);
  const [showAmendForm, setShowAmendForm] = useState(false);
  const [amendReason, setAmendReason] = useState("");
  const [amendDiagnosis, setAmendDiagnosis] = useState("");
  const [amending, setAmending] = useState(false);

  const dirtyRef = useRef(dirty);
  const noteRef = useRef(note);
  const conflictRef = useRef(conflict);
  const baseUpdatedRef = useRef<string | null>(null); // server note's updatedAt when we last synced
  const needsServerCheckRef = useRef(false); // compare with the server copy before the next PATCH
  const localTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const openSeqRef = useRef(0);
  const deepLinkRef = useRef<{ entry: string; step: Step | null } | null>(null);
  useEffect(() => {
    dirtyRef.current = dirty;
    noteRef.current = note;
    conflictRef.current = conflict;
  }, [dirty, note, conflict]);

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

  const { ctx, reload: reloadCtx } = useClinicalContext(authedFetch, activeEntry?.patientId ?? null, activeEntry?.id ?? null);

  const load = useCallback(async (which: "mine" | "all" = "mine") => {
    setLoading(true);
    setError(null);
    setForbidden(false);
    const token = localStorage.getItem("nabd_access_token");
    if (!token) {
      router.replace("/login");
      return;
    }
    const self = String(decodeJwt(token).sub ?? "");
    setSelfId(self);
    try {
      const res = await authedFetch(which === "mine" ? `/queue?doctorId=${self}` : "/queue");
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
      // One patient request per distinct patient (not per row): the detail carries the allergy,
      // chronic-condition and package flags shown in the list. There is no batch endpoint yet.
      const byPatient = new Map<string, Promise<PatientDetail | null>>();
      for (const e of entries) {
        if (!byPatient.has(e.patientId)) {
          byPatient.set(e.patientId, authedFetch(`/patients/${e.patientId}`).then(async (r) => (r?.ok ? ((await r.json()) as PatientDetail) : null)).catch(() => null));
        }
      }
      const withNames = await Promise.all(entries.map(async (e): Promise<Row> => {
        const patient = (await byPatient.get(e.patientId)) ?? null;
        return { ...e, patientName: patient?.name ?? "Unknown patient", patient };
      }));
      setRows(withNames);
      // One flow lookup per department, so "Start consultation" is offered exactly where the server
      // will accept the move. An unreadable flow falls back to the default pipeline (vitals first).
      const depts = [...new Set(entries.map((e) => e.departmentId))];
      const flows = await Promise.all(depts.map(async (d) => {
        try {
          const r = await authedFetch(`/departments/${d}/flow`);
          if (!r?.ok) return [d, DEFAULT_START_STATUS] as const;
          const steps: { stepType: string }[] = await r.json();
          return [d, statusBeforeConsult(statusSequence(steps.map((x) => x.stepType)))] as const;
        } catch {
          return [d, DEFAULT_START_STATUS] as const;
        }
      }));
      setStartStatus(Object.fromEntries(flows));
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
      setNow(Date.now());
    }
  }, [authedFetch, router]);

  useEffect(() => {
    void Promise.resolve().then(() => {
      const q = new URLSearchParams(window.location.search);
      const entry = q.get("entry");
      if (entry) deepLinkRef.current = { entry, step: isStep(q.get("step")) ? (q.get("step") as Step) : null };
      else setUrlReady(true);
      setOnline(navigator.onLine);
      return load("mine");
    });
  }, [load]);

  // Wait times re-render once a minute.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    const on = () => { setOnline(true); needsServerCheckRef.current = true; };
    const off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => { window.removeEventListener("online", on); window.removeEventListener("offline", off); };
  }, []);

  function changeScope(next: "mine" | "all") {
    setScope(next);
    void load(next);
  }

  // Keep the visit and step in the URL so My Day (and a reload) can deep-link into the workspace.
  useEffect(() => {
    if (!urlReady) return;
    const url = new URL(window.location.href);
    if (activeEntry) {
      url.searchParams.set("entry", activeEntry.id);
      url.searchParams.set("step", step);
    } else {
      url.searchParams.delete("entry");
      url.searchParams.delete("step");
    }
    window.history.replaceState(null, "", url);
  }, [activeEntry, step, urlReady]);

  // ---- note persistence ---------------------------------------------------------------------

  function scheduleLocalDraft(entryId: string) {
    if (localTimerRef.current) clearTimeout(localTimerRef.current);
    localTimerRef.current = setTimeout(() => {
      if (!dirtyRef.current) return;
      void writeDraft({ entryId, note: noteRef.current, baseUpdatedAt: baseUpdatedRef.current, savedAt: new Date().toISOString() });
    }, LOCAL_DRAFT_MS);
  }

  async function saveNote(entryId: string) {
    if (conflictRef.current) return null; // never overwrite while a conflict is unresolved
    if (noteLoadRef.current !== "ok") return null; // the server copy was never read: nothing to save over
    const sent = noteRef.current;
    if (!navigator.onLine) {
      setSaveState("offline");
      await writeDraft({ entryId, note: sent, baseUpdatedAt: baseUpdatedRef.current, savedAt: new Date().toISOString() });
      needsServerCheckRef.current = true;
      return null;
    }
    setSaveState("saving");
    try {
      if (needsServerCheckRef.current) {
        const g = await authedFetch(`/clinical/notes/${entryId}`);
        if (g?.status === 200) {
          const server: Note = await g.json();
          if (server.status === "signed") {
            // Signed from another session while this device held unsaved text: never overwrite a
            // signed note. Lock the view to the server copy and keep the local text visible.
            needsServerCheckRef.current = false;
            baseUpdatedRef.current = server.updatedAt;
            setNote(toFields(server));
            setNoteStatus("signed");
            setSignedAt(server.signedAt);
            setDirty(false);
            setSaveState("idle");
            setOrphanText(sameFields(toFields(server), sent) ? null : sent);
            void deleteDraft(entryId);
            return null;
          }
          if (server.updatedAt !== baseUpdatedRef.current && !sameFields(toFields(server), sent)) {
            setConflict({ mine: sent, server: toFields(server), serverUpdatedAt: server.updatedAt });
            setConflictOpen(true);
            setSaveState("error");
            return null;
          }
          baseUpdatedRef.current = server.updatedAt;
        } else if (g && g.status !== 404) {
          setSaveState("error");
          return g;
        }
        needsServerCheckRef.current = false;
      }
      const res = await authedFetch(`/clinical/notes/${entryId}`, { method: "PATCH", body: JSON.stringify(sent) });
      if (res?.ok) {
        if (noteRef.current === sent) setDirty(false);
        setLastSavedAt(new Date());
        setSaveState("saved");
        try {
          const saved: Partial<Note> = await res.clone().json();
          if (typeof saved.updatedAt === "string") baseUpdatedRef.current = saved.updatedAt;
        } catch { /* body not needed */ }
        if (noteRef.current === sent) void deleteDraft(entryId);
      } else {
        setSaveState("error");
        needsServerCheckRef.current = true;
        await writeDraft({ entryId, note: sent, baseUpdatedAt: baseUpdatedRef.current, savedAt: new Date().toISOString() });
      }
      return res;
    } catch {
      setSaveState(navigator.onLine ? "error" : "offline");
      needsServerCheckRef.current = true;
      await writeDraft({ entryId, note: sent, baseUpdatedAt: baseUpdatedRef.current, savedAt: new Date().toISOString() });
      return null;
    }
  }

  // Autosave every 10s while there are unsaved changes — cleared when the shell closes. A failed
  // save keeps `dirty` set, so this retries on the next tick.
  useEffect(() => {
    if (!activeEntry) return;
    const id = setInterval(() => {
      if (dirtyRef.current) saveNote(activeEntry.id);
    }, AUTOSAVE_MS);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeEntry?.id]);

  // On reconnect, retry straight away (the server copy is compared first).
  useEffect(() => {
    if (online && activeEntry && dirtyRef.current) void saveNote(activeEntry.id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [online]);

  // ---- opening visits -----------------------------------------------------------------------

  // Who may start which visit: the visit doctor, from the status just before in_consult.
  const canStartRow = (r: Row) => {
    const from = r.departmentId in startStatus ? startStatus[r.departmentId] : DEFAULT_START_STATUS;
    return r.doctorId === selfId && from !== null && r.status === from;
  };

  function openRow(row: Row) {
    setPreviewError(null);
    if (row.status === "in_consult" && row.doctorId === selfId) void openConsult(row);
    else setPreview(row);
  }

  async function startConsult(row: Row) {
    setStarting(true);
    setPreviewError(null);
    try {
      const res = await authedFetch(`/queue/${row.id}/status`, { method: "PATCH", body: JSON.stringify({ status: "in_consult" }) });
      if (!res) return;
      if (!res.ok) {
        const pr: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't start the consultation." }));
        setPreviewError(pr.detail || "Couldn't start the consultation.");
        return;
      }
      setRows((prev) => prev.map((r) => (r.id === row.id ? { ...r, status: "in_consult" } : r)));
      setPreview(null);
      void openConsult({ ...row, status: "in_consult" });
    } catch {
      setPreviewError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setStarting(false);
    }
  }

  async function switchTo(row: Row) {
    if (!activeEntry || row.id === activeEntry.id) return;
    if (dirtyRef.current) {
      const r = await saveNote(activeEntry.id);
      if (!r?.ok) {
        setShellError("Couldn't save the note, so the patient was not switched. Your changes are kept on this device; try again.");
        return;
      }
    }
    openRow(row);
  }

  async function openConsult(row: Row, wantStep?: Step) {
    const seq = ++openSeqRef.current;
    const stale = () => seq !== openSeqRef.current;
    setActiveEntry(row);
    setActivePatient(row.patient);
    setStep(wantStep ?? "reason");
    setNote(BLANK_DRAFT);
    setNoteStatus("draft");
    setSignedAt(null);
    setOrphanText(null);
    noteLoadRef.current = "loading";
    setNoteLoad("loading");
    setDirty(false);
    setLastSavedAt(null);
    setSaveState("idle");
    setConflict(null);
    setNotice(null);
    setShellError(null);
    setCloseAck(false);
    setFollowUpLabel(undefined);
    setShowDental(false);
    setRxItems([]);
    setRxStatus("draft");
    setRxError(null);
    setRxDirty(false);
    setOverride(null);
    setShowAllergies(false);
    setAmendments([]);
    setShowAmendForm(false);
    setAmendReason("");
    setAmendDiagnosis("");
    baseUpdatedRef.current = null;
    needsServerCheckRef.current = false;

    try {
      const pRes = await authedFetch(`/patients/${row.patientId}`);
      if (stale()) return;
      if (pRes?.ok) setActivePatient(await pRes.json());

      const nRes = await authedFetch(`/clinical/notes/${row.id}`);
      if (stale()) return;
      let server: Note | null = null;
      if (nRes?.status === 200) {
        server = await nRes.json();
        baseUpdatedRef.current = server!.updatedAt;
        setNote(toFields(server!));
        setNoteStatus(server!.status as "draft" | "signed");
        setSignedAt(server!.signedAt);
        if (server!.status === "signed") {
          const aRes = await authedFetch(`/clinical/notes/${row.id}/amendments`);
          if (stale()) return;
          if (aRes?.ok) setAmendments(await aRes.json());
        }
      } else if (nRes && nRes.status !== 404) {
        noteLoadRef.current = "error";
        setNoteLoad("error");
      }
      if (!nRes) return;
      if (noteLoadRef.current === "loading") { noteLoadRef.current = "ok"; setNoteLoad("ok"); }

      // A local draft means the last save never reached the server (offline, crash, reload).
      // Not when the note failed to load: the draft is kept untouched for the next successful open.
      const draft = noteLoadRef.current === "ok" ? await readDraft(row.id) : null;
      if (stale()) return;
      if (draft) {
        const serverFields = server ? toFields(server) : BLANK_DRAFT;
        if (server?.status === "signed") {
          // Same as the reconnect path: never applied to a signed note, but listed for an amendment.
          void deleteDraft(row.id);
          if (!sameFields(draft.note, serverFields)) setOrphanText(draft.note);
        } else if (sameFields(draft.note, serverFields)) {
          void deleteDraft(row.id);
        } else if ((server?.updatedAt ?? null) === draft.baseUpdatedAt) {
          setNote(draft.note);
          setDirty(true);
          needsServerCheckRef.current = true;
          setNotice(`Restored an unsaved local draft from ${new Date(draft.savedAt).toLocaleString()}.`);
        } else {
          setConflict({ mine: draft.note, server: serverFields, serverUpdatedAt: server?.updatedAt ?? null });
          setConflictOpen(true);
          setNotice("The note changed on the server since your local draft. Choose what to keep.");
        }
      }

      const rRes = await authedFetch(`/clinical/prescriptions/${row.id}`);
      if (stale()) return;
      if (rRes?.status === 200) {
        const rx: Prescription = await rRes.json();
        setRxItems(rx.items.length > 0 ? rx.items.map(normalizeRxItem) : [{ ...BLANK_RX_ITEM }]);
        setRxStatus(rx.status as "draft" | "signed");
      } else {
        setRxItems([{ ...BLANK_RX_ITEM }]);
      }
    } catch {
      if (!stale()) {
        if (noteLoadRef.current === "loading") { noteLoadRef.current = "error"; setNoteLoad("error"); }
        setShellError("Couldn't reach the server. Check your connection, then reopen the visit.");
      }
    }
  }

  // Deep link (?entry=&step=) once the queue is loaded.
  useEffect(() => {
    const link = deepLinkRef.current;
    if (!link || loading) return;
    deepLinkRef.current = null;
    setUrlReady(true);
    const row = rows.find((r) => r.id === link.entry);
    if (!row) return;
    if (row.status === "in_consult" && row.doctorId === selfId) void Promise.resolve().then(() => openConsult(row, link.step ?? undefined));
    else void Promise.resolve().then(() => setPreview(row));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, loading, selfId]);

  // ---- prescription -------------------------------------------------------------------------

  // Editing a line drops its warnings from view (they were computed for the old text); the server
  // recomputes them on the next save, and signing is checked again on the server regardless.
  // DOC-033: the structured pad (RxPad) edits several fields at once; the name fields keep that rule.
  function patchRxItem(index: number, patch: Partial<PrescriptionItem>) {
    setRxDirty(true);
    setRxItems((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch, ...("drugName" in patch || "brandName" in patch ? { warnings: undefined } : {}) } : item)));
  }
  function addRxItem() { setRxDirty(true); setRxItems((prev) => [...prev, { ...BLANK_RX_ITEM }]); }
  function removeRxItem(index: number) { setRxDirty(true); setRxItems((prev) => prev.filter((_, i) => i !== index)); }
  function focusDrug(index: number) {
    const el = document.getElementById(`rx-drug-${index}`) as HTMLInputElement | null;
    el?.focus();
    el?.select();
  }

  // Saves the pad and returns the saved prescription (with the server's current warnings), or null.
  // Only the fields the API accepts are sent; a recorded decision is carried by the server unless a new
  // one is passed in `overrides` (keyed by item index).
  async function saveRx(overrides?: { index: number; kind: string; reasonCode: string; note: string }): Promise<Prescription | null> {
    if (!activeEntry) return null;
    if (!navigator.onLine) {
      setRxError("You're offline. The prescription can't be saved, checked or signed until you reconnect; your changes stay on this screen.");
      return null;
    }
    setRxSaving(true);
    setRxError(null);
    try {
      const items = rxItems
        .map((i, index) => ({ i, index }))
        .filter(({ i }) => i.drugName.trim() !== "")
        .map(({ i, index }) => ({
          drugName: i.drugName, dosage: i.dosage, frequency: i.frequency, duration: i.duration, instructions: i.instructions,
          brandName: i.brandName?.trim() || undefined, sigPattern: i.sigPattern || undefined, route: i.route || undefined,
          quantity: i.quantity ?? undefined, quantityUnit: i.quantityUnit?.trim() || undefined,
          overrides: overrides && overrides.index === index ? [{ kind: overrides.kind, reasonCode: overrides.reasonCode, note: overrides.note || undefined }] : undefined,
        }));
      const res = await authedFetch(`/clinical/prescriptions/${activeEntry.id}`, { method: "PATCH", body: JSON.stringify({ items }) });
      if (!res?.ok) {
        const pr: Problem = await res?.json().catch(() => ({ title: "Error", detail: "Couldn't save prescription." }));
        setRxError(pr?.detail || "Couldn't save prescription.");
        return null;
      }
      const rx: Prescription = await res.json();
      setRxItems(rx.items.length > 0 ? rx.items.map(normalizeRxItem) : [{ ...BLANK_RX_ITEM }]);
      setRxDirty(false);
      return rx;
    } catch {
      setRxError("Couldn't reach the server. Check your connection and try again.");
      return null;
    } finally {
      setRxSaving(false);
    }
  }

  // Step 1 of signing: save (so the server re-checks allergy/pregnancy/controlled rules), then ask
  // the doctor to confirm what is being signed. A save that the server rejects stays on the pad.
  async function reviewAndSign() {
    if (!rxItems.some((i) => i.drugName.trim() !== "")) {
      setRxError("Add at least one drug before signing.");
      return;
    }
    const saved = await saveRx();
    if (!saved) return;
    if (saved.items.some((i) => (i.warnings ?? []).some((w) => w.requiresDecision && !w.resolved))) {
      return; // the warning cards and the "Resolve the warning first" button now show what is open
    }
    setSignAck(false);
    setSignError(null);
    setShowSignConfirm(true);
  }

  async function confirmSignRx() {
    if (!activeEntry) return;
    setSigning(true);
    setSignError(null);
    try {
      const res = await authedFetch(`/clinical/prescriptions/${activeEntry.id}/sign`, { method: "POST" });
      if (!res) return;
      if (!res.ok) {
        const pr: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't sign the prescription." }));
        setSignError(pr.detail || "Couldn't sign the prescription.");
        // The server re-checks safety at signing (e.g. an allergy recorded since the last save): show its current view.
        const fresh = await authedFetch(`/clinical/prescriptions/${activeEntry.id}`);
        if (fresh?.ok) {
          const rx: Prescription = await fresh.json();
          setRxItems(rx.items.length > 0 ? rx.items.map(normalizeRxItem) : [{ ...BLANK_RX_ITEM }]);
          setRxDirty(false);
        }
        return;
      }
      setRxStatus("signed");
      setShowSignConfirm(false);
      setNotice("Prescription signed.");
      reloadCtx();
    } catch {
      setSignError("Couldn't reach the server. The prescription was not signed; try again.");
    } finally {
      setSigning(false);
    }
  }

  async function confirmOverride(reasonCode: string, note: string) {
    if (!override) return;
    setOverrideError(null);
    const saved = await saveRx({ index: override.index, kind: override.warning.kind, reasonCode, note });
    if (!saved) { setOverrideError("The decision was not recorded. Nothing was changed; try again."); return; }
    setOverride(null);
    setNotice("Decision recorded and audited.");
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

  function updateField(field: keyof NoteDraft, value: string) {
    if (noteLoadRef.current !== "ok") return;
    setNote((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
    if (activeEntry) scheduleLocalDraft(activeEntry.id);
  }

  function resolveConflict(merged: NoteFields) {
    const rebase = conflict?.serverUpdatedAt ?? null;
    setConflict(null);
    setConflictOpen(false);
    conflictRef.current = null;
    setNote(merged);
    setDirty(true);
    if (activeEntry) {
      // Re-base on the server copy the doctor just compared against.
      needsServerCheckRef.current = true;
      baseUpdatedRef.current = rebase;
      scheduleLocalDraft(activeEntry.id);
    }
    setNotice("Choices applied. The note will be saved after one more check against the server copy.");
  }

  async function closeShell() {
    if (activeEntry && dirtyRef.current) {
      const r = await saveNote(activeEntry.id);
      if (!r?.ok) {
        setShellError("Couldn't save the note. It is kept on this device; stay on this page and try again.");
        return;
      }
    }
    openSeqRef.current++;
    setActiveEntry(null);
    void load(scope);
  }

  // ---- close consultation (DOC-029) ---------------------------------------------------------

  const hasDrugs = rxItems.some((i) => i.drugName.trim() !== "");
  const noteEmpty = Object.values(note).every((v) => v.trim() === "");
  const closeIssues: string[] = [];
  if (noteStatus !== "signed") {
    if (note.diagnosis.trim() === "") closeIssues.push("No diagnosis is entered.");
    if (noteEmpty) closeIssues.push("The note is empty.");
  }
  if (hasDrugs && rxStatus !== "signed") closeIssues.push("The prescription is not signed, so the patient would leave without it.");
  const handoffPending = noteStatus === "signed" && activeEntry?.status !== "checkout_pending";
  const closedOut = noteStatus === "signed" && activeEntry?.status === "checkout_pending";

  async function completeConsultation() {
    if (!activeEntry) return;
    setCompleting(true);
    setShellError(null);
    try {
      if (noteStatus !== "signed") {
        // Always flush a save first — even an untouched note needs one draft row to exist before it
        // can be signed, and this also catches the last few seconds of typing the 10s interval hasn't.
        needsServerCheckRef.current = needsServerCheckRef.current || dirtyRef.current;
        const saveRes = await saveNote(activeEntry.id);
        if (!saveRes?.ok) {
          setShellError(conflictRef.current ? "The note changed on the server. Resolve the differences before closing." : (navigator.onLine ? "Couldn't save the note. Try again before completing." : "You're offline. The note is kept on this device; it can be signed once you reconnect."));
          return;
        }
        const signRes = await authedFetch(`/clinical/notes/${activeEntry.id}/sign`, { method: "POST" });
        if (!signRes?.ok) {
          const pr: Problem = await signRes?.json().catch(() => ({ title: "Error", detail: "Couldn't sign the note." }));
          setShellError(pr?.detail || "Couldn't sign the note.");
          return;
        }
        setNoteStatus("signed");
        const signed: Partial<Note> | null = await signRes.json().catch(() => null);
        setSignedAt(typeof signed?.signedAt === "string" ? signed.signedAt : new Date().toISOString());
      }
      const statusRes = await authedFetch(`/queue/${activeEntry.id}/status`, { method: "PATCH", body: JSON.stringify({ status: "checkout_pending" }) });
      if (!statusRes?.ok) {
        const pr: Problem | null = await statusRes?.json().catch(() => null);
        setShellError(`Note signed, but couldn't move the patient to checkout${pr?.detail ? ` (${pr.detail})` : ""}. Use “Retry checkout handoff”.`);
        return;
      }
      setActiveEntry((prev) => (prev ? { ...prev, status: "checkout_pending" } : prev));
      setRows((prev) => prev.map((r) => (r.id === activeEntry.id ? { ...r, status: "checkout_pending" } : r)));
      setNotice("Sent to checkout. Reception can see this visit now.");
      // Stay in the workspace (don't auto-close): follow-up and amendments remain reachable.
    } catch {
      setShellError(noteStatus === "signed" || handoffPending
        ? "Couldn't reach the server. Retry the checkout handoff when you're back online."
        : "Couldn't reach the server. The note was not signed; try again.");
    } finally {
      setCompleting(false);
    }
  }

  // ---- transfer -----------------------------------------------------------------------------

  async function openTransfer() {
    if (!activeEntry) return;
    setTransferError(null);
    setTransferDepartmentId("");
    setTransferDoctorId("");
    setTransferReason("");
    setTransferNote("");
    setTransferTargets(null);
    setShowTransfer(true);
    const res = await authedFetch(`/departments/${activeEntry.departmentId}/transfer-targets`);
    if (!res?.ok) {
      setTransferError("Couldn't load transfer options.");
      setTransferTargets([]);
      return;
    }
    setTransferTargets(await res.json());
  }

  async function submitTransfer() {
    if (!activeEntry || !transferDepartmentId || !transferDoctorId || !transferReason) return;
    setTransferring(true);
    setTransferError(null);
    try {
      const res = await authedFetch(`/queue/${activeEntry.id}/transfer`, {
        method: "POST",
        body: JSON.stringify({ toDepartmentId: transferDepartmentId, doctorId: transferDoctorId, reason: transferReason, ...(transferReason === "other" && transferNote.trim() ? { reasonNote: transferNote.trim() } : {}) }),
      });
      if (!res) return;
      if (!res.ok) {
        const pr: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't transfer the patient." }));
        setTransferError(pr.detail || "Couldn't transfer the patient.");
        return;
      }
      setShowTransfer(false);
      void closeShell();
    } finally {
      setTransferring(false);
    }
  }

  // ---- shared render pieces -----------------------------------------------------------------

  const dialogs = (
    <>
      {preview && (
        <PreConsultDialog
          row={preview}
          authedFetch={authedFetch}
          starting={starting}
          error={previewError}
          onClose={() => { setPreview(null); setPreviewError(null); }}
          onStart={canStartRow(preview) ? () => startConsult(preview) : undefined}
          canEditAllergies={can("clinical:edit")}
          onOpenVisit={(preview.status === "checkout_pending" || preview.status === "completed") && !dirty
            ? () => { const r = preview; setPreview(null); void openConsult(r); }
            : undefined}
        />
      )}
    </>
  );

  if (activeEntry) {
    const pat = activePatient;
    const saveBadge = conflict ? <StatusPill tone="warn" icon="alert" label="Sync conflict — review" />
      : !online || saveState === "offline"
      ? <StatusPill tone="sync" icon="sync" label="Offline · local draft" />
      : saveState === "saving" ? <StatusPill tone="sync" icon="sync" label="Saving…" />
      : saveState === "error" ? <StatusPill tone="danger" label="Couldn't save — retrying" />
      : dirty ? <StatusPill tone="warn" icon="clock" label="Unsaved changes" />
      : lastSavedAt ? <StatusPill tone="ok" label={`Saved ${lastSavedAt.toLocaleTimeString()}`} />
      : null;
    const locked = noteStatus === "signed";
    const openWarnings = rxItems.reduce((n, i) => n + (i.warnings ?? []).filter((w) => w.requiresDecision && !w.resolved).length, 0);
    // Recording a warning decision and signing are the visit doctor's (or a clinical:approve holder's); the server enforces it (403 not-prescriber), the UI simply doesn't offer it to anyone else.
    const mayDecideRx = activeEntry.doctorId === selfId || can("clinical:approve");
    // Advisory warnings (moderate/mild allergy) don't block but are acknowledged at signing; decisions already recorded are shown, not re-asked.
    const advisoryItems = rxItems.filter((i) => (i.warnings ?? []).some((w) => !w.requiresDecision));
    const doctorName = getIdentity()?.staffName ?? "";
    const signedBlocked = closeIssues.length > 0 && !closeAck;
    const canEditNote = can("clinical:edit");
    const stepIndex = Math.max(0, STEPS.findIndex((x) => x.id === step));
    const next = closedOut ? nextPatient(rows, activeEntry.id, selfId, canStartRow) : null;
    const vd = ctx.vitals.data;
    const timeline = buildTimeline({
      checkedInAt: activeEntry.createdAt ?? null,
      vitals: {
        status: ctx.vitals.status, recordedAt: vd?.recordedAt ?? null,
        summary: vd ? [
          vd.bpSystolic != null && vd.bpDiastolic != null ? `BP ${vd.bpSystolic}/${vd.bpDiastolic}` : null,
          vd.pulseBpm != null ? `${vd.pulseBpm} bpm` : null,
          vd.tempCelsius != null ? `${vd.tempCelsius} °C` : null,
          vd.spo2Percent != null ? `SpO₂ ${vd.spo2Percent}%` : null,
        ].filter(Boolean).join(" · ") || null : null,
      },
      status: activeEntry.status,
      draft: { savedAt: lastSavedAt ? lastSavedAt.toISOString() : null, pendingOnDevice: dirty && (saveState === "offline" || saveState === "error" || !online) },
      signedAt,
      noteSigned: locked,
      amendments,
    });
    const primaryLabel = completing ? "Working…" : handoffPending ? "Retry checkout handoff" : closedOut ? "Sent to checkout" : "Sign note and send to checkout";

    return (
      <main className={`${styles.page} ${styles.pageWide}`}>
        <div className={styles.strip}>
          <div className={styles.stripIdentity}>
            <button className={styles.backBtn} onClick={closeShell}>Queue</button>
            {pat && <span className={styles.avatar} aria-hidden="true">{initials(pat.name)}</span>}
            <span className={styles.stripText}>
              {pat && (
                <>
                  <span className={styles.stripName}>{pat.name}</span>
                  <span className={styles.stripMeta}>{pat.mrn} · {age(pat.dob)} · {pat.gender} · Token #{activeEntry.tokenNumber}</span>
                </>
              )}
              {activeEntry.presentingComplaint?.trim() && <span className={styles.stripComplaint}>Reason: {activeEntry.presentingComplaint}</span>}
            </span>
            <StatusBadge status={activeEntry.status} />
            {saveBadge}
            {(activeEntry.status === "in_consult" || locked) && <ConsultTimer entryId={activeEntry.id} ended={locked} />}
          </div>
        </div>

        {!online && (
          <div className={`${p.notice} ${p.noticeInfo}`} role="status" style={{ marginBlockEnd: "var(--nb-space-16)" }}>
            <Icon name="sync" size={16} />
            <span>You&apos;re offline. The note is saved on this device and will never silently overwrite server data; it is compared with the server copy when you reconnect. Prescriptions, orders and follow-ups need a connection.</span>
          </div>
        )}
        {shellError && <div className={styles.errorState} role="alert">{shellError}</div>}
        {noteLoad === "error" && (
          <div className={`${p.notice} ${p.noticeDanger}`} role="alert" style={{ marginBlockEnd: "var(--nb-space-16)" }}>
            <Icon name="alert" size={16} />
            <span>Couldn&apos;t load the note, so editing and saving are locked to protect the saved copy. <button type="button" className={p.linkBtn} onClick={() => void openConsult(activeEntry, step)}>Try again</button></span>
          </div>
        )}
        {notice && (
          <div className={`${p.notice} ${p.noticeInfo}`} role="status" style={{ marginBlockEnd: "var(--nb-space-16)" }}>
            <Icon name="info" size={16} />
            <span>{notice} <button type="button" className={p.linkBtn} onClick={() => setNotice(null)}>Dismiss</button></span>
          </div>
        )}
        {orphanText && (
          <div className={`${p.notice} ${p.noticeWarn}`} role="alert" style={{ marginBlockEnd: "var(--nb-space-16)" }}>
            <Icon name="alert" size={16} />
            <span>
              This note was signed in another session, so the text on this device was not saved. Copy anything still needed into an amendment.
              <ul style={{ margin: "4px 0 0", paddingInlineStart: "18px" }}>
                {(Object.keys(orphanText) as (keyof NoteFields)[]).filter((k) => orphanText[k].trim() !== "").map((k) => <li key={k}><strong>{NOTE_FIELD_LABEL[k]}:</strong> {orphanText[k]}</li>)}
              </ul>
              <button type="button" className={p.linkBtn} onClick={() => setOrphanText(null)}>Dismiss</button>
            </span>
          </div>
        )}
        {conflict && (
          <div className={`${p.notice} ${p.noticeWarn}`} role="alert" style={{ marginBlockEnd: "var(--nb-space-16)" }}>
            <Icon name="alert" size={16} />
            <span>Saving is paused: this note changed on the server. <button type="button" className={p.linkBtn} onClick={() => setConflictOpen(true)}>Review differences</button></span>
          </div>
        )}

        <SafetyStrip patient={pat} ctx={ctx} onRetry={reloadCtx} onManageAllergies={can("clinical:view") ? () => setShowAllergies(true) : undefined} canEditPregnancy={can("clinical:edit")} authedFetch={authedFetch} />

        <div className={styles.workspace}>
          <aside className={styles.today} aria-label="Today's patients">
            <h2 className={styles.todayTitle}>Today&apos;s patients</h2>
            <ul className={styles.todayList}>
              {rows.map((r) => (
                <li key={r.id}>
                  <button type="button" className={`${styles.todayBtn} ${r.id === activeEntry.id ? styles.todayBtnOn : ""}`} aria-current={r.id === activeEntry.id ? "true" : undefined} onClick={() => switchTo(r)}>
                    <span><strong>#{r.tokenNumber}</strong> {r.patientName}</span>
                    <span className={styles.todayMeta}>{r.appointmentId ? "Appointment" : (VISIT_LABEL[r.source ?? ""] ?? "Walk-in")}</span>
                    <span className={styles.flags}>
                      {(r.patient?.allergies.length ?? 0) > 0 && <StatusPill tone="danger" label="Allergy" />}
                      {r.priority && <StatusPill tone="urgent" label="Urgent" />}
                      <StatusBadge status={r.status} />
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </aside>

          <div className={styles.centerCol}>
            <nav aria-label="Consultation steps">
              <ol className={styles.steps}>
                {STEPS.map((s, i) => {
                  const done = i < stepIndex;
                  return (
                    <li key={s.id}>
                      <button type="button" className={`${styles.stepBtn} ${step === s.id ? styles.stepBtnOn : done ? styles.stepBtnDone : ""}`} aria-current={step === s.id ? "step" : undefined} onClick={() => setStep(s.id)}>
                        <Icon name={done ? "check" : s.icon} size={14} />
                        <span className={styles.stepName}>{i + 1}. {s.label}</span>
                        {done && <span className={p.srOnly}>(earlier step)</span>}
                      </button>
                    </li>
                  );
                })}
              </ol>
              <p className={styles.stepHint}>{STEPS[stepIndex].hint}</p>
            </nav>

            <VisitTimeline events={timeline} />

            {locked && (
              <div className={styles.signedBanner}>
                <span>This note is finalised. Changes need a documented amendment with a reason, which is audited.</span>
                {canEditNote && (
                  <span className={styles.followUpControls}>
                    <button className={styles.backBtn} onClick={() => setShowAmendForm((v) => !v)}>
                      {showAmendForm ? "Cancel amendment" : "Amend note"}
                    </button>
                  </span>
                )}
              </div>
            )}

            {showAmendForm && canEditNote && (
              <div className={styles.card} style={{ padding: "16px" }}>
                <div className={styles.noteField}>
                  <label className={styles.noteLabel} htmlFor="amendReason">Reason for amendment</label>
                  <input id="amendReason" className={styles.input} value={amendReason} onChange={(e) => setAmendReason(e.target.value)} />
                </div>
                <div className={styles.noteField} style={{ marginBlockStart: "8px" }}>
                  <label className={styles.noteLabel} htmlFor="amendDiagnosis">Corrected diagnosis (optional)</label>
                  <input id="amendDiagnosis" className={styles.input} value={amendDiagnosis} onChange={(e) => setAmendDiagnosis(e.target.value)} />
                </div>
                <button className={styles.completeBtn} style={{ marginBlockStart: "8px" }} onClick={submitAmendment} disabled={amending || !amendReason.trim()}>
                  {amending ? "Saving…" : "Save amendment"}
                </button>
              </div>
            )}

            {amendments.length > 0 && (
              <div className={styles.card} style={{ padding: "16px" }}>
                <div className={styles.rxTitle} style={{ fontSize: "12px" }}>Amendment history</div>
                {amendments.map((a) => (
                  <div key={a.id} className={styles.previousMedsRow}>
                    <span className={styles.saveStatus}>{new Date(a.createdAt).toLocaleString()} — </span>
                    {a.reason}{a.diagnosis ? ` (diagnosis: ${a.diagnosis})` : ""}
                  </div>
                ))}
              </div>
            )}

            {step === "reason" && (
              <>
                <section className={p.card} aria-label="Presenting complaint">
                  <h3 className={p.cardTitle}>Presenting complaint</h3>
                  {activeEntry.presentingComplaint?.trim()
                    ? <p className={p.complaint}>{activeEntry.presentingComplaint}</p>
                    : <p className={p.muted}>No reason was recorded at check-in. Ask the patient.</p>}
                </section>
                <VitalsCard vitals={ctx.vitals} onRetry={reloadCtx} />
                <div><button type="button" className={p.btn} onClick={() => setStep("examine")}>Continue to Examine</button></div>
              </>
            )}

            {step === "examine" && (
              <>
                {!locked && can("clinical:edit") && noteLoad === "ok" && <NoteTemplates authedFetch={authedFetch} patientId={activeEntry.patientId} entryId={activeEntry.id} note={note} onSetField={updateField} />}
                <div className={styles.card} style={{ padding: "24px" }}>
                  <h3 className={p.cardTitle}>{NOTE_TITLE}</h3>
                  <div className={styles.noteGrid}>
                    {(["subjective", "objective"] as const).map((field) => (
                      <div className={styles.noteField} key={field}>
                        <label className={styles.noteLabel} htmlFor={field}>{NOTE_FIELD_LABEL[field]}</label>
                        <textarea id={field} className={styles.textarea} value={note[field]} disabled={locked || !canEditNote || noteLoad !== "ok"} onChange={(e) => updateField(field, e.target.value)} />
                      </div>
                    ))}
                  </div>
                </div>
                {can("specialty_dental:view") && (
                  <section className={p.card} aria-label="Specialty tools">
                    <h3 className={p.cardTitle}>Specialty tools</h3>
                    <div className={p.row}>
                      <button type="button" className={p.btn} onClick={() => setShowDental(true)}>
                        <Icon name="stethoscope" size={16} /> Tooth chart
                      </button>
                      <span className={p.muted}>Opens beside this note with {pat?.name ?? "the patient"}&apos;s chart.</span>
                    </div>
                  </section>
                )}
              </>
            )}

            {step === "assess" && (
              <div className={styles.card} style={{ padding: "24px" }}>
                <h3 className={p.cardTitle}>{NOTE_TITLE}</h3>
                <div className={styles.noteGrid}>
                  <div className={styles.noteField}>
                    <label className={styles.noteLabel} htmlFor="assessment">{NOTE_FIELD_LABEL.assessment}</label>
                    <textarea id="assessment" className={styles.textarea} value={note.assessment} disabled={locked || !canEditNote || noteLoad !== "ok"} onChange={(e) => updateField("assessment", e.target.value)} />
                  </div>
                  <div className={styles.noteField}>
                    <DiagnosisField value={note.diagnosis} disabled={locked || !canEditNote || noteLoad !== "ok"} inputClassName={styles.input} onChange={(v) => updateField("diagnosis", v)} />
                  </div>
                </div>
              </div>
            )}

            {step === "plan" && (
              <>
                <div className={styles.card} style={{ padding: "24px" }}>
                  <div className={styles.noteField}>
                    <label className={styles.noteLabel} htmlFor="plan">{NOTE_FIELD_LABEL.plan}</label>
                    <textarea id="plan" className={styles.textarea} value={note.plan} disabled={locked || !canEditNote || noteLoad !== "ok"} onChange={(e) => updateField("plan", e.target.value)} />
                  </div>
                </div>

                <div className={styles.card} style={{ padding: "24px" }}>
                  <div className={styles.rxHeader}>
                    <h3 className={styles.rxTitle}>Prescription {rxStatus === "signed" && <StatusPill tone="ok" label="Signed" />}</h3>
                    {rxStatus === "signed" && can("clinical:view") && (
                      <div className={styles.rxHeaderActions}>
                        <Link className={styles.backBtn} style={{ display: "inline-flex", alignItems: "center", textDecoration: "none" }} href={`/consult/rx/${activeEntry.id}/print`}>Print prescription</Link>
                      </div>
                    )}
                    {rxStatus !== "signed" && (
                      <div className={styles.rxHeaderActions}>
                        <button className={styles.backBtn} onClick={() => void saveRx()} disabled={rxSaving}>{rxSaving ? "Saving…" : "Save"}</button>
                        {mayDecideRx && (
                          <button className={styles.completeBtn} onClick={reviewAndSign} disabled={rxSaving || (openWarnings > 0 && !rxDirty)}>
                            {openWarnings > 0 && !rxDirty ? "Resolve the warning first" : "Review and sign"}
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                  {rxStatus !== "signed" && <p className={p.muted}>The allergy check matches drug names and common drug classes only. It is not a drug database, so confirm against the patient&apos;s record.</p>}
                  {rxError && <div className={styles.errorState} role="alert">{rxError}</div>}
                  {openWarnings > 0 && rxStatus !== "signed" && (
                    <div className={`${p.notice} ${p.noticeDanger}`} role="alert" style={{ marginBlockEnd: "var(--nb-space-12)" }}>
                      <Icon name="alert" size={16} />
                      <span>{openWarnings} safety warning{openWarnings === 1 ? "" : "s"} need{openWarnings === 1 ? "s" : ""} a decision before this prescription can be signed.</span>
                    </div>
                  )}
                  {rxItems.map((item, i) => (
                    <div key={i}>
                      <RxPad item={item} index={i} locked={rxStatus === "signed"} authedFetch={authedFetch} onChange={(patch) => patchRxItem(i, patch)}>
                        {rxStatus !== "signed" && rxItems.length > 1 && (
                          <button type="button" className={styles.removeBtn} onClick={() => removeRxItem(i)} aria-label="Remove">×</button>
                        )}
                      </RxPad>
                      <RxWarnings
                        item={item}
                        locked={rxStatus === "signed"}
                        onOverride={mayDecideRx ? (warning) => { setOverrideError(null); setOverride({ index: i, warning }); } : undefined}
                        onChangeMedicine={() => focusDrug(i)}
                        onRemove={rxItems.length > 1 ? () => removeRxItem(i) : undefined}
                      />
                    </div>
                  ))}
                  {rxStatus !== "signed" && <button type="button" className={styles.backBtn} onClick={addRxItem}>+ Add drug</button>}

                  <div className={styles.previousMeds}>
                    <DoseCalculator key={activeEntry.patientId} authedFetch={authedFetch} patientId={activeEntry.patientId} queueEntryId={activeEntry.id} canRecordWeight={can("clinical:edit")} onWeightRecorded={reloadCtx} />
                  </div>

                  <div className={styles.previousMeds}>
                    <div className={styles.rxTitle} style={{ fontSize: "12px" }}>Favourite Rx sets</div>
                    <FavouriteRxSetsPanel
                      authedFetch={authedFetch} entryId={activeEntry?.id ?? null} locked={rxStatus === "signed"} items={rxItems}
                      onError={setRxError}
                      onApplied={(rx) => {
                        setRxItems(rx.items.length > 0 ? rx.items.map(normalizeRxItem) : [{ ...BLANK_RX_ITEM }]);
                        setRxDirty(false);
                        setRxError(null);
                      }}
                    />
                  </div>

                  <div className={styles.previousMeds}>
                    <div className={styles.rxTitle} style={{ fontSize: "12px", marginBlockEnd: "8px" }}>Previous medicines</div>
                    <MedicinesList prescriptions={ctx.prescriptions} onRetry={reloadCtx} />
                  </div>
                </div>
              </>
            )}

            {step === "close" && (
              <section className={p.card} aria-label="Close consultation">
                <h3 className={p.cardTitle}>Close consultation</h3>
                <div className={p.stack}>
                  <ul className={p.list}>
                    <li className={p.listRow}>
                      <strong>Note</strong>
                      {locked ? <StatusPill tone="ok" label="Signed" /> : <StatusPill tone="warn" label="Draft — will be signed" />}
                      <span className={p.muted}>Diagnosis: {note.diagnosis.trim() || "not entered"}</span>
                    </li>
                    <li className={p.listRow}>
                      <strong>Prescription</strong>
                      {!hasDrugs ? <StatusPill tone="neutral" icon="list" label="None" />
                        : rxStatus === "signed" ? <StatusPill tone="ok" label="Signed" />
                        : <StatusPill tone="danger" label="Not signed" />}
                      {hasDrugs && <span className={p.muted}>{rxItems.filter((i) => i.drugName.trim()).map((i) => i.drugName).join(", ")}</span>}
                      {hasDrugs && rxStatus !== "signed" && <button type="button" className={p.linkBtn} onClick={() => setStep("plan")}>Go to prescription</button>}
                    </li>
                    <li className={p.listRow}>
                      <strong>Follow-up</strong>
                      {followUpLabel ? <StatusPill tone="ok" label={`Set · ${followUpLabel}`} />
                        : followUpLabel === null ? <StatusPill tone="neutral" icon="calendar" label="Not set" />
                        : <StatusPill tone="warn" icon="alert" label="Unknown — couldn't check" />}
                    </li>
                    <li className={p.listRow}>
                      <strong>Checkout</strong>
                      <span className={p.muted}>Visible to reception as soon as you close.</span>
                    </li>
                  </ul>
                  <VisitOutputs authedFetch={authedFetch} queueEntryId={activeEntry.id} patientName={activeEntry.patientName} patient={pat} />

                  {closeIssues.length > 0 && !closedOut && (
                    <div className={`${p.notice} ${p.noticeDanger}`} role="alert">
                      <Icon name="alert" size={16} />
                      <span>
                        Check before closing:
                        <ul style={{ margin: "4px 0 0", paddingInlineStart: "18px" }}>{closeIssues.map((t) => <li key={t}>{t}</li>)}</ul>
                        <label className={p.check} style={{ marginBlockStart: "8px" }}>
                          <input type="checkbox" checked={closeAck} onChange={(e) => setCloseAck(e.target.checked)} />
                          I understand and want to close anyway.
                        </label>
                      </span>
                    </div>
                  )}
                  {handoffPending && (
                    <div className={`${p.notice} ${p.noticeWarn}`} role="status">
                      <Icon name="alert" size={16} />
                      <span>The note is signed but the patient has not reached checkout yet.</span>
                    </div>
                  )}
                  {closedOut && <div className={`${p.notice} ${p.noticeOk}`} role="status"><Icon name="check" size={16} /><span>Sent to checkout.</span></div>}
                </div>
              </section>
            )}
          </div>

          <aside className={styles.tools} aria-label="Contextual tools">
            <section className={p.card} aria-label="Prescription summary">
              <h3 className={p.cardTitle}>
                Prescription
                {!hasDrugs ? <StatusPill tone="neutral" icon="list" label="None" /> : rxStatus === "signed" ? <StatusPill tone="ok" label="Signed" /> : <StatusPill tone="warn" label="Draft" />}
              </h3>
              {hasDrugs ? <ul className={p.list}>{rxItems.filter((i) => i.drugName.trim()).map((i, n) => <li key={n}>{rxItemLine(i)}</li>)}</ul> : <p className={p.muted}>No drugs added.</p>}
              <button type="button" className={`${p.linkBtn} ${p.linkBtnTouch}`} onClick={() => setStep("plan")}>Open prescription</button>
            </section>
            <DoctorOrders authedFetch={authedFetch} queueEntryId={activeEntry.id} />
            <FollowUpPanel key={activeEntry.id} authedFetch={authedFetch} doctorId={activeEntry.doctorId} patientId={activeEntry.patientId} onBooked={setFollowUpLabel} canBook={can("queue:create")} />
            <section className={p.card} aria-label="Package">
              <h3 className={p.cardTitle}>Package</h3>
              {pat && pat.activePackages > 0
                ? <p className={p.muted}>{pat.activePackages} active package{pat.activePackages === 1 ? "" : "s"}. <Link href="/packages" className={p.linkBtn}>Open packages</Link></p>
                : <p className={p.muted}>No active package.</p>}
              {can("packages:view") && <PackageSessionCard authedFetch={authedFetch} patientId={activeEntry.patientId} canRedeem={can("packages:edit")} />}
            </section>
          </aside>
        </div>

        <div className={styles.actionBar}>
          <div className={styles.stripActions}>{saveBadge ?? <span className={styles.saveStatus}>No changes yet</span>}</div>
          <div className={styles.stripActions}>
            {closedOut ? (
              <>
                {canEditNote && <button className={styles.completeBtn} onClick={() => { setShowAmendForm(true); window.scrollTo({ top: 0, behavior: "smooth" }); }}>Add amendment</button>}
                {step !== "close" && <button className={styles.backBtn} onClick={() => setStep("close")}>Visit outputs</button>}
                {next
                  ? <button className={styles.backBtn} onClick={() => void switchTo(next)}>Next patient · #{next.tokenNumber}</button>
                  : <button className={styles.backBtn} onClick={closeShell}>Back to queue</button>}
              </>
            ) : (
              <>
                {!locked && canEditNote && noteLoad === "ok" && (
                  <button className={styles.backBtn} onClick={() => void saveNote(activeEntry.id)} disabled={saveState === "saving" || !!conflict || (!dirty && saveState !== "error")}>
                    {saveState === "saving" ? "Saving…" : "Save draft"}
                  </button>
                )}
                {activeEntry.status === "in_consult" && (
                  <button className={styles.backBtn} onClick={openTransfer} disabled={completing}>Transfer to another department</button>
                )}
                {canEditNote && (step !== "close" ? (
                  <button className={styles.completeBtn} onClick={() => setStep("close")}>Close consultation</button>
                ) : (
                  <button className={styles.completeBtn} onClick={completeConsultation} disabled={completing || closedOut || signedBlocked || !!conflict}>
                    {primaryLabel}
                  </button>
                ))}
              </>
            )}
          </div>
        </div>

        {conflict && conflictOpen && <ConflictDialog mine={conflict.mine} server={conflict.server} onResolve={resolveConflict} onClose={() => setConflictOpen(false)} />}

        {showSignConfirm && (
          <Dialog title="Sign prescription" onClose={() => setShowSignConfirm(false)}>
            <p className={p.muted}>Signing locks this prescription and can&apos;t be undone.</p>
            <ul className={p.list} style={{ marginBlockStart: "var(--nb-space-12)" }}>
              {rxItems.filter((i) => i.drugName.trim()).map((i, n) => <li key={n}>{rxItemLine(i)}{i.instructions ? ` — ${i.instructions}` : ""}</li>)}
            </ul>
            {rxItems.some((i) => (i.warnings ?? []).some((w) => w.decision)) && (
              <div className={`${p.notice} ${p.noticeInfo}`} role="status" style={{ marginBlockStart: "var(--nb-space-12)" }}>
                <Icon name="info" size={16} />
                <span>
                  Decisions recorded on this prescription:
                  <ul style={{ margin: "4px 0 0", paddingInlineStart: "18px" }}>
                    {rxItems.flatMap((i) => (i.warnings ?? []).filter((w) => w.decision).map((w) => (
                      <li key={`${i.drugName}-${w.kind}`}>{i.drugName}: {WARNING_TITLE[w.kind]} — {w.decision?.reasonLabel ?? w.decision?.reasonCode}{w.decision?.byName ? ` (${w.decision.byName})` : ""}</li>
                    )))}
                  </ul>
                </span>
              </div>
            )}
            {advisoryItems.length > 0 && (
              <div className={`${p.notice} ${p.noticeWarn}`} role="alert" style={{ marginBlockStart: "var(--nb-space-12)" }}>
                <Icon name="alert" size={16} />
                <span>
                  Advisory warnings on this prescription:
                  <ul style={{ margin: "4px 0 0", paddingInlineStart: "18px" }}>
                    {advisoryItems.map((i, n) => (
                      <li key={n}>{i.drugName}: {(i.warnings ?? []).filter((w) => !w.requiresDecision).map((w) => `${WARNING_TITLE[w.kind].toLowerCase()} — ${w.message}`).join("; ")}</li>
                    ))}
                  </ul>
                  <label className={p.check} style={{ marginBlockStart: "8px" }}>
                    <input type="checkbox" checked={signAck} onChange={(e) => setSignAck(e.target.checked)} />
                    I have reviewed these warnings.
                  </label>
                </span>
              </div>
            )}
            {signError && <div className={`${p.notice} ${p.noticeDanger}`} role="alert" style={{ marginBlockStart: "var(--nb-space-12)" }}>{signError}</div>}
            <div className={p.dialogActions}>
              <button type="button" className={p.btn} onClick={() => setShowSignConfirm(false)}>Cancel</button>
              <button type="button" className={p.btnPrimary} onClick={confirmSignRx} disabled={signing || (advisoryItems.length > 0 && !signAck)}>
                {signing ? "Signing…" : "Sign prescription"}
              </button>
            </div>
          </Dialog>
        )}

        {showTransfer && (
          <Dialog title="Transfer to another department" onClose={() => setShowTransfer(false)}>
            {transferTargets === null ? (
              <Skeleton rows={2} label="Loading transfer options" />
            ) : transferTargets.length === 0 ? (
              <p className={p.muted}>This department isn&apos;t configured to transfer patients anywhere.</p>
            ) : (
              <>
                <div className={styles.formRow}>
                  <label className={styles.fieldLabel} htmlFor="trDept">Department</label>
                  <select id="trDept" className={styles.select} value={transferDepartmentId} onChange={(e) => { setTransferDepartmentId(e.target.value); setTransferDoctorId(""); }}>
                    <option value="">Select a department…</option>
                    {transferTargets.map((t) => <option key={t.departmentId} value={t.departmentId}>{t.departmentName}</option>)}
                  </select>
                </div>
                {transferDepartmentId && (
                  <div className={styles.formRow}>
                    <label className={styles.fieldLabel} htmlFor="trDoc">Doctor</label>
                    <select id="trDoc" className={styles.select} value={transferDoctorId} onChange={(e) => setTransferDoctorId(e.target.value)}>
                      <option value="">Select a doctor…</option>
                      {transferTargets.find((t) => t.departmentId === transferDepartmentId)?.doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                    </select>
                  </div>
                )}
                {transferDoctorId && (
                  <div className={styles.formRow}>
                    <label className={styles.fieldLabel} htmlFor="trReason">Reason</label>
                    <select id="trReason" className={styles.select} value={transferReason} onChange={(e) => setTransferReason(e.target.value)}>
                      <option value="">Select a reason…</option>
                      <option value="wrong_department">Wrong department</option>
                      <option value="doctor_unavailable">Doctor unavailable</option>
                      <option value="patient_request">Patient request</option>
                      <option value="other">Other</option>
                    </select>
                  </div>
                )}
                {transferReason === "other" && (
                  <div className={styles.formRow}>
                    <label className={styles.fieldLabel} htmlFor="trNote">Note</label>
                    <input id="trNote" className={styles.select} maxLength={200} value={transferNote} onChange={(e) => setTransferNote(e.target.value)} />
                  </div>
                )}
              </>
            )}
            {transferError && <div className={`${p.notice} ${p.noticeDanger}`} role="alert">{transferError}</div>}
            <div className={p.dialogActions}>
              <button type="button" className={p.btn} onClick={() => setShowTransfer(false)}>Cancel</button>
              <button type="button" className={p.btnPrimary} onClick={submitTransfer} disabled={transferring || !transferDepartmentId || !transferDoctorId || !transferReason}>
                {transferring ? "Transferring…" : "Transfer"}
              </button>
            </div>
          </Dialog>
        )}

        {override && rxItems[override.index] && (
          <OverrideDialog item={rxItems[override.index]} warning={override.warning} doctorName={doctorName} busy={rxSaving} error={overrideError}
            onConfirm={confirmOverride} onClose={() => setOverride(null)} />
        )}

        {showAllergies && pat && (
          <AllergyRegister authedFetch={authedFetch} patientId={pat.id} patientName={pat.name} canEdit={can("clinical:edit")}
            onClose={() => setShowAllergies(false)}
            onChanged={() => { reloadCtx(); void authedFetch(`/patients/${pat.id}`).then(async (r) => { if (r?.ok) setActivePatient(await r.json()); }); }} />
        )}

        {showDental && pat && (
          <DentalDrawer authedFetch={authedFetch} patientId={pat.id} patientName={pat.name} onClose={() => setShowDental(false)} />
        )}
        {dialogs}
      </main>
    );
  }

  // ---- queue list ---------------------------------------------------------------------------

  const visible = rows.filter((r) =>
    statusFilter === "all" ? true
    : statusFilter === "waiting" ? WAITING_STATUSES.includes(r.status)
    : statusFilter === "in_consult" ? r.status === "in_consult"
    : DONE_STATUSES.includes(r.status));

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Consultation Workspace</h1>
        <p className={styles.subtitle}>Today&apos;s queue.</p>
      </div>

      <div className={styles.toolbar}>
        <div className={styles.seg} role="group" aria-label="Queue scope">
          {([["mine", "My patients"], ["all", "All clinic"]] as const).map(([id, label]) => (
            <button key={id} type="button" className={`${styles.segBtn} ${scope === id ? styles.segBtnOn : ""}`} aria-pressed={scope === id} onClick={() => changeScope(id)}>{label}</button>
          ))}
        </div>
        <div className={styles.seg} role="group" aria-label="Status filter">
          {([["all", "All"], ["waiting", "Waiting"], ["in_consult", "In consult"], ["done", "Done"]] as const).map(([id, label]) => (
            <button key={id} type="button" className={`${styles.segBtn} ${statusFilter === id ? styles.segBtnOn : ""}`} aria-pressed={statusFilter === id} onClick={() => setStatusFilter(id)}>{label}</button>
          ))}
        </div>
      </div>

      <div className={styles.card}>
        {loading ? (
          <div style={{ padding: "0 16px" }}><Skeleton rows={5} label="Loading today's queue" /></div>
        ) : forbidden ? (
          <EmptyState icon="shield" title="No access to the queue" hint="Your role doesn't include the consultation queue." />
        ) : error ? (
          <ErrorState message={error} onRetry={() => void load(scope)} />
        ) : rows.length === 0 ? (
          <EmptyState title="Nothing here today" hint={scope === "mine" ? "No patients are assigned to you in today's queue. Switch to All clinic to see everyone." : "No patients are in today's queue."}
            action={<Link href="/patients" className={styles.actionBtnGhost} style={{ display: "inline-flex", alignItems: "center", textDecoration: "none" }}>Open patients</Link>} />
        ) : visible.length === 0 ? (
          <EmptyState title="No patients match this filter" hint="Choose another status to see the rest of the queue." />
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th>Patient</th><th className={styles.hideNarrow}>Visit type</th><th>Clinical flags</th><th className={styles.hideNarrow}>Wait · arrival</th><th>Status</th><th><span className={p.srOnly}>Action</span></th></tr>
              </thead>
              <tbody>
                {visible.map((r) => {
                  const waiting = WAITING_STATUSES.includes(r.status);
                  const mins = waiting && now ? minutesSince(r.createdAt, now) : null;
                  const mine = r.doctorId === selfId;
                  const pt = r.patient;
                  return (
                    <tr key={r.id}>
                      <td className={styles.token}>
                        #{r.tokenNumber} {r.patientName}
                        <span className={styles.subline}>{[pt ? `${age(pt.dob)} y` : null, r.presentingComplaint?.trim() || null].filter(Boolean).join(" · ") || "No reason recorded"}</span>
                      </td>
                      <td className={styles.hideNarrow}>{r.appointmentId ? "Appointment" : (VISIT_LABEL[r.source ?? ""] ?? "Walk-in")}</td>
                      <td>
                        <div className={styles.flags}>
                          {r.priority && <StatusPill tone="urgent" label={r.priorityReason ? `Urgent: ${r.priorityReason}` : "Urgent"} />}
                          {pt && pt.allergies.length > 0 && <StatusPill tone="danger" label="Allergy" />}
                          {pt && pt.chronicConditions.length > 0 && <StatusPill tone="warn" icon="pulse" label="Chronic" />}
                          {pt && pt.activePackages > 0 && <StatusPill tone="info" icon="package" label="Package" />}
                          {!pt && <StatusPill tone="warn" label="Flags unavailable" />}
                        </div>
                      </td>
                      <td className={styles.hideNarrow}>
                        {waiting && mins !== null ? `${mins} min` : "—"}{r.createdAt ? <span className={styles.subline}>Arrived {new Date(r.createdAt).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}</span> : null}
                      </td>
                      <td><StatusBadge status={r.status} /></td>
                      <td>
                        {r.status === "in_consult" && mine
                          ? <button className={styles.actionBtn} onClick={() => openRow(r)}>Resume</button>
                          : canStartRow(r)
                            ? <button className={styles.actionBtn} onClick={() => openRow(r)}>Start consultation</button>
                            : <button className={styles.actionBtnGhost} onClick={() => openRow(r)}>Summary</button>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {dialogs}
    </main>
  );
}
