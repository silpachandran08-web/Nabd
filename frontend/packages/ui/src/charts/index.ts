// FEF-026: the public API of the chart kit (see packages/ui/README.md).
export { Chart, CHART_LABELS, type ChartLabels, type ChartProps } from "./Chart.tsx";
export { Meter, Sparkline, StatTile, type StatDelta } from "./figures.tsx";
export { MAX_SERIES, chartTable, limitSeries, toCsv, type ChartKind, type ChartSeries } from "./model.ts";
