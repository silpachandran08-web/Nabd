"use client";

import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import type { Part } from "./clinical";
import type { Vitals } from "./shared";
import s from "./consultParts.module.css";

const KEYS: { id: string; label: string; match: string[]; text: (v: Vitals) => string | null }[] = [
  { id: "bp", label: "BP", match: ["bp", "systolic", "diastolic", "blood"], text: (v) => (v.bpSystolic != null && v.bpDiastolic != null ? `${v.bpSystolic}/${v.bpDiastolic} mmHg` : null) },
  { id: "pulse", label: "Pulse", match: ["pulse", "heart"], text: (v) => (v.pulseBpm != null ? `${v.pulseBpm} bpm` : null) },
  { id: "temp", label: "Temp", match: ["temp"], text: (v) => (v.tempCelsius != null ? `${v.tempCelsius} °C` : null) },
  { id: "spo2", label: "SpO₂", match: ["spo2", "oxygen", "saturation"], text: (v) => (v.spo2Percent != null ? `${v.spo2Percent}%` : null) },
  { id: "weight", label: "Weight", match: ["weight"], text: (v) => (v.weightKg != null ? `${v.weightKg} kg` : null) },
  { id: "height", label: "Height", match: ["height"], text: (v) => (v.heightCm != null ? `${v.heightCm} cm` : null) },
];

/** Latest vitals for the visit. Out-of-range values (abnormalFlags from the server) carry an icon
 * and the flag text, never colour alone; every server flag is also listed verbatim. */
export default function VitalsCard({ vitals, onRetry }: { vitals: Part<Vitals>; onRetry: () => void }) {
  const flags = vitals.data?.abnormalFlags ?? [];
  const flagged = (match: string[]) => flags.some((f) => match.some((m) => f.toLowerCase().includes(m)));

  return (
    <section className={s.card} aria-label="Vitals">
      <h3 className={s.cardTitle}>
        Vitals
        {vitals.status === "ok" && (flags.length > 0 ? <StatusPill tone="danger" label="Out of range" /> : <StatusPill tone="ok" label="In range" />)}
        {vitals.status === "none" && <StatusPill tone="warn" label="Not recorded yet" />}
      </h3>
      {vitals.status === "loading" && <Skeleton rows={2} label="Loading vitals" />}
      {vitals.status === "none" && <p className={s.muted}>Vitals have not been recorded for this visit. Ask nursing, or examine before prescribing.</p>}
      {vitals.status === "error" && (
        <div className={`${s.notice} ${s.noticeDanger}`} role="alert">
          <Icon name="alert" size={16} />
          <span>Couldn&apos;t load vitals. <button type="button" className={s.linkBtn} onClick={onRetry}>Retry</button></span>
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
                  <dt>{k.label}</dt>
                  <dd>{bad && <Icon name="alert" size={14} label="Out of range" />}{t}</dd>
                </div>
              );
            })}
          </dl>
          {flags.length > 0 && <p className={s.muted}>Flagged: {flags.join(", ")}</p>}
          {vitals.data.recordedAt && <p className={s.muted}>Recorded {new Date(vitals.data.recordedAt).toLocaleString()}</p>}
        </>
      )}
    </section>
  );
}
