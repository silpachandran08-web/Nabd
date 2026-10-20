// S12 FIX-B, ACC-028 ui: the phone data-quality list on /patients ("Numbers to fix", patients:edit only): paged list,
// each row opens the phone edit form (PATCH /patients/{id}), loading/empty/error states, permission-absent, 390px.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3560 node tests/e2e/s12-acc-028-data-quality.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const registry = { body: { total: 0, counts: { all: 0 }, rows: [], matchedTotal: 0, hasMore: false } };
const EDIT = ['patients:view', 'patients:create', 'patients:edit'];
const item = (i) => ({ patientId: `p${i}`, displayName: `Legacy ${i}`, rawPhone: `050-1${i}` });

async function open(perms, dq, extra = [], viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { tenantRegion: 'AE' });
  await mockApi(s.page, s.log, [...extra, ['GET', /^\/patients\/data-quality/, dq], ['GET', /^\/patients\/registry/, registry],
    ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }]]);
  await s.page.goto(`${APP}/patients`); await sleep(1200);
  return s;
}
const entry = (page) => page.getByRole('button', { name: 'Numbers to fix' });
const dqCalls = (log) => log.requests.filter((r) => r.path.startsWith('/patients/data-quality'));

let s;
try {
  // permission absent: no entry point and no request
  s = await open(['patients:view', 'patients:create'], { body: { items: [], nextCursor: null } });
  check('A1 without patients:edit the entry point is absent', (await entry(s.page).count()) === 0);
  check('A2 without patients:edit the list is never fetched', dqCalls(s.log).length === 0);
  await s.browser.close(); s = null;

  // happy path: two pages, fix one row, 390px
  const patches = [];
  s = await open(EDIT, (r, req) => r.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify(new URL(req.url()).searchParams.get('cursor') === 'c2'
      ? { items: [item(3)], nextCursor: null } : { items: [item(1), item(2)], nextCursor: 'c2' }) }), [
    ['PATCH', /^\/patients\/p\d$/, (r, req) => { patches.push({ path: new URL(req.url()).pathname, body: JSON.parse(req.postData() ?? '{}') });
      return JSON.parse(req.postData() ?? '{}').phone === 'abc'
        ? r.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Validation failed', detail: 'phone: must be a valid phone number for AE',
          errors: [{ field: 'phone', message: 'must be a valid phone number for AE, or start with + and the country code' }] }) })
        : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 'p1' }) }); }],
  ], { width: 390, height: 844 });
  check('H1 with patients:edit the entry point is shown', (await entry(s.page).count()) === 1);
  check('H2 no data-quality request before it is opened', dqCalls(s.log).length === 0);
  await entry(s.page).click(); await sleep(600);
  const d = s.page.getByRole('dialog', { name: 'Numbers to fix' });
  check('H3 first page lists the flagged patients with their raw number', /Legacy 1/.test(await d.innerText()) && /050-12/.test(await d.innerText()));
  check('H4 the request asks for issue=phone', dqCalls(s.log)[0]?.path.includes('issue=phone'), dqCalls(s.log)[0]?.path);
  await d.getByRole('button', { name: 'Load more' }).click(); await sleep(500);
  check('H5 Load more fetches the next cursor and appends', dqCalls(s.log).some((r) => r.path.includes('cursor=c2')) && /Legacy 3/.test(await d.innerText()) && /Legacy 1/.test(await d.innerText()));
  check('H6 no Load more after the last page', (await d.getByRole('button', { name: 'Load more' }).count()) === 0);
  check('H7 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await d.getByRole('button', { name: 'Fix mobile of Legacy 1' }).click(); await sleep(300);
  const f = s.page.getByRole('dialog', { name: 'Mobile of Legacy 1' });
  const input = f.getByLabel('Mobile');
  check('E1 the row opens the edit form prefilled with the raw number', (await input.inputValue()) === '050-11');
  await input.fill('abc'); await f.getByRole('button', { name: 'Save number' }).click(); await sleep(500);
  check('E2 a refused number shows the field error naming the country', /for AE/.test(await f.locator('#dq-phone-error').innerText().catch(() => '')));
  check('E3 the input is aria-invalid and described by the error', (await input.getAttribute('aria-invalid')) === 'true' && (await input.getAttribute('aria-describedby')) === 'dq-phone-error');
  await input.fill('050 123 4567'); await f.getByRole('button', { name: 'Save number' }).click(); await sleep(600);
  check('E4 PATCH /patients/p1 carries only the phone', patches.at(-1)?.path === '/v1/patients/p1' && JSON.stringify(patches.at(-1)?.body) === '{"phone":"050 123 4567"}', JSON.stringify(patches));
  const back = s.page.getByRole('dialog', { name: 'Numbers to fix' });
  check('E5 back on the list, the fixed row is gone and the others stay', !/Legacy 1\b/.test(await back.innerText()) && /Legacy 2/.test(await back.innerText()));
  check('E6 targets are at least 44px', (await back.getByRole('button', { name: 'Fix mobile of Legacy 2' }).boundingBox()).height >= 44);
  check('H8 zero console errors', noise(s.log).filter((x) => !/400/.test(x)).length === 0, JSON.stringify(noise(s.log)));
  dumpLog('happy', s.log);
  await s.browser.close(); s = null;

  // empty
  s = await open(EDIT, { body: { items: [], nextCursor: null } });
  await entry(s.page).click(); await sleep(500);
  check('S1 empty list says so', /No numbers to fix/.test(await s.page.getByRole('dialog').innerText()));
  await s.browser.close(); s = null;

  // error then retry
  let calls = 0;
  s = await open(EDIT, (r) => (++calls === 1 ? r.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"title":"boom"}' })
    : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [item(7)], nextCursor: null }) })));
  await entry(s.page).click(); await sleep(500);
  const dlg = s.page.getByRole('dialog');
  check('S2 a failed load shows the error state', /Couldn't load the numbers to fix/.test(await dlg.innerText()));
  await dlg.getByRole('button', { name: /retry|try again/i }).first().click(); await sleep(500);
  check('S3 retry loads the list', /Legacy 7/.test(await dlg.innerText()));
  await s.browser.close(); s = null;
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
  if (s) dumpLog('last', s.log);
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
