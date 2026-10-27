"use client";

import { useTranslations } from "next-intl";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import type { Part } from "./clinical";
import type { Vitals } from "./shared";
import s from "./consultParts.module.css";

const KEYS: { id: string; label: string; match: string[]; text: (v: Vitals) => string | null }[] = [
  { id: "bp", label: "vitals.bp", match: ["bp", "systolic", "diastolic", "blood"], text: (v) => (v.bpSystolic != null && v.bpDiastolic != null ? `${v.bpSystolic}/${v.bpDiastolic} mmHg` : null) },
  { id: "pulse", label: "vitals.pulse", match: ["pulse", "heart"], text: (v) => (v.pulseBpm != null ? `${v.pulseBpm} bpm` : null) },
  { id: "temp", label: "vitals.temp", match: ["temp"], text: (v) => (v.tempCelsius != null ? `${v.tempCelsius} °C` : null) },
  { id: "spo2", label: "vitals.spo2", match: ["spo2", "oxygen", "saturation"], text: (v) => (v.spo2Percent != null ? `${v.spo2Percent}%` : null) },
  { id: "weight", label: "vitals.weight", match: ["weight"], text: (v) => (v.weightKg != null ? `${v.weightKg} kg` : null) },
  { id: "height", label: "vitals.height", match: ["height"], text: (v) => (v.heightCm != null ? `${v.heightCm} cm` : null) },
];

/** Latest vitals for the visit. Out-of-range values (abnormalFlags from the server) carry an icon
 * and the flag text, never colour alone; every server flag is also listed verbatim. */
export default function VitalsCard({ vitals, onRetry }: { vitals: Part<Vitals>; onRetry: () => void }) {
  const tx = useTranslations("consult.vitalsCard");
  const g = useTranslations("glossary");
  const flags = vitals.data?.abnormalFlags ?? [];
  const flagged = (match: string[]) => flags.some((f) => match.some((m) => f.toLowerCase().includes(m)));

  return (
    <section className={s.card} aria-label={g("vitals.label")}>
      <h3 className={s.cardTitle}>
        {g("vitals.label")}
        {vitals.status === "ok" && (flags.length > 0 ? <StatusPill tone="danger" label={tx("outOfRange")} /> : <StatusPill tone="ok" label={tx("inRange")} />)}
        {vitals.status === "none" && <StatusPill tone="warn" label={tx("notRecordedYet")} />}
      </h3>
      {vitals.status === "loading" && <Skeleton rows={2} label={tx("loadingVitals")} />}
      {vitals.status === "none" && <p className={s.muted}>{tx("vitalsHaveNotBeenRecorded")}</p>}
      {vitals.status === "error" && (
        <div className={`${s.notice} ${s.noticeDanger}`} role="alert">
          <Icon name="alert" size={16} />
          <span>{tx("couldnTLoadVitals")}{" "}<button type="button" className={s.linkBtn} onClick={onRetry}>{tx("retry")}</button></span>
        </div>
      )}
      {vitals.status === "ok" && vitals.data && (
        <>
          <dl className={s.vitalsGrid}>
            {KEYS.map((k) => {
              const t = k.text(vitals.data!);
              if (!t) return null;
              const bad = flagged(k.match);
              return (
                <div key={k.id} className={`${s.vital} ${bad ? s.vitalFlag : ""}`}>
                  <dt>{g(k.label)}</dt>
                  <dd>{bad && <Icon name="alert" size={14} label={tx("outOfRange")} />}{t}</dd>
                </div>
              );
            })}
          </dl>
          {flags.length > 0 && <p className={s.muted}>{tx("flagged")}{" "}{flags.join(", ")}</p>}
          {vitals.data.recordedAt && <p className={s.muted}>{tx("recorded")}{" "}{new Date(vitals.data.recordedAt).toLocaleString()}</p>}
        </>
      )}
    </section>
  );
}
