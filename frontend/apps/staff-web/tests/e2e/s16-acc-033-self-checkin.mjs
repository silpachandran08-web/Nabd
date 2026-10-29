// S16 ACC-033: the self check-in screen (/checkin): door poster + settings, recent self check-ins, failures with one-tap confirm.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3804 node tests/e2e/s16-acc-033-self-checkin.mjs
import { launch, seedClinic, mockApi, check, sleep, dumpLog, results, APP } from './qa-lib.mjs';

const ago = (min) => new Date(Date.now() - min * 60000).toISOString();
const CFG = { checkinCode: 'ABC234', lat: 12.97, lng: 77.59, geofenceRequired: true, geofenceRadiusM: 200, walkinAllowed: false, whatsappNumber: '919876543210' };
const ATT = [
  { id: 'k1', appointmentId: 'a1', channel: 'qr', distanceM: 40, outcome: 'checked_in', createdAt: ago(5) },
  { id: 'k2', appointmentId: 'a2', channel: 'whatsapp', distanceM: 1400, outcome: 'outside_geofence', createdAt: ago(3) },
  { id: 'k3', appointmentId: null, channel: 'qr', distanceM: null, outcome: 'no_appointment', createdAt: ago(2) },
];
const EXC = [{ id: 'e1', kind: 'checkin_failed', severity: 'urgent', refType: 'appointment', refId: 'a2', summary: 'x', params: { reason: 'outside_geofence', distanceM: 1400 }, actions: ['confirm_arrival', 'ask_patient', 'resolve', 'snooze'], createdAt: ago(3) }];

function server(opts = {}) {
  const st = { cfg: opts.cfg ?? CFG, attempts: opts.attempts ?? ATT, failCfg: !!opts.failCfg, failAttempts: !!opts.failAttempts, putStatus: opts.putStatus ?? 200 };
  const err = (route) => route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x', title: 'x' }) });
  return { st, handlers: [
    ['GET', /^\/queue(\?|$)/, { body: [] }],
    ['GET', /^\/me\/entitlements$/, { body: { modules: [{ code: 'queue_eta', enabled: true }], killedFeatures: [], seatLimit: null } }],
    ['GET', /^\/front-desk\/exceptions/, { body: { items: opts.exceptions ?? EXC, nextCursor: null } }],
    ['GET', /^\/facility\/checkin-config$/, (route) => st.failCfg ? err(route) : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(st.cfg) })],
    ['PUT', /^\/facility\/checkin-config$/, (route, req) => st.putStatus >= 400
      ? route.fulfill({ status: st.putStatus, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x', title: 'Bad', detail: 'x' }) })
      : (st.cfg = { ...st.cfg, ...JSON.parse(req.postData()) }, route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(st.cfg) }))],
    ['GET', /^\/facility\/checkin-attempts/, (route) => st.failAttempts ? err(route) : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(st.attempts) })],
    ['GET', /^\/facility\/checkin-qr\?format=png$/, (route) => route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from('89504e470d0a1a0a', 'hex') })],
  ] };
}

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
async function run(tag, perms, srv, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, srv.handlers);
    await page.goto(APP + '/checkin'); await sleep(1800);
    await fn({ page, log, st: srv.st });
    check(`${tag} zero console/page errors`, noise(log).length === 0, JSON.stringify(noise(log)));
    if (viewport) check(`${tag} no horizontal scroll at ${viewport.width}px`, await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const recent = (page) => page.getByRole('region', { name: 'Recent self check-ins' });
const poster = (page) => page.getByRole('region', { name: 'Door poster and settings' });
const ADMIN = ['queue:view', 'queue:create', 'queue:edit', 'setup:edit', 'patients:view'];

await run('happy', ADMIN, server(), async ({ page, log, st }) => {
  const main = await page.locator('main').innerText();
  check('H1 title, clinic code and the exception rail with Confirm arrival', /Self check-in/.test(main) && /ABC234/.test(main) && (await page.getByRole('button', { name: 'Confirm arrival' }).count()) === 1, main.slice(0, 300));
  const rec = await recent(page).innerText();
  check('H2 recent list shows channel, distance and outcome, never a coordinate', /Door QR/.test(rec) && /WhatsApp/.test(rec) && /1400 m/.test(rec) && /Checked in/.test(rec) && /Too far away/.test(rec) && /No appointment today/.test(rec) && !/12\.97|77\.59/.test(rec), rec);
  const dl = page.waitForEvent('download');
  await poster(page).getByRole('button', { name: 'Download poster (PNG)' }).click();
  check('H3 PNG poster downloads', (await dl).suggestedFilename() === 'checkin-poster.png' && log.requests.some((r) => /checkin-qr\?format=png/.test(r.path)));
  await poster(page).getByLabel('Distance allowed (metres)').fill('10');
  await poster(page).getByRole('button', { name: 'Save settings' }).click(); await sleep(300);
  check('H4 a bad distance is refused in the form, nothing sent', /20 to 5000/.test(await poster(page).innerText()) && !log.requests.some((r) => r.method === 'PUT'));
  await poster(page).getByLabel('Distance allowed (metres)').fill('300');
  await poster(page).getByLabel('Let walk-ins ask for a token with the QR').check();
  await poster(page).getByRole('button', { name: 'Save settings' }).click(); await sleep(700);
  const put = log.requests.find((r) => r.method === 'PUT');
  check('H5 Save sends the settings and says Saved', !!put && JSON.parse(put.body).geofenceRadiusM === 300 && JSON.parse(put.body).walkinAllowed === true && /Saved/.test(await poster(page).innerText()), put?.body);
}, { width: 1280, height: 900 });

await run('save error', ADMIN, server({ putStatus: 500 }), async ({ page }) => {
  await poster(page).getByRole('button', { name: 'Save settings' }).click(); await sleep(700);
  const al = await poster(page).getByRole('alert').innerText();
  check('S1 a failed save shows an error and never says Saved', al.length > 0 && !/Saved/.test(await poster(page).innerText()), al);
});

await run('empty', ADMIN, server({ attempts: [], exceptions: [] }), async ({ page }) => {
  check('E1 empty states for the list and the rail', /No self check-ins yet/.test(await recent(page).innerText()) && /Nothing needs you right now/.test(await page.locator('main').innerText()));
});

await run('error', ADMIN, server({ failAttempts: true, failCfg: true }), async ({ page, st }) => {
  check('X1 both sections show an error with Retry', /Couldn.t load recent check-ins/.test(await recent(page).innerText()) && /Couldn.t load the check-in settings/.test(await page.locator('main').innerText()));
  st.failAttempts = false; st.failCfg = false;
  await recent(page).getByRole('button', { name: 'Retry' }).click(); await sleep(800);
  check('X2 Retry loads the list', /Checked in/.test(await recent(page).innerText()));
});

await run('desk only', ['queue:view', 'queue:create', 'queue:edit', 'patients:view'], server(), async ({ page, log }) => {
  check('P1 without setup:edit there is no poster or settings and the config is never fetched', (await poster(page).count()) === 0 && (await page.getByRole('button', { name: 'Save settings' }).count()) === 0 && log.requests.filter((r) => /checkin-config|checkin-qr/.test(r.path)).length === 0);
  check('P2 the recent list and the exception rail are still there', /Door QR/.test(await recent(page).innerText()));
});

await run('phone', ADMIN, server(), async ({ page }) => {
  check('M1 poster and list render at 390px', (await poster(page).count()) === 1 && /Door QR/.test(await recent(page).innerText()));
}, { width: 390, height: 844 });

const failed = results.filter((r) => !r.ok);
console.log(failed.length ? `FAILED ${failed.length}` : 'ALL PASSED');
process.exit(failed.length ? 1 : 0);
