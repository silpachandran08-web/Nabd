// FEF-028: Arrivals feature folder. Board does not re-render per keystroke (80 entries), a render error in the walk-in
// dialog leaves the board usable and offers a recover action, error.tsx recovers a page-level error, per-route title,
// duplicate review and walk-in behaviour unchanged, permission-absent / empty / error states, 390 px, zero console errors.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3711 node tests/e2e/s13-fef028-arrivals-split.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const DOC = '33333333-3333-3333-3333-333333333331';
const RECEPTION = ['queue:view', 'queue:edit', 'queue:create', 'patients:view', 'patients:create'];
const entry = (n, status = 'waiting') => ({
  id: `q${n}`, appointmentId: null, patientId: `p${n}`, doctorId: DOC, departmentId: 'd1', queueDate: '2026-10-20', tokenNumber: n,
  status, priority: false, priorityReason: null, createdAt: new Date(Date.now() - n * 60000).toISOString(), source: 'walk_in',
});
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const handlers = (state, extra = []) => [
  ...extra,
  ['GET', /^\/queue(\?|$)/, (route) => json(route, state.queue)],
  ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr Amal Nasser', departmentName: 'General' }] }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
  ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
  ['GET', /^\/appointments\/callback-list/, { body: [] }],
  ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => json(route, { id: m[1], name: `Patient ${m[1]}`, mrn: `MRN-${m[1]}` })],
  ['GET', /^\/patients\?/, (route) => json(route, state.search ?? { data: [] })],
  ['GET', /./, { body: [] }],
];
async function open({ perms = RECEPTION, state, extra, viewport, probe = false, url = '/arrivals' } = {}) {
  const ctx = await launch(viewport ? { viewport } : {});
  if (probe) await ctx.page.addInitScript(() => { window.__nbRenderProbe = {}; });
  await seedClinic(ctx.page, perms);
  await mockApi(ctx.page, ctx.log, handlers(state, extra));
  await ctx.page.goto(APP + url);
  await ctx.page.waitForLoadState('networkidle').catch(() => {});
  await sleep(600);
  return ctx;
}
const probe = (page) => page.evaluate(() => ({ ...window.__nbRenderProbe }));
const cleanConsole = (log, allow = []) => [...log.pageErrors.filter((e) => !allow.some((a) => a.test(e))), ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED/.test(x) && !allow.some((a) => a.test(x)))];
// A deliberate render error: dev says "Objects are not valid as a React child", the production build "Minified React error #31".
const REACT_RENDER_ERR = [/Objects are not valid as a React child/, /Minified React error #31/, /arrivals dialog failed/, /The above error occurred/];
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);

// AC2: 80 entries; typing in the walk-in form never re-renders the board or a row. Typing speed is reported.
{
  const state = { queue: Array.from({ length: 80 }, (_, i) => entry(i + 1, i % 3 ? 'waiting' : 'checked_in')) };
  const { page, browser, log } = await open({ state, probe: true });
  check('AC2 80 rows rendered', (await page.locator('tbody tr').count()) === 80);
  check('title is the per-route Arrivals title', (await page.title()) === 'Arrivals · Nabd', await page.title());
  await page.getByRole('button', { name: /Walk-in/ }).click();
  await page.locator('#patientSearch').waitFor();
  const before = await probe(page);
  check('probe counts commits (board and rows committed on load)', before['arrivals-board'] >= 1 && before['arrivals-row'] >= 80, JSON.stringify(before));
  const text = 'Layla Haddad Test Name 12345';
  const t0 = Date.now();
  await page.locator('#patientSearch').pressSequentially(text, { delay: 0 });
  const per = (Date.now() - t0) / text.length;
  await sleep(500);
  const after = await probe(page);
  check('AC2 board did not re-render while typing', after['arrivals-board'] === before['arrivals-board'], `${before['arrivals-board']} -> ${after['arrivals-board']}`);
  check('AC2 no row re-rendered while typing', after['arrivals-row'] === before['arrivals-row'], `${before['arrivals-row']} -> ${after['arrivals-row']}`);
  check('AC2 typed text is in the field', (await page.locator('#patientSearch').inputValue()) === text);
  check('AC2 average time per keystroke well under 100 ms (Playwright round trip, proxy for INP)', per < 100, `${per.toFixed(1)} ms/key`);
  console.log(`  typing: ${per.toFixed(1)} ms per keystroke over ${text.length} keys`);
  check('no horizontal scroll at 1280', await noOverflow(page));
  check('zero console errors (happy path)', cleanConsole(log).length === 0, cleanConsole(log).join(' | '));
  dumpLog('ac2', log);
  await browser.close();
}

// Clock tick isolated: a minute passing re-renders only the Wait cells (board and rows not re-committed).
{
  const state = { queue: Array.from({ length: 10 }, (_, i) => entry(i + 1)) };
  const ctx = await launch();
  const { page, browser, log } = ctx;
  await page.addInitScript(() => { window.__nbRenderProbe = {}; });
  await page.clock.install({ time: Date.now() });
  await seedClinic(page, RECEPTION);
  await mockApi(page, log, handlers(state));
  await page.goto(APP + '/arrivals');
  await page.locator('tbody tr').first().waitFor();
  await page.clock.runFor(1000);
  const waitCell = () => page.locator('tbody tr').first().locator('td[data-label="Wait"]').innerText();
  const w0 = await waitCell();
  const p0 = await probe(page);
  await page.clock.runFor(61_000);
  const w1 = await waitCell();
  const p1 = await probe(page);
  check('tick: Wait cell advanced after a minute', w0 !== w1, `${w0} -> ${w1}`);
  check('tick: rows did not re-commit for the clock', p1['arrivals-row'] === p0['arrivals-row'], `${p0['arrivals-row']} -> ${p1['arrivals-row']}`);
  await browser.close();
}

// AC3: a render error inside the walk-in dialog -> recover action, board stays usable.
{
  const state = { queue: Array.from({ length: 4 }, (_, i) => entry(i + 1)), search: { data: [{ id: 'x', name: { oops: 1 }, phone: '1', mrn: 'm' }] } };
  const { page, browser, log } = await open({ state });
  await page.getByRole('button', { name: /Walk-in/ }).click();
  await page.locator('#patientSearch').fill('Zed');
  await page.getByRole('button', { name: 'Close and continue' }).waitFor({ timeout: 5000 }).catch(() => {});
  const recover = page.getByRole('button', { name: 'Close and continue' });
  check('AC3 walk-in render error shows a recover action', await recover.isVisible());
  check('AC3 the error page was not used (board still on screen)', (await page.locator('tbody tr').count()) === 4 && !(await page.getByText("Arrivals couldn't be shown").count()));
  await page.locator('tbody tr').nth(1).click(); // board still usable: a row opens its detail rail, which replaces the failed dialog
  check('AC3 board usable: clicking a row opens its detail', await page.getByRole('dialog').getByText('#2').first().isVisible().catch(() => false));
  await page.keyboard.press('Escape');
  state.search = { data: [] };
  await page.getByRole('button', { name: /Walk-in/ }).click();
  check('AC3 the walk-in dialog opens again afterwards', await page.locator('#patientSearch').isVisible());
  await page.locator('#patientSearch').fill('Zed');
  await sleep(500);
  check('AC3 no recover panel when the dialog renders', (await page.getByRole('button', { name: 'Close and continue' }).count()) === 0);
  const errs = cleanConsole(log, REACT_RENDER_ERR);
  check('AC3 no unexpected console errors', errs.length === 0, errs.join(' | '));
  await browser.close();
}
{
  // Same, but use the recover button itself.
  const state = { queue: [entry(1), entry(2)], search: { data: [{ id: 'x', name: { oops: 1 }, phone: '1', mrn: 'm' }] } };
  const { page, browser } = await open({ state });
  await page.getByRole('button', { name: /Walk-in/ }).click();
  await page.locator('#patientSearch').fill('Zed');
  await page.getByRole('button', { name: 'Close and continue' }).click();
  check('AC3 recover closes the failed dialog, board intact', (await page.getByRole('dialog').count()) === 0 && (await page.locator('tbody tr').count()) === 2);
  await browser.close();
}

// error.tsx: a render error outside the dialogs (a row with a malformed token) -> the route error page with Try again.
{
  const bad = { ...entry(1), tokenNumber: { oops: 1 } };
  const state = { queue: [bad] };
  const { page, browser, log } = await open({ state });
  const again = page.getByRole('button', { name: 'Try again' });
  await again.waitFor({ timeout: 5000 }).catch(() => {});
  check('error.tsx: page-level render error shows "Arrivals couldn\'t be shown" with Try again', await again.isVisible() && await page.getByText("Arrivals couldn't be shown").isVisible());
  state.queue = [entry(1), entry(2)];
  await again.click();
  await page.locator('tbody tr').first().waitFor({ timeout: 5000 }).catch(() => {});
  check('error.tsx: Try again brings the board back', (await page.locator('tbody tr').count()) === 2);
  dumpLog('error.tsx', log);
  await browser.close();
}

// Duplicate review (behaviour unchanged): 409 with candidates -> list, "Use this patient" then check-in with that id.
{
  const state = { queue: [entry(1)], search: { data: [] } };
  const posts = [];
  const extra = [
    ['POST', /^\/patients$/, (route, req) => { posts.push(['patients', req.postDataJSON()]); return json(route, { type: 'duplicate', title: 'Possible duplicate', status: 409, candidates: [{ patientId: 'pd1', name: 'Layla Hadad', phone: '555', matchScore: 0.9 }] }, 409); }],
    ['POST', /^\/queue\/check-in$/, (route, req) => { posts.push(['checkin', req.postDataJSON()]); return json(route, { id: 'q9', tokenNumber: 9 }, 201); }],
  ];
  const { page, browser, log } = await open({ state, extra });
  await page.getByRole('button', { name: /Walk-in/ }).click();
  await page.locator('#patientSearch').fill('Layla');
  await sleep(700);
  await page.getByRole('button', { name: /No match/ }).click();
  await page.locator('#newName').fill('Layla Haddad');
  await page.locator('#newPhone').fill('555');
  await page.locator('#newDob').fill('1990-01-01');
  await page.locator('#doctor').selectOption(DOC);
  await page.getByRole('button', { name: 'Check in', exact: true }).last().click();
  const group = page.getByRole('group', { name: 'Possible existing patients' });
  await group.waitFor();
  check('duplicate review lists the candidate with its match percent', /Layla Hadad/.test(await group.innerText()) && /90% match/.test(await group.innerText()));
  await group.getByRole('button', { name: 'Use this patient' }).click();
  check('duplicate review closes once a candidate is chosen', (await page.getByRole('group', { name: 'Possible existing patients' }).count()) === 0);
  await page.getByRole('button', { name: 'Check in', exact: true }).last().click();
  await page.getByText(/token/i).first().waitFor({ timeout: 5000 }).catch(() => {});
  const ci = posts.find((p) => p[0] === 'checkin');
  check('chosen candidate is checked in by id, no second registration', ci?.[1].patientId === 'pd1' && posts.filter((p) => p[0] === 'patients').length === 1, JSON.stringify(posts));
  check('result dialog ("Checked in") shown after check-in', await page.getByRole('dialog', { name: 'Checked in' }).isVisible({ timeout: 5000 }).catch(() => false));
  dumpLog('dup', log);
  await browser.close();
}

// Permission-absent, empty, error, 390 px.
{
  const { page, browser } = await open({ perms: ['queue:view'], state: { queue: [entry(1)] } });
  check('perm-absent: no Walk-in / Check in / Move controls, no row actions', (await page.getByRole('button', { name: /Walk-in|Check in|Move queue|Notify delay|Send for vitals/ }).count()) === 0);
  check('perm-absent: board still shown', (await page.locator('tbody tr').count()) === 1);
  await browser.close();
}
{
  const { page, browser } = await open({ state: { queue: [] } });
  check('empty: empty state', await page.getByText('No patients here yet').isVisible());
  await browser.close();
}
{
  const { page, browser } = await open({ state: { queue: [] }, extra: [['GET', /^\/queue(\?|$)/, { status: 500, body: { title: 'x', detail: 'y' } }]] });
  check('error: error state with Retry', await page.getByText(/Couldn.t load today.s arrivals/).isVisible() && await page.getByRole('button', { name: 'Retry' }).isVisible());
  await browser.close();
}
{
  const { page, browser, log } = await open({ state: { queue: Array.from({ length: 12 }, (_, i) => entry(i + 1)) }, viewport: { width: 390, height: 800 } });
  check('390px: no horizontal scroll', await noOverflow(page));
  await page.getByRole('button', { name: /Walk-in/ }).click();
  check('390px: walk-in dialog opens, no horizontal scroll', await page.locator('#patientSearch').isVisible() && await noOverflow(page));
  check('390px: zero console errors', cleanConsole(log).length === 0, cleanConsole(log).join(' | '));
  await browser.close();
}
// ?action=walkin deep link (TopBar) opens the dialog once and strips the param.
{
  const { page, browser } = await open({ state: { queue: [entry(1)] }, url: '/arrivals?action=walkin' });
  check('?action=walkin opens the walk-in dialog', await page.locator('#patientSearch').isVisible());
  check('?action=walkin is stripped from the URL', !page.url().includes('action='), page.url());
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
