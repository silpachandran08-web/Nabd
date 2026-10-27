"use client";

import { useTranslations } from "next-intl";
import Icon, { type IconName } from "../../ui/Icon";
import { STEP_IDS, type Step } from "./consultWorkflow";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

// Step rail and the one-line note for the current step (wireframe cwSteps / cwStepNote).
// The four clinical step names are glossary terms (soap.*); "close" and every hint are plain UI text (catalogue consult.stepRail).
export const STEPS: { id: Step; icon: IconName }[] = [
  { id: "reason", icon: "inbox" },
  { id: "examine", icon: "stethoscope" },
  { id: "assess", icon: "list" },
  { id: "plan", icon: "pulse" },
  { id: "close", icon: "check" },
];
if (STEPS.length !== STEP_IDS.length) throw new Error("STEPS and STEP_IDS drifted apart");

export default function StepRail({ step, onGoto }: { step: Step; onGoto: (s: Step) => void }) {
  const tx = useTranslations("consult.stepRail");
  const g = useTranslations("glossary");
  const stepIndex = Math.max(0, STEPS.findIndex((x) => x.id === step));
  return (
    <nav aria-label={tx("consultationSteps")}>
      <ol className={styles.steps}>
        {STEPS.map((s, i) => {
          const done = i < stepIndex;
          return (
            <li key={s.id}>
              <button type="button" className={`${styles.stepBtn} ${step === s.id ? styles.stepBtnOn : done ? styles.stepBtnDone : ""}`} aria-current={step === s.id ? "step" : undefined} onClick={() => onGoto(s.id)}>
                <Icon name={done ? "check" : s.icon} size={14} />
                <span className={styles.stepName}>{i + 1}. {s.id === "close" ? tx("close") : g(`soap.${s.id}`)}</span>
                {done && <span className={p.srOnly}>{tx("earlierStep")}</span>}
              </button>
            </li>
          );
        })}
      </ol>
      <p className={styles.stepHint}>{tx(`hint.${STEPS[stepIndex].id}`)}</p>
    </nav>
  );
}
