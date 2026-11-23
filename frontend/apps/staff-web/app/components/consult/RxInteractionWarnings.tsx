"use client";

import { useTranslations } from "next-intl";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { formatClinicDate, formatClinicTime } from "../../lib/clinicTime";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { WARNING_TITLE_ID } from "./RxWarnings";
import { interactionCards, lineNotChecked } from "./rx/interactionWarnings";
import type { PrescriptionItem, RxWarning } from "./shared";
import s from "./consultParts.module.css";
import x from "./RxInteractionWarnings.module.css";

const TONE = { danger: s.noticeDanger, info: s.noticeInfo, warn: s.noticeWarn } as const;
const KIND_ICON = { ddi: "bolt", duplicate: "plusSquare" } as const;

/** CLN-032: the drug-drug interaction and duplicate-therapy cards of one prescription line: kind icon, severity, the
 * other medicine, and a "Why?" expander with the knowledge base's mechanism, advice and source. Contraindicated and major
 * ones use the same decision flow as the other warnings (change the medicine, or override with a named reason); moderate
 * and minor ones are advisory. Everything shown is the server's answer. There is deliberately NO "no interactions" state:
 * with no knowledge base this renders nothing, and a free-text line that could not be checked says so. */
export default function RxInteractionWarnings({
  item, locked, onOverride, onChangeMedicine, onRemove,
}: {
  item: PrescriptionItem; locked: boolean;
  /** Absent for someone who may not record a decision: the button is then not rendered. */
  onOverride?: (w: RxWarning) => void; onChangeMedicine: () => void; onRemove?: () => void;
}) {
  const t = useTranslations("consult.rxInteractions");
  const tw = useTranslations("consult.rxWarnings");
  const g = useTranslations("glossary");
  const tz = useClinicTimeZone();
  const cards = interactionCards(item);
  const notChecked = lineNotChecked(item);
  if (cards.length === 0 && !notChecked) return null;
  return (
    <div className={s.warnStack} data-testid="rx-interaction-warnings">
      {cards.map(({ key, warning: w, tone, open, advisory, severityId, other, otherLine, hasWhy }) => (
        <div key={key} className={`${s.notice} ${TONE[tone]} ${s.warnCard}`} role={open ? "alert" : "status"}>
          <Icon name={KIND_ICON[w.kind === "ddi" ? "ddi" : "duplicate"]} size={16} />
          <div className={s.warnBody}>
            <strong>{g(WARNING_TITLE_ID[w.kind])} · {severityId ? g(severityId) : w.severity}{advisory && <> {g("warning.advisory")}</>}</strong>
            <span>{t(`with.${other}`, { drug: w.otherDrug ?? "", line: otherLine ?? 0 })} · {w.matched}</span>
            {w.totalDailyDose && <span>{t("totalDailyDose", { dose: w.totalDailyDose })}</span>}
            {hasWhy && (
              <details className={x.why}>
                <summary>{t("why")}</summary>
                <dl className={x.whyBody}>
                  {w.mechanism && <><dt>{t("mechanism")}</dt><dd>{w.mechanism}</dd></>}
                  {w.management && <><dt>{t("management")}</dt><dd>{w.management}</dd></>}
                  {(w.source || w.sourceRef) && <><dt>{t("source")}</dt><dd>{w.source}{w.source && w.sourceRef && <> · </>}{w.sourceRef}</dd></>}
                </dl>
              </details>
            )}
            {w.decision && (
              <span>
                <StatusPill tone="info" icon="check" label={g("warning.decisionRecorded")} />{" "}
                {w.decision.reasonLabel ?? w.decision.reasonCode}{w.decision.note ? ` — ${w.decision.note}` : ""}
                {w.decision.byName ? ` · ${w.decision.byName}` : ""}{w.decision.at ? ` · ${formatClinicDate(w.decision.at, { day: "2-digit", month: "short" }, tz)} ${formatClinicTime(w.decision.at, tz)}` : ""}
              </span>
            )}
            {open && !locked && onOverride && <span className={s.muted}>{tw("anOverrideLogsTheWarning")}</span>}
            {open && !locked && (
              <span className={s.row}>
                <button type="button" className={s.btn} onClick={onChangeMedicine}>{tw("changeMedicine")}</button>
                {onOverride && <button type="button" className={s.btn} onClick={() => onOverride(w)}>{tw("overrideWithAClinicalReason")}</button>}
                {onRemove && <button type="button" className={s.btn} onClick={onRemove}>{tw("removeMedicine")}</button>}
              </span>
            )}
            {w.decision && !locked && onOverride && (
              <span><button type="button" className={s.linkBtn} onClick={() => onOverride(w)}>{tw("changeReason")}</button></span>
            )}
          </div>
        </div>
      ))}
      {notChecked && (
        <p className={`${s.muted} ${x.notChecked}`} role="note" data-testid="rx-interaction-not-checked">
          <Icon name="info" size={16} />
          <span>{t("notChecked")}</span>
        </p>
      )}
    </div>
  );
}
