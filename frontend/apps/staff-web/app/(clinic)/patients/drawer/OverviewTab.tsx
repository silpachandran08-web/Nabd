"use client";

import { useState } from "react";
import { formatNumber } from "@nabd/i18n";
import styles from "../patients.module.css";
import BillingStatePill from "../../../components/ui/BillingStatePill";
import { formatLastVisit } from "../../../lib/lastVisit";
import { ErrorSummary, useFormErrors } from "../../../lib/formErrors";
import { maskPhone, type PatientDetail, type Perms } from "../patientsModel";
import AllergySection, { AllergyBanner } from "./AllergySection";
import ConditionsSection from "./ConditionsSection";
import GuardianSection from "./GuardianSection";
import CompleteInChat from "../CompleteInChat";
import MedicinesSection from "./MedicinesSection";
import TimelineSection from "./TimelineSection";

// The drawer's Overview tab: header facts plus one self-loading section per clinical record.
export default function OverviewTab({ patient, listLastVisit, perms, onRegistryChanged }: {
  patient: PatientDetail; listLastVisit: string | null | undefined; perms: Perms; onRegistryChanged: () => void;
}) {
  const { clinicalView, clinicalEdit, billingView, register: canRegister, patientsEdit } = perms;
  const drawerPatient = patient;
  const [phoneShown, setPhoneShown] = useState(false);
  // A write that failed must say so — never leave the clinician thinking an allergy/guardian change landed.
  const fe = useFormErrors("pt", { substance: "Substance", severity: "Severity", reaction: "Reaction", category: "Category", condition: "Condition", reviewDueDate: "Review due date" });
  return (
    <>
      <ErrorSummary fe={fe} className={styles.errorInline} />
      {/* Never assert "no known allergies" for a role that can't see the allergy data. */}
      <AllergyBanner patient={patient} clinicalView={clinicalView} />

      <div className={styles.statGrid}>
        <div className={styles.section}>
          <div className={styles.sectionLabel}>Last visit</div>
          <div className={styles.sectionValue}>{/* LIVE-03: the list row's value first so list and drawer agree; detail when the row is not on this page */}
          {formatLastVisit(listLastVisit ?? drawerPatient.lastVisit ?? null)}</div>
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

                  {patientsEdit && <CompleteInChat key={patient.id} patientId={patient.id} />}

      <GuardianSection patient={patient} patientsEdit={patientsEdit} onRegistryChanged={onRegistryChanged} />

      {clinicalView ? (
        <>
          <ConditionsSection patient={patient} clinicalEdit={clinicalEdit} fe={fe} onRegistryChanged={onRegistryChanged} />
          <AllergySection patient={patient} clinicalEdit={clinicalEdit} fe={fe} />
          <MedicinesSection patientId={patient.id} />
          <TimelineSection patientId={patient.id} />
        </>
      ) : (
        <div className={styles.muted}>Clinical records aren&apos;t available to your role.</div>
      )}
    </>
  );
}
