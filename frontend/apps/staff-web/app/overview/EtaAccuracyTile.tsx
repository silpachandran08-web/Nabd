"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./overview.module.css";
import { api } from "../lib/api";
import { ErrorState, Skeleton } from "../components/ui/StateViews";
import { dailyShare, sparkline, type EtaDay, type EtaItem } from "../lib/etaAccuracy";

type State = { kind: "loading" } | { kind: "error" } | { kind: "ready"; days: EtaDay[] };

/** ACC-019: yesterday's share of visits called within ±10 min of the time we showed, with the last 14 days. Owner overview only (reports:view). */
export default function EtaAccuracyTile() {
  const [state, setState] = useState<State>({ kind: "loading" });

  const load = useCallback(async () => {
    setState({ kind: "loading" });
    try {
      const body = await api.get<{ items?: EtaItem[] }>("/reports/eta-accuracy");
      setState({ kind: "ready", days: dailyShare(body.items ?? []) });
    } catch {
      setState({ kind: "error" });
    }
  }, []);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const days = state.kind === "ready" ? state.days : [];
  const yesterday = days.length ? days[days.length - 1] : null;
  const series = days.filter((d) => d.pct !== null);

  return (
    <section className={styles.card} aria-label="ETA accuracy" aria-busy={state.kind === "loading"}>
      <div className={styles.cardLabel}>ETA accuracy</div>
      {state.kind === "loading" ? (
        <Skeleton rows={2} label="Loading ETA accuracy" />
      ) : state.kind === "error" ? (
        <ErrorState message="Couldn't load ETA accuracy." onRetry={() => void load()} />
      ) : !yesterday || yesterday.pct === null ? (
        <p className={styles.empty}>No ETA data yet. Figures appear after the first full clinic day with times shown to patients.</p>
      ) : (
        <>
          <div className={styles.kpiValue} data-testid="eta-accuracy-value">{yesterday.pct}%</div>
          <div className={styles.kpiSub}>
            of {yesterday.n} visits on {yesterday.date} were called within ±10 min of the time we showed
          </div>
          {yesterday.low && (
            <div className={`${styles.alert} ${styles.alertInfo}`} data-testid="eta-low-confidence">
              <div className={styles.alertTitle}>Low confidence</div>
              <div className={styles.alertSub}>Fewer than 30 visits that day, so this figure can swing and raises no drift alert.</div>
            </div>
          )}
          <svg className={styles.etaSpark} viewBox="0 0 140 40" preserveAspectRatio="none" role="img"
            aria-label={`Share within ±10 min over the last ${days.length} days: ${series.map((d) => `${d.pct}%`).join(", ")}`}>
            <polyline points={sparkline(days, 140, 40)} fill="none" stroke="var(--ov-series-1)" strokeWidth="2" vectorEffect="non-scaling-stroke" />
          </svg>
          <div className={styles.kpiSub}>Last {days.length} days</div>
        </>
      )}
    </section>
  );
}
