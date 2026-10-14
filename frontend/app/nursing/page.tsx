"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import styles from "./nursing.module.css";
import Icon from "../components/ui/Icon";
import Dialog from "../components/ui/Dialog";
import VaccinePanel, { RecordVaccineDialog, type VaccineVisit } from "../components/nursing/VaccinePanel";
import AccessGate from "../components/ui/AccessGate";
import StatusPill from "../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { can } from "../lib/permissions";
import { getIdentity } from "../lib/session";
import { VITAL_FIELDS, EMPTY_VITALS, vitalsErrors, vitalsPayload, VITALS_BLANK_HINT, type VitalKey, type VitalsForm } from "../lib/vitals";

// E13 Nursing, Orders & Triage. NB-142's vitals worklist plus NB-143/145/146/148 as tabs on the
// same page — NB-144/149 (clinical triage inbox, task handoff) aren't built, both need NB-197's
// WhatsApp inbox, which doesn't exist. NB-147 (package sessions) is a link to /packages, which
// already has the redeem action — no new UI needed for it.
type QueueEntry = {
  id: string; patientId: string; doctorId: string; departmentId: string; tokenNumber: number; status: string; createdAt: string;
  priority: boolean; priorityReason: string | null; priorityFlaggedBy: string | null; priorityFlaggedAt: string | null;
  priorityAcknowledgedBy: string | null; priorityAcknowledgedAt: string | null;
  source: string; presentingComplaint: string | null; appointmentId: string | null;
};
type Row = QueueEntry & {
  patientName: string; patientMrn: string; doctorName: string;
  allergies: string[]; chronicConditions: string[];
  lastVitalsAt: string | null; vitalsOutOfRange: boolean;
  dob: string | null; gender: string | null; hasAppointment: boolean;
  // The patient record could not be read (no patients:view, 404, 5xx): allergies are UNKNOWN, not absent.
  flagsUnknown: boolean;
};
// A patient's clinical-flags chips: allergy (danger), chronic condition (warn), presenting
// complaint (neutral info). "Urgent" is reserved for the queue's priority flag (rendered
// separately) — a routine complaint such as "cough" must never look urgent.
type Flag = { kind: "danger" | "warn" | "info"; text: string };
function flagsFor(r: { allergies: string[]; chronicConditions: string[]; presentingComplaint: string | null; priorityReason: string | null }): Flag[] {
  return [
    ...r.allergies.map((a): Flag => ({ kind: "danger", text: `Allergy: ${a}` })),
    ...r.chronicConditions.map((c): Flag => ({ kind: "warn", text: c })),
    ...(r.presentingComplaint && r.presentingComplaint.trim().toLowerCase() !== (r.priorityReason ?? "").trim().toLowerCase()
      ? [{ kind: "info", text: r.presentingComplaint } as Flag] : []),
  ];
}

// "58 M" from the patient's real dob/gender; either part is simply left out when unknown.
function ageSex(dob: string | null, gender: string | null): string {
  const parts: string[] = [];
  if (dob) {
    const d = new Date(dob);
    if (!Number.isNaN(d.getTime())) {
      const now = new Date();
      let age = now.getFullYear() - d.getFullYear();
      if (now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())) age -= 1;
      parts.push(age >= 0 ? String(age) : "");
    }
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
  if (!dob) return false;
  const born = new Date(dob).getTime();
  return Number.isFinite(born) && Date.now() - born >= 18 * 365.25 * 86_400_000;
}

function softWarning(key: VitalKey, raw: string, adult: boolean): string | null {
  const d = VITAL_FIELDS.find((x) => x.key === key);
  if (!adult || !d?.soft || raw.trim() === "") return null;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < d.min || n > d.max) return null;
  return n < d.soft[0] || n > d.soft[1] ? `Outside usual range (${d.soft[0]}–${d.soft[1]} ${d.unit}) — recheck` : null;
}
const VISIT_LABEL: Record<string, string> = {
  walk_in: "Walk-in", referral: "Referral", online: "Online", social_media: "Social media",
  returning: "Returning", other: "Other", internal_transfer: "Internal transfer",
};
// Same catalogue as arrivals' Mark priority — nursing can only acknowledge an existing flag
// today, never raise one; this is the missing "Mark urgent" quick action from DESIGN.md's
// Vitals Worklist toolbar, using the same POST /queue/{id}/reorder reception already drives.
const URGENT_REASON_CODES = [
  "Chest pain / cardiac symptoms", "Breathing difficulty", "Severe bleeding",
  "High fever in infant", "Post-operative complication", "Severe pain", "Other",
] as const;
type VitalsFilter = "due" | "recorded" | "all";
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
const PERM = { vitalsWrite: "clinical:edit", vitalsRead: "clinical:view", create: "nursing:create", edit: "nursing:edit", packages: "packages:view", queueEdit: "queue:edit" } as const;
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
  const [tab, setTab] = useState<Tab>(() => validTab(searchParams.get("tab")));
  const [rows, setRows] = useState<Row[]>([]);
  const [recordedRows, setRecordedRows] = useState<Row[]>([]);
  const [vitalsFilter, setVitalsFilter] = useState<VitalsFilter>(() => validFilter(searchParams.get("filter")));

  function changeFilter(f: VitalsFilter) {
    router.replace(f === "due" ? "/nursing" : `/nursing?filter=${f}`);
  }
  const [proceduresPendingRows, setProceduresPendingRows] = useState<Row[]>([]);
  const [priorityRows, setPriorityRows] = useState<Row[]>([]);
  const [administrationOrders, setAdministrationOrders] = useState<AdministrationOrder[]>([]);
  const [procedureOrders, setProcedureOrders] = useState<ProcedureOrder[]>([]);
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [staff, setStaff] = useState<StaffOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sectionFailed, setSectionFailed] = useState(false);
  const [dateLine, setDateLine] = useState("");
  const [forbidden, setForbidden] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [, setNow] = useState(() => Date.now());

  const [vitalsEntryId, setVitalsEntryId] = useState<string | null>(null);
  const [vitalsForm, setVitalsForm] = useState<VitalsForm>(EMPTY_VITALS);
  const [vitalsTouched, setVitalsTouched] = useState<Partial<Record<VitalKey, boolean>>>({});
  const [vitalsAttempted, setVitalsAttempted] = useState(false);
  const [vitalsError, setVitalsError] = useState<string | null>(null);
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

  // ClinicNav's nurse sub-items (Today, Waiting Patients, Vitals, ...) are all deep links into
  // this one page via ?tab=/?filter= — a click while already here changes the URL but doesn't
  // remount the page, so this re-syncs local state whenever the query string changes.
  useEffect(() => {
    // Deferred to a microtask so the effect body itself never calls setState synchronously.
    void Promise.resolve().then(() => {
      setTab(validTab(searchParams.get("tab")));
      setVitalsFilter(validFilter(searchParams.get("filter")));
      // TopBar's "Capture vitals" button (DESIGN.md's top-bar primary action) signals through
      // ?action=capture rather than a cross-component callback, since TopBar is mounted above
      // and outside this page. Strip it once consumed so back/refresh doesn't reopen the picker.
      if (searchParams.get("action") === "capture") {
        setShowCapturePicker(true);
        router.replace("/nursing");
      }
    });
  }, [searchParams, router]);

  // Day label (client-only: depends on the clock and the token's region). Saudi tenants show Hijri
  // primary + Gregorian secondary (DESIGN.md); everyone else Gregorian.
  useEffect(() => {
    void Promise.resolve().then(() => {
      const now = new Date();
      const greg = now.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" });
      if (getIdentity()?.tenantRegion === "KSA") {
        const hijri = new Intl.DateTimeFormat("en-SA-u-ca-islamic-umalqura", { day: "numeric", month: "long", year: "numeric" }).format(now);
        setDateLine(`${hijri} · ${greg}`);
      } else {
        setDateLine(greg);
      }
    });
  }, []);

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
    try {
      const [queueRes, priorityRes, staffRes, adminRes, procRes, activityRes] = await Promise.all([
        authedFetch("/queue"), authedFetch("/queue?priority=true"), authedFetch("/staff/roster"),
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
      setForbidden(false);
      const entries: QueueEntry[] = await queueRes.json();
      const staffMap = new Map<string, string>();
      if (staffRes?.ok) {
        const options: StaffOption[] = await staffRes.json();
        setStaff(options);
        options.forEach((s) => staffMap.set(s.id, s.name));
      }
      const withNames = async (list: QueueEntry[]) => Promise.all(list.map(async (e) => {
        const pRes = await authedFetch(`/patients/${e.patientId}`);
        const p: { name?: string; mrn?: string; dob?: string; gender?: string; allergies?: string[]; chronicConditions?: string[] } | null = pRes?.ok ? await pRes.json() : null;
        return {
          ...e, patientName: p?.name ?? "Unknown patient", patientMrn: p?.mrn ?? "", doctorName: staffMap.get(e.doctorId) ?? "—",
          allergies: p?.allergies ?? [], chronicConditions: p?.chronicConditions ?? [],
          lastVitalsAt: null, vitalsOutOfRange: false,
          dob: p?.dob ?? null, gender: p?.gender ?? null, hasAppointment: !!e.appointmentId, flagsUnknown: !p,
        };
      }));
      // "Recorded" (Vitals Worklist filter pill): anyone who has moved past vitals_pending today —
      // fetched vitals confirm it actually happened (a tenant's flow can skip the vitals step type
      // entirely) rather than assuming from status alone.
      const RECORDED_STATUSES = ["vitals_done", "in_consult", "procedures_pending", "checkout_pending", "completed"];
      const withVitals = async (list: Row[]) => (await Promise.all(list.map(async (r): Promise<Row | null> => {
        // Reading recorded vitals needs clinical:view; without it the Recorded view simply stays empty.
        if (!can(PERM.vitalsRead)) return null;
        const vRes = await authedFetch(`/clinical/vitals/${r.id}`);
        if (!vRes?.ok) return null;
        const v: { recordedAt: string; abnormalFlags: string[] } = await vRes.json();
        return { ...r, lastVitalsAt: v.recordedAt, vitalsOutOfRange: v.abnormalFlags.length > 0 };
      }))).filter((r): r is Row => r !== null);

      setRows(await withNames(entries.filter((e) => e.status === "vitals_pending")));
      setRecordedRows(await withVitals(await withNames(entries.filter((e) => RECORDED_STATUSES.includes(e.status)))));
      setProceduresPendingRows(await withNames(entries.filter((e) => e.status === "procedures_pending")));
      if (priorityRes?.ok) setPriorityRows(await withNames(await priorityRes.json()));
      if (adminRes?.ok) setAdministrationOrders(await adminRes.json());
      if (procRes?.ok) setProcedureOrders(await procRes.json());
      if (activityRes?.ok) setActivity(await activityRes.json());
      // A 403 on a secondary list just means that section isn't theirs; anything else is a failure
      // the nurse should hear about rather than see as a silently empty tab.
      setSectionFailed([priorityRes, adminRes, procRes, activityRes, staffRes].some((r) => r && !r.ok && r.status !== 403));
      setError(null);
      setLoaded(true);
    } catch {
      // Keep whatever was last loaded on screen (and any open dialog mounted); the page shows an
      // inline banner with Retry instead of replacing the worklist.
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

  function retryLoad() {
    setLoading(true);
    void load();
  }

  function openVitalsModal(entryId: string) {
    setVitalsEntryId(entryId);
    setVitalsForm(EMPTY_VITALS);
    setVitalsTouched({});
    setVitalsAttempted(false);
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
    // An empty or implausible save must never move the visit on to the doctor with no vitals.
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
      const res = await authedFetch(`/queue/${urgentEntryId}/reorder`, { method: "POST", body: JSON.stringify({ priority: true, reason }) });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't flag this patient." }));
        setUrgentError(p.detail || "Couldn't flag this patient.");
        return;
      }
      setShowUrgentPicker(false);
      load();
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

  // ── NB-355: the procedures_pending queue checkpoint — a coordination stop, not itself a
  // procedure_orders row; advancing it needs no special backend action beyond the generic
  // status PATCH, same as vitals_done -> in_consult on the consult page. ────────────────────
  async function continueAfterProcedures(entry: Row) {
    setActionError(null);
    const flowRes = await authedFetch(`/departments/${entry.departmentId}/flow`);
    const steps: { stepType: string }[] = flowRes?.ok ? await flowRes.json() : [];
    const sequence = resolveSequence(steps);
    const next = sequence[sequence.indexOf(entry.status) + 1];
    if (!next) {
      setActionError("Couldn't determine the next stage.");
      return;
    }
    const res = await authedFetch(`/queue/${entry.id}/status`, { method: "PATCH", body: JSON.stringify({ status: next }) });
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't continue the visit." }));
      setActionError(p.detail || "Couldn't continue the visit.");
    }
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

  const [procRowError, setProcRowError] = useState<Record<string, string>>({});

  async function setProcedureStatus(id: string, status: string) {
    setProcRowError((prev) => { const n = { ...prev }; delete n[id]; return n; });
    const res = await authedFetch(`/nursing/procedure-orders/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) });
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't update the procedure." }));
      setProcRowError((prev) => ({ ...prev, [id]: p.detail || "Couldn't update the procedure." }));
    }
    load();
  }

  // ── NB-119: signed consent gates starting the procedure ────────────────────
  const [consentModal, setConsentModal] = useState<{ orderId: string; signedName: string } | null>(null);
  const [consentError, setConsentError] = useState<string | null>(null);

  async function submitConsent() {
    if (!consentModal || !consentModal.signedName.trim()) return;
    setConsentError(null);
    const res = await authedFetch(`/nursing/procedure-orders/${consentModal.orderId}/consent`, {
      method: "POST", body: JSON.stringify({ signedName: consentModal.signedName }),
    });
    if (res && !res.ok) {
      // Stay open: consent that failed to record must not look recorded.
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't record consent." }));
      setConsentError(p.detail || "Couldn't record consent.");
      return;
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
    a.download = `shift-handover-${new Date().toLocaleDateString("en-CA")}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  const allow = (permission: string) => !loading && can(permission);
  if (forbidden) return <main className={styles.page}><EmptyState icon="shield" title="No access to the nursing worklist" hint="Your role doesn't include the nursing worklist." /></main>;

  const visibleVitalsRows = vitalsFilter === "due" ? rows : vitalsFilter === "recorded" ? recordedRows : [...rows, ...recordedRows];
  const urgentCandidates = [...rows, ...recordedRows].filter((r) => !r.priority);
  const outOfRangeCount = recordedRows.filter((r) => r.vitalsOutOfRange).length;
  const staffName = (id: string | null) => (id ? staff.find((s) => s.id === id)?.name ?? null : null);
  const canCaptureVitals = allow(PERM.vitalsWrite);
  const canCreateOrders = allow(PERM.create);
  const canEditOrders = allow(PERM.edit);
  // Flagging and acknowledging priority call POST /queue/{id}/reorder and /priority/acknowledge (queue:edit).
  const canFlagPriority = allow(PERM.queueEdit);
  const showSkeleton = !loaded && !error;
  const showLoadError = !loaded && !!error;
  const vitalsEmpty = {
    due: { title: "No one is waiting for vitals", hint: "New arrivals appear here as soon as reception checks them in." },
    recorded: { title: "No vitals recorded yet today", hint: "Patients whose vitals you capture will show up here." },
    all: { title: "No patients on the worklist", hint: "Nobody has been checked in for vitals today." },
  }[vitalsFilter];
  const vErrors = vitalsErrors(vitalsForm);
  const vitalsRow = vitalsEntryId ? [...rows, ...recordedRows].find((r) => r.id === vitalsEntryId) : undefined;
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
            {dateLine}{loaded && !isVaccinesView && <>{dateLine ? " · " : ""}{rows.length} awaiting vitals</>}
          </p>
        </div>
        <div className={styles.headerRight}>
          {loaded && tab === "vitals" && (
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
            {canCaptureVitals && <button className={styles.actionBtn} onClick={() => setShowCapturePicker(true)}><Icon name="pulse" size={14} />Capture vitals</button>}
            {canCreateOrders && <button className={styles.secondaryBtn} onClick={() => setShowVaccineDialog(true)}><Icon name="syringe" size={14} />Record vaccine</button>}
            {canFlagPriority && <button className={styles.secondaryBtn} onClick={() => openUrgentPicker()}><Icon name="bolt" size={14} />Mark urgent</button>}
          </div>

          <div className={styles.layout}>
            <div className={styles.card}>
              {visibleVitalsRows.length === 0 ? (
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
                          <tr key={r.id} className={rowClass}>
                            <td data-label="Patient" className={styles.patientCell} title={r.patientMrn ? `MRN ${r.patientMrn}` : undefined}>
                              <div><span className={styles.patientName}>{r.patientName}</span>
                              <span className={styles.mrn} title={r.doctorName !== "—" ? r.doctorName : undefined}>{[ageSex(r.dob, r.gender), `T-${r.tokenNumber}`, r.doctorName !== "—" ? r.doctorName : ""].filter(Boolean).join(" · ")}</span></div>
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
                            <td data-label="Last vitals" className={styles.muted}>{r.lastVitalsAt ? hhmm(r.lastVitalsAt) : "—"}</td>
                            <td data-label="Status">
                              {r.status === "vitals_pending"
                                ? <StatusPill tone="warn" label="Waiting" icon="clock" />
                                : <StatusPill tone={r.vitalsOutOfRange ? "danger" : "ok"} label={r.vitalsOutOfRange ? `${statusLabel(r.status)} · out of range` : statusLabel(r.status)} icon={r.vitalsOutOfRange ? "alert" : "check"} />}
                            </td>
                            <td data-label="" className={styles.actionCell}>{r.status === "vitals_pending" && canCaptureVitals && <button className={styles.actionBtn} onClick={() => openVitalsModal(r.id)}><Icon name="pulse" size={14} />Capture vitals</button>}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className={styles.sidebarStack}>
              <div className={styles.card}>
                <div className={styles.sideTitle}><Icon name="pulse" size={14} />Vitals summary</div>
                <div className={styles.summaryRow}><span>Recorded today</span><span className={styles.summaryValue}>{recordedRows.length}</span></div>
                <div className={styles.summaryRow}>
                  <span>Out of range</span>
                  <span className={outOfRangeCount > 0 ? styles.summaryDanger : styles.summaryValue}>
                    {outOfRangeCount > 0 && <Icon name="alert" size={14} />} {outOfRangeCount}
                  </span>
                </div>
                {/* No real source for capture duration yet — an honest dash, never an invented figure. */}
                <div className={styles.summaryRow}><span>Avg capture time</span><span className={styles.summaryValue} title="Not measured yet">—</span></div>
              </div>

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
          {priorityRows.length === 0 ? (
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
                        <td data-label=""><button className={styles.actionBtn} onClick={() => continueAfterProcedures(r)}>Procedures done, continue</button></td>
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
                      return (
                        <tr key={p.id}>
                          <td data-label="Patient">{p.patientName}</td>
                          <td data-label="Procedure">{p.chargeName}</td>
                          <td data-label="Consent">
                            {consented
                              ? <StatusPill tone="ok" label={`Signed · ${p.consentSignedName}${p.consentSignedAt ? ` · ${hhmm(p.consentSignedAt)}` : ""}`} />
                              : <div className={styles.rowActions}>
                                  <StatusPill tone="danger" label="Consent missing" icon="alert" />
                                  {canEditOrders && <button className={styles.smallBtn} onClick={() => { setConsentError(null); setConsentModal({ orderId: p.id, signedName: "" }); }}>Record consent</button>}
                                </div>}
                          </td>
                          <td data-label="Preparation" className={styles.muted}>{p.prepNotes ?? "—"}</td>
                          <td data-label="State">
                            {p.status === "ordered" && <StatusPill tone="neutral" label="Not started" />}
                            {p.status === "prepped" && <StatusPill tone="info" label="In progress" icon="sync" />}
                            {p.status === "completed" && <StatusPill tone="ok" label={`Completed${p.billed ? " · billed" : " · pending bill"}`} />}
                          </td>
                          <td data-label="">
                            {open && !consented && <StatusPill tone="warn" label="Blocked until consent" icon="clock" />}
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
          onClose={() => { if (!vitalsSubmitting && VITAL_FIELDS.every((d) => vitalsForm[d.key].trim() === "")) closeVitalsModal(); }}>
          <form noValidate onSubmit={submitVitals}>
            <p className={styles.muted}>{VITALS_BLANK_HINT}</p>
            {vitalsAttempted && vErrors.form && <div className={styles.formError} role="alert">{vErrors.form}</div>}
            <div className={styles.fieldGrid}>
              {VITAL_FIELDS.map((d) => {
                const showErr = (vitalsAttempted || vitalsTouched[d.key]) ? vErrors[d.key] : undefined;
                const warn = showErr ? null : softWarning(d.key, vitalsForm[d.key], isAdult(vitalsRow?.dob ?? null));
                return (
                  <div className={styles.field} key={d.key}>
                    <label className={styles.label} htmlFor={d.key}>{d.label} ({d.unit})</label>
                    <div className={styles.unitInput}>
                      <input id={d.key} type="number" inputMode="decimal" step={d.step} min={d.min} max={d.max}
                        className={styles.input} value={vitalsForm[d.key]} aria-invalid={!!showErr}
                        aria-describedby={showErr || warn ? `${d.key}-msg` : undefined}
                        onBlur={() => setVitalsTouched((prev) => ({ ...prev, [d.key]: true }))}
                        onChange={(e) => setVitalsForm((prev) => ({ ...prev, [d.key]: e.target.value }))} />
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
        </Dialog>
      )}

      {showCapturePicker && canCaptureVitals && (
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
