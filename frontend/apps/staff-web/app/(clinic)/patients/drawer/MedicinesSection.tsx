"use client";

import styles from "../patients.module.css";
import { Skeleton } from "../../../components/ui/StateViews";
import { usePrescriptions } from "./usePatientQueries";

// Previous medicines: own query, own loading / error state.
export default function MedicinesSection({ patientId }: { patientId: string }) {
  const q = usePrescriptions(patientId, true);
  const rxLoad = q.isError ? "error" : q.isPending ? "loading" : "ok";
  const prescriptions = q.data ?? [];
  return (
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
  );
}
