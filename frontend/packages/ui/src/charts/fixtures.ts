import type { ChartProps } from "./Chart.tsx";
import type { ChartSeries } from "./model.ts";

// FEF-026: the ten chart states as data (synthetic numbers only). Used by the unit tests and the /ui-gallery/charts route,
// which stands in for Storybook (not in this repository). Not part of the public API.
type Fixture = { state: string; props: Omit<ChartProps, "kind"> };

const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const two: ChartSeries[] = [
  { id: "walkin", label: "Walk-in", values: [12, 18, 9, 14, 20, 7] },
  { id: "booked", label: "Booked", values: [8, 11, 13, 9, 15, 4] },
];
const many: ChartSeries[] = ["A", "B", "C", "D", "E", "F", "G", "H"].map((n, j) => ({ id: `d${j}`, label: `Doctor ${n}`, values: days.map((_, i) => 20 - 2 * j + ((i * (j + 3)) % 5)) }));

export const CHART_FIXTURES: Fixture[] = [
  { state: "loading", props: { title: "Visits per day", categories: [], series: [], status: "loading" } },
  { state: "empty", props: { title: "Visits per day", categories: days, series: [] } },
  { state: "partial", props: { title: "Visits per day", categories: days, series: [{ ...two[0], values: [12, 18, null, 14, 20, null] }, two[1]], partial: "Wednesday and Saturday are still being counted." } },
  { state: "error", props: { title: "Visits per day", categories: [], series: [], status: "error", onRetry: () => {} } },
  { state: "stale", props: { title: "Visits per day", categories: days, series: two, stale: "Updated at 06:00" } },
  { state: "noPermission", props: { title: "Revenue per day", categories: [], series: [], status: "noPermission" } },
  { state: "tooManySeries", props: { title: "Visits per doctor", categories: days, series: many } },
  { state: "singlePoint", props: { title: "Visits per day", categories: ["Mon"], series: [{ id: "visits", label: "Visits", values: [12] }] } },
  { state: "negative", props: { title: "Net change in waiting patients", categories: days, series: [{ id: "net", label: "Net change", values: [4, -3, 6, -8, 2, -1] }] } },
  { state: "allZero", props: { title: "No-shows per day", categories: days, series: [{ id: "noshow", label: "No-shows", values: [0, 0, 0, 0, 0, 0] }] } },
];
/** The plain case: two series over six days. */
export const CHART_BASE: Omit<ChartProps, "kind"> = { title: "Visits per day", categories: days, series: two, categoryLabel: "Day" };
