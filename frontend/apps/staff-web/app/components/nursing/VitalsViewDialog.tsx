"use client";

import styles from "./nursing.module.css";
import Icon from "../ui/Icon";
import { useQuery } from "@tanstack/react-query";
import type { VitalsResponse } from "@nabd/api-client/generated";
import { BLOOD_SUGAR_UNIT, PAIN_SCORE_MAX, formatBp } from "@nabd/domain";
import Dialog from "../ui/Dialog";
import { Skeleton } from "../ui/StateViews";
import { getter, key } from "../../lib/query";
import { ageSex, hhmm, type Row } from "./model";

// NUR-012: read-only view of a recorded visit's vitals (GET /clinical/vitals/{queueEntryId}); own loading/error state.
export default function VitalsViewDialog({ viewRow, onClose }: { viewRow: Row; onClose: () => void }) {
  const viewQ = useQuery({ queryKey: key("vitals", { queueEntryId: viewRow.id }), queryFn: getter<VitalsResponse>(`/clinical/vitals/${viewRow.id}`) });
  const viewVitals = viewQ.data ?? null;
  const viewError = viewQ.isError;
  return (
        <Dialog title={`Vitals — ${viewRow.patientName}`} onClose={() => onClose()}>
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
          <div className={styles.modalActions}><button type="button" className={styles.cancelBtn} onClick={() => onClose()}>Close</button></div>
        </Dialog>
  );
}
