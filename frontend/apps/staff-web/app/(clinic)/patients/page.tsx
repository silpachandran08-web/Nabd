"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import styles from "./patients.module.css";
import GuardianReviews from "./GuardianReviews";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import DeskShortcuts from "../../components/arrivals/shortcuts/DeskShortcuts";
import { FILTERS, NO_PERMS, readPerms, type Perms, type RegistryRow } from "./patientsModel";
import { useRegistry } from "./registry/useRegistry";
import RegistryTable from "./registry/RegistryTable";
import RegistryOverlays from "./registry/RegistryOverlays";
import PatientDrawer from "./drawer/PatientDrawer";

// FEF-031: the page only composes. Registry list + paging: registry/*; the patient drawer and its self-loading sections: drawer/*.
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
  const searchParams = useSearchParams();
  const reg = useRegistry(searchParams.get("q") ?? "");
  const { registry, filter, setFilter, q, setQ, loading, error, forbidden, load } = reg;
  const [perms, setPerms] = useState<Perms>(NO_PERMS);
  const [drawerId, setDrawerId] = useState<string | null>(null); // set while the drawer is open
  const [checkInFor, setCheckInFor] = useState<RegistryRow | null>(null);
  const [overlay, setOverlay] = useState<"phones" | "duplicates" | "register" | null>(null); // ACC-028 / ACC-030 / register
  const [flash, setFlash] = useState<string | null>(null);
  const { register: canRegister, billingView, patientsEdit, merge: canMerge } = perms;
  const reload = () => void load(q, filter);

  // Actions a role can't take are absent, not disabled; read after hydration.
  useEffect(() => { void Promise.resolve().then(() => setPerms(readPerms())); }, []);
  // FEF-027: the row link's href (/patients?open=<id>) also works when opened in a new tab.
  const deepLinkId = searchParams.get("open");
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (deepLinkId) setDrawerId(deepLinkId);
  }, [deepLinkId]);

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
            <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => setOverlay("phones")}>Numbers to fix</button>
          )}
          {canMerge && !forbidden && (
            <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => setOverlay("duplicates")}>Possible duplicates</button>
          )}
          {canRegister && !forbidden && (
            <button type="button" className={styles.primaryBtn} onClick={() => setOverlay("register")}>＋ Register patient</button>
          )}
        </div>
      </div>

      {!forbidden && (
        <>
          <form className={styles.searchBar} role="search" onSubmit={(e) => { e.preventDefault(); reload(); }}>
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

      {!forbidden && <GuardianReviews canAct={patientsEdit} onOpen={setDrawerId} onChanged={reload} />}

      <div className={styles.card}>
        {loading ? (
          <Skeleton rows={6} label="Loading patients" />
        ) : forbidden ? (
          <EmptyState icon="shield" title="Your role doesn't have access to patient records." />
        ) : error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : !registry || registry.rows.length === 0 ? (
          <EmptyState icon="users" title={q.trim() ? "No patients match that search." : "No patients here."} />
        ) : (
          <RegistryTable registry={registry} perms={perms} filter={filter} nextCursor={reg.nextCursor} moreRef={reg.moreRef} moreState={reg.moreState}
            loadMore={() => void reg.loadMore()} openDrawer={setDrawerId} onCheckIn={(p) => { setFlash(null); setCheckInFor(p); }} />
        )}
      </div>

      <RegistryOverlays checkInFor={checkInFor} fixingPhones={overlay === "phones"} reviewingDuplicates={overlay === "duplicates"} registering={overlay === "register"}
        close={(w) => { if (w === "checkIn") setCheckInFor(null); else setOverlay(null); }} setFlash={setFlash} reload={reload} openDrawer={setDrawerId} />

      {drawerId && (
        <PatientDrawer key={drawerId} patientId={drawerId} row={registry?.rows.find((r) => r.id === drawerId)} perms={perms}
          onClose={() => setDrawerId(null)} onRegistryChanged={reload} />
      )}
    </main>
  );
}
