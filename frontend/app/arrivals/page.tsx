"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import styles from "./arrivals.module.css";
import Dialog from "../components/ui/Dialog";
import StatusPill, { type PillTone } from "../components/ui/StatusPill";
import { Skeleton, EmptyState, ErrorState } from "../components/ui/StateViews";
import type { IconName } from "../components/ui/Icon";
import { can } from "../lib/permissions";
import { getIdentity } from "../lib/session";
import { usePolling } from "../lib/usePolling";
import { useLatestLoad } from "../lib/useLatestLoad";
import { VITAL_FIELDS, EMPTY_VITALS, vitalsErrors, vitalsPayload, VITALS_BLANK_HINT, type VitalsForm } from "../lib/vitals";
import { CheckInDialog, CheckInIntakeFields, CheckInResultDialog, problemMessage, type CheckInResult } from "../components/arrivals/CheckInDialog";

// Matches GET /v1/queue (QueueController), GET /v1/patients (PatientController),
// GET /v1/staff/roster (StaffController — id+name only, queue:view not staff:view, so Reception
// can populate the doctor picker), POST /v1/queue/check-in, POST /v1/queue/{id}/reorder.
type QueueEntry = {
  id: string; appointmentId: string | null; patientId: string; doctorId: string; departmentId: string;
  queueDate: string; tokenNumber: number; status: string; priority: boolean; priorityReason: string | null;
  createdAt: string; source: string;
  // REC-011: already on GET /v1/queue rows; optional so an older backend just shows less.
  visitType?: string | null; presentingComplaint?: string | null; ageYears?: number | null; gender?: string | null;
};
type Row = QueueEntry & { patientName: string; patientMrn: string };
type PatientOption = { id: string; name: string; phone: string; mrn: string };
type GuardianOption = { id: string; name: string; phone: string };
// GET /v1/patients/{id} — only the fields the detail rail shows; all optional on purpose.
type PatientDetail = { name?: string; mrn?: string; phone?: string; dob?: string; gender?: string };
const DELAY_PRESETS = [10, 15, 30, 45, 60];
// LIVE-04: each flag is exactly what its control's endpoint needs (see the setGrants effect).
type Grants = { queueCreate: boolean; queueEdit: boolean; checkout: boolean; vitalsRecord: boolean; patientsView: boolean; patientsCreate: boolean };
type StaffOption = { id: string; name: string; departmentName?: string | null };
// Matches DuplicateCandidatesResponse/PatientMatchCandidateResponse (PatientService.register's 409 body).
type DuplicateCandidate = { patientId: string; name: string; phone: string; matchScore: number };
// NB-116: a follow-up appointment either marked no_show or still "scheduled" 15+ days past its start.
type CallbackEntry = { appointmentId: string; patientId: string; patientName: string; doctorId: string; startTime: string; status: string };
// A today's-schedule appointment not yet checked in — the "Check in" quick action's picker.
type AppointmentOption = { id: string; patientId: string; patientName: string; doctorId: string; doctorName: string; startTime: string };
// Matches GET /departments/{id}/transfer-targets (DepartmentController) — same shape consult's
// own transfer picker already uses.
// REC-021 transfer reasons (S4 contract values); `other` takes a free-text note (<=200 chars).
const MOVE_REASONS: [string, string][] = [["wrong_department", "Wrong department"], ["doctor_unavailable", "Doctor unavailable"], ["patient_request", "Patient request"], ["other", "Other"]];
type TransferTarget = { departmentId: string; departmentName: string; doctors: { id: string; name: string }[] };

const VISIT_LABEL: Record<string, string> = {
  walk_in: "Walk-in", referral: "Referral", online: "Online", social_media: "Social media",
  returning: "Returning", other: "Other", internal_transfer: "Internal transfer",
};

// REC-011: the table's Visit column (QueueRepository's visit_type), same labels as /schedule.
const VISIT_TYPE_LABEL: Record<string, string> = {
  appointment: "Appointment", follow_up: "Follow-up", walk_in: "Walk-in", transfer: "Transfer",
};

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const POLL_MS = 15_000;
// NB-143: a small fixed reason-code catalogue for this one call site — not NB-011's generic
// shared reason-code service (doesn't exist), same "skip the generic service" trick used all
// session for NB-008/NB-010.
const PRIORITY_REASON_CODES = [
  "Chest pain / cardiac symptoms", "Breathing difficulty", "Severe bleeding",
  "High fever in infant", "Post-operative complication", "Severe pain", "Other",
] as const;

// Status is icon + text, never colour alone (DESIGN.md §8) — rendered through the shared StatusPill.
const STATUS_PILL: Record<string, { label: string; tone: PillTone; icon: IconName }> = {
  checked_in: { label: "Checked in", tone: "info", icon: "check" },
  waiting: { label: "Waiting", tone: "warn", icon: "clock" },
  billing_pending: { label: "Billing pending", tone: "warn", icon: "receipt" },
  vitals_pending: { label: "Vitals pending", tone: "warn", icon: "pulse" },
  vitals_done: { label: "Vitals done", tone: "ok", icon: "check" },
  in_consult: { label: "In consultation", tone: "sync", icon: "stethoscope" },
  procedures_pending: { label: "Procedures pending", tone: "sync", icon: "syringe" },
  checkout_pending: { label: "Checkout pending", tone: "info", icon: "receipt" },
  completed: { label: "Completed", tone: "ok", icon: "check" },
  no_show: { label: "No show", tone: "danger", icon: "alert" },
};

function StatusBadge({ status }: { status: string }) {
  const s = STATUS_PILL[status];
  const fallback = status.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
  return <StatusPill tone={s?.tone ?? "neutral"} label={s?.label ?? fallback} icon={s?.icon} />;
}

type Bucket = "waiting" | "in_consult" | "checkout_pending" | "completed" | "other";
function bucketOf(status: string): Bucket {
  // NB-355: a tenant's configured visit flow can add billing_pending (pre-consultation limbo,
  // same rationale as checked_in/vitals_pending) and procedures_pending (post-consultation,
  // still with clinical/nursing staff, same rationale as in_consult) — folded into the existing
  // buckets rather than growing the tab list, front desk doesn't need a finer-grained view here.
  if (["checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done"].includes(status)) return "waiting";
  if (["in_consult", "procedures_pending"].includes(status)) return "in_consult";
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

// PatientMatchCandidateResponse.matchScore is a 0-1 fraction (1.0 = exact phone match, otherwise
// trigram name similarity); show it as a percentage.
function matchPercent(score: number): number {
  return Math.round(Math.min(1, Math.max(0, score)) * 100);
}

function waitMinutes(createdAt: string): number {
  return Math.max(0, Math.round((Date.now() - new Date(createdAt).getTime()) / 60000));
}

type ArrivalsTab = "all" | Bucket;
const isArrivalsTab = (t: string | null): t is ArrivalsTab => TABS.some((x) => x.key === t);

// ?tab= deep links (e.g. /arrivals?tab=checkout_pending; the sidebar's Billing & Day Close opens /billing) read
// useSearchParams, which needs a Suspense boundary or the page's static prerender breaks.
export default function ArrivalsPage() {
  return (
    <Suspense fallback={null}>
      <ArrivalsPageInner />
    </Suspense>
  );
}

function ArrivalsPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get("tab");
  const [rows, setRows] = useState<Row[]>([]);
  const [staff, setStaff] = useState<StaffOption[]>([]);
  const [rosterFailed, setRosterFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [, setNow] = useState(() => Date.now());
  // Per-action visibility (REC-014/052/062). Read after mount: the token lives in localStorage, so
  // evaluating can() during SSR/first render would mismatch. Absent until known, never disabled.
  const [grants, setGrants] = useState<Grants>({ queueCreate: false, queueEdit: false, checkout: false, vitalsRecord: false, patientsView: false, patientsCreate: false });
  // Patient name/MRN lookups are per-row GET /patients/{id}; cache them so the 15s poll only
  // fetches patients it hasn't seen (the N+1 shrinks to the first load).
  const patientCache = useRef(new Map<string, { name: string; mrn: string }>());
  // Only the newest load may write the board: a slower, older poll must never put a stale status back.
  const newSignal = useLatestLoad();
  const [sendingIds, setSendingIds] = useState<string[]>([]);
  const [railId, setRailId] = useState<string | null>(null);
  const [railPatient, setRailPatient] = useState<{ state: "loading" | "error" | "ready"; data: PatientDetail | null }>({ state: "loading", data: null });
  const [checkInResult, setCheckInResult] = useState<CheckInResult | null>(null);
  const [checkinTarget, setCheckinTarget] = useState<AppointmentOption | null>(null);
  const [guardianQuery, setGuardianQuery] = useState("");
  const [guardianResults, setGuardianResults] = useState<GuardianOption[]>([]);
  const [guardian, setGuardian] = useState<GuardianOption | null>(null);
  // CGV-005: staff-confirmed guardian consent (wording constant needs legal review, same as the registry dialog).
  const [guardianConsent, setGuardianConsent] = useState(false);
  const [guardianChannel, setGuardianChannel] = useState("");
  // Computed client-side only — server and browser locale defaults for toLocaleDateString can
  // differ, and doing this during the initial render causes a hydration mismatch.
  const [dateLabel, setDateLabel] = useState("");

  const [vitalsEntryId, setVitalsEntryId] = useState<string | null>(null);
  const [vitalsForm, setVitalsForm] = useState<VitalsForm>(EMPTY_VITALS);
  const [vitalsAttempted, setVitalsAttempted] = useState(false);
  const [vitalsError, setVitalsError] = useState<string | null>(null);
  const [vitalsSubmitting, setVitalsSubmitting] = useState(false);

  const [priorityEntryId, setPriorityEntryId] = useState<string | null>(null);
  const [priorityReasonCode, setPriorityReasonCode] = useState<string>(PRIORITY_REASON_CODES[0]);
  const [priorityOtherReason, setPriorityOtherReason] = useState("");
  const [prioritySubmitting, setPrioritySubmitting] = useState(false);
  const [priorityError, setPriorityError] = useState<string | null>(null);

  const [showModal, setShowModal] = useState(false);
  const [patientQuery, setPatientQuery] = useState("");
  const [searchResults, setSearchResults] = useState<PatientOption[]>([]);
  const [selectedPatient, setSelectedPatient] = useState<PatientOption | null>(null);
  const [newPatientMode, setNewPatientMode] = useState(false);
  const [duplicateCandidates, setDuplicateCandidates] = useState<DuplicateCandidate[]>([]);
  const [newName, setNewName] = useState("");
  const [newPhone, setNewPhone] = useState("");
  const [newDob, setNewDob] = useState("");
  const [newGender, setNewGender] = useState("male");
  const [doctorId, setDoctorId] = useState("");
  const [presentingComplaint, setPresentingComplaint] = useState("");
  const [paymentMode, setPaymentMode] = useState("cash");
  const [modalError, setModalError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const [waitEstimates, setWaitEstimates] = useState<Record<string, number>>({});
  const [delays, setDelays] = useState<Record<string, { delayMinutes: number; reason: string | null } | null>>({});
  const [delayFormFor, setDelayFormFor] = useState<string | null>(null);
  const [delayMinutesInput, setDelayMinutesInput] = useState("15");
  const [delayReasonInput, setDelayReasonInput] = useState("");
  const [delayBusy, setDelayBusy] = useState(false);
  const [delayError, setDelayError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [callbackList, setCallbackList] = useState<CallbackEntry[]>([]);
  const [showDelayPicker, setShowDelayPicker] = useState(false);

  // "Check in" quick action: a today's-appointment picker, distinct from Register walk-in.
  const [showCheckin, setShowCheckin] = useState(false);
  const [checkinAppointments, setCheckinAppointments] = useState<AppointmentOption[] | null>(null);
  const [checkinError, setCheckinError] = useState<string | null>(null);

  // "Move queue" quick action: pick a queue entry, then the same department/doctor transfer
  // picker consult's page already uses against POST /queue/{id}/transfer.
  const [showMove, setShowMove] = useState(false);
  const [moveEntryId, setMoveEntryId] = useState("");
  const [moveTargets, setMoveTargets] = useState<TransferTarget[] | null>(null);
  const [moveDepartmentId, setMoveDepartmentId] = useState("");
  const [moveDoctorId, setMoveDoctorId] = useState("");
  const [moveReason, setMoveReason] = useState("");
  const [moveReasonNote, setMoveReasonNote] = useState("");
  const [moveError, setMoveError] = useState<string | null>(null);
  const [moveSubmitting, setMoveSubmitting] = useState(false);

  // "Checkout" quick action: pick which checkout-pending patient, then reuse the existing route.
  const [showCheckoutPicker, setShowCheckoutPicker] = useState(false);

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
    const signal = newSignal();
    setError(null);
    try {
      if (!can("queue:view")) { setForbidden(true); return; } // LIVE-09: no requests that can only 403
      const [queueRes, staffRes] = await Promise.all([authedFetch("/queue", { signal }), authedFetch("/staff/roster", { signal })]);
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
      if (signal.aborted) return;
      const staffMap = new Map<string, string>();
      let doctorIds: string[] = [];
      setRosterFailed(!staffRes?.ok);
      if (staffRes?.ok) {
        const roster: StaffOption[] = await staffRes.json();
        if (signal.aborted) return;
        setStaff(roster.map((s) => ({ id: s.id, name: s.name, departmentName: s.departmentName ?? null })));
        roster.forEach((s) => staffMap.set(s.id, s.name));
        doctorIds = roster.map((s) => s.id);
      }
      const withNames = await Promise.all(entries.map(async (e) => {
        const cached = patientCache.current.get(e.patientId);
        if (cached) return { ...e, patientName: cached.name, patientMrn: cached.mrn };
        const pRes = can("patients:view") ? await authedFetch(`/patients/${e.patientId}`, { signal }) : null;
        const p = pRes?.ok ? await pRes.json() : null;
        if (p?.name) patientCache.current.set(e.patientId, { name: p.name, mrn: p.mrn ?? "" });
        return { ...e, patientName: p?.name ?? "Unknown patient", patientMrn: p?.mrn ?? "" };
      }));
      if (signal.aborted) return;
      setRows(withNames);

      // NB-101/NB-100: per-doctor wait estimate and any active delay, for the sidebar.
      const estimateEntries = await Promise.all(doctorIds.map(async (id: string) => {
        const res = await authedFetch(`/queue/wait-estimate?doctorId=${id}`, { signal });
        return [id, res?.ok ? (await res.json()).estimatedMinutes : null] as const;
      }));
      if (signal.aborted) return;
      setWaitEstimates(Object.fromEntries(estimateEntries.filter(([, v]) => v !== null)));

      const delayEntries = await Promise.all(doctorIds.map(async (id: string) => {
        const res = await authedFetch(`/doctors/${id}/delay`, { signal });
        if (!res?.ok) return [id, null] as const;
        const history: { active: boolean; delayMinutes: number; reason: string | null }[] = await res.json();
        const active = history.find((h) => h.active);
        return [id, active ? { delayMinutes: active.delayMinutes, reason: active.reason } : null] as const;
      }));
      if (signal.aborted) return;
      setDelays(Object.fromEntries(delayEntries));
    } catch {
      if (signal.aborted) return;
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch, newSignal]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const [region, setRegion] = useState<string | undefined>(undefined);
  // A patient created by a check-in whose second call failed: a retry must reuse it, not re-register.
  // shortcut: editing any new-patient field after "registered, check-in failed" re-registers on retry (like the name edit already did), so a corrected DOB is never dropped; the duplicate check then surfaces the earlier record. A PATCH of the changed fields would avoid the second record.
  const registeredRef = useRef<{ id: string; name: string } | null>(null);
  useEffect(() => {
    void Promise.resolve().then(() => setRegion(getIdentity()?.tenantRegion));
  }, []);

  useEffect(() => {
    void Promise.resolve().then(() => setGrants({
      queueCreate: can("queue:create"), queueEdit: can("queue:edit"),
      // Checkout opens GET /billing/checkout (billing:view) to bill (POST, billing:create) or take payment (billing:edit).
      checkout: can("billing:view") && (can("billing:create") || can("billing:edit")),
      // REC-060: first vitals for a vitals_pending visit need clinical:create or clinical:edit.
      vitalsRecord: can("clinical:create") || can("clinical:edit"),
      patientsView: can("patients:view"), patientsCreate: can("patients:create"),
    }));
  }, []);

  // Guardian search for a minor's new record (same /patients?q= call the registry's dialog uses).
  useEffect(() => {
    const q = guardianQuery.trim();
    let cancelled = false;
    const timer = setTimeout(async () => {
      if (q.length < 2) {
        if (!cancelled) setGuardianResults([]);
        return;
      }
      const res = await authedFetch(`/patients?q=${encodeURIComponent(q)}&limit=6`);
      if (cancelled || !res?.ok) return;
      const page: { data: GuardianOption[] } = await res.json();
      if (!cancelled) setGuardianResults(page.data);
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [guardianQuery, authedFetch]);

  // Detail rail: one GET /patients/{id} when a row is opened.
  const loadRailPatient = useCallback(async (patientId: string) => {
    setRailPatient({ state: "loading", data: null });
    try {
      const res = await authedFetch(`/patients/${patientId}`);
      if (!res) return;
      if (!res.ok) { setRailPatient({ state: "error", data: null }); return; }
      setRailPatient({ state: "ready", data: await res.json() });
    } catch {
      setRailPatient({ state: "error", data: null });
    }
  }, [authedFetch]);
  useEffect(() => {
    void Promise.resolve().then(async () => {
      if (!can("queue:view")) return;
      const res = await authedFetch("/appointments/callback-list");
      if (res?.ok) setCallbackList(await res.json());
    });
  }, [authedFetch]);

  // NB-095: "live-updates without refresh" via polling — full push-based sync is NB-096, separate.
  usePolling(load, POLL_MS);

  // Re-render the "Wait" column once a minute without waiting on the full poll.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);

  // TopBar's "Register walk-in" (on the schedule board) lands here with ?action=walkin; strip it
  // once consumed so back/refresh doesn't reopen the dialog. Fresh mount, so fields are already blank.
  useEffect(() => {
    if (searchParams.get("action") !== "walkin") return;
    if (!can("queue:create") || !can("patients:view")) { router.replace("/arrivals"); return; }
    void Promise.resolve().then(() => {
      setShowModal(true);
      router.replace("/arrivals");
    });
  }, [searchParams, router]);

  useEffect(() => {
    // Deferred to a microtask so the effect body itself never calls setState synchronously.
    void Promise.resolve().then(() => {
      setDateLabel(new Date().toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" }));
    });
  }, []);

  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  function onPatientQueryChange(value: string) {
    setPatientQuery(value);
    registeredRef.current = null;
    setSelectedPatient(null);
    setNewPatientMode(false);
    setDuplicateCandidates([]);
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
    registeredRef.current = null;
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
    setPresentingComplaint("");
    setPaymentMode("cash");
    setModalError(null);
    setDuplicateCandidates([]);
    setGuardianQuery("");
    setGuardianResults([]);
    setGuardian(null);
    setGuardianConsent(false);
    setGuardianChannel("");
  }

  const minor = (() => {
    if (!newDob) return false;
    const [y, m, d] = newDob.split("-").map(Number);
    const now = new Date();
    let age = now.getFullYear() - y;
    if (now.getMonth() + 1 < m || (now.getMonth() + 1 === m && now.getDate() < d)) age--;
    return age < 18;
  })();

  function submitCheckIn(e: React.FormEvent) {
    e.preventDefault();
    return registerAndCheckIn(false);
  }

  // confirmedNotDuplicate=true is only sent after the receptionist reviewed the match list and
  // chose "Different person" — the server then files the record under duplicate review.
  async function registerAndCheckIn(confirmedNotDuplicate: boolean) {
    setModalError(null);
    if (!doctorId) {
      setModalError("Pick a doctor.");
      return;
    }
    setSubmitting(true);
    try {
      let patientId = selectedPatient?.id ?? registeredRef.current?.id ?? null;
      let patientName = selectedPatient?.name ?? registeredRef.current?.name ?? "";
      if (!patientId) {
        if (!newName || !newPhone || !newDob) {
          setModalError("Fill in the new patient's name, phone and date of birth.");
          return;
        }
        if (minor && !guardian) {
          setModalError("A guardian is required for patients under 18. Search for the guardian's record below.");
          return;
        }
        if (minor && (!guardianConsent || !guardianChannel)) {
          setModalError("Confirm the guardian's consent and how it was given.");
          return;
        }
        const res = await authedFetch("/patients", {
          method: "POST",
          body: JSON.stringify({
            name: newName, phone: newPhone, dob: newDob, gender: newGender,
            guardianId: minor ? guardian?.id ?? null : null,
            ...(minor ? { guardianConsent: { confirmed: true, channel: guardianChannel } } : {}),
            ...(confirmedNotDuplicate ? { confirmedNotDuplicate: true } : {}),
          }),
        });
        if (!res) return;
        if (res.status === 409) {
          const body: { candidates: DuplicateCandidate[] } = await res.json().catch(() => ({ candidates: [] }));
          setDuplicateCandidates([...body.candidates].sort((x, y) => y.matchScore - x.matchScore));
          setModalError(body.candidates.length > 0
            ? "Possible existing records found. Review the matches below — use an existing patient, or confirm this is a different person."
            : "A similar patient already exists — search for them above instead of registering again.");
          return;
        }
        if (!res.ok) {
          setModalError(await problemMessage(res, "Couldn't register patient."));
          return;
        }
        patientId = (await res.json()).id;
        patientName = newName;
        registeredRef.current = { id: patientId as string, name: newName };
      }

      const ciRes = await authedFetch("/queue/check-in", {
        method: "POST",
        body: JSON.stringify({
          patientId, doctorId,
          presentingComplaint: presentingComplaint.trim() || null,
          paymentMode,
        }),
      });
      if (!ciRes) return;
      if (!ciRes.ok) {
        setModalError(await problemMessage(ciRes, "Couldn't check in."));
        return;
      }
      const entry: { id?: string; tokenNumber: number } = await ciRes.json();
      setShowModal(false);
      setCheckInResult({
        tokenNumber: entry.tokenNumber, patientName, doctorId,
        doctorName: staff.find((s) => s.id === doctorId)?.name ?? "", entryId: entry.id,
      });
      void load();
    } catch {
      setModalError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSubmitting(false);
    }
  }

  function openVitalsModal(entryId: string) {
    setVitalsEntryId(entryId);
    setVitalsForm(EMPTY_VITALS);
    setVitalsAttempted(false);
    setVitalsError(null);
  }

  async function submitVitals(e: React.FormEvent) {
    e.preventDefault();
    if (!vitalsEntryId) return;
    setVitalsError(null);
    setVitalsAttempted(true);
    if (Object.keys(vitalsErrors(vitalsForm)).length > 0) return;
    setVitalsSubmitting(true);
    try {
      const res = await authedFetch(`/clinical/vitals/${vitalsEntryId}`, {
        method: "PATCH",
        body: JSON.stringify(vitalsPayload(vitalsForm)),
      });
      if (!res) return;
      if (!res.ok) {
        setVitalsError(await problemMessage(res, "Couldn't save vitals."));
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
    setPriorityError(null);
  }

  async function submitPriority(e: React.FormEvent) {
    e.preventDefault();
    if (!priorityEntryId) return;
    const reason = priorityReasonCode === "Other" ? priorityOtherReason.trim() : priorityReasonCode;
    if (reason.length < 3) return; // QueueReorderRequest.reason: @Size(min = 3)
    setPrioritySubmitting(true);
    try {
      const res = await authedFetch(`/queue/${priorityEntryId}/reorder`, { method: "POST", body: JSON.stringify({ priority: true, reason }) });
      if (res?.ok) {
        setPriorityEntryId(null);
        load();
      } else if (res) {
        setPriorityError(await problemMessage(res, "Couldn't mark this patient as priority."));
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
      } else if (res) {
        setDelayError(await problemMessage(res, "Couldn't announce the delay."));
      }
    } finally {
      setDelayBusy(false);
    }
  }

  async function clearDelay(doctorId: string) {
    const res = await authedFetch(`/doctors/${doctorId}/delay/clear`, { method: "POST" });
    if (res?.ok) load();
    else if (res) setActionError(await problemMessage(res, "Couldn't clear the delay."));
  }

  function pickDelayDoctor(doctorId: string) {
    setShowDelayPicker(false);
    setDelayFormFor(doctorId);
    setDelayMinutesInput("15");
    setDelayReasonInput("");
    setDelayError(null);
  }

  // Today's scheduled appointments not yet checked in — distinct from Register walk-in, which
  // is always a fresh/unscheduled arrival.
  async function openCheckin() {
    setShowCheckin(true);
    setCheckinError(null);
    setCheckinAppointments(null);
    // Local date, not toISOString() (UTC) — before 05:30 in India that would ask for yesterday.
    const today = new Date().toLocaleDateString("en-CA");
    const res = await authedFetch(`/appointments?date=${today}&limit=100`);
    if (!res?.ok) {
      setCheckinError(res ? await problemMessage(res, "Couldn't load today's appointments.") : "Couldn't load today's appointments.");
      setCheckinAppointments([]);
      return;
    }
    const body = await res.json();
    const list: { id: string; patientId: string; doctorId: string; startTime: string; status: string }[] = body.data ?? body;
    const alreadyArrived = new Set(rows.map((r) => r.appointmentId).filter(Boolean));
    const pending = list.filter((a) => a.status === "scheduled" && !alreadyArrived.has(a.id));
    const withNames = await Promise.all(pending.map(async (a) => {
      const pRes = can("patients:view") ? await authedFetch(`/patients/${a.patientId}`) : null;
      const p = pRes?.ok ? await pRes.json() : null;
      return {
        id: a.id, patientId: a.patientId, doctorId: a.doctorId,
        doctorName: staff.find((s) => s.id === a.doctorId)?.name ?? "—",
        patientName: p?.name ?? "Unknown patient", startTime: a.startTime,
      };
    }));
    setCheckinAppointments(withNames);
  }

  // "Move queue": pick which active patient, then the same transfer-target picker consult's
  // page already drives against the same POST /queue/{id}/transfer.
  function openMove() {
    setShowMove(true);
    setMoveEntryId("");
    setMoveTargets(null);
    setMoveDepartmentId("");
    setMoveDoctorId("");
    setMoveReason(""); setMoveReasonNote(""); setMoveError(null);
  }

  async function selectMoveEntry(entryId: string) {
    setMoveEntryId(entryId);
    setMoveDepartmentId("");
    setMoveDoctorId("");
    setMoveTargets(null);
    setMoveReason(""); setMoveReasonNote(""); setMoveError(null);
    const entry = rows.find((r) => r.id === entryId);
    if (!entry) return;
    const res = await authedFetch(`/departments/${entry.departmentId}/transfer-targets`);
    if (!res?.ok) {
      setMoveError("Couldn't load transfer options.");
      setMoveTargets([]);
      return;
    }
    setMoveTargets(await res.json());
  }

  // The reason is required only when the department changes (same-department = doctor change).
  const movingDept = moveDepartmentId !== "" && moveDepartmentId !== rows.find((r) => r.id === moveEntryId)?.departmentId;
  const reasonMissing = movingDept && !moveReason;
  async function submitMove() {
    if (!moveEntryId || !moveDepartmentId || !moveDoctorId) return;
    if (reasonMissing) { setMoveError("Choose a reason for moving the patient to another department."); return; }
    setMoveSubmitting(true);
    setMoveError(null);
    try {
      const res = await authedFetch(`/queue/${moveEntryId}/transfer`, {
        method: "POST",
        body: JSON.stringify({
          toDepartmentId: moveDepartmentId, doctorId: moveDoctorId,
          ...(moveReason ? { reason: moveReason, ...(moveReason === "other" && moveReasonNote.trim() ? { reasonNote: moveReasonNote.trim() } : {}) } : {}),
        }),
      });
      if (!res) return;
      if (!res.ok) {
        setMoveError(await problemMessage(res, "Couldn't move the patient."));
        return;
      }
      setShowMove(false);
      load();
    } finally {
      setMoveSubmitting(false);
    }
  }

  // QueueService's state machine only allows one hop at a time (checked_in -> waiting ->
  // vitals_pending), and front desk has no reason to care about that middle "waiting" state —
  // it's rendered with the same pill and grouped into the same tab as checked_in. This one button
  // walks both hops (or just the second, if already "waiting") so vitals becomes reachable at all.
  async function sendForVitals(row: Row) {
    if (sendingIds.includes(row.id)) return;
    setSendingIds((ids) => [...ids, row.id]);
    setActionError(null);
    // Show each hop as soon as the server accepts it, so the board never waits on the full reload.
    const showStatus = (status: string) => setRows((prev) => prev.map((r) => (r.id === row.id ? { ...r, status } : r)));
    try {
      if (row.status === "checked_in") {
        const toWaiting = await authedFetch(`/queue/${row.id}/status`, { method: "PATCH", body: JSON.stringify({ status: "waiting" }) });
        if (!toWaiting) return;
        if (!toWaiting.ok) {
          setActionError(await problemMessage(toWaiting, "Couldn't send the patient for vitals."));
          return;
        }
        showStatus("waiting");
      }
      const res = await authedFetch(`/queue/${row.id}/status`, { method: "PATCH", body: JSON.stringify({ status: "vitals_pending" }) });
      if (!res) return;
      if (!res.ok) {
        setActionError(await problemMessage(res, "Couldn't send the patient for vitals."));
        return;
      }
      showStatus("vitals_pending");
    } catch {
      setActionError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSendingIds((ids) => ids.filter((id) => id !== row.id));
      // Always re-read the queue, success or not: the first hop may have applied even if the second did not.
      void load();
    }
  }

  // The URL is the tab's only state: sidebar links and tab clicks both just change ?tab=, so the
  // sidebar's active item always matches what's on screen.
  const tab: ArrivalsTab = isArrivalsTab(tabParam) ? tabParam : "all";
  const selectTab = (key: ArrivalsTab) =>
    router.replace(key === "all" ? "/arrivals" : `/arrivals?tab=${key}`, { scroll: false });
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
    // REC-023: every active doctor stays on the rail; idle ones sink to the bottom, muted.
    .sort((a, b) => b.waiting - a.waiting);

  const delayMins = Number(delayMinutesInput);
  const delayValid = delayMinutesInput.trim() !== "" && Number.isInteger(delayMins) && delayMins >= 1;
  const delayWaiting = doctorQueues.find((d) => d.id === delayFormFor)?.waiting ?? 0;

  // Stable close handlers: Dialog re-focuses its first control whenever onClose changes identity.
  const closeWalkin = useCallback(() => setShowModal(false), []);
  const vErrors = vitalsErrors(vitalsForm);
  const closeVitals = useCallback(() => setVitalsEntryId(null), []);
  const closePriority = useCallback(() => setPriorityEntryId(null), []);
  const closeDelayForm = useCallback(() => setDelayFormFor(null), []);
  const closeDelayPicker = useCallback(() => setShowDelayPicker(false), []);
  const closeCheckoutPicker = useCallback(() => setShowCheckoutPicker(false), []);
  const closeCheckin = useCallback(() => setShowCheckin(false), []);
  const closeMove = useCallback(() => setShowMove(false), []);
  const closeRail = useCallback(() => setRailId(null), []);
  const closeCheckinTarget = useCallback(() => setCheckinTarget(null), []);
  const closeResult = useCallback(() => setCheckInResult(null), []);

  function openRail(row: Row) {
    setRailId(row.id);
    void loadRailPatient(row.patientId);
  }
  // Rail actions close the rail first so only one dialog traps focus at a time.
  function fromRail(fn: () => void) {
    setRailId(null);
    fn();
  }

  const railRow = railId ? rows.find((r) => r.id === railId) ?? null : null;
  const checkoutRows = rows.filter((r) => bucketOf(r.status) === "checkout_pending");
  const hasQuickActions = grants.queueCreate || grants.queueEdit || grants.checkout;

  // One contextual set of row actions, shared by the table and the detail rail. Each is absent
  // (not disabled) when the role lacks the permission the endpoint needs.
  function rowActions(r: Row, afterClick: (fn: () => void) => void) {
    const b = bucketOf(r.status);
    return (
      <>
        {grants.queueEdit && !r.priority && b === "waiting" && (
          <button type="button" className={styles.actionBtn} onClick={() => afterClick(() => openPriorityModal(r.id))}>Mark priority</button>
        )}
        {grants.queueEdit && (r.status === "checked_in" || r.status === "waiting") && (
          <button type="button" className={styles.actionBtn} disabled={sendingIds.includes(r.id)} onClick={() => afterClick(() => void sendForVitals(r))}>{sendingIds.includes(r.id) ? "Sending…" : "Send for vitals"}</button>
        )}
        {grants.vitalsRecord && r.status === "vitals_pending" && (
          <button type="button" className={styles.actionBtn} onClick={() => afterClick(() => openVitalsModal(r.id))}>Record vitals</button>
        )}
        {grants.checkout && b === "checkout_pending" && (
          <button type="button" className={styles.actionBtn} onClick={() => afterClick(() => router.push(`/checkout/${r.id}`))}>Checkout</button>
        )}
      </>
    );
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Today · Arrivals</h1>
          <p className={styles.subtitle}>{dateLabel} · {rows.length} on the board</p>
        </div>
        {!forbidden && (
          <div className={styles.headerActions}>
            {/* Clinic Operations' two views (DESIGN.md): this page is the Live Queue half. */}
            <nav className={styles.segmented} aria-label="Clinic operations view">
              <Link className={styles.segment} href="/schedule">Schedule</Link>
              <span className={styles.segmentActive} aria-current="page">Live Queue</span>
            </nav>
          </div>
        )}
      </div>

      {!forbidden && (
        <div className={styles.tabs} role="group" aria-label="Filter arrivals by status">
          {TABS.map((t) => (
            <button type="button" key={t.key} className={tab === t.key ? styles.tabActive : styles.tab}
              aria-pressed={tab === t.key} onClick={() => selectTab(t.key)}>
              {t.label}
              {counts[t.key] !== undefined && <span className={styles.chip}>{counts[t.key]}</span>}
            </button>
          ))}
        </div>
      )}

      {!forbidden && hasQuickActions && (
        <div className={styles.quickActions}>
          {/* A token is only ever created as part of check-in, so walk-in and "issue token" are one action. */}
          {grants.queueCreate && grants.patientsView && <button type="button" className={styles.primaryAction} onClick={openModal}>Walk-in &amp; token</button>}
          {grants.queueCreate && <button type="button" className={styles.actionBtn} onClick={openCheckin}>Check in</button>}
          {grants.queueEdit && <button type="button" className={styles.actionBtn} onClick={openMove}>Move queue</button>}
          {grants.queueEdit && <button type="button" className={styles.actionBtn} onClick={() => setShowDelayPicker(true)}>Notify delay</button>}
          {grants.checkout && <button type="button" className={styles.actionBtn} onClick={() => setShowCheckoutPicker(true)}>Checkout</button>}
        </div>
      )}

      {actionError && (
        <div className={styles.formError} role="alert">
          {actionError}{" "}
          <button type="button" className={styles.linkBtn} onClick={() => setActionError(null)}>Dismiss</button>
        </div>
      )}

      {loading ? (
        <div className={styles.card}><Skeleton rows={6} label="Loading today's arrivals" /></div>
      ) : forbidden ? (
        <div className={styles.card}><EmptyState icon="shield" title="No access to the arrivals board" hint="Your role doesn't include the live queue. Ask your clinic owner if you need it." /></div>
      ) : error && rows.length === 0 ? (
        <div className={styles.card}><ErrorState message={error} onRetry={() => { setLoading(true); void load(); }} /></div>
      ) : (
        <>
        {error && (
          <div className={styles.formError} role="alert">
            {error} Showing the last loaded list.{" "}
            <button type="button" className={styles.linkBtn} onClick={() => void load()}>Retry</button>
          </div>
        )}
        <div className={styles.layout}>
          <div className={styles.card}>
            {filtered.length === 0 ? (
              <EmptyState icon="users" title="No patients here yet"
                hint={tab === "all" ? "Walk-ins and checked-in appointments appear here as they arrive." : "Nobody is in this stage right now."}
                action={tab !== "all" ? <button type="button" className={styles.linkBtn} onClick={() => selectTab("all")}>Show all arrivals</button> : undefined} />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr><th>Token</th><th>Patient</th><th className={styles.colVisit}>Visit</th><th className={styles.colDoctor}>Doctor</th><th>Wait</th><th>Status</th><th><span className={styles.srOnly}>Actions</span></th></tr>
                  </thead>
                  <tbody>
                    {filtered.map((r) => {
                      const b = bucketOf(r.status);
                      return (
                        <tr key={r.id} className={styles.row} tabIndex={0} aria-label={`Open details for ${r.patientName}, token ${r.tokenNumber}`}
                          onClick={() => openRail(r)}
                          onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); openRail(r); } }}>
                          <td className={styles.token} data-label="Token">
                            #{r.tokenNumber}
                            {r.priority && <span className={styles.priorityTag}><StatusPill tone="urgent" label="Priority" /></span>}
                          </td>
                          <td data-label="Patient"><span className={styles.patientName}>{r.patientName}</span>
                            <span className={styles.mrn}>{[r.patientMrn, [r.ageYears, (r.gender ?? "").trim().charAt(0).toUpperCase()].filter((x) => x != null && x !== "").join(" ")].filter(Boolean).join(" · ")}</span>
                            {r.presentingComplaint && <span className={styles.complaint} title={r.presentingComplaint}>{r.presentingComplaint}</span>}</td>
                          <td className={`${styles.muted} ${styles.colVisit}`} data-label="Visit">{(r.visitType && VISIT_TYPE_LABEL[r.visitType]) || (VISIT_LABEL[r.source] ?? r.source)}</td>
                          <td className={styles.colDoctor} data-label="Doctor">{staff.find((s) => s.id === r.doctorId)?.name ?? "—"}</td>
                          <td className={styles.muted} data-label="Wait">{b === "waiting" || b === "in_consult" ? `${waitMinutes(r.createdAt)}m` : "—"}</td>
                          <td data-label="Status"><StatusBadge status={r.status} /></td>
                          <td onClick={(e) => e.stopPropagation()}>
                            <div className={styles.rowActions}>{rowActions(r, (fn) => fn())}</div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className={styles.sideCol}>
          <div className={styles.card}>
            <div className={styles.sideTitle}>Doctor queues</div>
            {rosterFailed ? (
              <ErrorState message="Couldn't load the doctor roster." onRetry={() => void load()} />
            ) : doctorQueues.length === 0 ? (
              <EmptyState icon="stethoscope" title="No doctors on the roster" />
            ) : (
              doctorQueues.map((d) => (
                <div key={d.id} className={`${styles.doctorRow} ${styles.doctorRowStack}${d.waiting === 0 ? ` ${styles.doctorIdle}` : ""}`}>
                  <div className={styles.doctorLine}>
                    <span>
                      <span className={styles.doctorName}>{d.name}</span>
                      {d.departmentName && <span className={styles.doctorDept}>{d.departmentName}</span>}
                    </span>
                    <span className={styles.doctorCount}>
                      {d.waiting === 0 ? "No patients waiting" : `${d.waiting} waiting${d.estimatedMinutes !== undefined ? ` · ~${d.estimatedMinutes}m wait` : ""}`}
                    </span>
                  </div>
                  {d.delay ? (
                    <div className={styles.delayLine}>
                      <span><StatusPill tone="danger" label="Delayed" icon="clock" /> Running {d.delay.delayMinutes}m late{d.delay.reason ? ` — ${d.delay.reason}` : ""}</span>
                      {grants.queueEdit && <button type="button" className={styles.actionBtn} onClick={() => clearDelay(d.id)}>Clear delay</button>}
                    </div>
                  ) : grants.queueEdit && (
                    <button type="button" className={`${styles.actionBtn} ${styles.alignStart}`}
                      onClick={() => { setDelayFormFor(d.id); setDelayMinutesInput("15"); setDelayReasonInput(""); setDelayError(null); }}>
                      Announce delay
                    </button>
                  )}
                </div>
              ))
            )}
          </div>

          {callbackList.length > 0 && (
            <div className={styles.card}>
              <div className={styles.sideTitle}>Follow-up callback list</div>
              {callbackList.map((c) => (
                <div key={c.appointmentId} className={`${styles.doctorRow} ${styles.doctorRowStack}`}>
                  <div className={styles.doctorLine}>
                    <span className={styles.doctorName}>{c.patientName}</span>
                    {c.status === "no_show" ? <StatusPill tone="danger" label="Missed" /> : <StatusPill tone="warn" label="Overdue" />}
                  </div>
                  <span className={styles.callbackNote}>
                    Follow-up was {new Date(c.startTime).toLocaleDateString()}
                  </span>
                </div>
              ))}
            </div>
          )}
          </div>
        </div>
        </>
      )}

      {railId && railRow && (
        <Dialog size="drawer" title={`#${railRow.tokenNumber} · ${railRow.patientName}`} onClose={closeRail}>
          <div className={styles.railStatus}>
            <StatusBadge status={railRow.status} />
            {railRow.priority && <StatusPill tone="urgent" label="Priority" />}
          </div>
          {railRow.priority && railRow.priorityReason && <p className={styles.railNote}>Priority reason: {railRow.priorityReason}</p>}
          <dl className={styles.railList}>
            <div><dt>MRN</dt><dd>{railRow.patientMrn || "—"}</dd></div>
            <div><dt>Visit</dt><dd>{(railRow.visitType && VISIT_TYPE_LABEL[railRow.visitType]) || (VISIT_LABEL[railRow.source] ?? railRow.source)}</dd></div>
            <div><dt>Doctor</dt><dd>{staff.find((s) => s.id === railRow.doctorId)?.name ?? "—"}</dd></div>
            <div><dt>Waiting</dt><dd>{bucketOf(railRow.status) === "waiting" || bucketOf(railRow.status) === "in_consult" ? `${waitMinutes(railRow.createdAt)} min` : "—"}</dd></div>
            <div><dt>Arrived</dt><dd>{new Date(railRow.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</dd></div>
          </dl>
          <div className={styles.sideTitle} style={{ paddingInline: 0 }}>Patient</div>
          {railPatient.state === "loading" ? (
            <Skeleton rows={3} label="Loading patient" />
          ) : railPatient.state === "error" ? (
            <ErrorState message="Couldn't load the patient record." onRetry={() => void loadRailPatient(railRow.patientId)} />
          ) : (
            <dl className={styles.railList}>
              <div><dt>Phone</dt><dd>{railPatient.data?.phone || "—"}</dd></div>
              <div><dt>Date of birth</dt><dd>{railPatient.data?.dob || "—"}</dd></div>
              <div><dt>Sex</dt><dd>{railPatient.data?.gender || "—"}</dd></div>
            </dl>
          )}
          <div className={styles.railActions}>
            {rowActions(railRow, fromRail)}
            {grants.patientsView && railRow.patientMrn && <Link className={styles.linkBtn} href={`/patients?q=${encodeURIComponent(railRow.patientMrn)}`}>Find in patient registry</Link>}
          </div>
        </Dialog>
      )}

      {showModal && (
        <Dialog size="drawer" title="Register walk-in" onClose={closeWalkin}>
          <form onSubmit={submitCheckIn}>
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
                    <button type="button" key={p.id} className={styles.searchResultRow} onClick={() => { setSelectedPatient(p); setSearchResults([]); }}>
                      {p.name} · {p.phone} · {p.mrn}
                    </button>
                  ))}
                </div>
              )}
              {grants.patientsCreate && !selectedPatient && patientQuery.trim().length >= 2 && searchResults.length === 0 && !newPatientMode && (
                <button type="button" className={styles.linkBtn} style={{ marginTop: "8px" }} onClick={() => setNewPatientMode(true)}>
                  No match — register a new patient
                </button>
              )}
            </div>

            {newPatientMode && !selectedPatient && (
              <>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="newName">Name</label>
                  <input id="newName" className={styles.input} value={newName} onChange={(e) => { registeredRef.current = null; setNewName(e.target.value); }} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="newPhone">Phone</label>
                  <input id="newPhone" className={styles.input} inputMode="tel" value={newPhone} onChange={(e) => { registeredRef.current = null; setNewPhone(e.target.value); }} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="newDob">Date of birth</label>
                  <input id="newDob" type="date" className={styles.input} value={newDob} max={new Date().toLocaleDateString("en-CA")} onChange={(e) => { registeredRef.current = null; setNewDob(e.target.value); }} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="newGender">Gender</label>
                  <select id="newGender" className={styles.select} value={newGender} onChange={(e) => { registeredRef.current = null; setNewGender(e.target.value); }}>
                    <option value="male">Male</option>
                    <option value="female">Female</option>
                    <option value="other">Other</option>
                  </select>
                </div>
                {minor && (
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="guardianSearch">Guardian (required for under-18s)</label>
                    {guardian ? (
                      <div className={styles.selectedPatient}>
                        <span>{guardian.name} · {guardian.phone}</span>
                        <button type="button" className={styles.linkBtn} onClick={() => { registeredRef.current = null; setGuardian(null); setGuardianQuery(""); setGuardianConsent(false); setGuardianChannel(""); }}>Change</button>
                      </div>
                    ) : (
                      <>
                        <input id="guardianSearch" className={styles.input} value={guardianQuery}
                          placeholder="Search the guardian's record by name or phone"
                          onChange={(e) => setGuardianQuery(e.target.value)} />
                        {guardianResults.length > 0 && (
                          <div className={styles.searchResults}>
                            {guardianResults.map((g) => (
                              <button type="button" key={g.id} className={styles.searchResultRow}
                                onClick={() => { registeredRef.current = null; setGuardian(g); setGuardianResults([]); }}>
                                {g.name} · {g.phone}
                              </button>
                            ))}
                          </div>
                        )}
                      </>
                    )}
                    {guardian && (
                      <>
                        <label className={styles.label}>
                          <input type="checkbox" checked={guardianConsent} onChange={(e) => setGuardianConsent(e.target.checked)} /> Guardian has agreed to access on this patient&apos;s behalf
                        </label>
                        <select aria-label="How consent was given" className={styles.select} value={guardianChannel} onChange={(e) => setGuardianChannel(e.target.value)}>
                          <option value="">How was consent given…</option>
                          <option value="in_person">In person</option>
                          <option value="phone">Phone</option>
                          <option value="written">Written</option>
                        </select>
                      </>
                    )}
                  </div>
                )}
              </>
            )}

            <div className={styles.field}>
              <label className={styles.label} htmlFor="doctor">Doctor</label>
              <select id="doctor" className={styles.select} value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
                <option value="">Select a doctor…</option>
                {staff.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>

            <CheckInIntakeFields complaint={presentingComplaint} onComplaint={setPresentingComplaint}
              paymentMode={paymentMode} onPaymentMode={setPaymentMode} region={region} />

            {modalError && <div className={styles.formError} role="alert">{modalError}</div>}

            {duplicateCandidates.length > 0 && (
              <div className={styles.matchList} role="group" aria-label="Possible existing patients">
                {duplicateCandidates.map((c) => (
                  <div key={c.patientId} className={styles.matchRow}>
                    <span>
                      <span className={styles.patientName}>{c.name}</span>
                      <span className={styles.mrn}>{c.phone}</span>
                    </span>
                    <StatusPill tone={matchPercent(c.matchScore) >= 85 ? "danger" : "warn"} icon="alert" label={`${matchPercent(c.matchScore)}% match`} />
                    <button type="button" className={styles.actionBtn} onClick={() => {
                      setSelectedPatient({ id: c.patientId, name: c.name, phone: c.phone, mrn: "" });
                      setDuplicateCandidates([]);
                      setModalError(null);
                    }}>Use this patient</button>
                  </div>
                ))}
                <button type="button" className={styles.cancelBtn} disabled={submitting} onClick={() => void registerAndCheckIn(true)}>
                  Different person — register anyway
                </button>
              </div>
            )}

            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={closeWalkin}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={submitting}>{submitting ? "Checking in…" : "Check in"}</button>
            </div>
          </form>
        </Dialog>
      )}

      {checkInResult && <CheckInResultDialog result={checkInResult} authedFetch={authedFetch} onClose={closeResult} />}

      {checkinTarget && (
        <CheckInDialog
          patient={{
            id: checkinTarget.patientId, name: checkinTarget.patientName, nextAppointmentToday: true,
            nextAppointmentId: checkinTarget.id, nextDoctorId: checkinTarget.doctorId,
            nextDoctorName: checkinTarget.doctorName, nextAppointment: checkinTarget.startTime,
          }}
          authedFetch={authedFetch}
          onClose={closeCheckinTarget}
          onCheckedIn={() => void load()}
          onDone={closeCheckinTarget}
        />
      )}

      {vitalsEntryId && (
        <Dialog title="Record vitals" onClose={closeVitals}>
          <form onSubmit={submitVitals} noValidate>
            <p className={styles.muted}>{VITALS_BLANK_HINT}</p>
            {vitalsAttempted && vErrors.form && <div className={styles.formError} role="alert">{vErrors.form}</div>}
            {VITAL_FIELDS.map((d) => {
              const err = vitalsAttempted ? vErrors[d.key] : undefined;
              return (
                <div className={styles.field} key={d.key}>
                  <label className={styles.label} htmlFor={d.key}>{d.label} ({d.unit})</label>
                  <input
                    id={d.key}
                    type="number"
                    inputMode="decimal"
                    step={d.step}
                    min={d.min}
                    max={d.max}
                    className={styles.input}
                    value={vitalsForm[d.key]}
                    aria-invalid={!!err}
                    aria-describedby={err ? `${d.key}-err` : undefined}
                    onChange={(e) => setVitalsForm((prev) => ({ ...prev, [d.key]: e.target.value }))}
                  />
                  {err && <div className={styles.formError} id={`${d.key}-err`} role="alert">{err}</div>}
                </div>
              );
            })}
            {vitalsError && <div className={styles.formError} role="alert">{vitalsError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={closeVitals}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={vitalsSubmitting}>{vitalsSubmitting ? "Saving…" : "Save vitals"}</button>
            </div>
          </form>
        </Dialog>
      )}

      {priorityEntryId && (
        <Dialog title="Mark priority" onClose={closePriority}>
          <form onSubmit={submitPriority}>
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
                <input id="priorityOther" className={styles.input} minLength={3} value={priorityOtherReason}
                  onChange={(e) => setPriorityOtherReason(e.target.value)} />
              </div>
            )}
            {priorityError && <div className={styles.formError} role="alert">{priorityError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={closePriority}>Cancel</button>
              <button type="submit" className={styles.submitBtn}
                disabled={prioritySubmitting || (priorityReasonCode === "Other" && priorityOtherReason.trim().length < 3)}>
                {prioritySubmitting ? "Saving…" : "Mark priority"}
              </button>
            </div>
          </form>
        </Dialog>
      )}

      {delayFormFor && (
        <Dialog title={`Announce delay — ${staff.find((s) => s.id === delayFormFor)?.name ?? ""}`} onClose={closeDelayForm}>
          <form onSubmit={submitDelay}>
            <div className={styles.field}>
              <span className={styles.label} id="delayPresetLabel">Running late by</span>
              <div className={styles.presets} role="group" aria-labelledby="delayPresetLabel">
                {DELAY_PRESETS.map((m) => (
                  <button type="button" key={m} className={delayMins === m ? styles.presetActive : styles.preset}
                    aria-pressed={delayMins === m} onClick={() => setDelayMinutesInput(String(m))}>{m} min</button>
                ))}
              </div>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="delayMinutes">Other (minutes)</label>
              <input id="delayMinutes" type="number" min="1" step="1" className={styles.input}
                value={delayMinutesInput} onChange={(e) => setDelayMinutesInput(e.target.value)} required />
              {!delayValid && delayMinutesInput !== "" && <span className={styles.formError} role="alert">Enter a whole number of minutes, 1 or more.</span>}
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="delayReason">Reason (optional)</label>
              <input id="delayReason" className={styles.input} value={delayReasonInput}
                onChange={(e) => setDelayReasonInput(e.target.value)} placeholder="Emergency case running long" />
            </div>
            <div className={styles.delayPreview} aria-label="Delay preview">
              <span className={styles.label}>Preview</span>
              {delayValid ? (
                <span><StatusPill tone="danger" label="Delayed" icon="clock" /> Running {delayMins}m late{delayReasonInput.trim() ? ` — ${delayReasonInput.trim()}` : ""}</span>
              ) : (
                <span className={styles.muted}>Pick or enter the delay to see the notice.</span>
              )}
              <span className={styles.muted}>{delayWaiting === 0 ? "No patients are waiting for this doctor." : `${delayWaiting} waiting ${delayWaiting === 1 ? "patient is" : "patients are"} affected.`}</span>
            </div>
            <ul className={styles.ladder} aria-label="How the delay is delivered">
              <li>Queue sidebar: shown to the front desk as soon as you announce.</li>
              {/* shortcut: no patient-messaging endpoint for delays yet; replace with a real channel ladder (WhatsApp, then SMS) when the backend sends them. */}
              <li className={styles.muted}>Patient message (WhatsApp, then SMS): not available yet.</li>
            </ul>
            {delayError && <div className={styles.formError} role="alert">{delayError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={closeDelayForm}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={delayBusy || !delayValid}>
                {delayBusy ? "Announcing…" : "Announce delay"}
              </button>
            </div>
          </form>
        </Dialog>
      )}

      {showDelayPicker && (
        <Dialog title="Notify delay — pick a doctor" onClose={closeDelayPicker}>
          {staff.length === 0 ? (
            <EmptyState icon="users" title="No doctors on the roster" />
          ) : (
            <div className={styles.searchResults}>
              {staff.map((s) => (
                <button type="button" key={s.id} className={styles.searchResultRow} onClick={() => pickDelayDoctor(s.id)}>{s.name}</button>
              ))}
            </div>
          )}
          <div className={styles.modalActions}>
            <button type="button" className={styles.cancelBtn} onClick={closeDelayPicker}>Cancel</button>
          </div>
        </Dialog>
      )}

      {showCheckoutPicker && (
        <Dialog title="Checkout — pick a patient" onClose={closeCheckoutPicker}>
          {checkoutRows.length === 0 ? (
            <EmptyState icon="receipt" title="No one is ready for checkout" />
          ) : (
            <div className={styles.searchResults}>
              {checkoutRows.map((r) => (
                <button type="button" key={r.id} className={styles.searchResultRow}
                  onClick={() => { setShowCheckoutPicker(false); router.push(`/checkout/${r.id}`); }}>
                  #{r.tokenNumber} · {r.patientName}
                </button>
              ))}
            </div>
          )}
          <div className={styles.modalActions}>
            <button type="button" className={styles.cancelBtn} onClick={closeCheckoutPicker}>Cancel</button>
          </div>
        </Dialog>
      )}

      {showCheckin && (
        <Dialog title="Check in — today's appointments" onClose={closeCheckin}>
          {checkinError && <div className={styles.formError} role="alert">{checkinError}</div>}
          {checkinAppointments === null ? (
            <Skeleton rows={3} label="Loading today's appointments" />
          ) : checkinAppointments.length === 0 ? (
            <EmptyState icon="calendar" title="No scheduled appointments are waiting to arrive" />
          ) : (
            <div className={styles.searchResults}>
              {checkinAppointments.map((a) => (
                <div key={a.id} className={styles.appointmentRow}>
                  <span>{a.patientName} · {a.doctorName} · {new Date(a.startTime).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
                  <button type="button" className={styles.actionBtn} onClick={() => { setShowCheckin(false); setCheckinTarget(a); }}>
                    Check in
                  </button>
                </div>
              ))}
            </div>
          )}
          <div className={styles.modalActions}>
            <button type="button" className={styles.cancelBtn} onClick={closeCheckin}>Cancel</button>
          </div>
        </Dialog>
      )}

      {showMove && (
        <Dialog title="Move queue" onClose={closeMove}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="moveEntry">Patient</label>
            <select id="moveEntry" className={styles.select} value={moveEntryId} onChange={(e) => selectMoveEntry(e.target.value)}>
              <option value="">Select a patient…</option>
              {rows.filter((r) => bucketOf(r.status) === "waiting").map((r) => (
                <option key={r.id} value={r.id}>#{r.tokenNumber} · {r.patientName}</option>
              ))}
            </select>
          </div>
          {moveEntryId && (
            moveTargets === null ? (
              <Skeleton rows={2} label="Loading transfer options" />
            ) : moveTargets.length === 0 ? (
              <EmptyState icon="info" title="This department isn't configured to transfer patients anywhere" />
            ) : (
              <>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="moveDepartment">Department</label>
                  <select id="moveDepartment" className={styles.select} value={moveDepartmentId}
                    onChange={(e) => { setMoveDepartmentId(e.target.value); setMoveDoctorId(""); }}>
                    <option value="">Select a department…</option>
                    {moveTargets.map((t) => <option key={t.departmentId} value={t.departmentId}>{t.departmentName}</option>)}
                  </select>
                </div>
                {moveDepartmentId && (
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="moveDoctor">Doctor</label>
                    <select id="moveDoctor" className={styles.select} value={moveDoctorId} onChange={(e) => setMoveDoctorId(e.target.value)}>
                      <option value="">Select a doctor…</option>
                      {moveTargets.find((t) => t.departmentId === moveDepartmentId)?.doctors.map((d) => (
                        <option key={d.id} value={d.id}>{d.name}</option>
                      ))}
                    </select>
                  </div>
                )}
                {moveDoctorId && (
                  <>
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor="moveReason">Reason{movingDept ? " (required)" : ""}</label>
                      <select id="moveReason" className={styles.select} value={moveReason} required={movingDept} onChange={(e) => setMoveReason(e.target.value)}>
                        <option value="">Select a reason…</option>
                        {MOVE_REASONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                      </select>
                    </div>
                    {moveReason === "other" && (
                      <div className={styles.field}>
                        <label className={styles.label} htmlFor="moveReasonNote">Note</label>
                        <input id="moveReasonNote" className={styles.input} maxLength={200} value={moveReasonNote} onChange={(e) => setMoveReasonNote(e.target.value)} />
                      </div>
                    )}
                  </>
                )}
              </>
            )
          )}
          {moveError && <div className={styles.formError} role="alert">{moveError}</div>}
          <div className={styles.modalActions}>
            <button type="button" className={styles.cancelBtn} onClick={closeMove}>Cancel</button>
            <button type="button" className={styles.submitBtn} disabled={moveSubmitting || !moveDepartmentId || !moveDoctorId || reasonMissing}
              onClick={submitMove}>
              {moveSubmitting ? "Moving…" : "Move"}
            </button>
          </div>
        </Dialog>
      )}
    </main>
  );
}
