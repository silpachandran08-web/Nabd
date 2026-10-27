"use client";

import type { VisitSummary } from "@nabd/api-client/generated";
import styles from "./solo.module.css";
import Icon from "../ui/Icon";

/** CLN-008: the first element of the summary. States are never invented: "not assessed" is a warning, not "none". */
export default function SafetyStripPhone({ safety }: { safety: NonNullable<VisitSummary["safety"]> }) {
  const allergies = safety.allergies ?? [];
  const severe = allergies.some((a) => a.severity === "severe");
  const preg = safety.pregnancy?.status;
  const pregnant = preg === "pregnant" || preg === "lactating";
  const state = safety.allergyState;
  return (
    <section className={`${styles.strip} ${severe || pregnant ? styles.stripDanger : ""}`} aria-label="Safety" data-testid="safety-strip">
      <h2 className={styles.stripTitle}><Icon name="alert" size={16} />Safety</h2>
      {state === "recorded" && allergies.map((a, i) => (
        <p key={i} className={styles.stripLine}><strong>Allergy: {a.substance}</strong> · {a.severity}{a.reaction ? ` · ${a.reaction}` : ""}</p>
      ))}
      {state === "none_documented" && <p className={styles.stripLine}>No known allergies (documented by a clinician)</p>}
      {state === "not_assessed" && <p className={styles.stripLine}><strong>Allergies not yet asked.</strong> This is not the same as none. Ask the patient.</p>}
      {pregnant && <p className={styles.stripLine}><strong>{preg === "pregnant" ? "Pregnant" : "Lactating"}</strong>{safety.pregnancy?.expectedDeliveryDate ? ` · due ${safety.pregnancy.expectedDeliveryDate}` : ""}</p>}
      {preg === "unknown" && <p className={styles.stripLine}>Pregnancy status: unknown</p>}
      {preg === "not_pregnant" && <p className={styles.stripLine}>Not pregnant (recorded)</p>}
    </section>
  );
}
