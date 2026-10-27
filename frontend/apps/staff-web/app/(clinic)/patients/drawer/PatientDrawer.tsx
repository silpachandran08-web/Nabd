"use client";

import { useState } from "react";
import { age } from "@nabd/domain";
import styles from "../patients.module.css";
import Dialog from "../../../components/ui/Dialog";
import { ErrorState, Skeleton } from "../../../components/ui/StateViews";
import { ApiError } from "../../../lib/api";
import PatientMessages from "../PatientMessages";
import type { Perms, RegistryRow } from "../patientsModel";
import { useDetail } from "./usePatientQueries";
import { useVisitContext } from "./VisitContext";
import OverviewTab from "./OverviewTab";
import DentalTab from "./DentalTab";

// FEF-031: the patient drawer. Mounted per patient (key = id), so a slow earlier load can never land in a later patient's drawer.
// The header loads first; every section below loads and fails on its own.
export default function PatientDrawer({ patientId, row, perms, onClose, onRegistryChanged }: {
  patientId: string; row: RegistryRow | undefined; perms: Perms; onClose: () => void; onRegistryChanged: () => void;
}) {
  const [drawerTab, setDrawerTab] = useState<"overview" | "dental" | "messages">("overview");
  const detailQ = useDetail(patientId);
  const drawerPatient = detailQ.data ?? null;
  const visit = useVisitContext(patientId, row?.queueToken != null, perms);
  const canDental = perms.dentalView;
  const drawerError = detailQ.isError ? (detailQ.error instanceof ApiError ? "Couldn't load this patient." : "Couldn't reach the server. Check your connection and try again.") : null;
  return (
    <Dialog title={drawerPatient?.name ?? "Patient"} size="drawer" onClose={onClose}>
      {drawerError ? (
        <ErrorState message={drawerError} onRetry={() => void detailQ.refetch()} />
      ) : !drawerPatient ? (
        <Skeleton rows={5} label="Loading patient" />
      ) : (
        <>
          <div className={styles.drawerHeader}>
            <span className={styles.drawerMeta}>{drawerPatient.mrn} · {age(drawerPatient.dob)} · {drawerPatient.gender}</span>
            {visit.action}
          </div>
          {visit.below}

          <div className={styles.drawerTabs}>
            <button className={drawerTab === "overview" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setDrawerTab("overview")}>Overview</button>
            {canDental && <button className={drawerTab === "dental" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setDrawerTab("dental")}>Dental chart</button>}
            <button className={drawerTab === "messages" ? styles.drawerTabActive : styles.drawerTab} onClick={() => setDrawerTab("messages")}>Messages</button>
          </div>

          {drawerTab === "messages" ? (
            <PatientMessages patientId={drawerPatient.id} />
          ) : drawerTab === "dental" && canDental ? (
            <DentalTab patientId={drawerPatient.id} dentalEdit={perms.dentalEdit} />
          ) : (
            <OverviewTab patient={drawerPatient} listLastVisit={row?.lastVisit} perms={perms} onRegistryChanged={onRegistryChanged} />
          )}
        </>
      )}
    </Dialog>
  );
}
