// S14 IDC-003: owner privacy notice editor (locked clauses, step-up publish, history), desk re-acknowledge prompt.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3818 node s14-idc003-privacy-notice.mjs
import { launch, seedClinic, mockApi, check, sleep, dumpLog, APP } from './qa-lib.mjs';

const CL = { en: ['[CLAUSE-EN-1]', '[CLAUSE-EN-2]'], hi: ['[CLAUSE-HI-1]', '[CLAUSE-HI-2]'] };
const mk = (lang, version, current, reviewed = false) => ({ version, language: lang, effectiveFrom: '2026-10-01T00:00:00Z', mandatoryHash: 'h'.repeat(64), templateKey: 'IN-DPDP-1', legalReviewed: reviewed, current,
  bodyMd: current ? `# QA Clinic\n\n${CL[lang].join('\n\n')}\n\nGrievance contact: Dr G` : null, lockedClauses: current ? CL[lang] : null });
const VERSIONS = { versions: [mk('en', '1', true), mk('hi', '1', true)] };
const now = new Date(Date.now() - 900000).toISOString();
const queue = [{ id: 'a1', appointmentId: null, patientId: 'p1', doctorId: 'd1', departmentId: 'dep1', queueDate: '2026-10-18', tokenNumber: 1, status: 'waiting', priority: false, priorityReason: null, createdAt: now, source: 'walk_in' }];
const arrivalsBase = [
  ['GET', /^\/queue(\?.*)?$/, { body: queue }], ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
  ['GET', /^\/patients\/p1$/, { body: { name: 'Patient One', mrn: 'M1', phone: '+911' } }], ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }], ['GET', /^\/appointments\/callback-list/, { body: [] }],
];

async function run(tag, perms, handlers, path, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms); await mockApi(page, log, handlers);
    await page.goto(APP + path); await sleep(1800); await fn({ page, log });
    check(`${tag} zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}

const editor = (put) => [['GET', /^\/settings\/privacy\/privacy-notice$/, { body: VERSIONS }], ['POST', /^\/auth\/mfa\/verify$/, { body: { stepUpToken: 'su-token' } }], ['PUT', /^\/settings\/privacy\/privacy-notice$/, put]];
const PRIV = '/setup?tab=privacy';

await run('editor', ['setup:view', 'setup:edit'], editor({ body: { version: '2' } }), PRIV, async ({ page, log }) => {
  check('draft banner shown while wording is unreviewed', (await page.getByText(/placeholders until your data protection officer/).count()) === 1);
  check('locked clauses listed', (await page.getByLabel('Locked clauses').locator('li').count()) === 2);
  check('language tabs en + hi', (await page.getByRole('tab').count()) === 2);
  const publish = page.getByRole('button', { name: 'Publish new version' });
  check('publish disabled until changed + code', await publish.isDisabled());
  const ta = page.getByLabel(/Notice text/);
  const body = await ta.inputValue();
  await ta.fill(body.replace('[CLAUSE-EN-2]', ''));
  await page.getByLabel('Authenticator code').fill('123456');
  check('deleting a locked clause is blocked in the UI', (await page.getByText(/locked clause was changed or removed/).count()) === 1 && await publish.isDisabled());
  await ta.fill(body + '\n\nOur own paragraph.');
  check('adding text enables publish', await publish.isEnabled());
  await publish.click(); await sleep(700);
  const put = log.requests.find((r) => r.method === 'PUT');
  const sent = put && JSON.parse(put.body);
  check('PUT sent with language, template, base version', sent && sent.language === 'en' && sent.basedOnTemplate === 'IN-DPDP-1' && sent.baseVersion === '1' && sent.bodyMd.includes('Our own paragraph.'), put?.body);
  check('success message', (await page.getByText(/Version 2 published/).count()) === 1);
  await page.getByRole('tab', { name: 'Hindi' }).click(); await sleep(200);
  check('switching language shows that language\'s locked clauses', (await page.getByLabel('Locked clauses').innerText()).includes('CLAUSE-HI-1'));
});

await run('conflict', ['setup:view', 'setup:edit'], editor({ status: 409, body: { type: 'version-conflict', title: 'Version conflict', detail: 'The notice changed.' } }), PRIV, async ({ page }) => {
  const ta = page.getByLabel(/Notice text/);
  await ta.fill((await ta.inputValue()) + '\n\nx'); await page.getByLabel('Authenticator code').fill('123456');
  await page.getByRole('button', { name: 'Publish new version' }).click(); await sleep(600);
  check('409 explained and reloaded', (await page.getByText(/changed since you opened it/).count()) === 1);
});
await run('bad code', ['setup:view', 'setup:edit'], [['GET', /^\/settings\/privacy\/privacy-notice$/, { body: VERSIONS }], ['POST', /^\/auth\/mfa\/verify$/, { status: 401, body: { type: 'mfa-failed', title: 'x', detail: 'Code not accepted.' } }]], PRIV, async ({ page, log }) => {
  const ta = page.getByLabel(/Notice text/);
  await ta.fill((await ta.inputValue()) + '\n\nx'); await page.getByLabel('Authenticator code').fill('000000');
  await page.getByRole('button', { name: 'Publish new version' }).click(); await sleep(500);
  check('wrong code shows the server detail and sends no PUT', (await page.getByText('Code not accepted.').count()) === 1 && !log.requests.some((r) => r.method === 'PUT'));
});
await run('read-only', ['setup:view'], editor({}), PRIV, async ({ page }) => {
  check('no editor, no code field, no publish button (absent, not disabled)', (await page.getByLabel(/Notice text/).count()) === 0 && (await page.getByLabel('Authenticator code').count()) === 0 && (await page.getByRole('button', { name: /Publish/ }).count()) === 0);
  check('notice text still readable', (await page.getByText('Grievance contact: Dr G').count()) === 1);
});
await run('empty', ['setup:view', 'setup:edit'], [['GET', /^\/settings\/privacy\/privacy-notice$/, { body: { versions: [] } }]], PRIV, async ({ page }) => {
  check('empty state', (await page.getByText('No privacy notice yet').count()) === 1);
});
await run('error', ['setup:view', 'setup:edit'], [['GET', /^\/settings\/privacy\/privacy-notice$/, { status: 500, body: { type: 'server-error', title: 'x' } }]], PRIV, async ({ page }) => {
  check('error state with retry', (await page.getByText(/server had a problem/).count()) === 1 && (await page.getByRole('button', { name: /retry|try again/i }).count()) >= 1);
});
await run('390px', ['setup:view', 'setup:edit'], editor({}), PRIV, async ({ page }) => {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal scroll', m.s <= m.c, JSON.stringify(m));
  const h = await page.getByRole('button', { name: 'Publish new version' }).boundingBox();
  check('390px: publish target >= 44px', h.height >= 44, String(h.height));
}, { width: 390, height: 844 });

const status = (needs) => ({ body: needs ? { needsReAcknowledgement: true, acceptedVersion: '1', currentVersion: '2', language: 'en' } : { needsReAcknowledgement: false, acceptedVersion: '2', currentVersion: '2', language: 'en' } });
const openRail = async (page) => { await page.getByText('Patient One').first().click(); await sleep(700); };
await run('desk prompt, one tap', ['queue:view', 'patients:view', 'patients:edit'], [...arrivalsBase,
  ['GET', /^\/patients\/p1\/privacy-notice$/, status(true)], ['POST', /^\/patients\/p1\/privacy-notice\/acknowledge$/, status(false)]], '/arrivals', async ({ page, log }) => {
  await openRail(page);
  check('prompt shown with versions', (await page.getByText(/accepted v1, now v2/).count()) === 1);
  await page.getByRole('button', { name: 'Patient re-acknowledged' }).click(); await sleep(600);
  check('one tap posts the acknowledgement and clears the prompt', log.requests.filter((r) => r.method === 'POST' && /acknowledge$/.test(r.path)).length === 1 && (await page.getByText(/accepted v1, now v2/).count()) === 0);
});
await run('desk prompt, view only', ['queue:view', 'patients:view'], [...arrivalsBase, ['GET', /^\/patients\/p1\/privacy-notice$/, status(true)]], '/arrivals', async ({ page }) => {
  await openRail(page);
  check('prompt visible but the button is absent', (await page.getByText(/accepted v1, now v2/).count()) === 1 && (await page.getByRole('button', { name: 'Patient re-acknowledged' }).count()) === 0);
});
await run('desk prompt, up to date', ['queue:view', 'patients:view', 'patients:edit'], [...arrivalsBase, ['GET', /^\/patients\/p1\/privacy-notice$/, status(false)]], '/arrivals', async ({ page }) => {
  await openRail(page);
  check('no prompt when the notice is current', (await page.getByText(/privacy notice changed/).count()) === 0);
});
await run('desk prompt, status error', ['queue:view', 'patients:view', 'patients:edit'], [...arrivalsBase, ['GET', /^\/patients\/p1\/privacy-notice$/, { status: 500, body: { type: 'server-error', title: 'x' } }]], '/arrivals', async ({ page }) => {
  await openRail(page);
  check('a failing status check never blocks the rail', (await page.getByText('Patient One').count()) >= 1 && (await page.getByText(/privacy notice changed/).count()) === 0);
});
const fails = (await import('./qa-lib.mjs')).results.filter((r) => !r.ok);
console.log(fails.length ? `FAILED ${fails.length}` : 'ALL PASS');
process.exit(fails.length ? 1 : 0);
