"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { formatMoney } from "@nabd/i18n";
import { Chart, Meter, StatTile } from "@nabd/ui";
import styles from "./flowOverview.module.css";
import { api, ApiError } from "../../lib/api";
import { can } from "../../lib/permissions";
import { EmptyState } from "../../components/ui/StateViews";
import {
  BUSINESS_TILES, RANGES, TILE_HINTS, TILE_LABELS, TIME_TILES, deltaOf, etaSeries, reportHref, splitRows, targetMissed, targetText, trendText,
  valueText, waitByHourSeries, type FlowOverview, type FlowRange, type FlowTile,
} from "./flowOverviewModel";

type State = { kind: "loading" } | { kind: "error" } | { kind: "forbidden" } | { kind: "ready"; data: FlowOverview };

/** ADM-041: time KPIs, money row and charts, all from rollups refreshed by 06:00 local. Owner overview; reports:view only. */
export default function FlowOverviewSection() {
  const [range, setRange] = useState<FlowRange>("today");
  const [state, setState] = useState<State>({ kind: "loading" });

  const load = useCallback(async (r: FlowRange) => {
    setState((s) => (s.kind === "ready" ? s : { kind: "loading" })); // a refetch keeps the figures on screen
    try {
      setState({ kind: "ready", data: await api.get<FlowOverview>(`/reports/overview/flow?range=${r}`) });
    } catch (e) {
      setState(e instanceof ApiError && e.status === 403 ? { kind: "forbidden" } : { kind: "error" });
    }
  }, []);
  useEffect(() => { void Promise.resolve().then(() => load(range)); }, [load, range]);

  if (!can("reports:view") || state.kind === "forbidden") return null; // absent, not disabled

  const data = state.kind === "ready" ? state.data : null;
  const money = (n: number) => formatMoney(n, data?.currency ?? "INR");
  const tile = (code: string) => data?.tiles.find((t) => t.code === code);
  const refreshed = data?.refreshedAt ? new Date(data.refreshedAt).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : null;
  const hours = data ? waitByHourSeries(data.waitByHour) : null;
  const eta = data ? etaSeries(data.etaAccuracy14d) : null;
  const chartStatus = state.kind === "loading" ? "loading" : state.kind === "error" ? "error" : undefined;
  const retry = () => void load(range);
  const stale = refreshed ? undefined : data ? "Not refreshed yet. Figures appear after the first full clinic day." : undefined;
  const csv = can("reports:export");

  const renderTile = (code: string, t: FlowTile | undefined) => {
    if (!data || !t) return <div key={code} className={styles.tileSkel} aria-hidden="true" />;
    const missed = targetMissed(t);
    return (
      <Link key={code} href={reportHref(code, data)} className={styles.tileLink} data-testid={`flow-tile-${code}`} data-code={code}>
        <StatTile label={TILE_LABELS[code] ?? code} value={valueText(t, money)} delta={deltaOf(t, range)}
          trend={t.trend} trendLabel={trendText(t, range)}
          hint={[targetText(t), missed === true ? "Over target" : undefined, TILE_HINTS[code]].filter(Boolean).join(" · ")} />
      </Link>
    );
  };

  return (
    <section className={styles.section} aria-label="Patient time and business" aria-busy={state.kind === "loading"}>
      <div className={styles.head}>
        <h2 className={styles.heading}>Patient time</h2>
        <div className={styles.seg} role="group" aria-label="Range">
          {RANGES.map(([key, label]) => (
            <button key={key} type="button" className={range === key ? styles.segOn : styles.segBtn} aria-pressed={range === key} onClick={() => setRange(key)}>{label}</button>
          ))}
        </div>
      </div>
      {state.kind === "error" && (
        <div className={styles.error} role="alert">Couldn&apos;t load the patient-time figures. <button type="button" className={styles.retry} onClick={retry}>Try again</button></div>
      )}
      {data && <p className={styles.meta}>{refreshed ? `Rollups refreshed ${refreshed}` : "Rollups not refreshed yet"} · {data.from === data.to ? data.from : `${data.from} to ${data.to}`}</p>}
      {data && data.tiles.every((t) => t.value === null) && <EmptyState icon="chart" title="No patient-time figures for this range yet." hint="They appear once visits have been through the clinic." />}

      <div className={styles.tiles} data-row="time">{TIME_TILES.map((c) => renderTile(c, tile(c)))}</div>
      <h2 className={styles.heading}>Business</h2>
      <div className={styles.tiles} data-row="business">{BUSINESS_TILES.map((c) => renderTile(c, tile(c)))}</div>

      <div className={styles.charts}>
        <Chart kind="column" title="Waiting time by hour (minutes)" categories={hours?.categories ?? []} series={[{ id: "wait", label: "Median wait", values: hours?.values ?? [] }]}
          categoryLabel="Hour" time status={chartStatus} onRetry={retry} stale={stale} csv={csv} fileName="waiting-time-by-hour" />
        <Chart kind="line" title="ETA accuracy, last 14 days (%)" categories={eta?.categories ?? []} series={[{ id: "eta", label: "Within 10 min", values: eta?.values ?? [] }]}
          categoryLabel="Day" status={chartStatus} onRetry={retry} stale={stale} csv={csv} fileName="eta-accuracy-14-days" />
      </div>

      <div className={styles.charts}>
        <section className={styles.panel} aria-label="Where the patient's time goes">
          <h3 className={styles.panelTitle}>Where the patient&apos;s time goes</h3>
          {data && splitRows(data.timeSplit).length === 0 ? <p className={styles.empty}>Not measured in this range.</p> : null}
          {data && splitRows(data.timeSplit).map((r) => {
            const max = Math.max(...splitRows(data.timeSplit).map((x) => x.minutes), 1);
            return <Meter key={r.label} label={r.label} value={r.minutes} max={max} valueText={`${r.minutes} min`} tone="neutral" />;
          })}
        </section>
        <section className={styles.panel} aria-label="Doctor punctuality">
          <h3 className={styles.panelTitle}>Doctor punctuality</h3>
          <p className={styles.meta}>On time = first patient called within 5 minutes of the session start.</p>
          {data && data.punctuality.length === 0 ? <p className={styles.empty}>No measured sessions in this range.</p> : null}
          {data && data.punctuality.length > 0 && (
            <Link href={reportHref("punctuality", data)} className={styles.tileLink} data-testid="flow-punctuality">
              <ul className={styles.list}>
                {data.punctuality.map((p) => (
                  <li key={p.doctorId} className={styles.listRow}><bdi>{p.doctorName ?? "Doctor"}</bdi><span>{p.onTimePct}% of {p.sessions} {p.sessions === 1 ? "session" : "sessions"}</span></li>
                ))}
              </ul>
            </Link>
          )}
        </section>
      </div>
    </section>
  );
}
