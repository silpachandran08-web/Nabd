// WB-FEF-021 E2E: honest connection pill. Run: node s8-rec9-connection-pill.mjs (app on :3109, API mocked).
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP, SHOTS } from './qa-lib.mjs';

const pill = (page) => page.locator('[role=status]').filter({ hasText: /Online|Offline|Reconnecting|Slow connection/ }).first();
const label = async (page) => ((await pill(page).textContent()) ?? '').replace(/\s+/g, ' ');
const waitLabel = async (page, re, ms = 5000) => {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { if (re.test(await label(page).catch(() => ''))) return Date.now() - t0; await sleep(100); }
  throw new Error(`pill never matched ${re}; last "${await label(page)}"`);
};
const PAGE_API = 'https://nabd-f0zb.onrender.com/v1'; // .env.production base baked into the build; mockApi serves both hosts
const call = (page, path) => page.evaluate(([a, p]) => fetch(a + p).then((r) => r.status, () => 'neterr'), [PAGE_API, path]);

let mode = { queue: 200 }; // mutable behaviour of GET /queue
const handlers = () => [
  ['GET', /^\/(queue|staff\/roster)/, (r) => mode.queue === 'abort' ? r.abort('failed') : r.fulfill({ status: mode.queue, contentType: mode.queue >= 400 ? 'application/problem+json' : 'application/json', body: mode.queue >= 400 ? JSON.stringify({ title: 'x' }) : '[]' })],
  ['GET', /^\/health/, { status: 404, body: { title: 'nf' } }], // reachable server, no such endpoint
  ['GET', /^\/slow/, { body: [], delay: 6000 }],
];

async function session(perms = ['queue:view', 'patients:view'], viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers());
  mode = { queue: 200 };
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];

let s;
try {
  // 1 happy path + 2 no "Synced"
  s = await session();
  await waitLabel(s.page, /^\s*Online/);
  const title = await pill(s.page).getAttribute('title');
  check('1 happy: Online with last-sync time after a good API answer', /Last sync \d{2}:\d{2}/.test(title) && /Online\s*· \d{2}:\d{2}/.test(await label(s.page)), `${await label(s.page)} | ${title}`);
  check('2 the word "Synced" appears nowhere on the page', !(await s.page.content()).includes('Synced'));
  await s.page.screenshot({ path: `${SHOTS}/online.png` });

  // 3 browser offline -> Offline (never green); back online -> Online again
  await s.context.setOffline(true);
  await waitLabel(s.page, /Offline/, 3000);
  check('3a browser offline shows Offline, warn style, no queue claim', !/waiting|queued|synced/i.test(await label(s.page)) && /can't be saved/.test(await pill(s.page).getAttribute('title')), await label(s.page));
  await s.page.screenshot({ path: `${SHOTS}/offline.png` });
  await s.context.setOffline(false);
  await waitLabel(s.page, /Online/, 5000);
  check('3b back online -> Online again (probe proved the API answers)', true);

  // 4 boundary: 2 failures stay Online, 3rd flips to Reconnecting within 5s
  mode.queue = 500;
  await call(s.page, '/queue'); await call(s.page, '/queue');
  await sleep(300);
  check('4a two consecutive 500s: still Online (boundary)', /Online/.test(await label(s.page)), await label(s.page));
  await call(s.page, '/queue');
  const ms = await waitLabel(s.page, /Reconnecting/, 5000);
  check('4b third consecutive 500 -> Reconnecting within 5s', ms < 5000, `${ms}ms`);
  check('4c Reconnecting is not the green style', !(await pill(s.page).getAttribute('class')).includes('syncOnline'));
  await s.page.screenshot({ path: `${SHOTS}/reconnecting.png` });

  // 5 recovery by background probe (nothing else calls the API), up to ~10s interval
  mode.queue = 200;
  const rec = await waitLabel(s.page, /Online/, 14000);
  check('5 probe recovers the pill to Online without user action', true, `${rec}ms`);

  // 6 network failures (not just 5xx) also count
  mode.queue = 'abort';
  for (let i = 0; i < 3; i++) await call(s.page, '/queue');
  await waitLabel(s.page, /Reconnecting/, 5000);
  check('6 three network failures -> Reconnecting', true);
  mode.queue = 200;
  await call(s.page, '/queue'); // a single good answer resets
  await waitLabel(s.page, /Online/, 3000);
  check('7 one good answer clears Reconnecting', true);

  // 8 4xx is a working server: three 403s stay Online
  mode.queue = 403;
  for (let i = 0; i < 3; i++) await call(s.page, '/queue');
  await sleep(300);
  check('8 three 403s do not degrade the pill', /Online/.test(await label(s.page)), await label(s.page));
  mode.queue = 200;

  // 9 slow: a request pending past 5s -> Slow connection; fast answer clears
  const slowCall = call(s.page, '/slow');
  const slowMs = await waitLabel(s.page, /Slow connection/, 7000);
  check('9a request pending >5s -> Slow connection', slowMs >= 3000, `${slowMs}ms after start of wait`);
  await slowCall;
  await call(s.page, '/queue');
  await waitLabel(s.page, /Online/, 3000);
  check('9b fast answer clears Slow connection', true);

  // 10 error state of the page itself: 500 on /queue shows the page error, pill stays Online (1 failure)
  mode.queue = 500;
  await s.page.reload({ waitUntil: 'networkidle' });
  await sleep(500);
  check('10 page error state (500) shown; pill honest (1 failure = Online)', /Couldn.t load today.s arrivals/.test(await s.page.locator('body').innerText()) && /Online/.test(await label(s.page)));
  check('11 no page errors / console errors (excluding intentional failed loads)', errs(s.log).length === 0, JSON.stringify(errs(s.log)));
  dumpLog('main', s.log);
  await s.browser.close(); s = null;

  // 12 empty state + no patients:view: pill still present (not a restricted control), search absent
  s = await session(['queue:view']);
  await waitLabel(s.page, /Online/, 5000);
  check('12 role without patients:view: search box absent, pill present', (await s.page.locator('#nb-global-search').count()) === 0 && (await pill(s.page).count()) === 1);
  await s.browser.close(); s = null;

  // 13 no session -> no pill on /login
  const l = await launch(); await mockApi(l.page, l.log, handlers());
  await l.page.goto(`${APP}/login`, { waitUntil: 'networkidle' });
  check('13 no session: no pill on /login', (await pill(l.page).count()) === 0);
  await l.browser.close();

  // 14 390px: no horizontal page scroll in Online and Reconnecting
  s = await session(undefined, { width: 390, height: 800 });
  await waitLabel(s.page, /Online/, 5000);
  const noScroll = () => s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  const okOnline = await noScroll();
  mode.queue = 500;
  for (let i = 0; i < 3; i++) await call(s.page, '/queue');
  await waitLabel(s.page, /Reconnecting/, 5000);
  const box = await pill(s.page).boundingBox();
  check('14 390px: no horizontal scroll (Online + Reconnecting), pill inside viewport', okOnline && (await noScroll()) && box.x >= 0 && box.x + box.width <= 390, JSON.stringify(box));
  await s.page.screenshot({ path: `${SHOTS}/reconnecting-390.png` });
  check('15 390px no page/console errors', errs(s.log).length === 0, JSON.stringify(errs(s.log)));
} catch (e) {
  check('RUN', false, String(e));
} finally {
  if (s) await s.browser.close();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
