"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { DEFAULT_START_STATUS, statusBeforeConsult, statusSequence } from "@nabd/domain";
import styles from "../patients.module.css";
import StatusPill from "../../../components/ui/StatusPill";
import VitalsCard from "../../../components/consult/VitalsCard";
import type { Part } from "../../../components/consult/clinical";
import type { Vitals } from "../../../components/consult/shared";
import { can } from "../../../lib/permissions";
import { api, ApiError } from "../../../lib/api";
import { staleMessage } from "../../../lib/queueMove";
import { failText } from "../../../lib/formErrors";
import { key, queueQuery } from "../../../lib/query";
import { TODAY_STATUS_LABEL, VISIT_DONE, selfId, type Perms, type TodayVisit } from "../patientsModel";

// DOC-052: the drawer's visit context (wireframe Patient 360: latest vitals + "Start consultation").
// Only patients the registry shows in today's queue have a visit to look up (`enabled`).
/** `action` goes in the drawer header beside the patient meta; `below` (error + latest vitals) under it. */
export function useVisitContext(patientId: string, enabled: boolean, perms: Perms): { action: React.ReactNode; below: React.ReactNode } {
  const router = useRouter();
  const qc = useQueryClient();
  const { clinicalView, clinicalEdit } = perms;
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);
  const visitQ = useQuery({
    queryKey: key("patient-visit", { id: patientId }), enabled: enabled && can("queue:view"),
    queryFn: async () => {
      const list = (await qc.fetchQuery(queueQuery())) as unknown as TodayVisit[]; // same key as the sidebar badge
      const open = list.filter((e) => e.patientId === patientId && !VISIT_DONE.includes(e.status));
      const entry = open.find((e) => e.doctorId === selfId()) ?? open[0] ?? null;
      // The status the department's pipeline puts right before in_consult (same rule as /consult, DOC-019); an unreadable flow keeps the default.
      let start: string | null = DEFAULT_START_STATUS;
      if (entry) {
        try {
          const steps = await api.get<{ stepType: string }[]>(`/departments/${entry.departmentId}/flow`);
          start = statusBeforeConsult(statusSequence(steps.map((x) => x.stepType)));
        } catch { /* keep the default */ }
      }
      return { entry, start };
    },
  });
  const visit = visitQ.data?.entry ?? null;
  const visitStartStatus = visitQ.data?.start ?? DEFAULT_START_STATUS;
  const vitalsQ = useQuery({ queryKey: key("patient-visit-vitals", { id: visit?.id }), enabled: !!visit && clinicalView, queryFn: () => api.get<Vitals>(`/clinical/vitals/${visit?.id}`) });
  const visitVitals: Part<Vitals> = vitalsQ.isError
    ? { status: vitalsQ.error instanceof ApiError && vitalsQ.error.status === 404 ? "none" : "error", data: null }
    : vitalsQ.data ? { status: "ok", data: vitalsQ.data } : { status: "loading", data: null };

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

  if (!visit) return { action: null, below: null };
  const mine = visit.doctorId === selfId();
  const action = mine && clinicalEdit && visit.status === "in_consult" ? (
    <button type="button" className={styles.primaryBtn} onClick={() => router.push(`/consult?entry=${encodeURIComponent(visit.id)}`)}>
      Resume consultation · T-{visit.tokenNumber}
    </button>
  ) : mine && clinicalEdit && visitStartStatus !== null && visit.status === visitStartStatus && can("queue:edit") ? (
    <button type="button" className={styles.primaryBtn} disabled={starting} onClick={() => void startConsultation(visit)}>
      {starting ? "Starting…" : `Start consultation · T-${visit.tokenNumber}`}
    </button>
  ) : (
    <StatusPill tone="info" icon="users" label={`${mine ? "In your queue" : "In today's queue"} · T-${visit.tokenNumber} · ${TODAY_STATUS_LABEL[visit.status] ?? visit.status.replace(/_/g, " ")}`} />
  );
  // Start/Resume only for the doctor whose queue holds the visit; everyone else sees where the visit is.
  const below = (
    <>
      {startError && <div className={styles.errorInline} role="alert">{startError}</div>}
      {clinicalView && (
        <div className={styles.section}>
          <VitalsCard vitals={visitVitals} onRetry={() => void vitalsQ.refetch()} />
        </div>
      )}
    </>
  );
  return { action, below };
}
