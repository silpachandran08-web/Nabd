"use client";

import { Chart, Meter, Sparkline, StatTile, type ChartKind } from "@nabd/ui";
import { CHART_BASE, CHART_FIXTURES } from "@nabd/ui/charts/fixtures";

const KINDS: ChartKind[] = ["column", "bar", "line", "area"];
const grid = { display: "grid", gap: 16, gridTemplateColumns: "repeat(auto-fit, minmax(min(100%, 340px), 1fr))" };

export default function ChartGallery() {
  return (
    <main style={{ padding: 16, display: "grid", gap: 16 }} id="chart-gallery">
      <h1>Chart gallery</h1>
      <section aria-label="Figures" style={grid}>
        <StatTile label="Door to doctor" value="14 min" delta={{ text: "4 min faster than last week", direction: "down", tone: "better" }} trend={[22, 20, 21, 18, 17, 15, 14]} trendLabel="Falling over 7 days" />
        <StatTile label="No-shows" value="9%" delta={{ text: "2 points more than last week", direction: "up", tone: "worse" }} />
        <StatTile label="Consult time" value="11 min" delta={{ text: "Same as last week", direction: "flat" }} hint="Median" />
        <div className="nbu-kpi" style={{ display: "grid", gap: 12 }}>
          <Meter label="Slots filled" value={42} max={60} />
          <Meter label="Stock used" value={55} max={60} valueText="55 of 60 packs" tone="warn" />
          <Sparkline values={[3, 5, 4, null, 7, 9, 8]} label="Rising over 7 days" />
        </div>
      </section>
      <section aria-label="Plain" data-state="ok" style={grid}>
        {KINDS.map((kind) => <Chart key={kind} kind={kind} {...CHART_BASE} title={`${CHART_BASE.title} (${kind})`} />)}
        <Chart kind="column" {...CHART_BASE} time title="Visits per day (column, time axis)" />
        <Chart kind="line" title="Average wait, minutes (line, fitted axis)" categories={["1 Mar", "8 Mar", "15 Mar", "22 Mar", "29 Mar"]} series={[{ id: "wait", label: "Average wait", values: [38, 34, 36, 29, 26] }]} fitY />
      </section>
      {CHART_FIXTURES.map(({ state, props }) => (
        <section key={state} aria-label={state} data-state={state} style={grid}>
          {KINDS.map((kind) => <Chart key={kind} kind={kind} {...props} title={`${props.title} (${kind}, ${state})`} />)}
        </section>
      ))}
    </main>
  );
}
