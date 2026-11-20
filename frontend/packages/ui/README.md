# @nabd/ui

Design-system components for the Nabd apps: controls, feedback, table, sheet, print kit and the chart kit. No data fetching and
no `@nabd/api-client` import (lint enforces it). Styles: `@import "@nabd/ui/ui.css"` once per app (staff-web does it in
`app/globals.css`), on top of `@nabd/tokens/tokens.css`.

## Chart kit (FEF-026)

Plain SVG and CSS on `--nb-*` tokens. No chart library, no canvas (so charts print and are readable by assistive technology).
The feature owns the data; the kit owns drawing, colour, states, the table view and the CSV download.

```tsx
import { Chart, StatTile, Meter, Sparkline, type ChartSeries } from "@nabd/ui";

const series: ChartSeries[] = [
  { id: "walkin", label: t("walkIn"), values: [12, 18, null, 14] }, // null = no data (a gap), never 0
  { id: "booked", label: t("booked"), values: [8, 11, 13, 9] },
];

<Chart
  kind="column"                       // "column" | "bar" | "line" | "area"
  title={t("visitsPerDay")}
  categories={["Mon", "Tue", "Wed", "Thu"]}   // already formatted for the reader
  series={series}
  time                                // the categories are points in time
  categoryLabel={t("day")}
  formatValue={(n) => formatNumber(n, locale)}
  status={q.isPending ? "loading" : q.isError ? "error" : undefined}
  onRetry={() => void q.refetch()}
  stale={rollupAge}                   // e.g. "Updated at 06:00"
  csv={can("reports:export")}
  labels={chartLabels}                // translated strings, see CHART_LABELS
/>
```

### `Chart` props

| Prop | Type | Default | Meaning |
|---|---|---|---|
| `kind` | `"column" \| "bar" \| "line" \| "area"` | required | `column`: vertical bars. `bar`: horizontal bars (use it for long category names; it grows with its rows). `line` / `area`: trends. |
| `title` | `string` | required | Visible title and accessible name. Name the measure and the unit here. |
| `categories` | `string[]` | required | One label per x position, already formatted (dates in the clinic timezone, names). |
| `series` | `ChartSeries[]` | required | `{ id, label, values }`; `values[i]` belongs to `categories[i]`; `null` is a gap. Colour = position in this array (slot 1 to 6), so keep the order stable when a filter changes. |
| `time` | `boolean` | `true` for line and area, else `false` | The x axis is time: drawn left to right in every direction, never mirrored in RTL. Set it on a `column` chart over days. |
| `status` | `"loading" \| "error" \| "noPermission"` | none | Set while there is nothing to draw. `noPermission` renders nothing from `series`. |
| `stale` | `string` | none | The data is old: the sentence to show ("Updated at 06:00"). |
| `partial` | `string` | none | Part of the data is missing: the sentence to show. |
| `categoryLabel` | `string` | `"Category"` | Header of the first table / CSV column. |
| `formatValue` | `(n: number) => string` | two decimals, no grouping | Axis ticks, labels, readout and table cells. Pass `formatNumber` / `formatMoney` from `@nabd/i18n`. The CSV always has raw numbers. |
| `fitY` | `boolean` | `false` | Line only: fit the axis to the data instead of starting at zero (a narrow range such as a weight trend). Bars and areas always start at zero. |
| `csv` | `boolean` | `true` | `false` removes the CSV button (absent, not disabled): no export permission, or the screen has its own audited export. |
| `fileName` | `string` | from the title | Download name without `.csv`. |
| `onRetry` | `() => void` | none | Shows a Retry button in the error state. |
| `height` | `number` | `240` | Plot height in px for column, line and area. |
| `labels` | `Partial<ChartLabels>` | `CHART_LABELS` (English) | Every string the kit renders: `table`, `csv`, `loading`, `empty`, `error`, `retry`, `noPermission`, `other`, `tooMany` (`{count}` is replaced), `allZero`, `singlePoint`, `keys`, `noValue`. |

### The ten states

The root element carries `data-state`. Three come from `status`, two from `stale` / `partial`, five from the data itself.

| `data-state` | When | What renders |
|---|---|---|
| `loading` | `status="loading"` | Skeleton (`role="status"`, busy). |
| `error` | `status="error"` | Alert with the message and Retry (`onRetry`). |
| `noPermission` | `status="noPermission"` | Lock and a sentence. No data, no table, no CSV. Prefer not rendering the chart at all when a whole screen is restricted; use this for one tile among visible ones. |
| `empty` | no categories, no series, or only gaps | Empty state. |
| `partial` | `partial` set | The chart plus the note; gaps break lines and leave bar slots empty. |
| `stale` | `stale` set | The chart plus the note. |
| `tooManySeries` | more than 6 series | The five largest keep their colour, the rest are summed into "Other" (`labels.other`), a note says so and a warning is logged outside production. The table and the CSV still list every series. Summing is right for counts and money; for rates or averages pass at most six series yourself. |
| `singlePoint` | one category | A dot instead of an invisible line, plus a note. |
| `negative` | any value below zero | The axis extends below zero; bars hang from the zero line. |
| `allZero` | every value is 0 | A flat chart on a 0 to 1 axis plus a note. |
| `ok` | otherwise | The chart. |

### What every chart has

- **Table view and CSV.** The "Table" toggle (`aria-pressed`) swaps the plot for a real `<table>` (caption, `scope` headers) with
  the same data; "Download CSV" saves that table (UTF-8 with BOM, RFC 4180, raw numbers; text that a spreadsheet would run as a
  formula is prefixed with `'`). `chartTable(...)` and `toCsv(...)` are exported for screens that need the same rows elsewhere.
- **Legend** for two or more series (one series needs none: the title names it). Direct value labels only on single-series charts
  with at most 12 categories; everything else is in the readout and the table.
- **Readout and crosshair.** Pointer: hover a position. Keyboard: Tab to the plot, then arrow keys, Home, End, Escape. The values
  at that position are shown in a tooltip and announced through a polite live region.
- **Colour.** Six categorical slots, `--nb-chart-1` to `--nb-chart-6`, different in the dark and light theme. Every pair is
  validated in normal vision and under simulated protanopia and deuteranopia, and each slot is at least 3:1 on `--nb-surface-1`
  (the chart draws its own surface-1 card). The gate is `packages/tokens/chart-palette.mjs`, run by the tokens build
  (`pnpm --filter @nabd/tokens lint` fails on a bad palette). Never add a seventh colour and never use a status colour
  (`--nb-ok-500`, `--nb-danger-500`, ...) for a series. Text is always a text token, never the series colour.
- **RTL.** Title, tools, notes, legend and table are HTML and mirror with the page. A time plot (`time`) is `dir="ltr"`: time runs
  left to right and the value axis stays on the left. A categorical plot is mirrored (first category at the inline start).

### Figures

```tsx
<StatTile label={t("doorToDoctor")} value="14 min"
  delta={{ text: t("fasterThanLastWeek", { n: 4 }), direction: "down", tone: "better" }}
  trend={[22, 20, 21, 18, 17, 15, 14]} trendLabel={t("fallingOver7Days")} />
<Meter label={t("slotsFilled")} value={42} max={60} valueText={t("nOfM", { n: 42, m: 60 })} tone="neutral" />
<Sparkline values={[3, 5, 4, null, 7]} label={t("risingOver5Days")} />
```

- `StatTile`: `label`, `value`, optional `delta` (`text` must say the whole change in words; `direction` `"up" | "down" | "flat"`
  picks the arrow, `tone` `"better" | "worse" | "neutral"` the colour: a falling wait time is `direction: "down", tone: "better"`),
  optional `trend` + `trendLabel` (sparkline and its text alternative), optional `hint`. It renders no control: wrap it in a link
  for tap-through.
- `Meter`: `label`, `value`, `max` (default 100), `valueText` (shown and read out, default "value / max"), `tone`
  `"neutral" | "ok" | "warn" | "danger"`. `role="meter"`.
- `Sparkline`: `values`, `label` (text alternative), `width` (96), `height` (28). Always left to right.

### Helpers

`MAX_SERIES` (6), `limitSeries(series, otherLabel?)` (the series-count guard), `chartTable(categoryLabel, categories, series, format?)`,
`toCsv(categoryLabel, categories, series)`, `CHART_LABELS` and the types `ChartProps`, `ChartSeries`, `ChartKind`, `ChartLabels`,
`StatDelta`.

### Not in the kit

One value axis per chart (two measures of different scale are two charts). No stacked bars, pie or donut, scatter, heatmap,
zoom or brush, no reference bands (growth-chart percentiles need their own story), and no texture fills for forced-colors mode.
Filters and date ranges are ordinary controls placed above the charts they scope.

### Gallery and tests

Storybook is not in this repository. Every primitive in each of the ten states renders at `/ui-gallery/charts` in staff-web
(server started with `NABD_UI_GALLERY=1`; the FEF-051 visual harness snapshots it in both themes). Fixtures:
`@nabd/ui/charts/fixtures`. Unit tests: `apps/staff-web/tests/unit/s25-fef026-charts.test.mjs`. Browser checks (axe, keyboard,
Table toggle, CSV, RTL): `apps/staff-web/tests/e2e/s25-fef-026-charts.mjs`.
