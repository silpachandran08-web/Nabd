import { useId, type ReactNode } from "react";
import { Icon } from "../Icon.tsx";
import { segments } from "./model.ts";

// FEF-026 figures: when the answer is one number, not a plot. No hover layer; each one is fully readable as text.

/** A small trend line. Always left to right (it is a time series); `label` is its text alternative, for example "Rising over 7 days". */
export function Sparkline({ values, label, width = 96, height = 28 }: { values: (number | null)[]; label: string; width?: number; height?: number }) {
  const present = values.filter((v): v is number => v != null && Number.isFinite(v));
  if (!present.length) return null;
  const lo = Math.min(...present), span = Math.max(...present) - lo || 1, step = values.length > 1 ? (width - 8) / (values.length - 1) : 0;
  const runs = segments(values.map((v, i) => (v == null || !Number.isFinite(v) ? null : { x: 4 + i * step, y: 4 + (height - 8) * (1 - (v - lo) / span) })));
  const last = runs[runs.length - 1][runs[runs.length - 1].length - 1];
  return (
    <svg className="nbu-spark" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}>
      {runs.map((r, q) => r.length > 1 && <path key={q} className="nbu-spark-line" d={r.map((p, i) => `${i ? "L" : "M"}${p.x},${p.y}`).join("")} />)}
      <circle className="nbu-spark-dot" cx={last.x} cy={last.y} r={3} />
    </svg>
  );
}

/** A value against a maximum. `valueText` is what is shown and read out (default "value / max"), so the tone is never colour alone. */
export function Meter({ label, value, max = 100, valueText, tone = "neutral" }: { label: string; value: number; max?: number; valueText?: string; tone?: "neutral" | "ok" | "warn" | "danger" }) {
  const id = useId();
  const text = valueText ?? `${value} / ${max}`;
  const share = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  return (
    <div className="nbu-meter" role="meter" aria-labelledby={id} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value} aria-valuetext={text}>
      <div className="nbu-meter-head"><span id={id}>{label}</span><span className="nbu-meter-value">{text}</span></div>
      <svg className="nbu-meter-bar nbu-chart-flip" width="100%" height={8} aria-hidden="true">
        <rect className="nbu-meter-track" width="100%" height={8} rx={4} />
        <rect className={`nbu-meter-fill nbu-meter-${tone}`} width={`${share * 100}%`} height={8} rx={4} />
      </svg>
    </div>
  );
}

/** `text` must say the whole thing ("4 min faster than last week"); the arrow and the tone only reinforce it. */
export type StatDelta = { text: string; direction: "up" | "down" | "flat"; tone?: "better" | "worse" | "neutral" };

/** One headline number with an optional change and trend. Wrap it in a link for tap-through; it renders no control itself. */
export function StatTile({ label, value, delta, trend, trendLabel, hint }: { label: string; value: ReactNode; delta?: StatDelta; trend?: (number | null)[]; trendLabel?: string; hint?: string }) {
  return (
    <div className="nbu-kpi nbu-stat">
      <div className="nbu-kpi-label">{label}</div>
      <div className="nbu-stat-row">
        <div className="nbu-kpi-value">{value}</div>
        {trend && <Sparkline values={trend} label={trendLabel ?? label} />}
      </div>
      {delta && (
        <div className={`nbu-stat-delta nbu-stat-${delta.tone ?? "neutral"}`}>
          {delta.direction !== "flat" && <Icon name={delta.direction === "up" ? "arrowUp" : "arrowDown"} size={14} />}
          {delta.text}
        </div>
      )}
      {hint && <div className="nbu-hint">{hint}</div>}
    </div>
  );
}
