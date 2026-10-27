"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import styles from "../patients.module.css";
import StatusPill from "../../../components/ui/StatusPill";
import { Skeleton } from "../../../components/ui/StateViews";
import { api, ApiError } from "../../../lib/api";
import { key } from "../../../lib/query";
import { FieldError, type FormErrors } from "../../../lib/formErrors";
import { ALLERGY_CATEGORIES, categoryLabel, type Allergy, type PatientDetail } from "../patientsModel";
import { useAllergies, useRefreshPatient } from "./usePatientQueries";
import ConfirmPortal from "./ConfirmPortal";

const BLANK = { substance: "", category: "", severity: "", reaction: "" };

/** The allergy banner: never asserts "no known allergies" unless a clinician documented it (CLN-001 rules, moved unchanged). */
export function AllergyBanner({ patient, clinicalView }: { patient: PatientDetail; clinicalView: boolean }) {
  const q = useAllergies(patient.id, clinicalView);
  const allergyLoad = q.isError ? "error" : q.isPending ? "loading" : "ok";
  const allergyStatus = q.data?.status ?? null;
  const names = [...new Set([...patient.allergies, ...(q.data?.list ?? []).filter((x) => x.active).map((x) => x.substance)])];
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
  if (allergyLoad === "loading" || q.isFetching || !allergyStatus) return <Skeleton rows={1} label="Checking allergies" />;
  // Only an explicit clinician statement may say "no known allergies"; nobody-asked is not that.
  return allergyStatus.state === "none_documented" ? (
    <div className={styles.allergyBannerNone}>No known allergies · documented{allergyStatus.documentedByName ? ` by ${allergyStatus.documentedByName}` : ""}</div>
  ) : (
    <div className={styles.allergyBannerNone}>None recorded · not yet asked. This is not the same as no known allergies. Ask the patient.</div>
  );
}

export default function AllergySection({ patient, clinicalEdit, fe }: { patient: PatientDetail; clinicalEdit: boolean; fe: FormErrors }) {
  const qc = useQueryClient();
  const q = useAllergies(patient.id, true);
  const refreshPatient = useRefreshPatient(patient.id);
  const [newAllergy, setNewAllergy] = useState(BLANK);
  // Severity starts unset: an unknown severity must never be guessed, so Add stays unavailable until a clinician picks one.
  const [allergyBusy, setAllergyBusy] = useState(false);
  const [showRemoved, setShowRemoved] = useState(false);
  const [removedAllergies, setRemovedAllergies] = useState<Allergy[] | null>(null);
  const [removedError, setRemovedError] = useState(false);
  const [removeAllergy, setRemoveAllergy] = useState<Allergy | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const allergyLoad = q.isError ? "error" : q.isPending ? "loading" : "ok";
  const allergies = q.data?.list ?? [];

  async function loadRemoved() {
    setRemovedError(false);
    try {
      setRemovedAllergies((await api.get<Allergy[]>(`/clinical/patients/${patient.id}/allergies?includeInactive=true`)).filter((x) => !x.active));
    } catch {
      setRemovedError(true);
    }
  }

  async function addAllergy() {
    if (!newAllergy.substance.trim() || !newAllergy.severity || allergyBusy) return;
    setAllergyBusy(true);
    fe.clear();
    let saved = false;
    try {
      await api.post(`/clinical/patients/${patient.id}/allergies`, { ...newAllergy, category: newAllergy.category || undefined });
      saved = true;
    } catch (e) {
      fe.fail(e, e instanceof ApiError ? "Couldn't save the allergy. It has NOT been recorded." : "Couldn't reach the server. The allergy has NOT been recorded.");
    } finally {
      setAllergyBusy(false);
    }
    if (!saved) return;
    setNewAllergy(BLANK);
    void qc.invalidateQueries({ queryKey: key("patient-allergies", { id: patient.id }) });
    if (showRemoved) void loadRemoved();
    await refreshPatient();
  }

  async function deactivateAllergy(reason: string) {
    if (!removeAllergy || confirmBusy) return;
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
    // Drop the removed name now (register and header); the refresh below is not awaited and the banner must not keep naming it.
    const gone = removeAllergy;
    qc.setQueryData(key("patient-allergies", { id: patient.id }), (d: { list: Allergy[] } | undefined) => d && { ...d, list: d.list.filter((x) => x.id !== gone.id) });
    qc.setQueryData(key("patient-detail", { id: patient.id }), (p: PatientDetail | undefined) => p && { ...p, allergies: p.allergies.filter((n) => n !== gone.substance) });
    setRemoveAllergy(null);
    void qc.invalidateQueries({ queryKey: key("patient-allergies", { id: patient.id }) });
    if (showRemoved) void loadRemoved();
    await refreshPatient();
  }

  return (
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
                              onClick={() => { const next = !showRemoved; setShowRemoved(next); if (next) void loadRemoved(); }}>
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
      {removeAllergy && (
        <ConfirmPortal title="Remove allergy" confirmLabel="Remove allergy" reasonLabel="Reason for removing (required; kept in the allergy history)"
          message={`${removeAllergy.substance} (${removeAllergy.severity}) will come off ${patient.name}'s active allergies. The entry stays in the history.`}
          busy={confirmBusy} error={confirmError} onConfirm={(reason) => void deactivateAllergy(reason)} onClose={() => setRemoveAllergy(null)} />
      )}
    </div>
  );
}
