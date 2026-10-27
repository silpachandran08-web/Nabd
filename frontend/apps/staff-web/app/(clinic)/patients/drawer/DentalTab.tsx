"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import styles from "../patients.module.css";
import { api } from "../../../lib/api";
import { getter, key } from "../../../lib/query";
import { ErrorSummary, failText, useFormErrors } from "../../../lib/formErrors";
import { FDI_TEETH, FDI_TO_UNIVERSAL, TOOTH_STATUSES, type Tooth, type ToothHistoryEntry } from "../patientsModel";

// The dental chart tab: own query; tooth edits patch the cached chart. Moved unchanged from the old drawer.
export default function DentalTab({ patientId, dentalEdit }: { patientId: string; dentalEdit: boolean }) {
  const qc = useQueryClient();
  const fe = useFormErrors("dt");
  const chartQ = useQuery({ queryKey: key("patient-chart", { id: patientId }), queryFn: getter<Tooth[]>(`/specialty/dental/patients/${patientId}/chart`) });
  const chart = chartQ.data ?? []; // an unreadable chart stays empty; tooth edits re-fetch nothing
  const setChart = (f: (prev: Tooth[]) => Tooth[]) => qc.setQueryData(key("patient-chart", { id: patientId }), (prev: Tooth[] | undefined) => f(prev ?? []));
  const [chartBusy, setChartBusy] = useState<number | null>(null);
  const [numbering, setNumbering] = useState<"fdi" | "universal">("fdi");
  const [newSupernumerary, setNewSupernumerary] = useState({ nearToothNumber: 11, status: "healthy", note: "" });
  const [supernumeraryBusy, setSupernumeraryBusy] = useState<string | null>(null);
  const [historyToothId, setHistoryToothId] = useState<string | null>(null);
  const [historyEntries, setHistoryEntries] = useState<ToothHistoryEntry[]>([]);
  const drawerPatient = { id: patientId };

  function toothLabel(fdiNumber: number): string {
    return numbering === "universal" ? `#${FDI_TO_UNIVERSAL[fdiNumber]}` : String(fdiNumber);
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
    <>
      <ErrorSummary fe={fe} className={styles.errorInline} />
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
  );
}
