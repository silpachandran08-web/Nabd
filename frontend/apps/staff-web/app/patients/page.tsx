"use client";

import { formatNumber } from "@nabd/i18n";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import styles from "./patients.module.css";
import { RegisterDialog } from "./RegistryDialogs";
import { CheckInDialog } from "../components/arrivals/CheckInDialog";
import Dialog from "../components/ui/Dialog";
import ConfirmDialog from "./ConfirmDialog";
import GuardianReviews from "./GuardianReviews";
import PhoneDataQuality from "./PhoneDataQuality";
import DuplicateSuggestions from "./DuplicateSuggestions";
import StatusPill from "../components/ui/StatusPill";
import BillingStatePill from "../components/ui/BillingStatePill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { can } from "../lib/permissions";
import { decodeAccessToken } from "../lib/session";
import { formatLastVisit } from "../lib/lastVisit";
import VitalsCard from "../components/consult/VitalsCard";
import type { Part } from "../components/consult/clinical";
import type { Vitals } from "../components/consult/shared";
import { age, DEFAULT_START_STATUS, initials, statusBeforeConsult, statusSequence, type PatientSummary } from "@nabd/domain";
import PatientMessages from "./PatientMessages";
import { staleMessage } from "../lib/queueMove";
import { ErrorSummary, FieldError, failText, useFormErrors } from "../lib/formErrors";
import { prefix, queueQuery } from "../lib/query";
import { useQueryClient } from "@tanstack/react-query";

import type {
  AllergyResponse, AllergyStatusResponse, ConditionResponse, EncounterResponse, PatientDetailResponse, PatientRegistryResponse, PatientRegistryRow,
  PrescriptionResponse, QueueEntryResponse, ToothHistoryEntryResponse, ToothResponse,
} from "@nabd/api-client/generated";
import { api, ApiError } from "../lib/api";
import type { Full } from "../lib/gen";
import DeskShortcuts from "../components/arrivals/shortcuts/DeskShortcuts";

type PatientDetail = PatientSummary & Full<Pick<PatientDetailResponse, "isMinor" | "guardianId" | "guardianName" | "guardianConsentGrantedAt" | "lastVisit">,
  "guardianId" | "guardianName" | "guardianConsentGrantedAt" | "lastVisit">;
// GET /clinical/patients/{id}/allergies/status. "not_assessed" (nobody asked) is NOT "none_documented".
type AllergyStatus = Full<AllergyStatusResponse, "documentedByName" | "documentedAt"> & { state: "recorded" | "none_documented" | "not_assessed" };
type Allergy = Full<Pick<AllergyResponse, "id" | "substance" | "severity" | "active">> & Pick<AllergyResponse, "category" | "recordedByName" | "recordedAt" | "deactivatedReason" | "deactivatedAt" | "deactivatedByName"> & { reaction: string | null };
const ALLERGY_CATEGORIES = [
  { id: "drug", label: "Drug / medicine" }, { id: "food", label: "Food" },
  { id: "environmental", label: "Environmental" }, { id: "other", label: "Other" },
];
const categoryLabel = (id?: string | null) => ALLERGY_CATEGORIES.find((c) => c.id === id)?.label;
type Condition = Full<Pick<ConditionResponse, "id" | "condition" | "status" | "reviewDueDate">, "reviewDueDate">;
// GET /v1/patients/registry (PatientRegistryRow in the generated client).
type RegistryRow = Full<PatientRegistryRow, "lastVisit" | "nextAppointment" | "nextDoctorName" | "nextDoctorId" | "nextAppointmentId" | "packageSessionsUsed" | "packageSessionsTotal" | "condition" | "allergy" | "queueToken" | "queueStatus">;
// OPS-008: keyset paging; page.nextCursor fetches the next page of the same filter + q.
type Registry = Omit<Full<PatientRegistryResponse>, "rows" | "page" | "matchedTotal" | "hasMore"> & { rows: RegistryRow[]; matchedTotal?: number; page?: { nextCursor?: string | null } };
// Today's queue entry for the drawer patient (GET /v1/queue, QueueEntryResponse subset).
type TodayVisit = Full<Pick<QueueEntryResponse, "id" | "patientId" | "doctorId" | "departmentId" | "status" | "tokenNumber">>;
const VISIT_DONE = ["completed", "cancelled", "no_show", "transferred_out"];
// Drawer wording for today's visit (shorter than the queue board labels), keyed by queue status.
const TODAY_STATUS_LABEL: Record<string, string> = {
  checked_in: "Waiting", waiting: "Waiting", billing_pending: "Billing", vitals_pending: "Waiting for vitals",
  vitals_done: "Ready for doctor", in_consult: "In consultation", procedures_pending: "Procedures", checkout_pending: "At checkout",
};
const FILTERS: [string, string][] = [["all", "All"], ["recent", "Recently visited"], ["followup", "Follow-up due"],
  ["package", "Active package"], ["balance", "Outstanding balance"], ["duplicate", "Duplicate review"], ["archived", "Archived"]];
// Financial and duplicate-review filters are absent (not disabled) for roles that don't hold the grant.
type Perms = { clinicalView: boolean; clinicalEdit: boolean; dentalView: boolean; dentalEdit: boolean; billingView: boolean; checkIn: boolean; register: boolean; patientsEdit: boolean; merge: boolean };
const NO_PERMS: Perms = { clinicalView: false, clinicalEdit: false, dentalView: false, dentalEdit: false, billingView: false, checkIn: false, register: false, patientsEdit: false, merge: false };

/** "+91 ••••• 4412" — only the last four digits; used in the list and, until revealed, in the drawer. */
function maskPhone(phone: string): string {
  const p = phone.replace(/\s/g, "");
  const digits = p.replace(/\D/g, "");
  if (digits.length < 8) return phone;
  const cc = p.startsWith("+966") ? "+966 " : p.startsWith("+91") ? "+91 " : "";
  return `${cc}••••• ${digits.slice(-4)}`;
}


function nextLabel(r: RegistryRow): string {
  if (!r.nextAppointment) return "—";
  const at = new Date(r.nextAppointment);
  const when = r.nextAppointmentToday
    ? `Today ${at.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}`
    : at.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
  return r.nextDoctorName ? `${when} · ${r.nextDoctorName}` : when;
}
type Prescription = Full<Pick<PrescriptionResponse, "id" | "status" | "signedAt" | "items">, "signedAt" | "items"> & { items: Full<NonNullable<PrescriptionResponse["items"]>[number], "dosage" | "frequency" | "duration">[] };
type Encounter = Full<EncounterResponse, "diagnosis" | "assessment" | "medications">;
type Tooth = Full<ToothResponse, "note">;
type ToothHistoryEntry = Full<ToothHistoryEntryResponse, "before" | "after">;

const FDI_TEETH = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28,
  48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38];
const TOOTH_STATUSES = ["healthy", "decayed", "filled", "missing", "crown", "root_canal"] as const;
// NB-122: the standard Universal Numbering System mapping for permanent teeth — a fixed notation
// conversion (not clinical judgment), so it's safe to hardcode. Storage always stays keyed by FDI;
// this only changes what's displayed.
const FDI_TO_UNIVERSAL: Record<number, number> = {
  18: 1, 17: 2, 16: 3, 15: 4, 14: 5, 13: 6, 12: 7, 11: 8,
  21: 9, 22: 10, 23: 11, 24: 12, 25: 13, 26: 14, 27: 15, 28: 16,
  38: 17, 37: 18, 36: 19, 35: 20, 34: 21, 33: 22, 32: 23, 31: 24,
  41: 25, 42: 26, 43: 27, 44: 28, 45: 29, 46: 30, 47: 31, 48: 32,
};


export default function PatientsPage() {
  return (
    <Suspense fallback={null}>
      <PatientsList />
    </Suspense>
  );
}

// useSearchParams (the global TopBar's search deep-links here as ?q=) needs a Suspense boundary
// above it — the outer component here is that boundary.
function PatientsList() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [registry, setRegistry] = useState<Registry | null>(null);
  const [filter, setFilter] = useState("all");
  const [checkInFor, setCheckInFor] = useState<RegistryRow | null>(null);
  const [registering, setRegistering] = useState(false);
  const [fixingPhones, setFixingPhones] = useState(false); // ACC-028 data-quality list
  const [reviewingDuplicates, setReviewingDuplicates] = useState(false); // ACC-030
  const [flash, setFlash] = useState<string | null>(null);
  const [perms, setPerms] = useState<Perms>(NO_PERMS);
  const { checkIn: canCheckIn, register: canRegister, clinicalView, clinicalEdit, dentalView: canDental, dentalEdit, billingView, patientsEdit, merge: canMerge } = perms;
  const loadSeq = useRef(0);
  const [moreState, setMoreState] = useState<"idle" | "loading" | "error">("idle");
  const moreRef = useRef<HTMLDivElement | null>(null);
  const [phoneShown, setPhoneShown] = useState(false);
  const [q, setQ] = useState(() => searchParams.get("q") ?? "");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [drawerPatient, setDrawerPatient] = useState<PatientDetail | null>(null);
  const [drawerError, setDrawerError] = useState<string | null>(null);
  const [drawerId, setDrawerId] = useState<string | null>(null); // set while the drawer is open (or loading)
  const [drawerTab, setDrawerTab] = useState<"overview" | "dental" | "messages">("overview");

  const [allergies, setAllergies] = useState<Allergy[]>([]);
  // Severity starts unset: an unknown severity must never be guessed, so Add stays unavailable until a clinician picks one.
  const [newAllergy, setNewAllergy] = useState({ substance: "", category: "", severity: "", reaction: "" });
  const [allergyStatus, setAllergyStatus] = useState<AllergyStatus | null>(null);
  const [showRemoved, setShowRemoved] = useState(false);
  const [removedAllergies, setRemovedAllergies] = useState<Allergy[] | null>(null);
  const [removedError, setRemovedError] = useState(false);
  const [removeAllergy, setRemoveAllergy] = useState<Allergy | null>(null);
  const [confirmGuardian, setConfirmGuardian] = useState(false);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const [allergyBusy, setAllergyBusy] = useState(false);
  // Never claim "none recorded" for a register that failed to load.
  const [allergyLoad, setAllergyLoad] = useState<"loading" | "ok" | "error">("loading");
  const [conditionLoad, setConditionLoad] = useState<"loading" | "ok" | "error">("loading");
  const [rxLoad, setRxLoad] = useState<"loading" | "ok" | "error">("loading");
  const [timelineLoad, setTimelineLoad] = useState<"loading" | "ok" | "error">("loading");
  // A write that failed must say so — never leave the clinician thinking an allergy/guardian change landed.
  const fe = useFormErrors("pt", { substance: "Substance", severity: "Severity", reaction: "Reaction", category: "Category", condition: "Condition", reviewDueDate: "Review due date" });
  const qc = useQueryClient();
  const openSeq = useRef(0); // guards against a slow earlier drawer load landing in a later patient's drawer

  const [conditions, setConditions] = useState<Condition[]>([]);
  const [newCondition, setNewCondition] = useState({ condition: "", reviewDueDate: "" });
  const [conditionBusy, setConditionBusy] = useState(false);

  const [prescriptions, setPrescriptions] = useState<Prescription[]>([]);
  const [timeline, setTimeline] = useState<Encounter[]>([]);
  const [timelineCursor, setTimelineCursor] = useState<string | null>(null);
  const [timelineLoadingMore, setTimelineLoadingMore] = useState(false);

  const [chart, setChart] = useState<Tooth[]>([]);
  const [chartBusy, setChartBusy] = useState<number | null>(null);
  const [numbering, setNumbering] = useState<"fdi" | "universal">("fdi");
  const [newSupernumerary, setNewSupernumerary] = useState({ nearToothNumber: 11, status: "healthy", note: "" });
  const [supernumeraryBusy, setSupernumeraryBusy] = useState<string | null>(null);
  const [historyToothId, setHistoryToothId] = useState<string | null>(null);
  const [historyEntries, setHistoryEntries] = useState<ToothHistoryEntry[]>([]);

  // DOC-052: the drawer's visit context (wireframe Patient 360: latest vitals + "Start consultation").
  const [visit, setVisit] = useState<TodayVisit | null>(null);
  const [visitVitals, setVisitVitals] = useState<Part<Vitals>>({ status: "loading", data: null });
  const [starting, setStarting] = useState(false);
  // The status the department's pipeline puts right before in_consult (same rule as /consult, DOC-019).
  const [visitStartStatus, setVisitStartStatus] = useState<string | null>(DEFAULT_START_STATUS);
  const [startError, setStartError] = useState<string | null>(null);
  const selfId = () => decodeAccessToken(localStorage.getItem("nabd_access_token") ?? "")?.sub ?? "";

  function toothLabel(fdiNumber: number): string {
    return numbering === "universal" ? `#${FDI_TO_UNIVERSAL[fdiNumber]}` : String(fdiNumber);
  }

  useEffect(() => {
    void Promise.resolve().then(() => {
      // Actions a role can't take are absent, not disabled (RBAC matrix rule). UI gating only —
      // the server still enforces every grant.
      setPerms({
        clinicalView: can("clinical:view"), clinicalEdit: can("clinical:edit"),
        dentalView: can("specialty_dental:view"), dentalEdit: can("specialty_dental:edit"),
        billingView: can("billing:view"), checkIn: can("queue:create"), register: can("patients:create"), patientsEdit: can("patients:edit"), merge: can("patients:delete"),
      });
    });
  }, []);

  const load = useCallback(
    async (query: string, which: string) => {
      setError(null); // `loading` is only the first load — searching/filtering keeps the table on screen
      const seq = ++loadSeq.current;
      try {
        const params = new URLSearchParams({ filter: which });
        if (query.trim()) params.set("q", query.trim());
        const body = await api.get<Registry>(`/patients/registry?${params}`);
        if (seq === loadSeq.current) { setRegistry(body); setMoreState("idle"); } // else a newer search is in flight
      } catch (e) {
        if (seq !== loadSeq.current) return;
        if (e instanceof ApiError && e.status === 403) setForbidden(true);
        else setError(e instanceof ApiError ? "Couldn't load patients. Try again." : "Couldn't reach the server. Check your connection and try again.");
      } finally {
        if (seq === loadSeq.current) setLoading(false);
      }
    },
    []
  );

  // OPS-008: the next keyset page of the same filter + search, appended (infinite scroll; the button is the keyboard path).
  const nextCursor = registry?.page?.nextCursor ?? null;
  const loadMore = useCallback(async () => {
    if (!nextCursor || moreState === "loading") return;
    const seq = loadSeq.current;
    setMoreState("loading");
    try {
      const params = new URLSearchParams({ filter, cursor: nextCursor });
      if (q.trim()) params.set("q", q.trim());
      const body = await api.get<Registry>(`/patients/registry?${params}`);
      if (seq !== loadSeq.current) return; // filter or search changed meanwhile
      setRegistry((prev) => prev && { ...body, rows: [...prev.rows, ...body.rows] });
      setMoreState("idle");
    } catch {
      if (seq === loadSeq.current) setMoreState("error");
    }
  }, [filter, q, nextCursor, moreState]);

  useEffect(() => {
    const el = moreRef.current;
    if (!el || !nextCursor || moreState !== "idle" || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) void loadMore(); });
    io.observe(el);
    return () => io.disconnect();
  }, [loadMore, nextCursor, moreState]);

  // Live search: first load is immediate, typing is debounced; filter changes reload straight away.
  const firstLoad = useRef(true);
  useEffect(() => {
    const delay = firstLoad.current ? 0 : 300;
    firstLoad.current = false;
    const t = setTimeout(() => void load(q, filter), delay);
    return () => clearTimeout(t);
  }, [load, q, filter]);

  const closeDrawer = useCallback(() => { openSeq.current++; setDrawerId(null); setDrawerPatient(null); setDrawerError(null); }, []);

  async function openDrawer(id: string) {
    const seq = ++openSeq.current;
    fe.clear();
    setRxLoad("loading");
    setTimelineLoad("loading");
    setDrawerId(id);
    setDrawerError(null);
    setDrawerPatient(null);
    setDrawerTab("overview");
    setAllergies([]);
    setConditions([]);
    setNewCondition({ condition: "", reviewDueDate: "" });
    setPrescriptions([]);
    setTimeline([]);
    setTimelineCursor(null);
    setChart([]);
    setHistoryToothId(null);
    setHistoryEntries([]);
    setNewSupernumerary({ nearToothNumber: 11, status: "healthy", note: "" });
    setNewAllergy({ substance: "", category: "", severity: "", reaction: "" });
    setAllergyStatus(null);
    setShowRemoved(false);
    setRemovedAllergies(null);
    setRemoveAllergy(null);
    setConfirmGuardian(false);
    setConfirmError(null);
    setPhoneShown(false);
    setAllergyLoad("loading");
    setConditionLoad("loading");
    setVisit(null);
    setVisitStartStatus(DEFAULT_START_STATUS);
    setVisitVitals({ status: "loading", data: null });
    setStartError(null);
    // Only patients the registry shows in today's queue have a visit to look up.
    if (registry?.rows.some((r) => r.id === id && r.queueToken != null)) void loadTodayVisit(id, seq);
    let detail: PatientDetail;
    try {
      detail = await api.get<PatientDetail>(`/patients/${id}`);
    } catch (e) {
      if (seq === openSeq.current) setDrawerError(e instanceof ApiError ? "Couldn't load this patient." : "Couldn't reach the server. Check your connection and try again.");
      return;
    }
    if (seq !== openSeq.current) return;
    setDrawerPatient(detail);
    if (clinicalView) {
      void refreshAllergies(id, seq);
      void refreshConditions(id, seq);
      try {
        const rx = await api.get<Prescription[]>(`/clinical/patients/${id}/prescriptions`);
        if (seq !== openSeq.current) return;
        setPrescriptions(rx);
        setRxLoad("ok");
      } catch { if (seq === openSeq.current) setRxLoad("error"); }
      try {
        const page = await api.get<{ data: Encounter[]; page: { nextCursor: string | null } }>(`/clinical/patients/${id}/timeline?limit=10`);
        if (seq !== openSeq.current) return;
        setTimeline(page.data);
        setTimelineCursor(page.page.nextCursor);
        setTimelineLoad("ok");
      } catch { if (seq === openSeq.current) setTimelineLoad("error"); }
    }

    if (canDental) {
      try {
        const teeth = await api.get<Tooth[]>(`/specialty/dental/patients/${id}/chart`);
        if (seq === openSeq.current) setChart(teeth);
      } catch { /* chart stays empty; tooth edits re-fetch nothing */ }
    }
  }

  /** The write landed; re-read the header. A failed re-read keeps the last values (never reports the write as failed). */
  async function refreshPatient(id: string) {
    try {
      const detail = await api.get<PatientDetail>(`/patients/${id}`);
      setDrawerPatient(detail);
      void qc.invalidateQueries({ queryKey: prefix("patient") }); // names on Arrivals follow a correction
    } catch { /* keep the last values */ }
  }
  // FEF-027: the row link's href (/patients?open=<id>) also works when opened in a new tab.
  const deepLinkId = searchParams.get("open");
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (deepLinkId) void openDrawer(deepLinkId);
  }, [deepLinkId]);

  async function loadTodayVisit(patientId: string, seq: number) {
    if (!can("queue:view")) return;
    try {
      const list = (await qc.fetchQuery(queueQuery())) as unknown as TodayVisit[]; // same key as the sidebar badge
      if (seq !== openSeq.current) return;
      const open = list.filter((e) => e.patientId === patientId && !VISIT_DONE.includes(e.status));
      const me = selfId();
      const entry = open.find((e) => e.doctorId === me) ?? open[0] ?? null;
      setVisit(entry);
      if (entry && clinicalView) void loadVisitVitals(entry.id, seq);
      if (entry) {
        // An unreadable flow falls back to the default pipeline (vitals first), as /consult does.
        let start: string | null = DEFAULT_START_STATUS;
        try {
          const steps = await api.get<{ stepType: string }[]>(`/departments/${entry.departmentId}/flow`);
          start = statusBeforeConsult(statusSequence(steps.map((x) => x.stepType)));
        } catch { /* keep the default */ }
        if (seq === openSeq.current) setVisitStartStatus(start);
      }
    } catch { /* no visit context; the drawer still shows the record */ }
  }

  async function loadVisitVitals(entryId: string, seq = openSeq.current) {
    setVisitVitals({ status: "loading", data: null });
    try {
      const data = await api.get<Vitals>(`/clinical/vitals/${entryId}`);
      if (seq === openSeq.current) setVisitVitals({ status: "ok", data });
    } catch (e) {
      if (seq === openSeq.current) setVisitVitals({ status: e instanceof ApiError && e.status === 404 ? "none" : "error", data: null });
    }
  }

  /** Same transition the consult workspace's Start uses (PATCH status in_consult); /consult?entry= then opens the visit. */
  async function startConsultation(entry: TodayVisit) {
    if (starting) return;
    setStarting(true);
    setStartError(null);
    try {
      await api.patch(`/queue/${entry.id}/status`, { status: "in_consult", expectedStatus: entry.status });
      router.push(`/consult?entry=${encodeURIComponent(entry.id)}`);
    } catch (e) {
      setStartError((e instanceof ApiError && staleMessage(e.problem)) || failText(e, "Couldn't start the consultation."));
    } finally {
      setStarting(false);
    }
  }

  async function loadMoreTimeline(patientId: string) {
    if (!timelineCursor) return;
    setTimelineLoadingMore(true);
    try {
      const page = await api.get<{ data: Encounter[]; page: { nextCursor: string | null } }>(`/clinical/patients/${patientId}/timeline?limit=10&cursor=${encodeURIComponent(timelineCursor)}`);
      setTimeline((prev) => [...prev, ...page.data]);
      setTimelineCursor(page.page.nextCursor);
    } catch { /* the button stays; the person can press it again */ } finally {
      setTimelineLoadingMore(false);
    }
  }

  async function refreshAllergies(patientId: string, seq = openSeq.current) {
    // The status is dropped first so a stale "none documented" can never sit next to a changed register.
    setAllergyStatus(null);
    try {
      const [list, status] = await Promise.all([
        api.get<Allergy[]>(`/clinical/patients/${patientId}/allergies`),
        api.get<AllergyStatus>(`/clinical/patients/${patientId}/allergies/status`),
      ]);
      if (seq !== openSeq.current) return;
      setAllergies(list);
      setAllergyStatus(status);
      setAllergyLoad("ok");
    } catch {
      if (seq === openSeq.current) setAllergyLoad("error");
    }
  }

  async function loadRemoved(patientId: string) {
    setRemovedError(false);
    try {
      setRemovedAllergies((await api.get<Allergy[]>(`/clinical/patients/${patientId}/allergies?includeInactive=true`)).filter((x) => !x.active));
    } catch {
      setRemovedError(true);
    }
  }

  async function addAllergy() {
    if (!drawerPatient || !newAllergy.substance.trim() || !newAllergy.severity || allergyBusy) return;
    setAllergyBusy(true);
    fe.clear();
    let saved = false;
    try {
      await api.post(`/clinical/patients/${drawerPatient.id}/allergies`, { ...newAllergy, category: newAllergy.category || undefined });
      saved = true;
    } catch (e) {
      fe.fail(e, e instanceof ApiError ? "Couldn't save the allergy. It has NOT been recorded." : "Couldn't reach the server. The allergy has NOT been recorded.");
    } finally {
      setAllergyBusy(false);
    }
    if (!saved) return;
    setNewAllergy({ substance: "", category: "", severity: "", reaction: "" });
    void refreshAllergies(drawerPatient.id);
    if (showRemoved) void loadRemoved(drawerPatient.id);
    await refreshPatient(drawerPatient.id);
  }

  async function deactivateAllergy(reason: string) {
    if (!drawerPatient || !removeAllergy || confirmBusy) return;
    setConfirmBusy(true);
    setConfirmError(null);
    let removed = false;
    try {
      await api.patch(`/clinical/allergies/${removeAllergy.id}/deactivate`, { reason });
      removed = true;
    } catch (e) {
      setConfirmError(e instanceof ApiError ? "Couldn't remove the allergy. It is still on record." : "Couldn't reach the server. The allergy is still on record.");
    } finally {
      setConfirmBusy(false);
    }
    if (!removed) return;
    // Drop the removed name now; the refresh below is not awaited and the banner must not keep naming it.
    const gone = removeAllergy;
    setAllergies((l) => l.filter((x) => x.id !== gone.id));
    setDrawerPatient((p) => (p ? { ...p, allergies: p.allergies.filter((n) => n !== gone.substance) } : p));
    setRemoveAllergy(null);
    void refreshAllergies(drawerPatient.id);
    if (showRemoved) void loadRemoved(drawerPatient.id);
    await refreshPatient(drawerPatient.id);
  }

  async function refreshConditions(patientId: string, seq = openSeq.current) {
    try {
      const list = await api.get<Condition[]>(`/clinical/patients/${patientId}/conditions`);
      if (seq !== openSeq.current) return;
      setConditions(list);
      setConditionLoad("ok");
    } catch {
      if (seq === openSeq.current) setConditionLoad("error");
    }
  }

  async function addCondition() {
    if (!drawerPatient || !newCondition.condition.trim() || conditionBusy) return;
    setConditionBusy(true);
    fe.clear();
    let saved = false;
    try {
      await api.post(`/clinical/patients/${drawerPatient.id}/conditions`, { condition: newCondition.condition, reviewDueDate: newCondition.reviewDueDate || null });
      saved = true;
    } catch (e) {
      fe.fail(e, e instanceof ApiError ? "Couldn't save the condition. It has NOT been recorded." : "Couldn't reach the server. The condition has NOT been recorded.");
    } finally {
      setConditionBusy(false);
    }
    if (!saved) return;
    setNewCondition({ condition: "", reviewDueDate: "" });
    void refreshConditions(drawerPatient.id);
    void load(q, filter); // Follow-up due flag/count may have changed
    await refreshPatient(drawerPatient.id);
  }

  /** NB-085 / CGV-001: dedicated DELETE /patients/{id}/guardian (204). It touches only the guardian link,
   * so address and national ID can never be wiped; the server audits it and withdraws the
   * guardian_access consent in the same write. */
  async function revokeGuardian() {
    if (!drawerPatient || confirmBusy) return;
    setConfirmBusy(true);
    setConfirmError(null);
    let revoked = false;
    try {
      await api.delete(`/patients/${drawerPatient.id}/guardian`);
      revoked = true;
    } catch (e) {
      setConfirmError(e instanceof ApiError ? "Couldn't revoke guardian access. It is still active." : "Couldn't reach the server. Guardian access is still active.");
    } finally {
      setConfirmBusy(false);
    }
    if (!revoked) return;
    setConfirmGuardian(false);
    void load(q, filter);
    await refreshPatient(drawerPatient.id);
  }

  async function resolveCondition(id: string) {
    if (!drawerPatient) return;
    fe.clear();
    try {
      await api.patch(`/clinical/conditions/${id}/resolve`);
    } catch (e) {
      fe.set(e instanceof ApiError ? "Couldn't resolve the condition. It is still active." : "Couldn't reach the server. The condition is still active.");
      return;
    }
    void refreshConditions(drawerPatient.id);
    void load(q, filter); // Follow-up due flag/count may have changed
    await refreshPatient(drawerPatient.id);
  }

  async function setToothStatus(toothNumber: number, status: string) {
    if (!drawerPatient) return;
    setChartBusy(toothNumber);
    try {
      const updated = await api.patch<Tooth>(`/specialty/dental/patients/${drawerPatient.id}/chart/${toothNumber}`, { status });
      setChart((prev) => [...prev.filter((t) => !(t.toothNumber === toothNumber && !t.isSupernumerary)), updated]);
    } catch (e) {
      fe.set(failText(e, "Couldn't update the tooth. It is unchanged."));
    } finally {
      setChartBusy(null);
    }
  }

  async function addSupernumerary() {
    if (!drawerPatient) return;
    setSupernumeraryBusy("new");
    try {
      const added = await api.post<Tooth>(`/specialty/dental/patients/${drawerPatient.id}/chart/supernumerary`, newSupernumerary);
      setChart((prev) => [...prev, added]);
      setNewSupernumerary({ nearToothNumber: 11, status: "healthy", note: "" });
    } catch (e) {
      fe.set(failText(e, "Couldn't add the tooth. It has NOT been recorded."));
    } finally {
      setSupernumeraryBusy(null);
    }
  }

  async function updateSupernumeraryStatus(id: string, status: string) {
    if (!drawerPatient) return;
    setSupernumeraryBusy(id);
    try {
      const updated = await api.patch<Tooth>(`/specialty/dental/patients/${drawerPatient.id}/chart/supernumerary/${id}`, { status });
      setChart((prev) => prev.map((t) => (t.id === id ? updated : t)));
    } catch (e) {
      fe.set(failText(e, "Couldn't update the tooth. It is unchanged."));
    } finally {
      setSupernumeraryBusy(null);
    }
  }

  async function removeSupernumerary(id: string) {
    if (!drawerPatient) return;
    setSupernumeraryBusy(id);
    try {
      await api.delete(`/specialty/dental/patients/${drawerPatient.id}/chart/supernumerary/${id}`);
      setChart((prev) => prev.filter((t) => t.id !== id));
    } catch (e) {
      fe.set(failText(e, "Couldn't remove the tooth. It is still on the chart."));
    } finally {
      setSupernumeraryBusy(null);
    }
  }

  async function openHistory(id: string) {
    if (!drawerPatient) return;
    setHistoryToothId(id);
    setHistoryEntries([]);
    try {
      setHistoryEntries(await api.get<ToothHistoryEntry[]>(`/specialty/dental/patients/${drawerPatient.id}/chart/entries/${id}/history`));
    } catch { /* the history panel stays empty */ }
  }

  return (
    <main className={styles.page}>
      <DeskShortcuts />
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Patient Registry</h1>
          {registry && (
            <p className={styles.subtitle}>
              {registry.counts[filter] ?? 0} of {(registry.counts.all ?? 0) + (registry.counts.archived ?? 0)} patients
            </p>
          )}
        </div>
        <div style={{ display: "flex", flexWrap: "wrap", gap: "var(--nb-space-8)" }}>
          {patientsEdit && !forbidden && (
            <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => setFixingPhones(true)}>Numbers to fix</button>
          )}
          {canMerge && !forbidden && (
            <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => setReviewingDuplicates(true)}>Possible duplicates</button>
          )}
          {canRegister && !forbidden && (
            <button type="button" className={styles.primaryBtn} onClick={() => setRegistering(true)}>＋ Register patient</button>
          )}
        </div>
      </div>

      {!forbidden && (
        <>
          <form className={styles.searchBar} role="search" onSubmit={(e) => { e.preventDefault(); void load(q, filter); }}>
            <input className={styles.searchInput} aria-label="Search patients" value={q}
              placeholder="Search by name, mobile, patient ID or token number"
              onChange={(e) => setQ(e.target.value)} />
          </form>
          <div className={styles.filterBar} role="tablist" aria-label="Filter patients">
            {FILTERS.filter(([key]) => (key !== "balance" || billingView) && (key !== "duplicate" || canRegister)).map(([key, label]) => (
              <button key={key} type="button" role="tab" aria-selected={filter === key}
                className={filter === key ? styles.filterOn : styles.filter}
                onClick={() => setFilter(key)}>
                {label} <span className={styles.filterCount}>{registry?.counts[key] ?? 0}</span>
              </button>
            ))}
          </div>
        </>
      )}

      {flash && <div className={styles.flash} role="status">{flash}</div>}

      {!forbidden && <GuardianReviews canAct={patientsEdit} onOpen={(id) => void openDrawer(id)} onChanged={() => void load(q, filter)} />}

      <div className={styles.card}>
        {loading ? (
          <Skeleton rows={6} label="Loading patients" />
        ) : forbidden ? (
          <EmptyState icon="shield" title="Your role doesn't have access to patient records." />
        ) : error ? (
          <ErrorState message={error} onRetry={() => void load(q, filter)} />
        ) : !registry || registry.rows.length === 0 ? (
          <EmptyState icon="users" title={q.trim() ? "No patients match that search." : "No patients here."} />
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th scope="col">Patient</th><th scope="col">Mobile</th><th scope="col">Age</th><th scope="col">Last visit</th><th scope="col">Next appointment</th><th scope="col">Flags</th><th scope="col"><span className="srOnly">Actions</span></th></tr>
              </thead>
              <tbody>
                {registry.rows.map((p) => (
                  <tr key={p.id} className={styles.row}>
                    <td>
                      <span className={styles.patientCell}>
                        <span className={p.archived ? styles.avatarMuted : styles.avatar} aria-hidden="true">{initials(p.name)}</span>
                        <span><a className={`${styles.patientName} ${styles.rowLink}`} href={`/patients?open=${encodeURIComponent(p.id)}`}
                          onClick={(e) => { if (e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey) { e.preventDefault(); void openDrawer(p.id); } }}>{p.name}</a><span className={styles.mrn}>{p.mrn}</span></span>
                      </span>
                    </td>
                    <td className={styles.nowrap}>{maskPhone(p.phone)}</td>
                    <td className={styles.nowrap}>{p.age} {p.gender === "female" ? "F" : p.gender === "male" ? "M" : "O"}</td>
                    <td className={styles.nowrap}>{formatLastVisit(p.lastVisit)}</td>
                    <td>{nextLabel(p)}</td>
                    <td>
                      <span className={styles.flags}>
                        {p.archived && <StatusPill tone="neutral" label="Archived" icon="inbox" />}
                        {p.packageSessionsTotal != null && <StatusPill tone="info" icon="package" label={`Package ${p.packageSessionsUsed}/${p.packageSessionsTotal}`} />}
                        {clinicalView && p.condition && <StatusPill tone="warn" icon="pulse" label={p.condition} />}
                        {p.followUpDue && <StatusPill tone="warn" label="Follow-up due" />}
                        {/* The registry row carries no consent state, so it can't say consent exists. */}
                        {p.minor && <StatusPill tone="info" icon="user" label="Minor" />}
                        {billingView && p.balanceDue && <BillingStatePill state="outstanding" />}
                        {p.allergy && <StatusPill tone="danger" label={`Allergy: ${p.allergy}`} />}
                        {canRegister && p.duplicate && <StatusPill tone="sync" icon="users" label="Duplicate review" />}
                      </span>
                    </td>
                    <td className={styles.actionCell}>
                      {p.queueToken != null ? (
                        <span className={styles.inQueue}>In queue · T-{p.queueToken}</span>
                      ) : canCheckIn && !p.archived && (
                        <button type="button" className={styles.checkInBtn}
                          onClick={(e) => { e.stopPropagation(); setFlash(null); setCheckInFor(p); }}>Check in</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {nextCursor && (
              <div ref={moreRef} className={styles.moreRow}>
                <p className={styles.muted} role="status">
                  {moreState === "error" ? "Couldn't load more patients." : `Showing ${registry.rows.length} of ${registry.matchedTotal ?? registry.counts[filter] ?? 0}.`}
                </p>
                <button type="button" className={styles.secondaryBtn} onClick={() => void loadMore()} disabled={moreState === "loading"}>
                  {moreState === "loading" ? "Loading…" : moreState === "error" ? "Try again" : "Load more"}
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {checkInFor && (
        <CheckInDialog patient={checkInFor} onClose={() => setCheckInFor(null)}
          onDone={(token) => { setFlash(`${checkInFor.name} checked in · T-${token}`); setCheckInFor(null); void load(q, filter); }} />
      )}
      {fixingPhones && (
        <PhoneDataQuality onClose={() => setFixingPhones(false)}
          onFixed={() => { setFlash("Mobile number corrected."); void load(q, filter); }} />
      )}
      {reviewingDuplicates && (
        <DuplicateSuggestions onClose={() => setReviewingDuplicates(false)} onChanged={() => void load(q, filter)} />
      )}
      {registering && (
        <RegisterDialog onClose={() => setRegistering(false)}
          onRegistered={(id) => { setRegistering(false); setFlash("Patient registered."); void load(q, filter); void openDrawer(id); }}
          onOpenExisting={(id) => { setRegistering(false); void openDrawer(id); }} />
      )}

      {drawerId && (
        <Dialog title={drawerPatient?.name ?? "Patient"} size="drawer" onClose={closeDrawer}>
          {drawerError ? (
            <ErrorState message={drawerError} onRetry={() => void openDrawer(drawerId)} />
          ) : !drawerPatient ? (
            <Skeleton rows={5} label="Loading patient" />
          ) : (
            <>
              <div className={styles.drawerHeader}>
                <span className={styles.drawerMeta}>{drawerPatient.mrn} · {age(drawerPatient.dob)} · {drawerPatient.gender}</span>
                {(() => {
                  if (!visit) return null;
                  const mine = visit.doctorId === selfId();
                  // Start/Resume only for the doctor whose queue holds the visit; everyone else sees where the visit is.
                  if (mine && clinicalEdit && visit.status === "in_consult") return (
                    <button type="button" className={styles.primaryBtn} onClick={() => router.push(`/consult?entry=${encodeURIComponent(visit.id)}`)}>
                      Resume consultation · T-{visit.tokenNumber}
                    </button>
                  );
                  if (mine && clinicalEdit && visitStartStatus !== null && visit.status === visitStartStatus && can("queue:edit")) return (
                    <button type="button" className={styles.primaryBtn} disabled={starting} onClick={() => void startConsultation(visit)}>
                      {starting ? "Starting…" : `Start consultation · T-${visit.tokenNumber}`}
                    </button>
                  );
                  return <StatusPill tone="info" icon="users" label={`${mine ? "In your queue" : "In today's queue"} · T-${visit.tokenNumber} · ${TODAY_STATUS_LABEL[visit.status] ?? visit.status.replace(/_/g, " ")}`} />;
                })()}
              </div>
              {startError && <div className={styles.errorInline} role="alert">{startError}</div>}
              {visit && clinicalView && (
                <div className={styles.section}>
                  <VitalsCard vitals={visitVitals} onRetry={() => void loadVisitVitals(visit.id)} />
                </div>
              )}

              <div className={styles.drawerTabs}>
                <button className={drawerTab === "overview" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setDrawerTab("overview")}>Overview</button>
                {canDental && <button className={drawerTab === "dental" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setDrawerTab("dental")}>Dental chart</button>}
                <button className={drawerTab === "messages" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setDrawerTab("messages")}>Messages</button>
              </div>

              {drawerTab === "messages" ? (
                <PatientMessages patientId={drawerPatient.id} />
              ) : drawerTab === "dental" && canDental ? (
                <>
                  <div className={styles.numberingToggle}>
                    <button className={numbering === "fdi" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setNumbering("fdi")}>FDI</button>
                    <button className={numbering === "universal" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setNumbering("universal")}>Universal</button>
                  </div>

                  <div className={styles.toothGrid}>
                    {FDI_TEETH.map((n) => {
                      const tooth = chart.find((t) => t.toothNumber === n && !t.isSupernumerary);
                      return (
                        <div key={n} className={styles.toothCell}>
                          <span className={styles.toothNumber}>{toothLabel(n)}</span>
                          {dentalEdit ? (
                            <select
                              className={styles.toothSelect}
                              aria-label={`Tooth ${toothLabel(n)} status`}
                              value={tooth?.status ?? "healthy"}
                              disabled={chartBusy === n}
                              onChange={(e) => setToothStatus(n, e.target.value)}
                            >
                              {TOOTH_STATUSES.map((s) => <option key={s} value={s}>{s.replace("_", " ")}</option>)}
                            </select>
                          ) : (
                            <span className={styles.toothStatus}>{(tooth?.status ?? "healthy").replace("_", " ")}</span>
                          )}
                          {tooth && (
                            <button className={styles.linkBtn} style={{ fontSize: "12px" }} onClick={() => openHistory(tooth.id)}>History</button>
                          )}
                        </div>
                      );
                    })}
                  </div>

                  <div className={styles.section}>
                    <div className={styles.sectionLabel}>Supernumerary teeth</div>
                    {chart.filter((t) => t.isSupernumerary).length === 0 ? (
                      <div className={styles.muted}>None recorded</div>
                    ) : (
                      chart.filter((t) => t.isSupernumerary).map((t) => (
                        <div key={t.id} className={styles.allergyRow}>
                          <span>Near {toothLabel(t.toothNumber)}{t.note ? ` · ${t.note}` : ""}</span>
                          {dentalEdit ? (
                            <select className={styles.smallInput} aria-label="Supernumerary tooth status" value={t.status} disabled={supernumeraryBusy === t.id}
                              onChange={(e) => updateSupernumeraryStatus(t.id, e.target.value)}>
                              {TOOTH_STATUSES.map((s) => <option key={s} value={s}>{s.replace("_", " ")}</option>)}
                            </select>
                          ) : (
                            <span>{t.status.replace("_", " ")}</span>
                          )}
                          <button className={styles.linkBtn} onClick={() => openHistory(t.id)}>History</button>
                          {dentalEdit && (
                            <button className={styles.linkBtn} disabled={supernumeraryBusy === t.id} onClick={() => removeSupernumerary(t.id)}>Remove</button>
                          )}
                        </div>
                      ))
                    )}
                    {dentalEdit && (
                      <div className={styles.allergyForm}>
                        <select className={styles.smallInput} aria-label="Near tooth" value={newSupernumerary.nearToothNumber}
                          onChange={(e) => setNewSupernumerary((p) => ({ ...p, nearToothNumber: Number(e.target.value) }))}>
                          {FDI_TEETH.map((n) => <option key={n} value={n}>Near {toothLabel(n)}</option>)}
                        </select>
                        <select className={styles.smallInput} aria-label="Status" value={newSupernumerary.status}
                          onChange={(e) => setNewSupernumerary((p) => ({ ...p, status: e.target.value }))}>
                          {TOOTH_STATUSES.map((s) => <option key={s} value={s}>{s.replace("_", " ")}</option>)}
                        </select>
                        <input className={styles.smallInput} aria-label="Note" placeholder="Note (optional)" value={newSupernumerary.note}
                          onChange={(e) => setNewSupernumerary((p) => ({ ...p, note: e.target.value }))} />
                        <button className={styles.linkBtn} disabled={supernumeraryBusy === "new"} onClick={addSupernumerary}>Add</button>
                      </div>
                    )}
                  </div>

                  {historyToothId && (
                    <div className={styles.section}>
                      <div className={styles.sectionLabel}>
                        Tooth history <button className={styles.linkBtn} onClick={() => setHistoryToothId(null)}>Close</button>
                      </div>
                      {historyEntries.length === 0 ? (
                        <div className={styles.muted}>No changes recorded yet</div>
                      ) : (
                        historyEntries.map((h, i) => (
                          <div key={i} className={styles.timelineRow}>
                            <span className={styles.muted}>{new Date(h.occurredAt).toLocaleString()} — {h.actorName} ({h.actorRole})</span>
                            <span>{h.action}</span>
                            {h.after && <span className={styles.muted}>{h.after}</span>}
                          </div>
                        ))
                      )}
                    </div>
                  )}
                </>
              ) : (
                <>
                  <ErrorSummary fe={fe} className={styles.errorInline} />
                  {/* Never assert "no known allergies" for a role that can't see the allergy data. */}
                  {(() => {
                    const names = [...new Set([...drawerPatient.allergies, ...allergies.filter((x) => x.active).map((x) => x.substance)])];
                    if (names.length > 0) return (
                      <div className={styles.allergyBannerSome}>
                        <StatusPill tone="danger" label="Allergies" /> {names.join(", ")}
                        {allergyLoad === "error" && <div role="alert">Couldn&apos;t confirm the current allergies. These names may be outdated and the list may be incomplete.</div>}
                      </div>
                    );
                    if (!clinicalView) return <div className={styles.allergyBannerNone}>Allergy information isn&apos;t available to your role.</div>;
                    if (allergyLoad === "error" || (allergyLoad === "ok" && allergyStatus?.state === "recorded")) {
                      return <div className={styles.allergyBannerNone}>Allergy status couldn&apos;t be confirmed. Don&apos;t assume there are none.</div>;
                    }
                    if (allergyLoad === "loading" || !allergyStatus) return <Skeleton rows={1} label="Checking allergies" />;
                    // Only an explicit clinician statement may say "no known allergies"; nobody-asked is not that.
                    return allergyStatus.state === "none_documented" ? (
                      <div className={styles.allergyBannerNone}>No known allergies · documented{allergyStatus.documentedByName ? ` by ${allergyStatus.documentedByName}` : ""}</div>
                    ) : (
                      <div className={styles.allergyBannerNone}>None recorded · not yet asked. This is not the same as no known allergies. Ask the patient.</div>
                    );
                  })()}

                  <div className={styles.statGrid}>
                    <div className={styles.section}>
                      <div className={styles.sectionLabel}>Last visit</div>
                      <div className={styles.sectionValue}>{/* LIVE-03: the list row's value first so list and drawer agree; detail when the row is not on this page */}
                      {formatLastVisit(registry?.rows.find((r) => r.id === drawerPatient.id)?.lastVisit ?? drawerPatient.lastVisit ?? null)}</div>
                    </div>
                    <div className={styles.section}>
                      <div className={styles.sectionLabel}>Active packages</div>
                      <div className={styles.sectionValue}>{drawerPatient.activePackages}</div>
                    </div>
                    {billingView && drawerPatient.outstandingBalance !== undefined && (
                      <div className={styles.section}>
                        <div className={styles.sectionLabel}>Outstanding balance</div>
                        <div className={styles.sectionValue}>{formatNumber(drawerPatient.outstandingBalance, { min: 2, max: 2 })} <BillingStatePill state={drawerPatient.outstandingBalance > 0 ? "outstanding" : "settled"} /></div>
                      </div>
                    )}
                    <div className={styles.section}>
                      <div className={styles.sectionLabel}>Phone</div>
                      <div className={styles.sectionValue}>
                        {phoneShown && canRegister ? drawerPatient.phone : maskPhone(drawerPatient.phone)}
                        {canRegister && (
                          <button type="button" className={styles.linkBtn} style={{ marginInlineStart: "8px" }}
                            aria-pressed={phoneShown} onClick={() => setPhoneShown((v) => !v)}>
                            {phoneShown ? "Hide" : "Reveal"}
                          </button>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className={styles.section}>
                    <div className={styles.sectionLabel}>Address and national ID</div>
                    <div className={styles.muted}>Not available yet. They are saved at registration, but the server does not return them.</div>
                  </div>

                  {drawerPatient.guardianId && (
                    <div className={styles.section}>
                      <div className={styles.sectionLabel}>Guardian / proxy</div>
                      <div className={styles.allergyRow}>
                        <span className={styles.guardianCell}>
                          {drawerPatient.guardianName ?? <span className={styles.muted}>Name not on file</span>}
                          {drawerPatient.guardianConsentGrantedAt ? (
                            <StatusPill tone="ok" label={`Consent captured ${new Date(drawerPatient.guardianConsentGrantedAt).toLocaleDateString()}`} />
                          ) : (
                            <StatusPill tone="danger" label="Consent missing" />
                          )}
                        </span>
                        {!drawerPatient.isMinor && patientsEdit && (
                          <button className={styles.linkBtn} onClick={() => { setConfirmError(null); setConfirmGuardian(true); }}>Revoke access</button>
                        )}
                      </div>
                      {drawerPatient.isMinor && (
                        <div className={styles.muted}>A minor&apos;s guardian can&apos;t be removed — reassign instead. At 18 the patient appears in Guardian reviews due.</div>
                      )}
                    </div>
                  )}

                  {clinicalView ? (
                    <>
                      <div className={styles.section}>
                        <div className={styles.sectionLabel}>Chronic conditions</div>
                        {conditionLoad === "error" ? (
                          <div className={styles.errorInline} role="alert">Couldn&apos;t load conditions.</div>
                        ) : conditionLoad === "loading" ? (
                          <Skeleton rows={1} label="Loading conditions" />
                        ) : conditions.length === 0 ? (
                          <div className={styles.muted}>None recorded</div>
                        ) : (
                          conditions.map((c) => (
                            <div key={c.id} className={styles.allergyRow}>
                              <span>{c.condition}{c.reviewDueDate ? ` · review due ${new Date(c.reviewDueDate).toLocaleDateString()}` : ""}</span>
                              {clinicalEdit && <button className={styles.linkBtn} onClick={() => resolveCondition(c.id)}>Resolve</button>}
                            </div>
                          ))
                        )}
                        {clinicalEdit && (
                          <div className={styles.allergyForm}>
                            <input className={styles.smallInput} aria-label="Condition" placeholder="Condition" value={newCondition.condition} {...fe.attrs("condition")}
                              onChange={(e) => { fe.clearField("condition"); setNewCondition((p) => ({ ...p, condition: e.target.value })); }} />
                            <FieldError fe={fe} name="condition" className={styles.errorInline} />
                            <input className={styles.smallInput} aria-label="Review due date" type="date" value={newCondition.reviewDueDate} {...fe.attrs("reviewDueDate")}
                              onChange={(e) => { fe.clearField("reviewDueDate"); setNewCondition((p) => ({ ...p, reviewDueDate: e.target.value })); }} />
                            <FieldError fe={fe} name="reviewDueDate" className={styles.errorInline} />
                            <button className={styles.linkBtn} disabled={conditionBusy || !newCondition.condition.trim()} onClick={addCondition}>Add</button>
                          </div>
                        )}
                      </div>

                      <div className={styles.section}>
                        <div className={styles.sectionLabel}>Allergy register</div>
                        {allergyLoad === "error" ? (
                          <div className={styles.errorInline} role="alert">Couldn&apos;t load the allergy register. Don&apos;t assume there are none.</div>
                        ) : allergyLoad === "loading" ? (
                          <Skeleton rows={1} label="Loading allergies" />
                        ) : allergies.length === 0 ? (
                          <div className={styles.muted}>None recorded</div>
                        ) : (
                          allergies.map((a) => (
                            <div key={a.id} className={styles.allergyRow}>
                              <span>
                                {a.substance} · {a.severity}{a.reaction ? ` · ${a.reaction}` : ""}
                                {categoryLabel(a.category) ? ` · ${categoryLabel(a.category)}` : ""}
                                {a.recordedAt ? <span className={styles.muted}> · recorded {new Date(a.recordedAt).toLocaleDateString()}{a.recordedByName ? ` by ${a.recordedByName}` : ""}</span> : null}
                              </span>
                              {clinicalEdit && <button className={styles.linkBtn} onClick={() => { setConfirmError(null); setRemoveAllergy(a); }}>Remove</button>}
                            </div>
                          ))
                        )}
                        {clinicalEdit && (
                          <div className={styles.allergyForm}>
                            <select className={styles.smallInput} aria-label="Category" value={newAllergy.category}
                              onChange={(e) => setNewAllergy((p) => ({ ...p, category: e.target.value }))}>
                              <option value="">Category (optional)</option>
                              {ALLERGY_CATEGORIES.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
                            </select>
                            <input className={styles.smallInput} aria-label="Substance" placeholder="Substance" value={newAllergy.substance} {...fe.attrs("substance")}
                              onChange={(e) => { fe.clearField("substance"); setNewAllergy((p) => ({ ...p, substance: e.target.value })); }} />
                            <FieldError fe={fe} name="substance" className={styles.errorInline} />
                            <select className={styles.smallInput} aria-label="Severity" value={newAllergy.severity} {...fe.attrs("severity")}
                              onChange={(e) => { fe.clearField("severity"); setNewAllergy((p) => ({ ...p, severity: e.target.value })); }}>
                              <option value="" disabled>Severity…</option>
                              <option value="mild">mild</option>
                              <option value="moderate">moderate</option>
                              <option value="severe">severe</option>
                            </select>
                            <input className={styles.smallInput} aria-label="Reaction" placeholder="Reaction (optional)" value={newAllergy.reaction} {...fe.attrs("reaction")}
                              onChange={(e) => { fe.clearField("reaction"); setNewAllergy((p) => ({ ...p, reaction: e.target.value })); }} />
                            <FieldError fe={fe} name="reaction" className={styles.errorInline} />
                            <button className={styles.linkBtn} disabled={allergyBusy || !newAllergy.substance.trim() || !newAllergy.severity} onClick={addAllergy}>Add</button>
                          </div>
                        )}
                        {allergyLoad === "ok" && (
                          <>
                            <button type="button" className={styles.linkBtn} aria-expanded={showRemoved}
                              onClick={() => { const next = !showRemoved; setShowRemoved(next); if (next && drawerPatient) void loadRemoved(drawerPatient.id); }}>
                              {showRemoved ? "Hide removed allergies" : "Show removed allergies"}
                            </button>
                            {showRemoved && (removedError ? (
                              <div className={styles.errorInline} role="alert">Couldn&apos;t load removed allergies.</div>
                            ) : removedAllergies === null ? (
                              <Skeleton rows={1} label="Loading removed allergies" />
                            ) : removedAllergies.length === 0 ? (
                              <div className={styles.muted}>No removed allergies</div>
                            ) : removedAllergies.map((a) => (
                              <div key={a.id} className={styles.allergyRow}>
                                <span>
                                  <s>{a.substance}</s> · {a.severity}{categoryLabel(a.category) ? ` · ${categoryLabel(a.category)}` : ""}
                                  <span className={styles.muted}>
                                    {" "}· removed{a.deactivatedAt ? ` ${new Date(a.deactivatedAt).toLocaleDateString()}` : ""}{a.deactivatedByName ? ` by ${a.deactivatedByName}` : ""}
                                    {a.deactivatedReason ? ` · Reason: ${a.deactivatedReason}` : ""}
                                  </span>
                                </span>
                              </div>
                            )))}
                          </>
                        )}
                      </div>

                      <div className={styles.section}>
                        <div className={styles.sectionLabel}>Previous medicines</div>
                        {rxLoad === "error" ? (
                          <div className={styles.errorInline} role="alert">Couldn&apos;t load previous medicines.</div>
                        ) : rxLoad === "loading" ? (
                          <Skeleton rows={1} label="Loading medicines" />
                        ) : prescriptions.length === 0 ? (
                          <div className={styles.muted}>None recorded</div>
                        ) : (
                          prescriptions.map((rx) => (
                            <div key={rx.id} className={styles.sectionValue} style={{ marginBottom: "8px" }}>
                              {rx.signedAt && <span className={styles.muted}>{new Date(rx.signedAt).toLocaleDateString()} — </span>}
                              {rx.items.map((i) => i.drugName).join(", ")}
                            </div>
                          ))
                        )}
                      </div>

                      <div className={styles.section}>
                        <div className={styles.sectionLabel}>Encounter timeline</div>
                        {timelineLoad === "error" ? (
                          <div className={styles.errorInline} role="alert">Couldn&apos;t load the encounter timeline.</div>
                        ) : timelineLoad === "loading" ? (
                          <Skeleton rows={2} label="Loading timeline" />
                        ) : timeline.length === 0 ? (
                          <div className={styles.muted}>No completed visits yet</div>
                        ) : (
                          timeline.map((e) => (
                            <div key={e.queueEntryId} className={styles.timelineRow}>
                              <span className={styles.muted}>{new Date(e.occurredAt).toLocaleDateString()}</span>
                              <span>{e.diagnosis || e.assessment || "—"}</span>
                              {e.medications && <span className={styles.muted}>{e.medications}</span>}
                            </div>
                          ))
                        )}
                        {timelineCursor && (
                          <button className={styles.linkBtn} onClick={() => loadMoreTimeline(drawerPatient.id)} disabled={timelineLoadingMore}>
                            {timelineLoadingMore ? "Loading…" : "Load more"}
                          </button>
                        )}
                      </div>
                    </>
                  ) : (
                    <div className={styles.muted}>Clinical records aren&apos;t available to your role.</div>
                  )}
                </>
              )}
            </>
          )}
        </Dialog>
      )}
      {removeAllergy && drawerPatient && (
        <ConfirmDialog title="Remove allergy" confirmLabel="Remove allergy" reasonLabel="Reason for removing (required; kept in the allergy history)"
          message={`${removeAllergy.substance} (${removeAllergy.severity}) will come off ${drawerPatient.name}'s active allergies. The entry stays in the history.`}
          busy={confirmBusy} error={confirmError} onConfirm={(reason) => void deactivateAllergy(reason)} onClose={() => setRemoveAllergy(null)} />
      )}
      {confirmGuardian && drawerPatient && (
        <ConfirmDialog title="Revoke guardian access" confirmLabel="Revoke access"
          message={`${drawerPatient.guardianName ?? "The guardian"} will no longer be able to act for ${drawerPatient.name}, and the guardian consent is withdrawn.`}
          busy={confirmBusy} error={confirmError} onConfirm={() => void revokeGuardian()} onClose={() => setConfirmGuardian(false)} />
      )}
    </main>
  );
}
