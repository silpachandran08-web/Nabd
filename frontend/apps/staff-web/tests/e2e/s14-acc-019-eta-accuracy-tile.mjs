// S14 ACC-019: owner overview "ETA accuracy" tile. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3801 node s14-acc-019-eta-accuracy-tile.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const today = new Date().toISOString().slice(0, 10);
const overview = { date: today, visits: { total: 3, completed: 1, inFlow: 2 }, collections: { collected: 0, invoices: 0, pendingInvoices: 0 }, checkout: { pending: 0, outstanding: 0 },
  packages: { soldToday: 0, sessionsOwed: 0 }, paymentSplit: [], dayClose: { unbilledConsultations: 0, pendingCheckouts: 0, closed: false }, activeStaff: [], staff: { active: 1, suspended: 0, invited: 0, roles: 1 } };
const day = (i) => new Date(Date.UTC(2026, 9, 1 + i)).toISOString().slice(0, 10);
const items = (n, pct, low = false) => Array.from({ length: 14 }, (_, i) => ({ clinicDate: day(i), doctorId: 'd1', n, maeMin: 4.2, p80HitRate: 0.81, within10Pct: i === 13 ? pct : 0.7, lowConfidence: low }));
const base = (eta) => [['GET', /^\/reports\/overview/, { body: overview }], ['GET', /^\/reports\/eta-accuracy/, eta], ['GET', /^\/compliance\/health/, { body: { items: [] } }],
  ['GET', /^\/setup\/(subscription|checklist)/, { body: [] }]];
async function run(tag, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + '/overview'); await sleep(2500); await fn({ page, log });
    check(`${tag}: zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const tile = (page) => page.getByRole('region', { name: 'ETA accuracy' });

await run('happy', ['reports:view'], base({ body: { items: items(40, 0.87) } }), async ({ page, log }) => {
  check('tile shows yesterday share 87%', (await page.getByTestId('eta-accuracy-value').innerText()) === '87%');
  check('tile names the 40 visits and the 14-day trend', /40 visits/.test(await tile(page).innerText()) && /Last 14 days/.test(await tile(page).innerText()));
  check('sparkline is an image with a text alternative', /over the last 14 days/.test((await tile(page).getByRole('img').getAttribute('aria-label')) ?? ''));
  check('no low-confidence note at 40 visits', (await page.getByTestId('eta-low-confidence').count()) === 0);
  check('request is the eta-accuracy endpoint', log.requests.some((r) => r.method === 'GET' && r.path.startsWith('/reports/eta-accuracy')));
});
await run('low confidence', ['reports:view'], base({ body: { items: items(12, 0.5, true) } }), async ({ page }) => {
  check('under 30 visits: figure marked low confidence', (await page.getByTestId('eta-low-confidence').count()) === 1 && /no drift alert/.test(await tile(page).innerText()));
});
await run('empty', ['reports:view'], base({ body: { items: [] } }), async ({ page }) => {
  check('empty: explains there is no data yet', /No ETA data yet/.test(await tile(page).innerText()));
});
await run('error', ['reports:view'], base({ status: 500, body: { type: 'server-error', title: 'x' } }), async ({ page }) => {
  check('error: tile shows a retry state, the rest of the overview still renders', /Couldn.t load ETA accuracy/.test(await tile(page).innerText()) && /Today at a glance/.test(await page.locator('main').innerText()));
});
await run('permission absent', ['setup:view'], base({ body: { items: items(40, 0.9) } }), async ({ page, log }) => {
  check('no reports:view: tile absent and endpoint never called', (await tile(page).count()) === 0 && !log.requests.some((r) => r.path.startsWith('/reports/eta-accuracy')));
});
await run('390px', ['reports:view'], base({ body: { items: items(40, 0.87) } }), async ({ page }) => {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal scroll', m.s <= m.c, JSON.stringify(m));
  check('390px: tile visible', await tile(page).isVisible());
}, { width: 390, height: 800 });

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
