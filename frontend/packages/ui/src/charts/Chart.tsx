"use client";

import { useCallback, useId, useMemo, useState, type KeyboardEvent, type ReactNode } from "react";
import { Icon, type IconName } from "../Icon.tsx";
import { EmptyState, ErrorState, Skeleton } from "../feedback.tsx";
import { barPath, chartTable, dataState, limitSeries, niceScale, segments, toCsv, type ChartKind, type ChartSeries } from "./model.ts";

// FEF-026 chart kit. Plain SVG (prints, no canvas), colours from --nb-chart-1..6, text in text tokens (never the series colour).
// Every chart has a legend (2+ series), a pointer and keyboard readout, a table view and a CSV download.

/** Every string the kit renders. English defaults; pass translated ones through `labels`. `{count}` is replaced. */
export const CHART_LABELS = {
  table: "Table",
  csv: "Download CSV",
  loading: "Loading chart",
  empty: "No data for this period",
  error: "Could not load this chart",
  retry: "Retry",
  noPermission: "You do not have access to this data",
  other: "Other",
  tooMany: "{count} smaller series are grouped as Other. The table lists every series.",
  allZero: "Every value is zero.",
  singlePoint: "Only one data point so far.",
  keys: "Use the arrow keys to read the values.",
  noValue: "no data",
};
export type ChartLabels = typeof CHART_LABELS;

export type ChartProps = {
  kind: ChartKind;
  /** Visible title and accessible name. */
  title: string;
  /** Axis labels, already formatted for the reader (dates in the clinic timezone, names). */
  categories: string[];
  /** Colour follows the position in this array (slot 1..6): keep the order stable when a filter changes. */
  series: ChartSeries[];
  /** The categories are points in time: drawn left to right in every direction, never mirrored in RTL. Default: true for line and area. */
  time?: boolean;
  /** Set while there is no data to draw. `noPermission` renders nothing from `series`. */
  status?: "loading" | "error" | "noPermission";
  /** The data is old: the text to show, for example "Updated at 06:00". */
  stale?: string;
  /** Some of the data is missing: the text to show. Missing values themselves are `null` in `values`. */
  partial?: string;
  /** Header of the first table / CSV column. */
  categoryLabel?: string;
  /** Formats axis ticks, labels, the readout and table cells (use formatNumber / formatMoney from @nabd/i18n). The CSV keeps raw numbers. */
  formatValue?: (value: number) => string;
  /** Line only: fit the axis to the data instead of starting at zero (a blood-pressure or weight trend). */
  fitY?: boolean;
  /** false removes the CSV button (no export permission, or the screen has its own audited export). */
  csv?: boolean;
  /** Download name without ".csv". Default: derived from the title. */
  fileName?: string;
  onRetry?: () => void;
  /** Plot height in px for column, line and area (a bar chart grows with its rows). */
  height?: number;
  labels?: Partial<ChartLabels>;
};

const plain = (n: number) => String(Math.round(n * 100) / 100);
const textWidth = (s: string) => s.length * 7; // 12px tabular text, estimated: nothing is measured, so the server render matches
const num = (s: ChartSeries, i: number) => { const v = s.values[i]; return v != null && Number.isFinite(v) ? v : null; };

function downloadCsv(name: string, csv: string) {
  const url = URL.createObjectURL(new Blob(["\uFEFF", csv], { type: "text/csv;charset=utf-8" })); // BOM: Excel reads UTF-8 names
  const a = document.createElement("a");
  a.href = url;
  a.download = `${name}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

export function Chart(props: ChartProps) {
  const { kind, title, categories, status, stale, partial, categoryLabel = "Category", formatValue = plain, csv = true, onRetry } = props;
  const L = { ...CHART_LABELS, ...props.labels };
  const titleId = useId();
  const [table, setTable] = useState(false);
  const { shown, grouped } = useMemo(() => limitSeries(props.series, L.other), [props.series, L.other]);
  const data = dataState(categories, props.series);
  const state = status ?? (data !== "ok" ? data : grouped ? "tooManySeries" : partial ? "partial" : stale ? "stale" : "ok");
  const drawn = !status && data !== "empty";
  const time = props.time ?? (kind === "line" || kind === "area");
  const notes = (drawn ? [["clock", stale], ["info", partial], ["info", grouped ? L.tooMany.replace("{count}", String(grouped)) : ""],
    ["info", data === "allZero" ? L.allZero : ""], ["info", data === "singlePoint" ? L.singlePoint : ""]] : []) as [IconName, string | undefined][];

  let body: ReactNode;
  if (status === "loading") body = <Skeleton rows={4} label={L.loading} />;
  else if (status === "error") body = <ErrorState message={L.error} onRetry={onRetry} retryLabel={L.retry} />;
  else if (status === "noPermission") body = <EmptyState icon="lock" title={L.noPermission} />;
  else if (!drawn) body = <EmptyState title={L.empty} />;
  else if (table) body = <ChartTable title={title} titleId={titleId} categoryLabel={categoryLabel} categories={categories} series={props.series} format={formatValue} />;
  else {
    body = (
      <>
        {shown.length > 1 && (
          <ul className="nbu-chart-legend">
            {shown.map((s, j) => <li key={s.id}><span className={`nbu-chart-key nbu-chart-s${j + 1}`} aria-hidden="true" />{s.label}</li>)}
          </ul>
        )}
        <Plot kind={kind} title={title} categories={categories} series={shown} time={time} format={formatValue} fitY={kind === "line" && !!props.fitY} height={props.height ?? 240} labels={L} />
      </>
    );
  }

  return (
    <div className="nbu-chart" role="group" aria-labelledby={titleId} data-kind={kind} data-state={state}>
      <div className="nbu-chart-head">
        <div id={titleId} className="nbu-chart-title">{title}</div>
        {drawn && (
          <div className="nbu-chart-tools">
            <button type="button" className="nbu-btn nbu-btn-ghost" aria-pressed={table} aria-label={`${L.table}: ${title}`} onClick={() => setTable(!table)}>
              <Icon name="list" size={14} /> {L.table}
            </button>
            {csv && (
              <button type="button" className="nbu-btn nbu-btn-ghost" aria-label={`${L.csv}: ${title}`}
                onClick={() => downloadCsv(props.fileName ?? (title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "chart"), toCsv(categoryLabel, categories, props.series))}>
                {L.csv}
              </button>
            )}
          </div>
        )}
      </div>
      {notes.map(([icon, text]) => text && <p key={text} className="nbu-chart-note"><Icon name={icon} size={14} /> {text}</p>)}
      {body}
    </div>
  );
}

/** The table view: a real <table> with every series (also those the chart grouped into "Other"). Internal; exported for tests. */
export function ChartTable({ title, titleId, categoryLabel, categories, series, format }: { title: string; titleId: string; categoryLabel: string; categories: string[]; series: ChartSeries[]; format: (n: number) => string }) {
  const t = chartTable(categoryLabel, categories, series, format);
  return (
    <div className="nbu-tablewrap nbu-chart-tablewrap" role="region" aria-labelledby={titleId} tabIndex={0}>
      <table className="nbu-chart-table">
        <caption className="nbu-vh">{title}</caption>
        <thead><tr>{t.head.map((h, i) => <th key={i} scope="col">{h}</th>)}</tr></thead>
        <tbody>{t.rows.map((r, i) => <tr key={i}><th scope="row">{r[0]}</th>{r.slice(1).map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}

type PlotProps = { kind: ChartKind; title: string; categories: string[]; series: ChartSeries[]; time: boolean; format: (n: number) => string; fitY: boolean; height: number; labels: ChartLabels };

function Plot({ kind, title, categories, series, time, format, fitY, height, labels }: PlotProps) {
  const [w, setW] = useState(640);
  // The plot is drawn in real pixels (text never scales down on a phone): follow the container width.
  const measure = useCallback((el: HTMLDivElement | null) => {
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(240, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const [active, setActive] = useState<number | null>(null);

  const n = categories.length, k = series.length, across = kind === "bar";
  const values = series.flatMap((s) => categories.map((_, i) => num(s, i))).filter((v): v is number => v != null);
  const scale = niceScale(Math.min(...values), Math.max(...values), 4, fitY);
  const ticks = scale.ticks.map((t) => ({ t, text: format(t) }));
  const direct = k === 1 && n <= 12; // direct labels only where they cannot collide; the readout and the table carry the rest
  const valueText = direct ? Math.max(...values.map((v) => textWidth(format(v)))) : 0;
  const band = across ? Math.max(32, k * 12 + 8) : 0;
  const m = across
    ? { t: 8, r: 16 + valueText, b: 28, l: Math.min(Math.max(...categories.map(textWidth)), Math.round(w * 0.35)) + 12 }
    : { t: 24, r: 16, b: 28, l: Math.max(...ticks.map((x) => textWidth(x.text))) + 12 };
  const h = across ? m.t + n * band + m.b : height;
  const plotW = w - m.l - m.r, plotH = h - m.t - m.b;
  const span = scale.max - scale.min;
  const at = (v: number) => (across ? m.l + (plotW * (v - scale.min)) / span : m.t + plotH * (1 - (v - scale.min) / span));
  const base = at(Math.min(Math.max(0, scale.min), scale.max));
  const slot = across ? band : plotW / n;
  const start = (i: number) => (across ? m.t : m.l) + slot * i;
  const mid = (i: number) => start(i) + slot / 2;
  const thick = Math.max(1, Math.min(24, (slot * (across ? 1 : 0.8) - (across ? 8 : 0) - 2 * (k - 1)) / k));
  const first = (i: number) => mid(i) - (k * thick + 2 * (k - 1)) / 2;
  const every = across ? 1 : Math.max(1, Math.ceil((Math.max(...categories.map(textWidth)) + 12) / slot));
  const clip = Math.max(2, Math.floor((m.l - 12) / 7));

  const onKey = (e: KeyboardEvent<SVGSVGElement>) => {
    // In RTL a categorical plot is mirrored, so "next" is the left arrow there; a time plot is always dir=ltr.
    const rtl = getComputedStyle(e.currentTarget.parentElement!).direction === "rtl"; // the svg itself is always direction: ltr
    const next = across ? "ArrowDown" : rtl ? "ArrowLeft" : "ArrowRight", prev = across ? "ArrowUp" : rtl ? "ArrowRight" : "ArrowLeft";
    const to = e.key === next ? Math.min(n - 1, (active ?? -1) + 1) : e.key === prev ? Math.max(0, (active ?? 1) - 1)
      : e.key === "Home" ? 0 : e.key === "End" ? n - 1 : e.key === "Escape" && active != null ? null : undefined;
    if (to === undefined) return;
    e.preventDefault();
    setActive(to);
  };

  const marks = series.map((s, j) => {
    const cls = `nbu-chart-s${j + 1}`;
    if (kind === "column" || kind === "bar") {
      return (
        <g key={s.id} className={cls}>
          {categories.map((_, i) => { const v = num(s, i); return v == null ? null : <path key={i} className="nbu-chart-fill" d={barPath(across, first(i) + j * (thick + 2), thick, base, at(v))} />; })}
        </g>
      );
    }
    const runs = segments(categories.map((_, i) => { const v = num(s, i); return v == null ? null : { x: mid(i), y: at(v) }; }));
    const last = runs.length ? runs[runs.length - 1][runs[runs.length - 1].length - 1] : null;
    return (
      <g key={s.id} className={cls}>
        {runs.map((r, q) => {
          const line = r.map((p, i) => `${i ? "L" : "M"}${p.x},${p.y}`).join("");
          return (
            <g key={q}>
              {kind === "area" && r.length > 1 && <path className="nbu-chart-area" d={`${line}L${r[r.length - 1].x},${base}L${r[0].x},${base}Z`} />}
              {r.length > 1 ? <path className="nbu-chart-line" d={line} /> : <circle className="nbu-chart-dot" cx={r[0].x} cy={r[0].y} r={4} />}
            </g>
          );
        })}
        {last && <circle className="nbu-chart-dot" cx={last.x} cy={last.y} r={4} />}
        {active != null && num(s, active) != null && <circle className="nbu-chart-dot" cx={mid(active)} cy={at(num(s, active)!)} r={5} />}
      </g>
    );
  });

  const labelsOnMarks = direct && series[0] ? categories.map((_, i) => {
    const v = num(series[0], i);
    if (v == null) return null;
    const text = format(v), p = at(v);
    if (across) return <text key={i} className="nbu-chart-value" x={p + (v < 0 ? -6 : 6)} y={mid(i)} textAnchor={v < 0 ? "end" : "start"} dominantBaseline="central">{text}</text>;
    if (kind === "column") return textWidth(text) > slot ? null : <text key={i} className="nbu-chart-value" x={mid(i)} y={p + (v < 0 ? 14 : -6)} textAnchor="middle">{text}</text>;
    const lastIndex = series[0].values.reduce<number>((a, x, q) => (q < n && x != null ? q : a), -1);
    return i === lastIndex ? <text key={i} className="nbu-chart-value" x={mid(i)} y={p - 10} textAnchor="middle">{text}</text> : null;
  }) : null;

  const readout = active == null ? null : series.map((s) => ({ s, v: num(s, active) }));
  const tipAt = active == null ? undefined : across ? { insetBlockStart: start(active), insetInlineEnd: 8 }
    : active < n / 2 ? { insetBlockStart: 0, insetInlineStart: mid(active) + 12 } : { insetBlockStart: 0, insetInlineEnd: w - mid(active) + 12 };

  return (
    <div ref={measure} className="nbu-chart-plot" dir={time ? "ltr" : undefined} data-time={time ? "" : undefined}>
      <svg className={time ? "nbu-chart-svg" : "nbu-chart-svg nbu-chart-flip"} viewBox={`0 0 ${w} ${h}`} role="img" aria-label={`${title}. ${labels.keys}`} tabIndex={0}
        onKeyDown={onKey} onBlur={() => setActive(null)} onPointerLeave={() => setActive(null)}>
        {ticks.map(({ t, text }) => across
          ? <g key={t}><line className="nbu-chart-grid" x1={at(t)} x2={at(t)} y1={m.t} y2={h - m.b} /><text className="nbu-chart-tick" x={at(t)} y={h - 8} textAnchor="middle">{text}</text></g>
          : <g key={t}><line className="nbu-chart-grid" x1={m.l} x2={w - m.r} y1={at(t)} y2={at(t)} /><text className="nbu-chart-tick" x={m.l - 8} y={at(t)} textAnchor="end" dominantBaseline="central">{text}</text></g>)}
        {active != null && (kind === "line" || kind === "area"
          ? <line className="nbu-chart-cross" x1={mid(active)} x2={mid(active)} y1={m.t} y2={h - m.b} />
          : <rect className="nbu-chart-band" x={across ? m.l : start(active)} y={across ? start(active) : m.t} width={across ? plotW : slot} height={across ? slot : plotH} />)}
        {marks}
        {across ? <line className="nbu-chart-axis" x1={base} x2={base} y1={m.t} y2={h - m.b} /> : <line className="nbu-chart-axis" x1={m.l} x2={w - m.r} y1={base} y2={base} />}
        {labelsOnMarks}
        {categories.map((c, i) => across
          ? <text key={i} className="nbu-chart-tick" x={m.l - 8} y={mid(i)} textAnchor="end" dominantBaseline="central">{c.length > clip ? `${c.slice(0, clip - 1)}…` : c}</text>
          : i % every === 0 && <text key={i} className="nbu-chart-tick" x={mid(i)} y={h - 8} textAnchor="middle">{c}</text>)}
        {categories.map((_, i) => (
          <rect key={i} className="nbu-chart-hit" x={across ? 0 : start(i)} y={across ? start(i) : 0} width={across ? w : slot} height={across ? slot : h} onPointerEnter={() => setActive(i)} />
        ))}
      </svg>
      {readout && (
        <div className="nbu-chart-tip" style={tipAt} aria-hidden="true" dir="auto">
          <div className="nbu-chart-tip-title">{categories[active!]}</div>
          {readout.map(({ s, v }, j) => (
            <div key={s.id} className="nbu-chart-tip-row"><span className={`nbu-chart-key nbu-chart-s${j + 1}`} /><strong>{v == null ? "–" : format(v)}</strong>{k > 1 && <span>{s.label}</span>}</div>
          ))}
        </div>
      )}
      <div className="nbu-vh" role="status">
        {readout && `${categories[active!]}: ${readout.map(({ s, v }) => `${k > 1 ? `${s.label} ` : ""}${v == null ? labels.noValue : format(v)}`).join(", ")}`}
      </div>
    </div>
  );
}
