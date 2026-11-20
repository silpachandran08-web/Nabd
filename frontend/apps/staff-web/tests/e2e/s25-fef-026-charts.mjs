// FEF-026 in a browser: the chart gallery (/ui-gallery/charts needs NABD_UI_GALLERY=1 on the server). Stands in for "Storybook + axe":
// the ten states x four primitives, axe in both themes, Table toggle, CSV download, keyboard readout, RTL and phone width.
// Written by the engineer, NOT run in the sprint (the brief leaves browser runs to QA).
// Run: NABD_UI_GALLERY=1 next start -p 3811; APP=http://localhost:3811 PLAYWRIGHT_MODULE=<playwright index.mjs> node tests/e2e/s25-fef-026-charts.mjs
import { readFileSync } from 'node:fs';
import { launch, check, results, dumpLog, APP } from './qa-lib.mjs';
import { axeStep } from './smoke/harness.mjs';

const TEN = ['loading', 'empty', 'partial', 'error', 'stale', 'noPermission', 'tooManySeries', 'singlePoint', 'negative', 'allZero'];
const s = await launch({ viewport: { width: 1280, height: 900 } });
const { page } = s;
await page.goto(`${APP}/ui-gallery/charts`, { waitUntil: 'networkidle' });

for (const st of TEN) check(`state ${st}: column, bar, line and area render it`, (await page.locator(`section[data-state="${st}"] .nbu-chart[data-state="${st}"]`).count()) === 4);
check('noPermission draws no plot, table toggle or CSV', (await page.locator('section[data-state="noPermission"]').locator('svg.nbu-chart-svg, table, .nbu-chart-tools').count()) === 0);
check('tooManySeries: six legend entries, the last is Other', (await page.locator('section[data-state="tooManySeries"] .nbu-chart').first().locator('.nbu-chart-legend li').allTextContents()).join() === 'Doctor A,Doctor B,Doctor C,Doctor D,Doctor E,Other');

for (const theme of ['dark', 'light']) {
  await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
  const e = await axeStep(page, `charts ${theme}`, []);
  check(`axe ${theme}: 0 critical/serious across every state`, e.violations.length === 0, JSON.stringify(e.violations.map((v) => `${v.id}: ${v.nodes[0]}`)).slice(0, 600));
  const palette = await page.evaluate(() => [1, 2, 3, 4, 5, 6].map((i) => getComputedStyle(document.documentElement).getPropertyValue(`--nb-chart-${i}`).trim()));
  check(`${theme}: six chart colours are defined and distinct`, new Set(palette.filter(Boolean)).size === 6, palette.join());
}

// Table toggle + CSV
const column = page.locator('.nbu-chart', { hasText: 'Visits per day (column)' }).first();
const toggle = column.getByRole('button', { name: /^Table/ });
await toggle.click();
check('Table toggle: a <table> with the chart data replaces the plot', (await column.locator('table.nbu-chart-table tbody tr').first().locator('th,td').allTextContents()).join() === 'Mon,12,8' && (await column.locator('svg.nbu-chart-svg').count()) === 0);
check('Table toggle: aria-pressed, column and row headers', (await toggle.getAttribute('aria-pressed')) === 'true' && (await column.locator('th[scope=col]').count()) === 3 && (await column.locator('th[scope=row]').count()) === 6);
await axeStep(page, 'table view', []).then((e) => check('axe: table view', e.violations.length === 0, JSON.stringify(e.violations.map((v) => v.id))));
const [download] = await Promise.all([page.waitForEvent('download'), column.getByRole('button', { name: /^Download CSV/ }).click()]);
const csv = readFileSync(await download.path(), 'utf8');
check('CSV download: file name from the title, same rows as the table', download.suggestedFilename() === 'visits-per-day-column.csv' && csv.replace(/^\uFEFF/, '').startsWith('Day,Walk-in,Booked\r\nMon,12,8\r\nTue,18,11\r\n'), csv.slice(0, 80));
await toggle.click();
check('Table toggle: back to the plot', (await column.locator('svg.nbu-chart-svg').count()) === 1 && (await toggle.getAttribute('aria-pressed')) === 'false');

// Keyboard readout (tooltip + crosshair + live region)
const line = page.locator('.nbu-chart', { hasText: 'Visits per day (line)' }).first();
const status = line.locator('[role=status]');
await line.locator('svg.nbu-chart-svg').focus();
await page.keyboard.press('End');
check('keyboard: End reads the last point', (await status.textContent()) === 'Sat: Walk-in 7, Booked 4');
await page.keyboard.press('ArrowLeft');
check('keyboard: ArrowLeft goes one point back, tooltip and crosshair show', (await status.textContent()) === 'Fri: Walk-in 20, Booked 15' && (await line.locator('.nbu-chart-tip').count()) === 1 && (await line.locator('.nbu-chart-cross').count()) === 1);
await page.keyboard.press('Escape');
check('keyboard: Escape clears the readout', (await status.textContent()) === '' && (await line.locator('.nbu-chart-tip').count()) === 0);
await line.locator('.nbu-chart-hit').nth(2).hover();
check('pointer: hovering a position shows every series there', (await line.locator('.nbu-chart-tip').textContent()) === 'Wed9Walk-in13Booked');

// RTL: time runs left to right, a categorical plot mirrors, the legend mirrors
await page.evaluate(() => document.documentElement.setAttribute('dir', 'rtl'));
const tickX = (chart, label) => chart.locator('svg.nbu-chart-svg text', { hasText: new RegExp(`^${label}$`) }).first().evaluate((e) => e.getBoundingClientRect().x);
check('RTL: a time series still runs left to right', (await tickX(line, 'Mon')) < (await tickX(line, 'Sat')));
check('RTL: a categorical plot mirrors', (await tickX(column, 'Mon')) > (await tickX(column, 'Sat')));
check('RTL: the legend mirrors (key to the right of its label)', await line.locator('.nbu-chart-legend li').first().evaluate((li) => { const r = document.createRange(); r.selectNodeContents(li.lastChild); return li.querySelector('.nbu-chart-key').getBoundingClientRect().x > r.getBoundingClientRect().x; }));
check('RTL: the title sits at the right, the tools at the left', await line.evaluate((c) => c.querySelector('.nbu-chart-title').getBoundingClientRect().x > c.querySelector('.nbu-chart-tools').getBoundingClientRect().x));
await page.locator('.nbu-chart[data-kind=column] svg.nbu-chart-svg').first().focus();
await page.keyboard.press('Home'); await page.keyboard.press('ArrowLeft');
check('RTL: on a mirrored plot the left arrow moves to the next category', (await column.locator('[role=status]').textContent()).startsWith('Tue:'));
await page.evaluate(() => document.documentElement.setAttribute('dir', 'ltr'));

// Phone
await page.setViewportSize({ width: 375, height: 812 });
check('375px: no horizontal scroll', await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
check('375px: plots are drawn at their real width (text is not scaled down)', await column.locator('svg.nbu-chart-svg').evaluate((e) => Math.abs(e.viewBox.baseVal.width - e.getBoundingClientRect().width) < 2));
check('console clean', !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length);
dumpLog('charts', s.log);
await s.browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
