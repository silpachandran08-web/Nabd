"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { STALE } from "@nabd/api-client/queries";
import type { ActivityEntryResponse, AdministrationOrderResponse, FlowStepResponse, ProcedureOrderResponse, StaffRosterEntry, VitalsHistoryResponse, VitalsResponse, WorklistResponse } from "@nabd/api-client/generated";
import styles from "./nursing.module.css";
import Icon from "../components/ui/Icon";
import Dialog from "../components/ui/Dialog";
import VaccinePanel, { RecordVaccineDialog, type VaccineVisit } from "../components/nursing/VaccinePanel";
import AccessGate from "../components/ui/AccessGate";
import StatusPill from "../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { can } from "../lib/permissions";
import { getIdentity } from "../lib/session";
import { usePolling } from "../lib/usePolling";
import { useHasMounted } from "../lib/useHasMounted";
import { adminOrdersQuery, getter, key, live, prefix, useReload } from "../lib/query";
import { VITAL_FIELDS, EMPTY_VITALS, EMPTY_EXTRAS, BLOOD_SUGAR_UNIT, PAIN_SCORE_MAX, nurseVitalsErrors, nurseVitalsPayload, formatBp, VITALS_BLANK_HINT, VISIT_LABEL, age, type QueueEntry, type VitalKey, type VitalsForm, type VitalsExtras } from "@nabd/domain";
import { apiFetch, goLogin } from "../lib/apiClient";
import { api, ApiError } from "../lib/api";
import { staleMessage } from "../lib/queueMove";
import { clinicToday } from "../lib/clinicTime";

// E13 Nursing, Orders & Triage. NB-142's vitals worklist plus NB-143/145/146/148 as tabs on the
// same page — NB-144/149 (clinical triage inbox, task handoff) aren't built, both need NB-197's
// WhatsApp inbox, which doesn't exist. NB-147 (package sessions) is a link to /packages, which
// already has the redeem action — no new UI needed for it.
// FEF-015: response types are generated from the API spec; a row's queue fields are the one domain QueueEntry
// (derived from the generated QueueEntryResponse, so a spec rename breaks here).
type Entry = Omit<QueueEntry, "gender" | "ageYears">;
type Row = Entry & {
  patientName: string; patientMrn: string; doctorName: string;
  allergies: string[]; chronicConditions: string[];
  lastVitalsAt: string | null; vitalsOutOfRange: boolean;
  dob: string | null; gender: string | null; hasAppointment: boolean; ageYears: number | null; wait: number;
  // The patient record could not be read (no patients:view, 404, 5xx): allergies are UNKNOWN, not absent.
  flagsUnknown: boolean;
};
// A patient's clinical-flags chips: allergy (danger), chronic condition (warn), presenting
// complaint (neutral info). "Urgent" is reserved for the queue's priority flag (rendered
// separately) — a routine complaint such as "cough" must never look urgent.
type Flag = { kind: "danger" | "warn" | "info"; text: string };
function flagsFor(r: { allergies: string[]; chronicConditions: string[]; presentingComplaint?: string | null; priorityReason?: string | null }): Flag[] {
  return [
    ...r.allergies.map((a): Flag => ({ kind: "danger", text: `Allergy: ${a}` })),
    ...r.chronicConditions.map((c): Flag => ({ kind: "warn", text: c })),
    ...(r.presentingComplaint && r.presentingComplaint.trim().toLowerCase() !== (r.priorityReason ?? "").trim().toLowerCase()
      ? [{ kind: "info", text: r.presentingComplaint } as Flag] : []),
  ];
}

// "58 M" from the patient's real dob/gender; either part is simply left out when unknown.
function ageSex(dob: string | null, gender: string | null, ageYears?: number | null): string {
  const parts: string[] = [];
  if (ageYears != null) parts.push(String(ageYears));
  else if (dob) {
    const a = age(dob, clinicToday());
    if (a >= 0) parts.push(String(a));
  }
  const g = (gender ?? "").trim().charAt(0).toUpperCase();
  if (g) parts.push(g);
  return parts.filter(Boolean).join(" ");
}
const FLAG_ICON = { danger: "alert", warn: "pulse", info: "info" } as const;

function statusLabel(s: string): string {
  const t = s.replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
}
const hhmm = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

// The soft ranges are adult ranges: for a child (or when the age is unknown) showing "outside usual
// range" would mislead, so no hint is given; the server's age-banded flags still appear after save.
function isAdult(dob: string | null): boolean {
  return !!dob && age(dob, clinicToday()) >= 18;
}

function softWarning(key: VitalKey, raw: string, adult: boolean): string | null {
  const d = VITAL_FIELDS.find((x) => x.key === key);
  if (!adult || !d?.soft || raw.trim() === "") return null;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < d.min || n > d.max) return null;
  return n < d.soft[0] || n > d.soft[1] ? `Outside usual range (${d.soft[0]}–${d.soft[1]} ${d.unit}) — recheck` : null;
}
// Same catalogue as arrivals' Mark priority — nursing can only acknowledge an existing flag
// today, never raise one; this is the missing "Mark urgent" quick action from DESIGN.md's
// Vitals Worklist toolbar, using the same POST /queue/{id}/reorder reception already drives.
const URGENT_REASON_CODES = [
  "Chest pain / cardiac symptoms", "Breathing difficulty", "Severe bleeding",
  "High fever in infant", "Post-operative complication", "Severe pain", "Other",
] as const;
type VitalsFilter = "due" | "recorded" | "all";
type StaffOption = StaffRosterEntry & { id: string; name: string };
type AdministrationOrder = AdministrationOrderResponse & { id: string };
type ProcedureOrder = ProcedureOrderResponse & { id: string };
type ActivityEntry = ActivityEntryResponse & { occurredAt: string };
/** The Problem detail of a failed call, else the fallback (network errors included). */
const detailOf = (e: unknown, fallback: string) => (e instanceof ApiError && e.detail) || fallback;
// REC-060: recording vitals (blank fields go out as null, so the server keeps stored values) needs
// clinical:create or clinical:edit; the server 403s (vitals-correction-forbidden) a changed recorded value
// without clinical:edit, and the capture form never pre-fills or offers to change recorded values.
const PERM = { vitalsCreate: "clinical:create", vitalsWrite: "clinical:edit", vitalsRead: "clinical:view", create: "nursing:create", edit: "nursing:edit", packages: "packages:view", queueView: "queue:view", queueEdit: "queue:edit" } as const;
type Tab = "vitals" | "priority" | "administration" | "procedures" | "activity";

const POLL_MS = 15_000;
const TABS: { key: Tab; label: string }[] = [
  { key: "vitals", label: "Arrivals & Vitals" },
  { key: "priority", label: "Priority Patients" },
  { key: "administration", label: "Administration Orders" },
  { key: "procedures", label: "Today's Procedures" },
  { key: "activity", label: "Completed Activity" },
];

function validTab(t: string | null): Tab {
  return TABS.some((x) => x.key === t) ? (t as Tab) : "vitals";
}

function validFilter(f: string | null): VitalsFilter {
  return f === "recorded" || f === "all" ? f : "due";
}

function waitMinutes(createdAt: string): number {
  return Math.max(0, Math.round((Date.now() - new Date(createdAt).getTime()) / 60000));
}

// Mirrors DepartmentService.resolveStatusSequence on the backend — see checkout/[queueEntryId]/page.tsx
// for the same helper, duplicated locally rather than shared since it's a handful of lines.
const STATUSES_FOR_STEP_TYPE: Record<string, string[]> = {
  billing: ["billing_pending"],
  vitals: ["vitals_pending", "vitals_done"],
  consultation: ["in_consult"],
  procedures: ["procedures_pending"],
};
const DEFAULT_STEP_TYPES = ["vitals", "consultation"];

function resolveSequence(steps: { stepType: string }[]): string[] {
  const stepTypes = steps.length > 0 ? steps.map((s) => s.stepType) : DEFAULT_STEP_TYPES;
  const sequence = ["checked_in", "waiting"];
  for (const t of stepTypes) sequence.push(...(STATUSES_FOR_STEP_TYPE[t] ?? []));
  sequence.push("checkout_pending", "completed");
  return sequence;
}

export default function NursingPage() {
  return (
    <AccessGate permission="nursing:view" surface="Nursing">
      <Suspense fallback={null}>
        <NursingWorklist />
      </Suspense>
    </AccessGate>
  );
}

// useSearchParams (ClinicNav's Today/Waiting Patients/... deep links) needs a Suspense
// boundary above it — the outer component here is that boundary.
function NursingWorklist() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const qc = useQueryClient();
  // ClinicNav's nurse sub-items (Today, Waiting Patients, Vitals, ...) are all deep links into this one
  // page via ?tab=/?filter=, so the URL is the only state for both.
  const tab = validTab(searchParams.get("tab"));
  const vitalsFilter = validFilter(searchParams.get("filter"));

  function changeFilter(f: VitalsFilter) {
    router.replace(f === "due" ? "/nursing" : `/nursing?filter=${f}`);
  }
  // Permissions, the clock and the token's region live in the browser: read only after hydration.
  const mounted = useHasMounted();
  const queueView = mounted && can(PERM.queueView);
  const [actionError, setActionError] = useState<string | null>(null);
  const [, setNow] = useState(() => Date.now());

  const [vitalsEntryId, setVitalsEntryId] = useState<string | null>(null);
  const [vitalsForm, setVitalsForm] = useState<VitalsForm>(EMPTY_VITALS);
  const [vitalsTouched, setVitalsTouched] = useState<Partial<Record<string, boolean>>>({});
  const [bpInput, setBpInput] = useState("");
  const [vitalsExtras, setVitalsExtras] = useState<VitalsExtras>(EMPTY_EXTRAS);
  const [vitalsAttempted, setVitalsAttempted] = useState(false);
  const [vitalsError, setVitalsError] = useState<string | null>(null);
  // NUR-012: read-only view of a recorded visit's vitals (GET /clinical/vitals/{queueEntryId}).
  const [viewRow, setViewRow] = useState<Row | null>(null);
  const [vitalsFlags, setVitalsFlags] = useState<string[] | null>(null);
  const [vitalsSubmitting, setVitalsSubmitting] = useState(false);

  // Quick actions (DESIGN.md's Vitals Worklist toolbar): "Capture vitals" is a picker over
  // dueRows reusing the existing per-row modal; "Mark urgent" is the missing create-a-flag
  // action (nursing could previously only acknowledge one someone else raised).
  const [showCapturePicker, setShowCapturePicker] = useState(false);
  const [showVaccineDialog, setShowVaccineDialog] = useState(false);
  const [vaccineReload, setVaccineReload] = useState(0);
  const [showUrgentPicker, setShowUrgentPicker] = useState(false);
  const [urgentEntryId, setUrgentEntryId] = useState("");
  const [urgentReasonCode, setUrgentReasonCode] = useState<string>(URGENT_REASON_CODES[0]);
  const [urgentOtherReason, setUrgentOtherReason] = useState("");
  const [urgentSubmitting, setUrgentSubmitting] = useState(false);
  const [urgentError, setUrgentError] = useState<string | null>(null);

  // TopBar's "Capture vitals" button (DESIGN.md's top-bar primary action) signals through
  // ?action=capture rather than a cross-component callback, since TopBar is mounted above
  // and outside this page. Strip it once consumed so back/refresh doesn't reopen the picker.
  useEffect(() => {
    if (searchParams.get("action") !== "capture") return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a one-shot URL signal, consumed and stripped
    setShowCapturePicker(true);
    router.replace("/nursing");
  }, [searchParams, router]);

  // Day label (client-only: depends on the clock and the token's region). Saudi tenants show Hijri
  // primary + Gregorian secondary (DESIGN.md); everyone else Gregorian.
  const dateLine = (() => {
    if (!mounted) return "";
    const now = new Date();
    const greg = now.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" });
    if (getIdentity()?.tenantRegion !== "KSA") return greg;
    return `${new Intl.DateTimeFormat("en-SA-u-ca-islamic-umalqura", { day: "numeric", month: "long", year: "numeric" }).format(now)} · ${greg}`;
  })();

  // VaccinePanel / RecordVaccineDialog still take a Response-returning fetcher (not part of this page's queries).
  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        goLogin();
        return null;
      }
      const res = await apiFetch(path, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        goLogin();
        return null;
      }
      return res;
    },
    []
  );

  // ---- server state (FEF-015) ---------------------------------------------------------------------
  // NUR-019: one worklist call (queue entry + patient + vitals per row) instead of a per-row fan-out, so a
  // 40-row worklist is still one request. The worklist, priority and procedures-pending lists need queue:view;
  // a nurse role without it still has the nursing:view sections (orders, procedures, activity).
  const worklistQ = useQuery({
    ...live, queryKey: key("nursing", { list: "worklist" }), queryFn: getter<WorklistResponse>("/nursing/worklist"), enabled: queueView,
  });
  const rosterQ = useQuery({ queryKey: key("roster"), queryFn: getter<StaffOption[]>("/staff/roster"), staleTime: STALE.roster, enabled: queueView });
  const adminQ = useQuery({ ...adminOrdersQuery(), enabled: mounted }); // shared with the sidebar badge
  const procQ = useQuery({
    ...live, queryKey: key("nursing", { list: "procedure-orders" }), queryFn: getter<ProcedureOrder[]>("/nursing/procedure-orders/today"), enabled: mounted,
  });
  const activityQ = useQuery({
    ...live, queryKey: key("nursing", { list: "activity" }), queryFn: getter<ActivityEntry[]>("/nursing/activity/today"), enabled: mounted,
  });
  const reload = useReload("nursing");
  // "updates live with queue state" (NB-142's acceptance bar) — polling, same as /arrivals (NB-095);
  // push-based sync is NB-096, a separate ticket.
  usePolling(reload, POLL_MS);
  // Every write here changes the worklist or the queue (and the sidebar badges reading them).
  const invalidateAll = () => Promise.all([qc.invalidateQueries({ queryKey: prefix("nursing") }), qc.invalidateQueries({ queryKey: prefix("queue") })]);

  const wErr = worklistQ.error;
  const queueForbidden = mounted && (!can(PERM.queueView) || (wErr instanceof ApiError && wErr.status === 403));
  // Keep whatever was last loaded on screen (and any open dialog mounted); a failed refresh shows an
  // inline banner with Retry instead of replacing the worklist.
  const error = !wErr || queueForbidden ? null : wErr instanceof ApiError ? "Couldn't load the nursing worklist. Try again." : "Couldn't reach the server. Check your connection and try again.";
  const loaded = mounted && (queueForbidden || worklistQ.data !== undefined) && [adminQ, procQ, activityQ].every((q) => !q.isPending);
  // A 403 on a secondary list just means that section isn't theirs; anything else is a failure
  // the nurse should hear about rather than see as a silently empty tab.
  const sectionFailed = [adminQ, procQ, activityQ, rosterQ].some((q) => q.isError && !(q.error instanceof ApiError && q.error.status === 403));
  const list = <T,>(d: T[] | undefined): T[] => (Array.isArray(d) ? d : []);
  const staff = list(rosterQ.data);
  const administrationOrders = list(adminQ.data) as AdministrationOrder[];
  const procedureOrders = list(procQ.data);
  const activity = list(activityQ.data);
  const staffMap = new Map(staff.map((s) => [s.id, s.name]));
  const all: Row[] = (queueForbidden ? [] : list(worklistQ.data?.items)).map(({ entry, patient: p, vitals: v }) => {
    const e = entry as Entry;
    return {
      ...e, patientName: p?.name ?? "Unknown patient", patientMrn: p?.mrn ?? "", doctorName: e.doctorName || (staffMap.get(e.doctorId) ?? "—"),
      allergies: p?.allergies ?? [], chronicConditions: p?.chronicConditions ?? [],
      lastVitalsAt: v?.recordedAt ?? null, vitalsOutOfRange: (v?.abnormalFlags?.length ?? 0) > 0,
      dob: p?.dob ?? null, gender: entry?.gender ?? p?.gender ?? null, hasAppointment: !!e.appointmentId, flagsUnknown: !p,
      ageYears: entry?.ageYears ?? null, wait: e.waitMinutes ?? waitMinutes(e.createdAt),
    };
  });
  // "Recorded" (Vitals Worklist filter pill): anyone who has moved past vitals_pending today. Rows
  // without vitals (flow skips the vitals step, or no clinical:view) stay, shown as not recorded.
  const RECORDED_STATUSES = ["vitals_done", "in_consult", "procedures_pending", "checkout_pending", "completed"];
  const rows = all.filter((r) => r.status === "vitals_pending");
  const recordedRows = all.filter((r) => RECORDED_STATUSES.includes(r.status));
  const proceduresPendingRows = all.filter((r) => r.status === "procedures_pending");
  const priorityRows = all.filter((r) => r.priority);

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);

  function retryLoad() {
    void reload();
    if (rosterQ.isError) void rosterQ.refetch();
  }

  function openVitalsModal(entryId: string) {
    setVitalsEntryId(entryId);
    setVitalsForm(EMPTY_VITALS);
    setBpInput("");
    setVitalsExtras(EMPTY_EXTRAS);
    setVitalsTouched({});
    setVitalsAttempted(false);
    setVitalsError(null);
    setVitalsFlags(null);
  }

  // Read-only view: the query below fetches it while the dialog is open.
  function openViewVitals(r: Row) {
    setViewRow(r);
  }
  const viewQ = useQuery({ queryKey: key("vitals", { queueEntryId: viewRow?.id }), queryFn: getter<VitalsResponse>(`/clinical/vitals/${viewRow?.id}`), enabled: !!viewRow });
  const viewVitals = viewQ.data ?? null;
  const viewError = viewQ.isError;
  const canViewVitals = can(PERM.vitalsRead);
  // Row click / Enter / Space: capture for a waiting visit, read-only view for a recorded one.
  const openRow = (r: Row) => { if (r.status === "vitals_pending") { if (canCaptureVitals) openVitalsModal(r.id); } else if (canViewVitals && r.lastVitalsAt) openViewVitals(r); };
  const rowActionable = (r: Row) => (r.status === "vitals_pending" ? canCaptureVitals : canViewVitals && !!r.lastVitalsAt);

  function closeVitalsModal() {
    setVitalsEntryId(null);
    setVitalsFlags(null);
    void invalidateAll();
  }

  async function submitVitals(e: React.FormEvent) {
    e.preventDefault();
    if (!vitalsEntryId) return;
    setVitalsError(null);
    // An empty or implausible save must never move the visit on to the doctor with no vitals.
    setVitalsAttempted(true);
    if (Object.keys(nurseVitalsErrors(vitalsForm, bpInput, vitalsExtras)).length > 0) return;
    setVitalsSubmitting(true);
    try {
      const saved = await api.patch<VitalsResponse>(`/clinical/vitals/${vitalsEntryId}`, nurseVitalsPayload(vitalsForm, bpInput, vitalsExtras));
      const flags = saved?.abnormalFlags ?? [];
      if (flags.length > 0) {
        setVitalsFlags(flags);
      } else {
        closeVitalsModal();
      }
    } catch (err) {
      setVitalsError(detailOf(err, "Couldn't save vitals."));
    } finally {
      setVitalsSubmitting(false);
    }
  }

  // ── NB-143: priority ────────────────────────────────────────────────────
  async function acknowledgePriority(id: string) {
    setActionError(null);
    await api.post(`/queue/${id}/priority/acknowledge`).catch((err) => setActionError(detailOf(err, "Couldn't acknowledge.")));
    void invalidateAll();
  }

  function openUrgentPicker(entryId = "") {
    setShowUrgentPicker(true);
    setUrgentEntryId(entryId);
    setUrgentReasonCode(URGENT_REASON_CODES[0]);
    setUrgentOtherReason("");
    setUrgentError(null);
  }

  async function submitMarkUrgent(e: React.FormEvent) {
    e.preventDefault();
    if (!urgentEntryId) return;
    const reason = urgentReasonCode === "Other" ? urgentOtherReason.trim() : urgentReasonCode;
    if (!reason) return;
    setUrgentSubmitting(true);
    setUrgentError(null);
    try {
      await api.post(`/queue/${urgentEntryId}/reorder`, { priority: true, reason });
      setShowUrgentPicker(false);
      void invalidateAll();
    } catch (err) {
      setUrgentError(detailOf(err, "Couldn't flag this patient."));
    } finally {
      setUrgentSubmitting(false);
    }
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
    try {
      await api.post("/nursing/administration-orders", adminForm);
    } catch (err) {
      setAdminFormError(detailOf(err, "Couldn't create the order."));
      return;
    }
    setShowAdminForm(false);
    setAdminForm({ queueEntryId: "", drugName: "", dose: "", route: "IM", site: "" });
    void invalidateAll();
  }

  async function submitAdminister() {
    if (!witnessModal) return;
    setActionError(null);
    await api.post(`/nursing/administration-orders/${witnessModal.orderId}/administer`, { witnessedByStaffId: witnessModal.witnessId })
      .catch((err) => setActionError(detailOf(err, "Couldn't record administration.")));
    setWitnessModal(null);
    void invalidateAll();
  }

  async function submitRefuse() {
    if (!refuseModal) return;
    setActionError(null);
    await api.post(`/nursing/administration-orders/${refuseModal.orderId}/refuse`, { reason: refuseModal.reason })
      .catch((err) => setActionError(detailOf(err, "Couldn't record refusal.")));
    setRefuseModal(null);
    void invalidateAll();
  }

  // ── NB-355: the procedures_pending queue checkpoint — a coordination stop, not itself a
  // procedure_orders row; advancing it needs no special backend action beyond the generic
  // status PATCH, same as vitals_done -> in_consult on the consult page. ────────────────────
  async function continueAfterProcedures(entry: Row) {
    setActionError(null);
    const steps = await qc.fetchQuery({
      queryKey: key("department-flow", { id: entry.departmentId }), staleTime: STALE.departments,
      queryFn: getter<FlowStepResponse[]>(`/departments/${entry.departmentId}/flow`),
    }).catch(() => []);
    const sequence = resolveSequence((Array.isArray(steps) ? steps : []).map((s) => ({ stepType: s.stepType ?? "" })));
    const next = sequence[sequence.indexOf(entry.status) + 1];
    if (!next) {
      setActionError("Couldn't determine the next stage.");
      return;
    }
    await api.patch(`/queue/${entry.id}/status`, { status: next, expectedStatus: entry.status })
      .catch((err) => setActionError((err instanceof ApiError && staleMessage(err.problem)) || detailOf(err, "Couldn't continue the visit.")));
    void invalidateAll();
  }

  // ── NB-146: procedure orders ─────────────────────────────────────────────
  const [showProcForm, setShowProcForm] = useState(false);
  const [procForm, setProcForm] = useState({ queueEntryId: "", chargeCode: "", prepNotes: "", consentNote: "" });
  const [procFormError, setProcFormError] = useState<string | null>(null);

  async function submitProcOrder(e: React.FormEvent) {
    e.preventDefault();
    setProcFormError(null);
    try {
      await api.post("/nursing/procedure-orders", procForm);
    } catch (err) {
      setProcFormError(detailOf(err, "Couldn't create the order."));
      return;
    }
    setShowProcForm(false);
    setProcForm({ queueEntryId: "", chargeCode: "", prepNotes: "", consentNote: "" });
    void invalidateAll();
  }

  const [procRowError, setProcRowError] = useState<Record<string, string>>({});

  async function setProcedureStatus(id: string, status: string) {
    setProcRowError((prev) => { const n = { ...prev }; delete n[id]; return n; });
    await api.patch(`/nursing/procedure-orders/${id}/status`, { status })
      .catch((err) => setProcRowError((prev) => ({ ...prev, [id]: detailOf(err, "Couldn't update the procedure.") })));
    void invalidateAll();
  }

  // ── NB-119: signed consent gates starting the procedure ────────────────────
  const [consentModal, setConsentModal] = useState<{ orderId: string; signedName: string } | null>(null);
  const [consentError, setConsentError] = useState<string | null>(null);

  async function submitConsent() {
    if (!consentModal || !consentModal.signedName.trim()) return;
    setConsentError(null);
    try {
      await api.post(`/nursing/procedure-orders/${consentModal.orderId}/consent`, { signedName: consentModal.signedName });
    } catch (err) {
      // Stay open: consent that failed to record must not look recorded.
      setConsentError(detailOf(err, "Couldn't record consent."));
      return;
    }
    setConsentModal(null);
    void invalidateAll();
  }

  // ── NB-148: export ────────────────────────────────────────────────────────
  function exportActivityCsv() {
    const header = "Kind,Activity,Patient,Occurred At\n";
    const body = activity.map((a) => [a.kind, a.activity, a.patientName, a.occurredAt].map((v) => `"${v}"`).join(",")).join("\n");
    const blob = new Blob([header + body], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `shift-handover-${new Date().toLocaleDateString("en-CA")}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  const allow = (permission: Parameters<typeof can>[0]) => mounted && can(permission);

  const visibleVitalsRows = vitalsFilter === "due" ? rows : vitalsFilter === "recorded" ? recordedRows : [...rows, ...recordedRows];
  const urgentCandidates = [...rows, ...recordedRows].filter((r) => !r.priority);
  const recordedCount = recordedRows.filter((r) => r.lastVitalsAt).length;
  const outOfRangeCount = recordedRows.filter((r) => r.vitalsOutOfRange).length;
  const staffName = (id: string | null | undefined) => (id ? staff.find((s) => s.id === id)?.name ?? null : null);
  const canCaptureVitals = allow(PERM.vitalsCreate) || allow(PERM.vitalsWrite);
  const canCreateOrders = allow(PERM.create);
  const canEditOrders = allow(PERM.edit);
  // Flagging and acknowledging priority call POST /queue/{id}/reorder and /priority/acknowledge (queue:edit);
  // "Procedures done, continue" is PATCH /queue/{id}/status (queue:edit) after GET /departments/{id}/flow (queue:view).
  const canFlagPriority = allow(PERM.queueEdit);
  const canAdvanceQueue = allow(PERM.queueEdit) && allow(PERM.queueView);
  const noQueueAccess = <EmptyState icon="shield" title="No access to the patient queue" hint="Your role doesn't include the patient queue, so arrivals and priority patients aren't shown here." />;
  const showSkeleton = !loaded && !error;
  const showLoadError = !loaded && !!error;
  const vitalsEmpty = {
    due: { title: "No one is waiting for vitals", hint: "New arrivals appear here as soon as reception checks them in." },
    recorded: { title: "No vitals recorded yet today", hint: "Patients whose vitals you capture will show up here." },
    all: { title: "No patients on the worklist", hint: "Nobody has been checked in for vitals today." },
  }[vitalsFilter];
  const vErrors = nurseVitalsErrors(vitalsForm, bpInput, vitalsExtras);
  const vitalsRow = vitalsEntryId ? [...rows, ...recordedRows].find((r) => r.id === vitalsEntryId) : undefined;
  const vitalsPatientId = vitalsRow?.patientId;
  // NUR-022: previous measurements for the open visit's patient (null = loading). One extra row is requested
  // because the current visit is excluded client-side (contract: server excludes nothing).
  const prevQ = useQuery({
    queryKey: key("vitals-previous", { patientId: vitalsPatientId }),
    queryFn: getter<VitalsHistoryResponse>(`/clinical/vitals/patient/${vitalsPatientId}/previous?limit=6`),
    enabled: !!vitalsEntryId && !!vitalsPatientId && can(PERM.vitalsRead),
  });
  const prevVitals = prevQ.data ? list(prevQ.data.items).filter((v) => v.queueEntryId !== vitalsEntryId).slice(0, 5) : null;
  const prevError = prevQ.isError;
  const vitalsFormEmpty = VITAL_FIELDS.every((d) => vitalsForm[d.key].trim() === "") && !bpInput.trim() && !vitalsExtras.bloodSugar.trim() && !vitalsExtras.painScore.trim();
  // ClinicNav's "Vaccines & Injections" sidebar item deep-links here (?tab=vaccines) even though
  // no backend exists for it — not one of the 5 real tabs, so it's checked from the raw query
  // param (validTab() would just fall it back to "vitals") and shown as its own honest section.
  const isVaccinesView = searchParams.get("tab") === "vaccines";
  // Today's visits a vaccine can be recorded against (one entry per visit, never a made-up patient).
  const vaccineVisits: VaccineVisit[] = [...new Map([...rows, ...recordedRows, ...proceduresPendingRows].map((r) => [r.id, r])).values()]
    .map((r) => ({ patientId: r.patientId, queueEntryId: r.id, patientName: r.patientName, tokenNumber: r.tokenNumber }));

  return (
    <main className={styles.page}>
      <div className={styles.headerRow}>
        <div>
          <h1 className={styles.title}>{isVaccinesView ? "Vaccines & Injections" : tab === "vitals" ? "Vitals Worklist" : TABS.find((t) => t.key === tab)?.label}</h1>
          <p className={styles.subtitle}>
            {dateLine}{loaded && !isVaccinesView && !queueForbidden && <>{dateLine ? " · " : ""}{rows.length} awaiting vitals</>}
          </p>
        </div>
        <div className={styles.headerRight}>
          {loaded && tab === "vitals" && !queueForbidden && (
            <div className={styles.filterPills} role="group" aria-label="Worklist view">
              <button className={!isVaccinesView && vitalsFilter === "due" ? styles.pillFilterActive : styles.pillFilter} aria-pressed={!isVaccinesView && vitalsFilter === "due"} onClick={() => changeFilter("due")}>
                Vitals due <span className={styles.pillCount}>{rows.length}</span>
              </button>
              {/* No vaccines backend: the segment opens the honest "not available" view and carries no count. */}
              <button className={isVaccinesView ? styles.pillFilterActive : styles.pillFilter} aria-pressed={isVaccinesView} onClick={() => router.replace("/nursing?tab=vaccines")}>
                Vaccines
              </button>
              <button className={!isVaccinesView && vitalsFilter === "recorded" ? styles.pillFilterActive : styles.pillFilter} aria-pressed={!isVaccinesView && vitalsFilter === "recorded"} onClick={() => changeFilter("recorded")}>
                Recorded <span className={styles.pillCount}>{recordedRows.length}</span>
              </button>
              <button className={!isVaccinesView && vitalsFilter === "all" ? styles.pillFilterActive : styles.pillFilter} aria-pressed={!isVaccinesView && vitalsFilter === "all"} onClick={() => changeFilter("all")}>
                All <span className={styles.pillCount}>{rows.length + recordedRows.length}</span>
              </button>
            </div>
          )}
        </div>
      </div>

      {isVaccinesView ? (
        <>
          {canCreateOrders && (
            <div className={`${styles.headerActions} ${styles.actionsGap}`}>
              <button className={styles.actionBtn} onClick={() => setShowVaccineDialog(true)}><Icon name="syringe" size={14} />Record vaccine</button>
            </div>
          )}
          <VaccinePanel authedFetch={authedFetch} reloadKey={vaccineReload} />
        </>
      ) : (
        <>

      {actionError && <div className={styles.banner} role="alert"><Icon name="alert" size={16} />{actionError}</div>}
      {loaded && error && (
        <div className={styles.banner} role="alert">
          <Icon name="alert" size={16} />{error} Showing the last loaded data.
          <button type="button" className={styles.smallBtn} onClick={retryLoad}>Retry</button>
        </div>
      )}
      {loaded && !error && sectionFailed && (
        <div className={styles.banner} role="alert">
          <Icon name="alert" size={16} />Some sections couldn&apos;t be refreshed.
          <button type="button" className={styles.smallBtn} onClick={retryLoad}>Retry</button>
        </div>
      )}

      {showSkeleton && <Skeleton rows={6} label="Loading nursing worklist" />}
      {showLoadError && <div className={styles.card}><ErrorState message={error} onRetry={retryLoad} /></div>}

      {loaded && tab === "vitals" && (
        <>
          <div className={`${styles.headerActions} ${styles.actionsGap}`}>
            {canCaptureVitals && !queueForbidden && <button className={styles.actionBtn} onClick={() => setShowCapturePicker(true)}><Icon name="pulse" size={14} />Capture vitals</button>}
            {canCreateOrders && <button className={styles.secondaryBtn} onClick={() => setShowVaccineDialog(true)}><Icon name="syringe" size={14} />Record vaccine</button>}
            {canFlagPriority && !queueForbidden && <button className={styles.secondaryBtn} onClick={() => openUrgentPicker()}><Icon name="bolt" size={14} />Mark urgent</button>}
          </div>

          <div className={styles.layout}>
            <div className={styles.card}>
              {queueForbidden ? noQueueAccess : visibleVitalsRows.length === 0 ? (
                <EmptyState icon="pulse" title={vitalsEmpty.title} hint={vitalsEmpty.hint} />
              ) : (
                <div className={styles.tableWrap}>
                  <table className={styles.table}>
                    <thead><tr><th>Patient</th><th>Visit</th><th>Clinical flags</th><th>Last vitals</th><th>Status</th><th><span className={styles.srOnly}>Action</span></th></tr></thead>
                    <tbody>
                      {visibleVitalsRows.map((r) => {
                        const flags = flagsFor(r);
                        const hasAllergy = flags.some((f) => f.kind === "danger");
                        const rowClass = hasAllergy ? styles.rowFlagged : r.priority ? styles.rowUrgent : undefined;
                        return (
                          <tr key={r.id} className={`${rowClass ?? ""} ${rowActionable(r) ? styles.rowClickable : ""}`}
                            {...(rowActionable(r) ? { tabIndex: 0, onClick: () => openRow(r), onKeyDown: (e: React.KeyboardEvent) => { if (e.target === e.currentTarget && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); openRow(r); } } } : {})}>
                            <td data-label="Patient" className={styles.patientCell} title={r.patientMrn ? `MRN ${r.patientMrn}` : undefined}>
                              <div><span className={styles.patientName}>{r.patientName}</span>
                              <span className={styles.mrn} title={r.doctorName !== "—" ? r.doctorName : undefined}>{[ageSex(r.dob, r.gender, r.ageYears), `T-${r.tokenNumber}`, r.doctorName !== "—" ? r.doctorName : "", `${r.wait}m wait`].filter(Boolean).join(" · ")}</span></div>
                            </td>
                            <td data-label="Visit">
                              <span className={styles.visitCell}>
                                <Icon name={r.hasAppointment ? "calendar" : r.source === "walk_in" ? "login" : "users"} size={15} />
                                {r.hasAppointment ? "Appointment" : VISIT_LABEL[r.source] ?? r.source}
                              </span>
                            </td>
                            <td data-label="Clinical flags" className={styles.flagCell}>
                              {flags.length === 0 && !r.priority && !r.flagsUnknown ? <span className={styles.muted}>No flags</span> : (
                                <div className={styles.flagList}>
                                  {r.flagsUnknown && <StatusPill tone="warn" label="Allergies unknown: record unavailable" icon="alert" />}
                                  {r.priority && <StatusPill tone="urgent" label={r.priorityReason ? `Urgent: ${r.priorityReason}` : "Urgent"} icon="alert" />}
                                  {flags.map((f, i) => <StatusPill key={i} tone={f.kind} label={f.text} icon={FLAG_ICON[f.kind]} />)}
                                </div>
                              )}
                            </td>
                            <td data-label="Last vitals" className={styles.muted}>{r.lastVitalsAt ? hhmm(r.lastVitalsAt) : r.status !== "vitals_pending" && canViewVitals ? "Not recorded" : "—"}</td>
                            <td data-label="Status">
                              {r.status === "vitals_pending"
                                ? <StatusPill tone="warn" label="Waiting" icon="clock" />
                                : <StatusPill tone={r.vitalsOutOfRange ? "danger" : "ok"} label={r.vitalsOutOfRange ? `${statusLabel(r.status)} · out of range` : statusLabel(r.status)} icon={r.vitalsOutOfRange ? "alert" : "check"} />}
                            </td>
                            <td data-label="" className={styles.actionCell}>{r.status === "vitals_pending" && canCaptureVitals && <button className={styles.actionBtn} onClick={(e) => { e.stopPropagation(); openVitalsModal(r.id); }}><Icon name="pulse" size={14} />Capture vitals</button>}
                              {r.status !== "vitals_pending" && canViewVitals && r.lastVitalsAt && <button className={styles.actionBtn} onClick={(e) => { e.stopPropagation(); void openViewVitals(r); }}><Icon name="pulse" size={14} />View vitals</button>}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className={styles.sidebarStack}>
              {!queueForbidden && <div className={styles.card}>
                <div className={styles.sideTitle}><Icon name="pulse" size={14} />Vitals summary</div>
                <div className={styles.summaryRow}><span>Recorded today</span><span className={styles.summaryValue}>{recordedCount}</span></div>
                <div className={styles.summaryRow}>
                  <span>Out of range</span>
                  <span className={outOfRangeCount > 0 ? styles.summaryDanger : styles.summaryValue}>
                    {outOfRangeCount > 0 && <Icon name="alert" size={14} />} {outOfRangeCount}
                  </span>
                </div>
                {/* No real source for capture duration yet — an honest dash, never an invented figure. */}
                <div className={styles.summaryRow}><span>Avg capture time</span><span className={styles.summaryValue} title="Not measured yet">—</span></div>
              </div>}

              {/* No backend exists for vaccines/injections yet (filed separately) — shown as an
                  honest "not available" card rather than fabricated due-dates. */}
              <div className={styles.card}>
                <div className={styles.sideTitle}><Icon name="syringe" size={14} />Vaccines &amp; injections due</div>
                <div className={styles.sideState}>Not available yet. No immunisation schedule is set up.</div>
                <div className={styles.sideFooter}>
                  <button type="button" className={styles.secondaryBtnWide} onClick={() => router.replace("/nursing?tab=vaccines")}>Open vaccine log</button>
                </div>
              </div>
            </div>
          </div>
        </>
      )}

      {loaded && tab === "priority" && (
        <div className={styles.card}>
          {queueForbidden ? noQueueAccess : priorityRows.length === 0 ? (
            <EmptyState icon="alert" title="No priority patients flagged" hint="Patients flagged urgent by reception or nursing appear here." />
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Patient</th><th>Flagged</th><th>Reason</th><th>Acknowledged</th><th>State</th><th></th></tr></thead>
                <tbody>
                  {priorityRows.map((r) => {
                    const flaggedBy = staffName(r.priorityFlaggedBy);
                    const ackBy = staffName(r.priorityAcknowledgedBy);
                    return (
                      <tr key={r.id} className={styles.rowUrgent}>
                        <td data-label="Patient"><span className={styles.patientName}>{r.patientName}</span><span className={styles.mrn}>{r.patientMrn}</span></td>
                        <td data-label="Flagged" className={styles.muted}>
                          {r.priorityFlaggedAt ? hhmm(r.priorityFlaggedAt) : "—"}{flaggedBy ? ` · by ${flaggedBy}` : ""}
                        </td>
                        <td data-label="Reason">{r.priorityReason}</td>
                        <td data-label="Acknowledged" className={styles.muted}>
                          {r.priorityAcknowledgedAt
                            ? <>{ackBy ? `${ackBy} · ` : ""}{hhmm(r.priorityAcknowledgedAt)}</>
                            : <StatusPill tone="urgent" label="Awaiting acknowledgement" icon="clock" />}
                        </td>
                        <td data-label="State"><StatusPill tone={r.priorityAcknowledgedAt ? "info" : "neutral"} label={statusLabel(r.status)} icon={r.priorityAcknowledgedAt ? "check" : "list"} /></td>
                        <td data-label="">{!r.priorityAcknowledgedAt && canFlagPriority && <button className={styles.actionBtn} onClick={() => acknowledgePriority(r.id)}>Acknowledge</button>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {loaded && tab === "administration" && (
        <>
          {canCreateOrders && (
            <div className={`${styles.headerActions} ${styles.actionsGap}`}>
              <button className={styles.actionBtn} onClick={() => setShowAdminForm(true)}>+ New order</button>
            </div>
          )}
          <div className={styles.card}>
            {administrationOrders.length === 0 ? (
              <EmptyState icon="syringe" title="No administration orders today" hint="Orders placed by doctors for administration appear here." />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th>Patient</th><th>Ordered item</th><th>Ordered by</th><th>Route &amp; site</th><th>State</th><th></th></tr></thead>
                  <tbody>
                    {administrationOrders.map((o) => (
                      <tr key={o.id}>
                        <td data-label="Patient">{o.patientName}</td>
                        <td data-label="Ordered item">{o.drugName} · {o.dose}</td>
                        <td data-label="Ordered by">{o.orderedByName}</td>
                        <td data-label="Route & site">{o.route}{o.site ? ` · ${o.site}` : ""}</td>
                        <td data-label="State">
                          {o.status === "not_started" && <StatusPill tone="neutral" label="Not started" />}
                          {o.status === "administered" && <StatusPill tone="ok" label={`Administered · witness ${o.witnessedByName}`} />}
                          {o.status === "refused" && <StatusPill tone="danger" label={`Refused: ${o.refuseReason}`} icon="x" />}
                        </td>
                        <td data-label="">
                          {o.status === "not_started" && canEditOrders && (
                            <div className={styles.rowActions}>
                              <button className={styles.smallBtn} onClick={() => setWitnessModal({ orderId: o.id, witnessId: "" })}>Administer</button>
                              <button className={styles.smallBtn} onClick={() => setRefuseModal({ orderId: o.id, reason: "" })}>Refuse</button>
                            </div>
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

      {loaded && tab === "procedures" && (
        <>
          {proceduresPendingRows.length > 0 && (
            <div className={`${styles.card} ${styles.cardGap}`}>
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th>Token</th><th>Patient</th><th>Doctor</th><th>Wait</th><th>Status</th><th></th></tr></thead>
                  <tbody>
                    {proceduresPendingRows.map((r) => (
                      <tr key={r.id} className={r.priority ? styles.rowUrgent : undefined}>
                        <td data-label="Token">#{r.tokenNumber}</td>
                        <td data-label="Patient"><span className={styles.patientName}>{r.patientName}</span><span className={styles.mrn}>{r.patientMrn}</span></td>
                        <td data-label="Doctor">{r.doctorName}</td>
                        <td data-label="Wait" className={styles.muted}>{waitMinutes(r.createdAt)}m</td>
                        <td data-label="Status">
                          {r.priority && <StatusPill tone="urgent" label="Urgent" icon="alert" />}
                          <StatusPill tone="warn" label="Procedures pending" icon="clock" />
                        </td>
                        <td data-label="">{canAdvanceQueue && <button className={styles.actionBtn} onClick={() => continueAfterProcedures(r)}>Procedures done, continue</button>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
          {canCreateOrders && (
            <div className={`${styles.headerActions} ${styles.actionsGap}`}>
              <button className={styles.actionBtn} onClick={() => setShowProcForm(true)}>+ New procedure</button>
            </div>
          )}
          <div className={styles.card}>
            {procedureOrders.length === 0 ? (
              <EmptyState icon="list" title="No procedures scheduled today" hint="Procedure orders for today appear here." />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th>Patient</th><th>Procedure</th><th>Consent</th><th>Preparation</th><th>State</th><th></th></tr></thead>
                  <tbody>
                    {procedureOrders.map((p) => {
                      const consented = !!p.consentSignedName;
                      const open = p.status === "ordered" || p.status === "prepped";
                      // Wireframe (Today's Procedures): an open order without signed consent reads "Consent missing" /
                      // "Blocked until consent" / Paused. The backend refuses prepped/completed until consent (ProcedureService).
                      const blocked = open && !consented;
                      return (
                        <tr key={p.id}>
                          <td data-label="Patient">{p.patientName}</td>
                          <td data-label="Procedure">{p.chargeName}</td>
                          <td data-label="Consent">
                            {consented
                              ? <span title={p.consentRecordedByName ? `Recorded by ${p.consentRecordedByName}` : undefined}>
                                  <StatusPill tone="ok" icon="check" label={`Signed · ${p.consentSignedName}${p.consentSignedAt ? ` · ${hhmm(p.consentSignedAt)}` : ""}`} />
                                </span>
                              : <div className={styles.rowActions}>
                                  <StatusPill tone="danger" label="Consent missing" icon="alert" />
                                  {canEditOrders && open && <button className={styles.smallBtn} onClick={() => { setConsentError(null); setConsentModal({ orderId: p.id, signedName: "" }); }}>Record consent</button>}
                                </div>}
                            {/* No consent-form version field exists in the API yet; the free-text note given at ordering is shown as typed. */}
                            {p.consentNote && <span className={`${styles.mrn} ${styles.muted}`}>Note: {p.consentNote}</span>}
                          </td>
                          <td data-label="Preparation">
                            {blocked
                              ? <StatusPill tone="warn" label="Blocked until consent" icon="clock" />
                              : <span className={styles.muted}>{p.prepNotes ?? "—"}</span>}
                            {blocked && p.prepNotes && <span className={`${styles.mrn} ${styles.muted}`}>{p.prepNotes}</span>}
                          </td>
                          <td data-label="State">
                            {blocked && <StatusPill tone="warn" label="Paused" icon="clock" />}
                            {p.status === "ordered" && !blocked && <StatusPill tone="neutral" label="Not started" />}
                            {p.status === "prepped" && !blocked && <StatusPill tone="info" label="In progress" icon="sync" />}
                            {p.status === "completed" && <StatusPill tone="ok" label={`Completed${p.billed ? " · billed" : " · pending bill"}`} />}
                          </td>
                          <td data-label="">
                            {open && consented && canEditOrders && (
                              <div className={styles.rowActions}>
                                {p.status === "ordered" && <button className={styles.smallBtn} onClick={() => setProcedureStatus(p.id, "prepped")}>Mark prepped</button>}
                                <button className={styles.smallBtn} onClick={() => setProcedureStatus(p.id, "completed")}>Complete</button>
                              </div>
                            )}
                            {procRowError[p.id] && <div className={styles.rowError} role="alert">{procRowError[p.id]}</div>}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {loaded && tab === "activity" && (
        <>
          <div className={`${styles.headerActions} ${styles.actionsGap}`}>
            <button className={styles.smallBtn} onClick={exportActivityCsv} disabled={activity.length === 0}>Export for handover</button>
          </div>
          <div className={styles.card}>
            {activity.length === 0 ? (
              <EmptyState icon="check" title="Nothing completed yet today" hint="Completed vitals, administrations and procedures appear here." />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th>Patient</th><th>Activity</th><th>Time</th></tr></thead>
                  <tbody>
                    {activity.map((a, i) => (
                      <tr key={i}>
                        <td data-label="Patient">{a.patientName}</td>
                        <td data-label="Activity">{a.activity}</td>
                        <td data-label="Time" className={styles.muted}>{hhmm(a.occurredAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
        </>
      )}

      {vitalsEntryId && vitalsFlags && (
        <Dialog title="Vitals saved" onClose={closeVitalsModal}>
          <div>
            <div className={styles.formError} role="alert">
              {vitalsFlags.map((f) => <div key={f} className={styles.flagLine}><Icon name="alert" size={14} />{f}</div>)}
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.submitBtn} onClick={closeVitalsModal}>Done</button>
            </div>
          </div>
        </Dialog>
      )}

      {/* Shared Dialog gives focus-in/restore, Tab trap and Escape. Escape and a backdrop tap close it only
          while nothing is typed: a stray tap must not discard half-typed vitals; Cancel is the explicit way out. */}
      {vitalsEntryId && !vitalsFlags && canCaptureVitals && (
        <Dialog title={`Capture vitals${vitalsRow ? ` — ${vitalsRow.patientName}` : ""}`} size="lg"
          onClose={() => { if (!vitalsSubmitting && vitalsFormEmpty) closeVitalsModal(); }}>
          {vitalsRow && (
            <div className={styles.vitalsPatient}>
              <strong>{vitalsRow.patientName}</strong>
              <span className={styles.muted}>#{vitalsRow.tokenNumber}{ageSex(vitalsRow.dob, vitalsRow.gender) ? ` · ${ageSex(vitalsRow.dob, vitalsRow.gender)}` : ""}{vitalsRow.patientMrn ? ` · ${vitalsRow.patientMrn}` : ""}</span>
            </div>
          )}
          <div className={styles.vitalsLayout}>
          <form noValidate onSubmit={submitVitals}>
            <p className={styles.muted}>{VITALS_BLANK_HINT}</p>
            {vitalsAttempted && vErrors.form && <div className={styles.formError} role="alert">{vErrors.form}</div>}
            <div className={styles.fieldGrid}>
              {([
                { key: "bp", label: "Blood pressure", unit: "mmHg", value: bpInput, set: (v: string) => setBpInput(v), type: "text", mode: "numeric", ph: "120/80", soft: null },
                ...VITAL_FIELDS.filter((d) => d.key !== "bpSystolic" && d.key !== "bpDiastolic").map((d) => ({
                  key: d.key as string, label: d.label, unit: d.unit, value: vitalsForm[d.key], type: "number", mode: "decimal", ph: undefined, step: d.step, min: d.min, max: d.max,
                  set: (v: string) => setVitalsForm((prev) => ({ ...prev, [d.key]: v })), soft: d.key as VitalKey,
                })),
                { key: "bloodSugar", label: "Blood sugar", unit: BLOOD_SUGAR_UNIT, value: vitalsExtras.bloodSugar, type: "number", mode: "decimal", ph: undefined, step: "any", min: 0, soft: null,
                  set: (v: string) => setVitalsExtras((prev) => ({ ...prev, bloodSugar: v })) },
                { key: "painScore", label: "Pain score", unit: `/${PAIN_SCORE_MAX}`, value: vitalsExtras.painScore, type: "number", mode: "numeric", ph: undefined, step: "1", min: 0, max: PAIN_SCORE_MAX, soft: null,
                  set: (v: string) => setVitalsExtras((prev) => ({ ...prev, painScore: v })) },
              ] as { key: string; label: string; unit: string; value: string; type: string; mode: "numeric" | "decimal"; ph?: string; step?: string; min?: number; max?: number; soft: VitalKey | null; set: (v: string) => void }[]).map((d) => {
                const errKey = d.key as keyof typeof vErrors;
                const showErr = (vitalsAttempted || vitalsTouched[d.key]) ? vErrors[errKey] : undefined;
                const warn = showErr || !d.soft ? null : softWarning(d.soft, d.value, isAdult(vitalsRow?.dob ?? null));
                return (
                  <div className={styles.field} key={d.key}>
                    <label className={styles.label} htmlFor={d.key}>{d.label} ({d.unit})</label>
                    <div className={styles.unitInput}>
                      <input id={d.key} type={d.type} inputMode={d.mode} step={d.step} min={d.min} max={d.max} placeholder={d.ph}
                        className={styles.input} value={d.value} aria-invalid={!!showErr}
                        aria-describedby={showErr || warn ? `${d.key}-msg` : undefined}
                        onBlur={() => setVitalsTouched((prev) => ({ ...prev, [d.key]: true }))}
                        onChange={(e) => d.set(e.target.value)} />
                      <span className={styles.unit} aria-hidden="true">{d.unit}</span>
                    </div>
                    {showErr && <div className={styles.fieldError} id={`${d.key}-msg`} role="alert"><Icon name="alert" size={12} />{showErr}</div>}
                    {warn && <div className={styles.fieldWarn} id={`${d.key}-msg`}><Icon name="info" size={12} />{warn}</div>}
                  </div>
                );
              })}
            </div>
            {vitalsError && <div className={styles.formError} role="alert">{vitalsError}</div>}
            <div className={styles.modalActions}>
              {canFlagPriority && vitalsRow && !vitalsRow.priority && (
                <button type="button" className={`${styles.smallBtn} ${styles.pushStart}`} onClick={() => openUrgentPicker(vitalsRow.id)}>Mark urgent</button>
              )}
              <button type="button" className={styles.cancelBtn} onClick={closeVitalsModal}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={vitalsSubmitting}>{vitalsSubmitting ? "Saving…" : "Save and mark ready"}</button>
            </div>
          </form>
          {can(PERM.vitalsRead) && (
            <aside className={styles.prevPanel} aria-label="Previous measurements">
              <h3 className={styles.prevTitle}>Previous measurements</h3>
              {prevError ? (
                <div className={styles.formError} role="alert">
                  Couldn&apos;t load previous measurements.{" "}
                  {vitalsPatientId && vitalsEntryId && <button type="button" className={styles.smallBtn} onClick={() => void prevQ.refetch()}>Retry</button>}
                </div>
              ) : prevVitals === null ? (
                <Skeleton rows={3} label="Loading previous measurements" />
              ) : prevVitals.length === 0 ? (
                <p className={styles.muted}>No earlier measurements for this patient.</p>
              ) : (
                <div className={styles.prevScroll}>
                  <table className={styles.prevTable}>
                    <thead><tr><th scope="col">Recorded</th><th scope="col">BP</th><th scope="col">Pulse</th><th scope="col">Temp</th><th scope="col">SpO2</th><th scope="col">Weight</th><th scope="col">Height</th><th scope="col">Sugar ({BLOOD_SUGAR_UNIT})</th><th scope="col">Pain</th></tr></thead>
                    <tbody>
                      {prevVitals.map((v) => (
                        <tr key={v.queueEntryId}>
                          <td>{v.recordedAt ? new Date(v.recordedAt).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }) : "—"}</td>
                          <td>{formatBp(v)}</td><td>{v.pulseBpm ?? "—"}</td><td>{v.tempCelsius ?? "—"}</td><td>{v.spo2Percent ?? "—"}</td>
                          <td>{v.weightKg ?? "—"}</td><td>{v.heightCm ?? "—"}</td><td>{v.bloodSugar ?? "—"}</td><td>{v.painScore ?? "—"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </aside>
          )}
          </div>
        </Dialog>
      )}

      {viewRow && (
        <Dialog title={`Vitals — ${viewRow.patientName}`} onClose={() => setViewRow(null)}>
          <div className={styles.vitalsPatient}>
            <span className={styles.muted}>T-{viewRow.tokenNumber}{ageSex(viewRow.dob, viewRow.gender, viewRow.ageYears) ? ` · ${ageSex(viewRow.dob, viewRow.gender, viewRow.ageYears)}` : ""}{viewRow.patientMrn ? ` · ${viewRow.patientMrn}` : ""}</span>
          </div>
          {viewError ? <div className={styles.formError} role="alert">Couldn&apos;t load vitals. <button type="button" className={styles.smallBtn} onClick={() => void viewQ.refetch()}>Retry</button></div>
            : !viewVitals ? <Skeleton rows={3} label="Loading vitals" /> : (
              <>
                <p className={styles.muted}>Recorded {viewVitals.recordedAt ? `${new Date(viewVitals.recordedAt).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" })} ${hhmm(viewVitals.recordedAt)}` : "—"}</p>
                <table className={styles.prevTable}>
                  <tbody>
                    {([["Blood pressure (mmHg)", formatBp(viewVitals)], ["Pulse (bpm)", viewVitals.pulseBpm], ["Temp (°C)", viewVitals.tempCelsius], ["SpO2 (%)", viewVitals.spo2Percent],
                      ["Weight (kg)", viewVitals.weightKg], ["Height (cm)", viewVitals.heightCm], [`Blood sugar (${BLOOD_SUGAR_UNIT})`, viewVitals.bloodSugar], [`Pain (/${PAIN_SCORE_MAX})`, viewVitals.painScore]] as [string, string | number | null | undefined][])
                      .map(([k, v]) => <tr key={k}><th scope="row">{k}</th><td>{v ?? "—"}</td></tr>)}
                  </tbody>
                </table>
                {(viewVitals.abnormalFlags?.length ?? 0) > 0 && <div className={styles.formError} role="alert">{viewVitals.abnormalFlags!.map((f) => <div key={f} className={styles.flagLine}><Icon name="alert" size={14} />{f}</div>)}</div>}
              </>
            )}
          <div className={styles.modalActions}><button type="button" className={styles.cancelBtn} onClick={() => setViewRow(null)}>Close</button></div>
        </Dialog>
      )}

      {showCapturePicker && canCaptureVitals && !queueForbidden && (
        <div className={styles.overlay} onClick={() => setShowCapturePicker(false)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Capture vitals — pick a patient</h2>
            {rows.length === 0 ? (
              <EmptyState icon="pulse" title="No one is waiting for vitals" hint="New arrivals appear here as soon as reception checks them in." />
            ) : (
              rows.map((r) => (
                <button type="button" key={r.id} className={styles.pickerRow} onClick={() => { setShowCapturePicker(false); openVitalsModal(r.id); }}>
                  <span className={styles.patientName}>{r.patientName}</span>
                  <span className={styles.muted}>{waitMinutes(r.createdAt)}m waiting</span>
                </button>
              ))
            )}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setShowCapturePicker(false)}>Cancel</button>
            </div>
          </div>
        </div>
      )}

      {showVaccineDialog && canCreateOrders && (
        <RecordVaccineDialog authedFetch={authedFetch} visits={vaccineVisits}
          onClose={() => setShowVaccineDialog(false)}
          onRecorded={() => { setShowVaccineDialog(false); setVaccineReload((n) => n + 1); if (!isVaccinesView) router.replace("/nursing?tab=vaccines"); }} />
      )}

      {showUrgentPicker && canFlagPriority && (
        <Dialog title="Mark urgent" onClose={() => { if (!urgentSubmitting) setShowUrgentPicker(false); }}>
          <form onSubmit={submitMarkUrgent}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="urgentPatient">Patient</label>
              <select id="urgentPatient" className={styles.select} value={urgentEntryId} onChange={(e) => setUrgentEntryId(e.target.value)}>
                <option value="">Select a patient…</option>
                {urgentCandidates.map((r) => <option key={r.id} value={r.id}>{r.patientName}</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="urgentReason">Reason</label>
              <select id="urgentReason" className={styles.select} value={urgentReasonCode} onChange={(e) => setUrgentReasonCode(e.target.value)}>
                {URGENT_REASON_CODES.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            {urgentReasonCode === "Other" && (
              <div className={styles.field}>
                <label className={styles.label} htmlFor="urgentOther">Specify</label>
                <input id="urgentOther" className={styles.input} value={urgentOtherReason} onChange={(e) => setUrgentOtherReason(e.target.value)} />
              </div>
            )}
            {urgentError && <div className={styles.formError} role="alert">{urgentError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setShowUrgentPicker(false)}>Cancel</button>
              <button type="submit" className={styles.submitBtn}
                disabled={urgentSubmitting || !urgentEntryId || (urgentReasonCode === "Other" && !urgentOtherReason.trim())}>
                {urgentSubmitting ? "Flagging…" : "Mark urgent"}
              </button>
            </div>
          </form>
        </Dialog>
      )}

      {showAdminForm && canCreateOrders && (
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

      {witnessModal && canEditOrders && (
        <div className={styles.overlay} onClick={() => setWitnessModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Confirm the five rights, then witness</h2>
            <p className={`${styles.muted} ${styles.modalNote}`}>Right patient, right drug, right dose, right route, right time — confirmed before administering.</p>
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

      {consentModal && canEditOrders && (
        <div className={styles.overlay} onClick={() => setConsentModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Record consent</h2>
            <div className={styles.field}>
              <label className={styles.label}>Patient&apos;s typed name (stands in for a signature)</label>
              <input className={styles.input} value={consentModal.signedName}
                onChange={(e) => setConsentModal({ ...consentModal, signedName: e.target.value })} />
            </div>
            {consentError && <div className={styles.formError} role="alert">{consentError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setConsentModal(null)}>Cancel</button>
              <button type="button" className={styles.submitBtn} disabled={!consentModal.signedName.trim()} onClick={submitConsent}>Record consent</button>
            </div>
          </div>
        </div>
      )}

      {refuseModal && canEditOrders && (
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

      {showProcForm && canCreateOrders && (
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
              <input className={styles.input} value={procForm.consentNote} onChange={(e) => setProcForm((p) => ({ ...p, consentNote: e.target.value }))} placeholder="Consent form name and version (optional)" />
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
