"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import styles from "./nursing.module.css";
import Icon from "../ui/Icon";
import VaccinePanel, { RecordVaccineDialog, type VaccineVisit } from "./VaccinePanel";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { getIdentity } from "../../lib/session";
import { useHasMounted } from "../../lib/useHasMounted";
import { apiFetch, goLogin } from "../../lib/apiClient";
import { PERM, TABS, validFilter, validTab, type Row, type VitalsFilter } from "./model";
import { useNursingData } from "./useNursingData";
import VitalsTab from "./VitalsTab";
import PriorityTab from "./PriorityTab";
import AdminOrdersTab from "./AdminOrdersTab";
import ProceduresTab from "./ProceduresTab";
import ActivityTab from "./ActivityTab";
import VitalsCaptureDialog from "./VitalsCaptureDialog";
import VitalsViewDialog from "./VitalsViewDialog";
import UrgentDialog from "./UrgentDialog";
import CapturePicker from "./CapturePicker";

// FEF-031: composes the nursing tabs. Server state: useNursingData; each tab and dialog owns its own form state.
// useSearchParams (ClinicNav's Today/Waiting Patients/... deep links) needs the Suspense boundary the page provides.
export default function NursingWorklist() {
  const router = useRouter();
  const searchParams = useSearchParams();
  // ClinicNav's nurse sub-items are deep links into this one page via ?tab=/?filter=, so the URL is the only state for both.
  const tab = validTab(searchParams.get("tab"));
  const vitalsFilter = validFilter(searchParams.get("filter"));
  function changeFilter(f: VitalsFilter) {
    router.replace(f === "due" ? "/nursing" : `/nursing?filter=${f}`);
  }
  // Permissions, the clock and the token's region live in the browser: read only after hydration.
  const mounted = useHasMounted();
  const [actionError, setActionError] = useState<string | null>(null);
  const [vitalsEntryId, setVitalsEntryId] = useState<string | null>(null);
  const [viewRow, setViewRow] = useState<Row | null>(null);
  const [showCapturePicker, setShowCapturePicker] = useState(false);
  const [showVaccineDialog, setShowVaccineDialog] = useState(false);
  const [vaccineReload, setVaccineReload] = useState(0);
  const [urgentFor, setUrgentFor] = useState<string | null>(null); // "" = picker open without a preselected visit
  const [, setNow] = useState(() => Date.now());
  const d = useNursingData(mounted);
  const { rows, recordedRows, proceduresPendingRows, priorityRows, staff, loaded, error, queueForbidden, sectionFailed, invalidateAll, retryLoad } = d;

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
    if (getIdentity()?.tenantRegion !== "SA") return greg;
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

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);

  const allow = (permission: Parameters<typeof can>[0]) => mounted && can(permission);
  const canViewVitals = can(PERM.vitalsRead);
  const canCaptureVitals = allow(PERM.vitalsCreate) || allow(PERM.vitalsWrite);
  const canCreateOrders = allow(PERM.create);
  const canEditOrders = allow(PERM.edit);
  // Flagging and acknowledging priority call POST /queue/{id}/reorder and /priority/acknowledge (queue:edit);
  // "Procedures done, continue" is PATCH /queue/{id}/status (queue:edit) after GET /departments/{id}/flow (queue:view).
  const canFlagPriority = allow(PERM.queueEdit);
  const canAdvanceQueue = allow(PERM.queueEdit) && allow(PERM.queueView);

  // Row click / Enter / Space: capture for a waiting visit, read-only view for a recorded one.
  const openRow = (r: Row) => { if (r.status === "vitals_pending") { if (canCaptureVitals) setVitalsEntryId(r.id); } else if (canViewVitals && r.lastVitalsAt) setViewRow(r); };
  const rowActionable = (r: Row) => (r.status === "vitals_pending" ? canCaptureVitals : canViewVitals && !!r.lastVitalsAt);
  const closeVitalsModal = () => { setVitalsEntryId(null); void invalidateAll(); };

  const visibleVitalsRows = vitalsFilter === "due" ? rows : vitalsFilter === "recorded" ? recordedRows : [...rows, ...recordedRows];
  const urgentCandidates = [...rows, ...recordedRows].filter((r) => !r.priority);
  const staffName = (id: string | null | undefined) => (id ? staff.find((s) => s.id === id)?.name ?? null : null);
  const noQueueAccess = <EmptyState icon="shield" title="No access to the patient queue" hint="Your role doesn't include the patient queue, so arrivals and priority patients aren't shown here." />;
  const showSkeleton = !loaded && !error;
  const showLoadError = !loaded && !!error;
  const vitalsEmpty = {
    due: { title: "No one is waiting for vitals", hint: "New arrivals appear here as soon as reception checks them in." },
    recorded: { title: "No vitals recorded yet today", hint: "Patients whose vitals you capture will show up here." },
    all: { title: "No patients on the worklist", hint: "Nobody has been checked in for vitals today." },
  }[vitalsFilter];
  const vitalsRow = vitalsEntryId ? [...rows, ...recordedRows].find((r) => r.id === vitalsEntryId) : undefined;
  const recordedCount = recordedRows.filter((r) => r.lastVitalsAt).length;
  const outOfRangeCount = recordedRows.filter((r) => r.vitalsOutOfRange).length;
  // ClinicNav's "Vaccines & Injections" sidebar item deep-links here (?tab=vaccines): not one of the 5 real tabs,
  // so it is read from the raw query param and shown as its own honest section.
  const isVaccinesView = searchParams.get("tab") === "vaccines";
  // Today's visits a vaccine can be recorded against (one entry per visit, never a made-up patient).
  const vaccineVisits: VaccineVisit[] = [...new Map([...rows, ...recordedRows, ...proceduresPendingRows].map((r) => [r.id, r])).values()]
    .map((r) => ({ patientId: r.patientId, queueEntryId: r.id, patientName: r.patientName, tokenNumber: r.tokenNumber }));
  const tabProps = { invalidateAll, setActionError, canCreateOrders, canEditOrders };

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
        <VitalsTab {...{ visibleVitalsRows, queueForbidden, noQueueAccess, vitalsEmpty, rowActionable, openRow, canCaptureVitals, canViewVitals, canCreateOrders, canFlagPriority, recordedCount, outOfRangeCount }}
          openVitalsModal={setVitalsEntryId} openViewVitals={setViewRow}
          onCapture={() => setShowCapturePicker(true)} onRecordVaccine={() => setShowVaccineDialog(true)} onMarkUrgent={() => setUrgentFor("")} onOpenVaccines={() => router.replace("/nursing?tab=vaccines")} />
      )}
      {loaded && tab === "priority" && <PriorityTab {...{ priorityRows, queueForbidden, noQueueAccess, canFlagPriority, staffName, invalidateAll, setActionError }} />}
      {loaded && tab === "administration" && <AdminOrdersTab {...tabProps} administrationOrders={d.administrationOrders} staff={staff} />}
      {loaded && tab === "procedures" && <ProceduresTab {...tabProps} canAdvanceQueue={canAdvanceQueue} proceduresPendingRows={proceduresPendingRows} procedureOrders={d.procedureOrders} />}
      {loaded && tab === "activity" && <ActivityTab activity={d.activity} />}
        </>
      )}

      {/* Shared Dialog gives focus-in/restore, Tab trap and Escape; the capture form closes on Escape only while nothing is typed. */}
      {vitalsEntryId && canCaptureVitals && (
        <VitalsCaptureDialog entryId={vitalsEntryId} row={vitalsRow} canFlagPriority={canFlagPriority} onMarkUrgent={setUrgentFor} onClose={closeVitalsModal} />
      )}
      {viewRow && <VitalsViewDialog viewRow={viewRow} onClose={() => setViewRow(null)} />}
      {showCapturePicker && canCaptureVitals && !queueForbidden && (
        <CapturePicker rows={rows} onPick={(id) => { setShowCapturePicker(false); setVitalsEntryId(id); }} onClose={() => setShowCapturePicker(false)} />
      )}
      {showVaccineDialog && canCreateOrders && (
        <RecordVaccineDialog authedFetch={authedFetch} visits={vaccineVisits}
          onClose={() => setShowVaccineDialog(false)}
          onRecorded={() => { setShowVaccineDialog(false); setVaccineReload((n) => n + 1); if (!isVaccinesView) router.replace("/nursing?tab=vaccines"); }} />
      )}
      {urgentFor !== null && canFlagPriority && (
        <UrgentDialog initialEntryId={urgentFor} candidates={urgentCandidates} onClose={() => setUrgentFor(null)} onDone={() => { setUrgentFor(null); void invalidateAll(); }} />
      )}
    </main>
  );
}
