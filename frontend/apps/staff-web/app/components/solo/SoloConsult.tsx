"use client";

import { Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import type { VisitSummary } from "@nabd/api-client/generated";
import styles from "./solo.module.css";
import StatusPill from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { ApiError } from "../../lib/api";
import { can } from "../../lib/permissions";
import { useHasMounted } from "../../lib/useHasMounted";
import { nextWaiting, useCallNext } from "./callNext";
import SafetyStripPhone from "./SafetyStripPhone";
import { useSoloQueue } from "./useSoloQueue";
import { useVisitSummary } from "./useVisitSummary";

// CLN-008 solo.consult: the summary card stack. The safety strip is always first; the action bar is fixed to the bottom.
export default function SoloConsult() {
  return <Suspense fallback={null}><SoloConsultInner /></Suspense>;
}

function SoloConsultInner() {
  const router = useRouter();
  const entry = useSearchParams().get("entry");
  const mounted = useHasMounted();
  const q = useVisitSummary(entry);
  const queue = useSoloQueue();
  const next = nextWaiting(queue.rows);
  const call = useCallNext(next, queue.refetch);
  const s = q.data;
  const inConsult = s?.patient?.queueStatus === "in_consult";
  const go = (qs = "") => router.push(`/consult?entry=${encodeURIComponent(entry ?? "")}${qs}`);
  const canEdit = mounted && can("clinical:edit");
  const error = q.error ? (q.error instanceof ApiError && q.error.status === 404 ? "This visit isn't available to you." : q.error instanceof ApiError ? "Couldn't load the summary. Try again." : "Couldn't reach the server. Check your connection and try again.") : null;

  return (
    <main className={styles.page}>
      {call.stale && (
        <div className={styles.notice} role="alert">
          <span>{call.stale}</span>
          <button type="button" className={styles.noticeBtn} onClick={() => { call.clear(); void queue.refetch(); }}>Refresh</button>
        </div>
      )}
      {call.error && <div className={styles.notice} role="alert">{call.error}</div>}
      {!entry ? <EmptyState icon="users" title="No visit selected." hint="Open a patient from Today." />
        : error ? <ErrorState message={error} onRetry={() => void q.refetch()} />
        : !s ? <Skeleton rows={6} label="Loading the patient summary" />
        : <Summary s={s} />}

      {mounted && (
        <nav className={styles.bar} aria-label="Consult actions">
          {can("queue:edit") && <button type="button" className={styles.barBtn} disabled={!next || call.busy || inConsult} onClick={() => void call.callNext()}>{call.busy ? "Calling…" : "Call next"}</button>}
          {canEdit && entry && <button type="button" className={`${styles.barBtn} ${styles.barPrimary}`} onClick={() => go()}>Start</button>}
          {canEdit && entry && <button type="button" className={styles.barBtn} onClick={() => go("&step=plan")}>Rx</button>}
          {canEdit && entry && <button type="button" className={styles.barBtn} onClick={() => go("&step=close")}>Done</button>}
        </nav>
      )}
    </main>
  );
}

function Summary({ s }: { s: VisitSummary }) {
  const p = s.patient;
  const conditions = s.conditions ?? [];
  const lastVisits = s.lastVisits ?? [];
  const meds = s.activeMeds ?? [];
  const v = s.vitals;
  return (
    <>
      {s.safety && <SafetyStripPhone safety={s.safety} />}
      <section className={styles.banner} aria-label="Patient">
        <strong>{p?.name}</strong>
        <span>{[p?.ageText, p?.sex].filter(Boolean).join(" · ")}</span>
        <span className={styles.muted}>{p?.mrn}</span>
        {s.intake?.presentingComplaint && <span style={{ flexBasis: "100%" }}>Reason: {s.intake.presentingComplaint}</span>}
      </section>
      <section className={styles.card} aria-label="Vitals">
        <h2 className={styles.cardTitle}>Vitals</h2>
        {!v ? <p className={styles.muted}>Not recorded for this visit.</p> : (
          <div className={styles.line}>
            <span>{[v.bpSystolic != null && v.bpDiastolic != null ? `BP ${v.bpSystolic}/${v.bpDiastolic}` : null, v.pulseBpm != null ? `Pulse ${v.pulseBpm}` : null, v.tempCelsius != null ? `Temp ${v.tempCelsius}` : null, v.spo2Percent != null ? `SpO2 ${v.spo2Percent}%` : null, v.weightKg != null ? `${v.weightKg} kg` : null].filter(Boolean).join(" · ") || "Recorded"}</span>
            {(v.abnormalFlags?.length ?? 0) > 0 && <StatusPill tone="danger" label={`Out of range: ${v.abnormalFlags!.join(", ")}`} />}
          </div>
        )}
      </section>
      <section className={styles.card} aria-label="Conditions">
        <h2 className={styles.cardTitle}>Conditions</h2>
        {conditions.length === 0 ? <p className={styles.muted}>None recorded</p> : conditions.map((c, i) => <div key={i} className={styles.line}>{c.condition}</div>)}
      </section>
      <section className={styles.card} aria-label="Medicines">
        <h2 className={styles.cardTitle}>Recent medicines</h2>
        {meds.length === 0 ? <p className={styles.muted}>None recorded</p> : meds.map((m, i) => <div key={i} className={styles.line}><span>{m.drugName}</span><span className={styles.muted}>{[m.dosage, m.frequency, m.duration].filter(Boolean).join(" · ")}</span></div>)}
      </section>
      <section className={styles.card} aria-label="Last visits">
        <h2 className={styles.cardTitle}>Last visits</h2>
        {lastVisits.length === 0 ? <p className={styles.muted}>No completed visits yet</p> : lastVisits.map((e) => (
          <div key={e.queueEntryId} className={styles.line}><span>{e.diagnosis || e.assessment || "—"}</span><span className={styles.muted}>{e.occurredAt ? new Date(e.occurredAt).toLocaleDateString() : ""}</span></div>
        ))}
      </section>
      <section className={styles.card} aria-label="Attachments"><div className={styles.line}><span>Attachments</span><span>{s.attachmentsCount ?? 0}</span></div></section>
    </>
  );
}
