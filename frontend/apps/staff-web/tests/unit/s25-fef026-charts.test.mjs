// FEF-026: chart kit (packages/ui/src/charts) and its palette gate (packages/tokens/chart-palette.mjs).
// The kit is TSX source: it is transpiled with the repo's TypeScript into a scratch folder inside packages/ui (so react resolves)
// and rendered with react-dom/server, like s16-fef023-ui.test.mjs. Pointer, keyboard, the Table toggle click, the CSV download,
// RTL pixels and axe run in a browser: tests/e2e/s25-fef-026-charts.mjs on /ui-gallery/charts.
import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { CHART_SLOTS, THRESHOLDS, contrast, difference, validateChartPalette } from "../../../../packages/tokens/chart-palette.mjs";
import { MAX_SERIES, barPath, chartTable, dataState, limitSeries, niceScale, segments, toCsv } from "../../../../packages/ui/src/charts/model.ts";
import { screensOf } from "../e2e/visual/screens.mjs";

const tokensDir = fileURLToPath(new URL("../../../../packages/tokens/", import.meta.url));
const uiDir = fileURLToPath(new URL("../../../../packages/ui/", import.meta.url));
const out = join(uiDir, ".test-out-charts"); // not .test-out: the FEF-023 test owns that folder and test files run in parallel
const ts = createRequire(import.meta.url)("typescript");
const { renderToStaticMarkup } = createRequire(import.meta.url)("react-dom/server");
const React = createRequire(join(uiDir, "package.json"))("react");
const h = React.createElement;
const html = (el) => renderToStaticMarkup(el);
const read = (p) => readFileSync(p, "utf8");
const count = (s, re) => (s.match(re) ?? []).length;
let kit, fixtures;

before(async () => {
  rmSync(out, { recursive: true, force: true });
  mkdirSync(join(out, "charts"), { recursive: true });
  const files = ["Icon.tsx", "feedback.tsx", ...readdirSync(join(uiDir, "src/charts")).map((n) => `charts/${n}`)];
  for (const f of files) {
    const src = read(join(uiDir, "src", f)).replace(/from "(\.\.?\/[^"]+)\.tsx?"/g, 'from "$1.mjs"');
    const js = ts.transpileModule(src, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
    writeFileSync(join(out, f.replace(/\.tsx?$/, ".mjs")), js);
  }
  const load = (f) => import(pathToFileURL(join(out, f)).href);
  kit = { ...(await load("charts/index.mjs")), ...(await load("charts/Chart.mjs")) };
  fixtures = await load("charts/fixtures.mjs");
});
after(() => rmSync(out, { recursive: true, force: true }));

const palette = (theme) => { const t = JSON.parse(read(join(tokensDir, `src/source/color.${theme}.json`))); return { colors: Array.from({ length: CHART_SLOTS }, (_, i) => t[`chart-${i + 1}`]), surface: t["surface-1"] }; };
/** Runs fn with console.warn captured; returns the messages. */
function warnings(fn) {
  const seen = [], real = console.warn;
  console.warn = (m) => seen.push(String(m));
  try { fn(); } finally { console.warn = real; }
  return seen;
}

// ---- AC1: palette validator
test("AC1: every pair of the six series colours clears the thresholds in both themes, in normal vision and under deuteranopia and protanopia", () => {
  assert.equal(CHART_SLOTS, 6);
  assert.equal(MAX_SERIES, CHART_SLOTS, "the series guard and the palette agree on six");
  for (const theme of ["dark", "light"]) {
    const { colors, surface } = palette(theme);
    assert.equal(new Set(colors).size, 6, theme);
    const r = validateChartPalette(colors, surface);
    assert.deepEqual(r.failures, [], theme);
    assert.equal(r.pass, true);
    let pairs = 0;
    for (let i = 0; i < 6; i++) for (let j = i + 1; j < 6; j++) {
      pairs++;
      assert.ok(difference(colors[i], colors[j], "deutan") >= THRESHOLDS.cvd, `${theme} ${i + 1}/${j + 1} deuteranopia`);
      assert.ok(difference(colors[i], colors[j], "protan") >= THRESHOLDS.cvd, `${theme} ${i + 1}/${j + 1} protanopia`);
      assert.ok(difference(colors[i], colors[j]) >= THRESHOLDS.normal, `${theme} ${i + 1}/${j + 1} normal vision`);
    }
    assert.equal(pairs, 15);
    for (const c of colors) assert.ok(contrast(c, surface) >= THRESHOLDS.contrast, `${theme} ${c} on ${surface}`);
  }
});

test("AC1: the validator does fail a confusable palette, a low-contrast slot and a malformed colour", () => {
  const redGreen = validateChartPalette(["#d03b3b", "#5a8f2e"], "#ffffff"); // classic red/green: fine in normal vision, collapses for deuteranopes
  assert.ok(difference("#d03b3b", "#5a8f2e") >= THRESHOLDS.normal);
  assert.equal(redGreen.pass, false);
  assert.ok(redGreen.failures.some((f) => /deutan difference/.test(f)), redGreen.failures.join("; "));
  assert.ok(validateChartPalette(["#2a78d6", "#2b79d7"], "#ffffff").failures.some((f) => /normal difference/.test(f)));
  assert.ok(validateChartPalette(["#f2f2a0"], "#ffffff").failures.some((f) => /needs 3:1/.test(f)));
  assert.throws(() => validateChartPalette(["red"], "#ffffff"), /#rrggbb/);
  assert.ok(difference("#000000", "#000000") === 0 && Math.abs(contrast("#000000", "#ffffff") - 21) < 0.01);
});

test("AC1: the palette ships as --nb-chart-1..6 in both themes, the kit uses only those, and the tokens build (CI gate) fails on a bad palette", () => {
  const css = read(join(tokensDir, "dist/tokens.css"));
  const [darkBlock, lightBlock] = css.split(':root[data-theme="light"]');
  for (const theme of ["dark", "light"]) palette(theme).colors.forEach((c, i) => assert.ok((theme === "dark" ? darkBlock : lightBlock).includes(`--nb-chart-${i + 1}: ${c};`), `${theme} chart-${i + 1}`));
  const report = JSON.parse(read(join(tokensDir, "dist/contrast-report.json")));
  assert.ok(report.chartPalette.dark.pass && report.chartPalette.light.pass);
  const ui = read(join(uiDir, "src/ui.css"));
  for (let i = 1; i <= 6; i++) assert.ok(ui.includes(`.nbu-chart-s${i} { --nbu-series: var(--nb-chart-${i}); }`), `slot ${i}`);
  const chartCss = ui.slice(ui.indexOf("FEF-026"));
  assert.equal(/#[0-9a-f]{3,8}\b|rgba?\(/i.test(chartCss), false, "no literal colour in the chart styles");
  assert.equal(/\b(left|right)\s*:|margin-(left|right)|padding-(left|right)/.test(chartCss), false, "logical properties only");

  const tmp = mkdtempSync(join(tmpdir(), "tok-chart-"));
  try {
    cpSync(tokensDir, tmp, { recursive: true, filter: (s) => !s.includes("node_modules") });
    const f = join(tmp, "src/source/color.light.json"), j = JSON.parse(read(f));
    j["chart-4"] = "#b8700a"; // next to chart-2: the same amber
    writeFileSync(f, JSON.stringify(j));
    const r = spawnSync("node", ["build.mjs"], { cwd: tmp, encoding: "utf8" });
    assert.equal(r.status, 1);
    assert.match(r.stderr, /chart palette FAIL light chart-2 .* vs chart-4/);
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});

// ---- AC2: series-count guard
const mk = (n) => Array.from({ length: n }, (_, j) => ({ id: `s${j}`, label: `S${j}`, values: [10 * (j + 1), j, null] }));

test("AC2: 7+ series keep the five largest, sum the rest into 'Other' and warn; up to six pass through untouched", () => {
  const seen = [];
  const { shown, grouped } = limitSeries(mk(8), "Other", (m) => seen.push(m));
  assert.equal(shown.length, 6);
  assert.equal(grouped, 3);
  assert.deepEqual(shown.map((s) => s.label), ["S3", "S4", "S5", "S6", "S7", "Other"]); // input order kept, so colours stay with their series
  assert.deepEqual(shown[5].values, [10 + 20 + 30, 0 + 1 + 2, null]); // a gap in every grouped series stays a gap
  assert.equal(seen.length, 1);
  assert.match(seen[0], /8 series passed; a chart shows at most 6/);
  const six = mk(6), calm = [];
  assert.equal(limitSeries(six, "Other", (m) => calm.push(m)).shown, six);
  assert.equal(calm.length, 0);
  // default warning channel: console.warn outside production, silent in production
  assert.equal(warnings(() => limitSeries(mk(7))).length, 1);
  const env = process.env.NODE_ENV;
  process.env.NODE_ENV = "production";
  try { assert.equal(warnings(() => limitSeries(mk(7))).length, 0); } finally { if (env === undefined) delete process.env.NODE_ENV; else process.env.NODE_ENV = env; }
});

test("AC2: a rendered chart with 8 series draws six colours, names 'Other' in the legend, says so, and logs the dev warning", () => {
  const fx = fixtures.CHART_FIXTURES.find((f) => f.state === "tooManySeries");
  assert.equal(fx.props.series.length, 8);
  let m;
  const seen = warnings(() => { m = html(h(kit.Chart, { kind: "column", ...fx.props, labels: { other: "Everything else" } })); });
  assert.ok(seen.length >= 1 && /grouped into "Everything else"/.test(seen[0]));
  assert.match(m, /data-state="tooManySeries"/);
  const legend = /<ul class="nbu-chart-legend">(.*?)<\/ul>/.exec(m)[1];
  assert.equal(count(legend, /<li>/g), 6);
  assert.match(legend, /nbu-chart-s6[^>]*><\/span>Everything else/);
  assert.equal(/nbu-chart-s7/.test(m), false, "never a seventh colour");
  assert.match(m, /3 smaller series are grouped as Other\. The table lists every series\./);
});

// ---- AC3: table view and CSV
test("AC3: the table view is a real <table> with the same data as the chart (every series), and the CSV is that table", () => {
  const cats = ["Mon", "Tue"], series = [{ id: "a", label: "Walk-in", values: [12, 1500.5] }, { id: "b", label: "Booked", values: [null, 4] }];
  const fmt = (n) => `${n} visits`;
  assert.deepEqual(chartTable("Day", cats, series, fmt), { head: ["Day", "Walk-in", "Booked"], rows: [["Mon", "12 visits", ""], ["Tue", "1500.5 visits", "4 visits"]] });
  const m = html(h(kit.ChartTable, { title: "Visits per day", titleId: "t1", categoryLabel: "Day", categories: cats, series, format: fmt }));
  assert.match(m, /<table class="nbu-chart-table"><caption class="nbu-vh">Visits per day<\/caption>/);
  assert.deepEqual([...m.matchAll(/<th scope="col">(.*?)<\/th>/g)].map((x) => x[1]), ["Day", "Walk-in", "Booked"]);
  assert.deepEqual([...m.matchAll(/<th scope="row">(.*?)<\/th>/g)].map((x) => x[1]), cats);
  assert.deepEqual([...m.matchAll(/<td>(.*?)<\/td>/g)].map((x) => x[1]), ["12 visits", "", "1500.5 visits", "4 visits"]);
  assert.match(m, /role="region" aria-labelledby="t1" tabindex="0"/); // a scrollable table is reachable by keyboard
  assert.equal(toCsv("Day", cats, series), "Day,Walk-in,Booked\r\nMon,12,\r\nTue,1500.5,4\r\n"); // raw numbers, a gap is an empty cell
  // the table and the CSV list all eight series even when the chart groups three into Other
  const fx = fixtures.CHART_FIXTURES.find((f) => f.state === "tooManySeries").props;
  assert.equal(chartTable("Day", fx.categories, fx.series).head.length, 9);
  assert.equal(toCsv("Day", fx.categories, fx.series).split("\r\n")[0].split(",").length, 9);
});

test("AC3: CSV quoting, and text a spreadsheet would run as a formula is neutralised (numbers stay numbers)", () => {
  const csv = toCsv('Doctor, "main"', ["=SUM(A1)", "+91 clinic", "@x", "Line\nbreak", "Plain"], [{ id: "a", label: "-Net", values: [-5, 0, 1.25, null, undefined] }]);
  assert.deepEqual(csv.split("\r\n"), ['"Doctor, ""main""",\'-Net', "'=SUM(A1),-5", "'+91 clinic,0", "'@x,1.25", '"Line\nbreak",', "Plain,", ""]);
});

test("AC3: every drawn chart has the Table toggle and the CSV button; csv={false} removes the button (absent, not disabled)", () => {
  const m = html(h(kit.Chart, { kind: "column", ...fixtures.CHART_BASE }));
  assert.match(m, /<button type="button" class="nbu-btn nbu-btn-ghost" aria-pressed="false" aria-label="Table: Visits per day">/);
  assert.match(m, /aria-label="Download CSV: Visits per day"/);
  const off = html(h(kit.Chart, { kind: "column", ...fixtures.CHART_BASE, csv: false }));
  assert.equal(/Download CSV/.test(off), false);
  assert.match(off, /aria-label="Table: Visits per day"/);
  const src = read(join(uiDir, "src/charts/Chart.tsx"));
  assert.match(src, /else if \(table\) body = <ChartTable /); // the toggle swaps the plot for the table
  assert.match(src, /downloadCsv\([^;]*toCsv\(categoryLabel, categories, props\.series\)\)/);
  assert.match(html(h(kit.Chart, { kind: "bar", ...fixtures.CHART_BASE, labels: { table: "جدول", csv: "CSV تنزيل" } })), /aria-label="جدول: Visits per day"/);
});

// ---- AC4: RTL
test("AC4: a time series is drawn dir=ltr and never mirrored; a categorical plot mirrors in RTL; legend and notes sit outside the plot", () => {
  const time = html(h(kit.Chart, { kind: "line", ...fixtures.CHART_BASE, stale: "Updated at 06:00" }));
  const plot = /<div class="nbu-chart-plot"[^>]*>/.exec(time)[0];
  assert.match(plot, /dir="ltr"/); assert.match(plot, /data-time=""/);
  assert.equal(/nbu-chart-flip/.test(time), false);
  // time runs left to right: x grows with the category index
  const xs = fixtures.CHART_BASE.categories.map((c) => Number(new RegExp(`<text class="nbu-chart-tick" x="([\\d.]+)"[^>]*>${c}</text>`).exec(time)[1]));
  assert.deepEqual(xs, [...xs].sort((a, b) => a - b));
  assert.ok(xs[0] < xs[5]);
  // legend and note come before the plot in the same group and carry no dir of their own: they follow the page direction
  assert.ok(time.indexOf("nbu-chart-legend") < time.indexOf("nbu-chart-plot") && time.indexOf("nbu-chart-note") < time.indexOf("nbu-chart-plot"));
  assert.equal(count(time, /dir="ltr"/g), 1);
  for (const kind of ["line", "area"]) assert.match(html(h(kit.Chart, { kind, ...fixtures.CHART_BASE })), /data-time=""/, `${kind} is a time series by default`);

  const cat = html(h(kit.Chart, { kind: "column", ...fixtures.CHART_BASE }));
  assert.equal(/dir="ltr"|data-time/.test(cat), false);
  assert.match(cat, /<svg class="nbu-chart-svg nbu-chart-flip"/);
  assert.match(html(h(kit.Chart, { kind: "column", ...fixtures.CHART_BASE, time: true })), /<div class="nbu-chart-plot" dir="ltr" data-time="">/); // columns over days
  assert.equal(/data-time/.test(html(h(kit.Chart, { kind: "line", ...fixtures.CHART_BASE, time: false }))), false);

  const ui = read(join(uiDir, "src/ui.css"));
  assert.ok(ui.includes('[dir="rtl"] .nbu-chart-flip { transform: scaleX(-1); }'));
  assert.ok(ui.includes('[dir="rtl"] .nbu-chart-flip text { transform: scaleX(-1); transform-box: fill-box; transform-origin: center; }'));
  assert.equal(count(ui, /\[dir="rtl"\][^{]*nbu-chart/g), 2, "nothing else in the chart kit is direction-specific");
  assert.match(ui, /\.nbu-chart-svg \{[^}]*direction: ltr;/, "text-anchor must not swap under an inherited rtl");
  assert.equal(/nbu-chart-flip/.test(html(h(kit.Sparkline, { values: [1, 2, 3], label: "Rising" }))), false, "a sparkline is a time series");
  assert.match(html(h(kit.Meter, { label: "Slots filled", value: 3, max: 4 })), /nbu-chart-flip/); // a meter fills from the inline start
});

// ---- AC5: the ten states (fixtures stand in for Storybook stories)
const KINDS = ["column", "bar", "line", "area"];
const TEN = ["loading", "empty", "partial", "error", "stale", "noPermission", "tooManySeries", "singlePoint", "negative", "allZero"];
/** Static accessibility invariants of the markup (not axe: axe needs a browser, see the e2e file). */
function staticA11y(m, where) {
  for (const svg of m.match(/<svg[^>]*>/g) ?? []) assert.ok(/aria-hidden="true"/.test(svg) || (/role="img"/.test(svg) && /aria-label="[^"]+"/.test(svg)), `${where}: svg without a name: ${svg}`);
  for (const b of m.match(/<button[^>]*>.*?<\/button>/g) ?? []) assert.ok(/aria-label="[^"]+"/.test(b) || b.replace(/<[^>]+>/g, "").trim(), `${where}: unnamed button`);
  const group = /<div class="nbu-chart" role="group" aria-labelledby="([^"]+)"/.exec(m);
  assert.ok(group && m.includes(`id="${group[1]}"`), `${where}: chart group is named by its title`);
  assert.equal(/style="[^"]*(#[0-9a-f]{3,8}|rgb)/i.test(m), false, `${where}: literal colour`);
}

test("AC5: all ten states exist as fixtures and every primitive renders each one with the right data-state and clean static a11y", () => {
  assert.deepEqual(fixtures.CHART_FIXTURES.map((f) => f.state), TEN);
  warnings(() => {
    for (const { state, props } of fixtures.CHART_FIXTURES) for (const kind of KINDS) {
      const m = html(h(kit.Chart, { kind, ...props }));
      assert.match(m, new RegExp(`data-kind="${kind}" data-state="${state}"`), `${kind}/${state}`);
      assert.equal(/NaN|Infinity|undefined/.test(m), false, `${kind}/${state}: bad geometry`);
      staticA11y(m, `${kind}/${state}`);
      const drawn = !["loading", "empty", "error", "noPermission"].includes(state);
      assert.equal(/<svg class="nbu-chart-svg/.test(m), drawn, `${kind}/${state}: plot`);
      assert.equal(/aria-label="Table: /.test(m), drawn, `${kind}/${state}: Table toggle only when there is data`);
    }
  });
});

test("AC5: what each state shows", () => {
  const of = (state, kind = "line", extra = {}) => html(h(kit.Chart, { kind, ...fixtures.CHART_FIXTURES.find((f) => f.state === state).props, ...extra }));
  assert.match(of("loading"), /role="status" aria-busy="true" aria-label="Loading chart"/);
  assert.match(of("empty"), /No data for this period/);
  const err = of("error");
  assert.match(err, /role="alert"/); assert.match(err, /Could not load this chart/); assert.match(err, /Retry/);
  // no permission: a lock and a sentence, and nothing of the data even when it was passed
  const locked = of("noPermission", "column", { categories: ["Mon"], series: [{ id: "x", label: "Secret revenue", values: [987654] }] });
  assert.match(locked, /You do not have access to this data/);
  assert.equal(/Secret revenue|987654|<table|Download CSV|nbu-chart-svg/.test(locked), false);
  // partial: the note, and the gap breaks the line instead of drawing a zero
  const partial = of("partial");
  assert.match(partial, /<p class="nbu-chart-note">.*Wednesday and Saturday are still being counted\./);
  const first = /<g class="nbu-chart-s1">(.*?)<\/g><g class="nbu-chart-s2">/.exec(partial)[1];
  assert.equal(count(first, /class="nbu-chart-line"/g), 2, "two runs around the Wednesday gap");
  assert.match(of("stale"), /<p class="nbu-chart-note">.*Updated at 06:00/);
  // single point: a dot instead of an invisible zero-length line, and it says so
  const single = of("singlePoint");
  assert.equal(count(single, /class="nbu-chart-line"/g), 0);
  assert.ok(count(single, /<circle class="nbu-chart-dot"/g) >= 1);
  assert.match(single, /Only one data point so far\./);
  // negative: the axis goes below zero and bars hang from the zero line
  const neg = of("negative", "column");
  assert.match(neg, /<text class="nbu-chart-tick"[^>]*>-10<\/text>/);
  assert.match(neg, /<text class="nbu-chart-value"[^>]*>-8<\/text>/);
  const zero = of("allZero", "column");
  assert.match(zero, /Every value is zero\./);
  assert.deepEqual([...zero.matchAll(/<text class="nbu-chart-tick"[^>]*text-anchor="end"[^>]*>(.*?)<\/text>/g)].map((x) => x[1]), ["0", "1"]);
  // one series: no legend box (the title names it); two or more: always a legend
  assert.equal(/nbu-chart-legend/.test(neg), false);
  assert.equal(count(html(h(kit.Chart, { kind: "area", ...fixtures.CHART_BASE })), /<li>/g), 2);
  // labels are data: rendered as text, never as markup
  assert.match(html(h(kit.Chart, { kind: "bar", title: "T", categories: ["<img src=x>"], series: [{ id: "a", label: "<b>x</b>", values: [1] }, { id: "b", label: "B", values: [2] }] })), /&lt;b&gt;x&lt;\/b&gt;/);
});

test("AC5: the gallery route renders every primitive x state, is gated like /ui-gallery and is a FEF-051 visual screen (both themes)", () => {
  const app = new URL("../../app/ui-gallery/charts/", import.meta.url);
  const page = read(new URL("page.tsx", app)), gallery = read(new URL("ChartGallery.tsx", app));
  assert.match(page, /process\.env\.NABD_UI_GALLERY !== "1"\) notFound\(\)/);
  assert.match(gallery, /CHART_FIXTURES\.map\(\(\{ state, props \}\) =>[\s\S]*KINDS\.map\(\(kind\) => <Chart /);
  assert.match(gallery, /const KINDS: ChartKind\[\] = \["column", "bar", "line", "area"\]/);
  for (const figure of ["<StatTile", "<Meter", "<Sparkline"]) assert.ok(gallery.includes(figure), figure);
  const screen = screensOf("staff").find((s) => s.id === "staff/ui-gallery/charts");
  assert.ok(screen && screen.url === "/ui-gallery/charts");
});

// ---- helpers and figures
test("unit: niceScale, dataState, segments, barPath", () => {
  assert.deepEqual(niceScale(3, 87), { min: 0, max: 100, ticks: [0, 25, 50, 75, 100] });
  assert.deepEqual(niceScale(-8, 6).ticks, [-10, -5, 0, 5, 10]);
  assert.deepEqual(niceScale(0, 0), { min: 0, max: 1, ticks: [0, 1] });
  assert.deepEqual(niceScale(0.1, 0.3).ticks, [0, 0.1, 0.2, 0.3]); // no 0.30000000000000004
  assert.deepEqual(niceScale(126, 138, 4, true), { min: 125, max: 140, ticks: [125, 130, 135, 140] });
  assert.equal(niceScale(126, 138).min, 0);
  assert.deepEqual(niceScale(5, 5, 4, true).ticks.length > 1, true);
  const s = (values) => [{ id: "a", label: "A", values }];
  assert.equal(dataState([], s([1])), "empty");
  assert.equal(dataState(["a", "b"], []), "empty");
  assert.equal(dataState(["a", "b"], s([null, NaN])), "empty");
  assert.equal(dataState(["a", "b"], s([0, 0])), "allZero");
  assert.equal(dataState(["a"], s([4])), "singlePoint");
  assert.equal(dataState(["a", "b"], s([4, -1])), "negative");
  assert.equal(dataState(["a", "b"], s([4, null])), "ok");
  assert.equal(dataState(["a"], s([0, -9])), "allZero", "values beyond the categories are ignored");
  assert.deepEqual(segments([1, 2, null, 3, null, null, 4, 5]), [[1, 2], [3], [4, 5]]);
  assert.deepEqual(segments([null]), []);
  assert.equal(barPath(false, 10, 20, 100, 40), "M10,100V44Q10,40 14,40H26Q30,40 30,44V100Z"); // rounded top, square at the baseline
  assert.equal(barPath(false, 10, 20, 100, 130), "M10,100V126Q10,130 14,130H26Q30,130 30,126V100Z"); // negative: rounded bottom
  assert.equal(barPath(true, 10, 20, 50, 150), "M50,10H146Q150,10 150,14V26Q150,30 146,30H50Z");
  assert.equal(barPath(false, 0, 20, 100, 99), "M0,100V100Q0,99 1,99H19Q20,99 20,100V100Z"); // a 1px bar keeps its shape
});

test("figures: Meter is a named role=meter with a readable value, StatTile says the change in words, Sparkline has a text alternative", () => {
  const meter = html(h(kit.Meter, { label: "Slots filled", value: 42, max: 60 }));
  assert.match(meter, /role="meter" aria-labelledby="([^"]+)" aria-valuemin="0" aria-valuemax="60" aria-valuenow="42" aria-valuetext="42 \/ 60"/);
  assert.match(meter, /<rect class="nbu-meter-fill nbu-meter-neutral" width="70%"/);
  assert.match(html(h(kit.Meter, { label: "L", value: 90, max: 60, valueText: "over", tone: "danger" })), /nbu-meter-danger" width="100%"/);
  const tile = html(h(kit.StatTile, { label: "Door to doctor", value: "14 min", delta: { text: "4 min faster than last week", direction: "down", tone: "better" }, trend: [22, 20, 14], trendLabel: "Falling over 3 weeks" }));
  assert.match(tile, /nbu-stat-better"><svg[^>]*aria-hidden="true"/); assert.match(tile, /4 min faster than last week/);
  assert.match(tile, /<svg class="nbu-spark"[^>]*role="img" aria-label="Falling over 3 weeks"/);
  assert.equal(/<svg/.test(html(h(kit.StatTile, { label: "L", value: 1, delta: { text: "Same as last week", direction: "flat" } }))), false);
  assert.equal(html(h(kit.Sparkline, { values: [null, null], label: "x" })), "");
  assert.equal(/<button|<a /.test(tile), false, "a tile renders no control of its own");
});

test("README documents the public API that the index exports", () => {
  const readme = read(join(uiDir, "README.md"));
  for (const name of ["Chart", "Sparkline", "Meter", "StatTile", "CHART_LABELS", "limitSeries", "chartTable", "toCsv", "MAX_SERIES"]) {
    assert.ok(name in kit, `index exports ${name}`);
    assert.ok(readme.includes(name), `README mentions ${name}`);
  }
  for (const prop of ["kind", "title", "categories", "series", "time", "status", "stale", "partial", "categoryLabel", "formatValue", "fitY", "csv", "fileName", "onRetry", "height", "labels"]) assert.ok(readme.includes(`\`${prop}\``), `README documents ${prop}`);
});
