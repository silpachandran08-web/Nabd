import { useTranslations } from "next-intl";
import type { DuplicateCandidate } from "@nabd/api-client/types";
import styles from "../arrivals.module.css";
import StatusPill from "../../ui/StatusPill";
import { matchPercent } from "../board/boardLogic";

// REC-0xx duplicate review: the receptionist picks an existing record or confirms a different person.
export default function DuplicateReview({ candidates, submitting, onUse, onDifferent }: {
  candidates: DuplicateCandidate[]; submitting: boolean; onUse: (c: DuplicateCandidate) => void; onDifferent: () => void;
}) {
  const t = useTranslations("arrivals.dup");
  if (candidates.length === 0) return null;
  return (
    <div className={styles.matchList} role="group" aria-label={t("group")}>
      {candidates.map((c) => (
        <div key={c.patientId} className={styles.matchRow}>
          <span>
            <span className={styles.patientName}>{c.name}</span>
            <span className={styles.mrn}>{c.phone}</span>
          </span>
          <StatusPill tone={matchPercent(c.matchScore) >= 85 ? "danger" : "warn"} icon="alert" label={t("match", { pct: matchPercent(c.matchScore) })} />
          <button type="button" className={styles.actionBtn} onClick={() => onUse(c)}>{t("use")}</button>
        </div>
      ))}
      <button type="button" className={styles.cancelBtn} disabled={submitting} onClick={onDifferent}>{t("different")}</button>
    </div>
  );
}
