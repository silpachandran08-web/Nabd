// S14 IDC-016: ABDM card in Setup > Integrations (off by default, HFR id, step-up, absent without setup:edit).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3818 node s14-idc016-abdm-card.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

async function run(tag, perms, handlers, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + '/setup?tab=integrations'); await sleep(1800); await fn({ page, log });
    check(`${tag} zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const off = { enabled: false, hfrVerified: false, hfrId: null };
const on = { enabled: true, hfrVerified: true, hfrId: 'HFR-1' };
const verify = ['POST', /^\/auth\/mfa\/verify$/, { body: { stepUpToken: 'su-token' } }];

await run('off by default', ['setup:view', 'setup:edit'], [['GET', /^\/settings\/integrations\/abdm$/, { body: off }]], async ({ page }) => {
  check('says Off and shares nothing', (await page.getByText(/Off\. Nothing is shared with ABDM/).count()) === 1);
  check('turn-on disabled until id + code', await page.getByRole('button', { name: 'Verify and turn on' }).isDisabled());
});
let state = off;
await run('enable', ['setup:view', 'setup:edit'], [verify,
  ['GET', /^\/settings\/integrations\/abdm$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state) })],
  ['PUT', /^\/settings\/integrations\/abdm$/, (r) => { state = on; return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(on) }); }]], async ({ page, log }) => {
  await page.getByLabel('Facility id (HFR)').fill('HFR-1'); await page.getByLabel('Authenticator code').fill('123456');
  await page.getByRole('button', { name: 'Verify and turn on' }).click(); await sleep(700);
  const put = log.requests.find((r) => r.method === 'PUT');
  check('PUT carries enabled + hfrId', put && JSON.parse(put.body).enabled === true && JSON.parse(put.body).hfrId === 'HFR-1', put?.body);
  check('card now shows on + verified, turn-off offered', (await page.getByText('On. Facility HFR-1 verified.').count()) === 1 && (await page.getByRole('button', { name: 'Turn off ABDM' }).count()) === 1);
});
await run('hfr not found', ['setup:view', 'setup:edit'], [verify, ['GET', /^\/settings\/integrations\/abdm$/, { body: off }],
  ['PUT', /^\/settings\/integrations\/abdm$/, { status: 422, body: { type: 'hfr-not-found', title: 'x', detail: 'not found' } }]], async ({ page }) => {
  await page.getByLabel('Facility id (HFR)').fill('NOPE-1'); await page.getByLabel('Authenticator code').fill('123456');
  await page.getByRole('button', { name: 'Verify and turn on' }).click(); await sleep(600);
  check('422 explained, still off', (await page.getByText(/not in the Health Facility Registry/).count()) === 1 && (await page.getByText(/Off\./).count()) === 1);
});
await run('abdm down', ['setup:view', 'setup:edit'], [verify, ['GET', /^\/settings\/integrations\/abdm$/, { body: off }],
  ['PUT', /^\/settings\/integrations\/abdm$/, { status: 503, body: { type: 'abdm-unavailable', title: 'x' } }]], async ({ page }) => {
  await page.getByLabel('Facility id (HFR)').fill('HFR-1'); await page.getByLabel('Authenticator code').fill('123456');
  await page.getByRole('button', { name: 'Verify and turn on' }).click(); await sleep(600);
  check('503 shows try later', (await page.getByText(/cannot be reached right now/).count()) === 1);
});
await run('read-only', ['setup:view'], [['GET', /^\/settings\/integrations\/abdm$/, { body: on }]], async ({ page }) => {
  check('status visible, no controls at all (absent)', (await page.getByText('On. Facility HFR-1 verified.').count()) === 1 && (await page.getByRole('button').filter({ hasText: /ABDM|Verify/ }).count()) === 0 && (await page.getByLabel('Authenticator code').count()) === 0);
});
await run('error', ['setup:view', 'setup:edit'], [['GET', /^\/settings\/integrations\/abdm$/, { status: 500, body: { type: 'server-error', title: 'x' } }]], async ({ page }) => {
  check('error state', (await page.getByText(/Could not load the ABDM settings/).count()) === 1);
});
await run('390px', ['setup:view', 'setup:edit'], [['GET', /^\/settings\/integrations\/abdm$/, { body: off }]], async ({ page }) => {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal scroll', m.s <= m.c, JSON.stringify(m));
  const b = await page.getByRole('button', { name: 'Verify and turn on' }).boundingBox();
  check('390px: button >= 44px', b.height >= 44, String(b.height));
}, { width: 390, height: 844 });
const fails = results.filter((r) => !r.ok);
console.log(fails.length ? `FAILED ${fails.length}` : 'ALL PASS');
process.exit(fails.length ? 1 : 0);
