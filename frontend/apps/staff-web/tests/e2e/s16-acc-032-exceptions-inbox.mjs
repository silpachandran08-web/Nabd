// S16 ACC-032: the front desk's exceptions rail on /arrivals.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3804 node tests/e2e/s16-acc-032-exceptions-inbox.mjs
import { launch, seedClinic, mockApi, check, sleep, dumpLog, results, APP } from './qa-lib.mjs';

const ago = (min) => new Date(Date.now() - min * 60000).toISOString();
const item = (id, kind, severity, params, actions, min) => ({ id, kind, severity, refType: 'appointment', refId: 'a-' + id, summary: kind, params, actions, createdAt: ago(min) });
const ITEMS = () => [
  item('e1', 'checkin_failed', 'urgent', { reason: 'outside_geofence', distanceM: 1400 }, ['confirm_arrival', 'ask_patient', 'resolve', 'snooze'], 3),
  item('e2', 'unconfirmed_booking', 'warn', {}, ['call', 'message', 'resolve', 'snooze'], 10),
  item('e3', 'handoff', 'info', {}, ['open_inbox', 'resolve', 'snooze'], 20),
];

function server(initial, opts = {}) {
  const st = { items: initial, failList: !!opts.failList, lost: new Set(opts.lost ?? []) };
  const gone = (id) => { st.items = st.items.filter((x) => x.id !== id); };
  return { st, handlers: [
    ['GET', /^\/queue(\?|$)/, { body: [] }],
    ['GET', /^\/front-desk\/exceptions/, (route) => st.failList ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x', title: 'x' }) })
      : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: st.items, nextCursor: null }) })],
    ['POST', /^\/front-desk\/exceptions\/[^/]+\/(resolve|snooze)$/, (route, req) => {
      const id = req.url().match(/exceptions\/([^/]+)\//)[1];
      if (st.lost.has(id)) { gone(id); return route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ type: 'already-resolved', title: 'Already resolved', detail: 'x', code: 'ALREADY_RESOLVED' }) }); }
      gone(id); return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id, status: 'resolved' }) });
    }],
    ['GET', /^\/appointments\/a-e1$/, { body: { id: 'a-e1', patientId: 'p1', doctorId: 'd1' } }],
    ['POST', /^\/queue\/check-in$/, (route) => { gone('e1'); return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ token: 'A-12' }) }); }],
  ] };
}

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
async function run(tag, perms, srv, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, srv.handlers);
    await page.goto(APP + '/arrivals'); await sleep(1800);
    await fn({ page, log, st: srv.st });
    check(`${tag} zero console/page errors`, noise(log).length === 0, JSON.stringify(noise(log)));
    if (viewport) check(`${tag} no horizontal scroll at ${viewport.width}px`, await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const rail = (page) => page.getByRole('region', { name: 'Needs you' });
const posts = (log, re) => log.requests.filter((r) => r.method === 'POST' && re.test(r.path));
const FULL = ['queue:view', 'queue:create', 'queue:edit', 'patients:view'];

await run('happy', FULL, server(ITEMS()), async ({ page, log }) => {
  const text = await rail(page).innerText();
  check('H1 three items, urgent first, with the distance in words', /Self check-in needs you/.test(text) && /1\.4 km away/.test(text) && /Unconfirmed/.test(text) && /Patient needs a person/.test(text) && text.indexOf('Self check-in') < text.indexOf('Unconfirmed'), text);
  check('H2 the failed check-in has Confirm arrival and Ask patient', (await rail(page).getByRole('button', { name: 'Confirm arrival' }).count()) === 1 && (await rail(page).getByRole('link', { name: 'Ask patient' }).count()) === 1);
  check('H3 the unconfirmed booking has Call and Message', (await rail(page).getByRole('button', { name: 'Call' }).count()) === 1 && (await rail(page).getByRole('button', { name: 'Message' }).count()) === 1);
  check('H4 action targets are at least 44px tall', await rail(page).getByRole('button', { name: 'Done' }).first().evaluate((b) => b.getBoundingClientRect().height >= 44));
  await rail(page).getByRole('button', { name: 'Done' }).nth(1).click(); await sleep(700);
  check('H5 Done posts resolve for that item and it leaves the list', posts(log, /exceptions\/e2\/resolve$/).length === 1 && !/Unconfirmed/.test(await rail(page).innerText()));
  await rail(page).getByRole('button', { name: 'Confirm arrival' }).click(); await sleep(800);
  check('H6 Confirm arrival does the desk check-in and the item leaves', posts(log, /^\/queue\/check-in$/).length === 1 && JSON.parse(posts(log, /^\/queue\/check-in$/)[0].body).appointmentId === 'a-e1' && !/Self check-in needs you/.test(await rail(page).innerText()));
  await rail(page).getByRole('button', { name: 'Snooze 30 min' }).click(); await sleep(700);
  check('H7 Snooze posts 30 minutes', JSON.parse(posts(log, /snooze$/)[0].body).snoozeMinutes === 30);
  check('H8 empty after the last item', /Nothing needs you right now/.test(await rail(page).innerText()));
}, { width: 1280, height: 900 });

await run('lost race', FULL, server(ITEMS(), { lost: ['e3'] }), async ({ page }) => {
  await rail(page).getByRole('button', { name: 'Done' }).nth(2).click(); await sleep(800);
  check('R1 ALREADY_RESOLVED says someone else dealt with it and the list is refreshed', /Someone else already dealt with this/.test(await rail(page).innerText()) && !/Patient needs a person/.test(await rail(page).innerText()));
});

await run('empty', FULL, server([]), async ({ page }) => {
  check('E1 empty state', /Nothing needs you right now/.test(await rail(page).innerText()));
});

await run('error', FULL, server([], { failList: true }), async ({ page, st }) => {
  check('X1 list failure shows an error with Retry', /Couldn.t load this list/.test(await rail(page).innerText()));
  st.failList = false; st.items = ITEMS();
  await rail(page).getByRole('button', { name: 'Retry' }).click(); await sleep(800);
  check('X2 Retry loads the items', /Self check-in needs you/.test(await rail(page).innerText()));
});

await run('view only', ['queue:view', 'patients:view'], server(ITEMS()), async ({ page }) => {
  check('P1 without queue:edit / queue:create there is no Done, Snooze or Confirm arrival (absent, not disabled)',
    (await rail(page).getByRole('button', { name: /^(Done|Snooze 30 min|Confirm arrival)$/ }).count()) === 0 && /Self check-in needs you/.test(await rail(page).innerText()));
});

await run('no queue:view', ['patients:view'], server(ITEMS()), async ({ page, log }) => {
  check('P2 without queue:view the rail is absent and nothing is fetched', (await rail(page).count()) === 0 && log.requests.filter((r) => /front-desk\/exceptions/.test(r.path)).length === 0);
});

await run('phone', FULL, server(ITEMS()), async ({ page }) => {
  check('M1 the rail renders at 390px', /Self check-in needs you/.test(await rail(page).innerText()));
}, { width: 390, height: 844 });

const failed = results.filter((r) => !r.ok);
console.log(failed.length ? `FAILED ${failed.length}` : 'ALL PASSED');
process.exit(failed.length ? 1 : 0);
