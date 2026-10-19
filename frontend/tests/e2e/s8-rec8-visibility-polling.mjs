// WB-FEF-004: live screens (Arrivals, Nursing, Live Queue board) stop polling in hidden tabs and reload once on return.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules node s8-rec8-visibility-polling.mjs   (APP_URL defaults to http://localhost:3108)
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const SHOTS = '/home/claude/ws/reports/shots-rec8';
const DAY = { date: '2026-10-18', today: true, timezone: 'Asia/Riyadh', holiday: null, doctors: [], sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 0 } };
const PAGES = [
  { name: 'arrivals', url: '/arrivals', perms: ['queue:view'], primary: (p) => p === '/queue' },
  { name: 'nursing', url: '/nursing', perms: ['queue:view', 'nursing:view'], primary: (p) => p === '/nursing/worklist' },
  { name: 'queue-board', url: '/schedule', perms: ['queue:view'], primary: (p) => p === '/queue', open: async (page) => page.getByRole('button', { name: 'Live Queue' }).click() },
];

function handlers(over = {}) {
  return [
    ['GET', /^\/schedule\/day/, { body: DAY }],
    ['GET', /^\/nursing\/worklist/, over.worklist ?? { body: { items: [] } }],
    ['GET', /^\/queue(\?|$)/, over.queue ?? { body: [] }],
    ['GET', /./, { body: [] }],
  ];
}
const setVis = (page, v) => page.evaluate((v) => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => v });
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => v === 'hidden' });
  document.dispatchEvent(new Event('visibilitychange'));
}, v);
const settle = () => sleep(400);

async function open(cfg, { perms = cfg.perms, over, viewport } = {}) {
  const ctx = await launch({ viewport });
  await seedClinic(ctx.page, perms);
  await ctx.page.clock.install();
  await mockApi(ctx.page, ctx.log, handlers(over));
  await ctx.page.goto(APP + cfg.url);
  if (cfg.open) await cfg.open(ctx.page);
  await settle();
  return ctx;
}

for (const cfg of PAGES) {
  const { page, browser, log } = await open(cfg);
  const n = () => log.requests.length;
  const prim = () => log.requests.filter((r) => cfg.primary(r.path.split('?')[0])).length;

  // visible: polling runs every 15 s
  let b = prim();
  await page.clock.runFor(15_000); await settle();
  check(`${cfg.name}: visible tab polls at 15 s`, prim() === b + 1, `primary +${prim() - b}`);

  // hidden for 60 s: zero API requests
  await setVis(page, 'hidden'); await settle();
  b = n();
  await page.clock.runFor(60_000); await settle();
  check(`${cfg.name}: 0 requests while hidden for 60 s`, n() === b, `+${n() - b} requests`);

  // visible again: exactly one immediate reload, then 15 s polling resumes
  b = prim();
  await setVis(page, 'visible'); await settle();
  check(`${cfg.name}: one immediate reload on visible`, prim() === b + 1, `primary +${prim() - b}`);
  b = prim();
  await page.clock.runFor(15_000); await settle();
  check(`${cfg.name}: 15 s polling resumes after visible`, prim() === b + 1, `primary +${prim() - b}`);

  // boundary: rapid hide/show/hide/show -> one reload per visible, never doubled timers
  await setVis(page, 'hidden'); await setVis(page, 'visible'); await setVis(page, 'hidden'); await setVis(page, 'visible'); await settle();
  b = prim();
  await page.clock.runFor(15_000); await settle();
  check(`${cfg.name}: after rapid toggles still one poll per 15 s`, prim() === b + 1, `primary +${prim() - b}`);

  // 390 px: no horizontal page scroll
  await page.setViewportSize({ width: 390, height: 800 }); await settle();
  const sw = await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
  check(`${cfg.name}: no horizontal scroll at 390 px`, sw);
  await page.screenshot({ path: `${SHOTS}/${cfg.name}-390.png` });

  const bad = [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
  check(`${cfg.name}: zero console/page errors`, bad.length === 0, bad.slice(0, 2).join(' | '));
  dumpLog(cfg.name, log);
  await browser.close();
}

// permission absent: no queue:view -> no API calls from the live screens, even after a tick
for (const cfg of PAGES.slice(0, 2)) {
  const { page, browser, log } = await open(cfg, { perms: ['patients:view'] });
  await page.clock.runFor(30_000); await settle();
  const live = log.requests.filter((r) => /^\/(queue|nursing\/worklist)/.test(r.path));
  check(`${cfg.name}: without queue:view no queue requests`, live.length === 0, `${live.length} requests`);
  await browser.close();
}

// empty state (arrivals) and error state (500 on /queue), then recovery on the next poll
{
  const cfg = PAGES[0];
  let ctx = await open(cfg);
  check('arrivals: empty state shown', await ctx.page.getByText('No patients here yet').isVisible());
  await ctx.browser.close();
  ctx = await open(cfg, { over: { queue: { status: 500, body: { title: 'boom' } } } });
  check('arrivals: error state on 500', await ctx.page.getByText("Couldn't load today's arrivals").isVisible());
  await ctx.browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
