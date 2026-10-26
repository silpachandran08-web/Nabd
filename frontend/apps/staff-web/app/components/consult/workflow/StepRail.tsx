"use client";

import Icon, { type IconName } from "../../ui/Icon";
import { STEP_IDS, type Step } from "./consultWorkflow";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

// Step rail and the one-line note for the current step (wireframe cwSteps / cwStepNote).
export const STEPS: { id: Step; label: string; hint: string; icon: IconName }[] = [
  { id: "reason", label: "Reason", hint: "Why they came — read, don't type.", icon: "inbox" },
  { id: "examine", label: "Examine", hint: "Specialty tools live here, inside the consult — no second screen.", icon: "stethoscope" },
  { id: "assess", label: "Assess", hint: "Two or three lines. Quick phrases cover most of it.", icon: "list" },
  { id: "plan", label: "Plan", hint: "Prescribe, order, follow up — actions only.", icon: "pulse" },
  { id: "close", label: "Close", hint: "What stays open after this visit.", icon: "check" },
];
if (STEPS.length !== STEP_IDS.length) throw new Error("STEPS and STEP_IDS drifted apart");

export default function StepRail({ step, onGoto }: { step: Step; onGoto: (s: Step) => void }) {
  const stepIndex = Math.max(0, STEPS.findIndex((x) => x.id === step));
  return (
    <nav aria-label="Consultation steps">
      <ol className={styles.steps}>
        {STEPS.map((s, i) => {
          const done = i < stepIndex;
          return (
            <li key={s.id}>
              <button type="button" className={`${styles.stepBtn} ${step === s.id ? styles.stepBtnOn : done ? styles.stepBtnDone : ""}`} aria-current={step === s.id ? "step" : undefined} onClick={() => onGoto(s.id)}>
                <Icon name={done ? "check" : s.icon} size={14} />
                <span className={styles.stepName}>{i + 1}. {s.label}</span>
                {done && <span className={p.srOnly}>(earlier step)</span>}
              </button>
            </li>
          );
        })}
      </ol>
      <p className={styles.stepHint}>{STEPS[stepIndex].hint}</p>
    </nav>
  );
}
