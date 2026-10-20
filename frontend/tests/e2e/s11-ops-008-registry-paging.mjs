// OPS-008: Patient Registry pages with the server's keyset cursor (infinite scroll + Load more), no client-side filtering.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3211 node s11-ops-008-registry-paging.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, sleep } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const RECEPTION = ['patients:view', 'patients:create', 'patients:edit', 'queue:view', 'queue:create', 'billing:view'];
const VIEW_ONLY = ['patients:view', 'queue:view'];
const row = (id, name, extra = {}) => ({ id, mrn: 'MRN-' + id, name, phone: '+911234567890', age: 30, gender: 'male', minor: false, lastVisit: null,
  nextAppointment: null, nextDoctorName: null, nextDoctorId: null, nextAppointmentId: null, nextAppointmentToday: false, packageSessionsUsed: null,
  packageSessionsTotal: null, condition: null, followUpDue: false, allergy: null, balanceDue: false, duplicate: false, archived: false,
  queueToken: null, queueStatus: null, tags: [], ...extra });
const counts = { all: 3, recent: 0, followup: 0, package: 0, balance: 1, duplicate: 0, archived: 0 };
const page1 = { total: 3, counts, rows: [row('p1', 'Aarav Mehta'), row('p2', 'Bina Shah')], matchedTotal: 3, hasMore: true, page: { nextCursor: 'CUR1', limit: 200 } };
const page2 = { total: 3, counts, rows: [row('p3', 'Zoya Khan')], matchedTotal: 3, hasMore: false, page: { nextCursor: null, limit: 200 } };
const balancePage = { total: 3, counts, rows: [row('p9', 'Zed Owes', { balanceDue: true, tags: ['balance'] })], matchedTotal: 1, hasMore: false, page: { nextCursor: null, limit: 200 } };
const empty = { total: 0, counts: { all: 0, recent: 0, followup: 0, package: 0, balance: 0, duplicate: 0, archived: 0 }, rows: [], matchedTotal: 0, hasMore: false, page: { nextCursor: null, limit: 200 } };

const base = [
  ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }],
  ['GET', /^\/queue/, { body: [] }],
];
async function open(perms, registry, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, [...base, ['GET', /^\/patients\/registry/, registry]]);
  await s.page.route('**/fake-poppins-*.woff2', (r) => r.fulfill({ status: 200, contentType: 'font/woff2', body: '' }));
  await s.page.goto(APP + '/patients', { waitUntil: 'domcontentloaded' });
  return s;
}
const regCalls = (s) => s.log.requests.filter((r) => r.path.startsWith('/patients/registry')).map((r) => r.path);
const names = (s) => s.page.locator('tbody tr').allInnerTexts();
async function finish(s, tag, allow = () => false) {
  const errs = [...s.log.pageErrors, ...s.log.console.filter((x) => x.startsWith('[error]') && !allow(x)), ...s.log.unmatched.map((u) => 'unmocked ' + u)];
  check(`${tag}: zero console/page errors`, errs.length === 0, errs.slice(0, 2).join(' | '));
  if (errs.length) dumpLog(tag, s.log);
  await s.browser.close();
}
async function run(name, fn) {
  try { await fn(); } catch (e) { check(name, false, String(e).split('\n')[0]); }
}
const byCursor = (second, delay = 0) => async (route, req) => {
  const u = new URL(req.url());
  if (u.searchParams.get('filter') === 'balance') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(balancePage) });
  if (u.searchParams.get('cursor') === 'CUR1') {
    if (delay) await sleep(delay);
    return second(route);
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(page1) });
};
const ok2 = (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(page2) });

// 1. Happy path: page 1, then the next keyset page is fetched with the cursor (infinite scroll) and appended.
await run('happy', async () => {
  const s = await open(RECEPTION, byCursor(ok2, 1200));
  await s.page.getByText('Aarav Mehta').waitFor({ timeout: 10000 });
  await s.page.getByText('Showing 2 of 3.').waitFor({ timeout: 3000 });
  check('page 1: footer says "Showing 2 of 3." while more exist', true);
  check('page 1: Load more shows "Loading…" while the next page is in flight', await s.page.getByRole('button', { name: 'Loading…' }).isVisible());
  await s.page.getByText('Zoya Khan').waitFor({ timeout: 5000 });
  const rows = await names(s);
  check('page 2 appended in server order: 3 rows, Zoya Khan last', rows.length === 3 && rows[2].includes('Zoya Khan'), String(rows.length));
  const calls = regCalls(s);
  const cur = calls.filter((p) => p.includes('cursor=CUR1'));
  check('next page requested once, with cursor=CUR1 and the same filter', cur.length === 1 && cur[0].includes('filter=all'), calls.join(' , '));
  check('no limit sent: server default page size (200)', calls.every((p) => !p.includes('limit=')));
  check('last page: footer and Load more are gone', (await s.page.getByRole('button', { name: 'Load more' }).count()) === 0 && (await s.page.getByText(/^Showing \d/).count()) === 0);
  await finish(s, 'happy');
});

// 2. Filter chip goes to the server (no client-side filtering): rows shown exactly as returned.
await run('filter', async () => {
  const s = await open(RECEPTION, byCursor(ok2, 5000));
  await s.page.getByText('Aarav Mehta').waitFor({ timeout: 10000 });
  await s.page.getByRole('tab', { name: /Outstanding balance/ }).click();
  await s.page.getByText('Zed Owes').waitFor({ timeout: 5000 });
  const rows = await names(s);
  check('balance chip: server-filtered rows replace the list (1 row)', rows.length === 1, String(rows.length));
  check('balance chip: request carries filter=balance', regCalls(s).some((p) => p.includes('filter=balance')));
  await finish(s, 'filter');
});

// 3. Error on the next page: message + Try again (the keyboard path) recovers.
await run('more-error', async () => {
  let fail = true;
  const s = await open(RECEPTION, byCursor((route) => fail
    ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', detail: 'x' }) })
    : ok2(route)));
  await s.page.getByText("Couldn't load more patients.").waitFor({ timeout: 10000 });
  check('next page 500: "Couldn\'t load more patients." shown, rows kept', (await names(s)).length === 2);
  const box = await s.page.getByRole('button', { name: 'Try again' }).boundingBox();
  check('footer button is a 44px target', box && box.height >= 44, box && String(box.height));
  fail = false;
  await s.page.getByRole('button', { name: 'Try again' }).click();
  await s.page.getByText('Zoya Khan').waitFor({ timeout: 5000 });
  check('Try again loads the page and appends it', (await names(s)).length === 3);
  await finish(s, 'more-error', (x) => x.includes('status of 500'));
});

// 4. Empty registry.
await run('empty', async () => {
  const s = await open(RECEPTION, { body: empty });
  await s.page.getByText('No patients here.').waitFor({ timeout: 10000 });
  check('empty: "No patients here." and no Load more', (await s.page.getByRole('button', { name: 'Load more' }).count()) === 0);
  await finish(s, 'empty');
});

// 5. Whole-registry error.
await run('error', async () => {
  const s = await open(RECEPTION, { status: 500, body: { title: 'x', detail: 'x' } });
  await s.page.getByText("Couldn't load patients. Try again.").waitFor({ timeout: 10000 });
  check('registry 500: error state shown', true);
  await finish(s, 'error', (x) => x.includes('status of 500'));
});

// 6. Permission-absent: a view-only role has no Register button and no balance / duplicate chips, but still pages.
await run('perm', async () => {
  const s = await open(VIEW_ONLY, byCursor(ok2));
  await s.page.getByText('Zoya Khan').waitFor({ timeout: 10000 });
  check('view-only: Register patient absent', (await s.page.getByRole('button', { name: /Register patient/ }).count()) === 0);
  check('view-only: balance and duplicate chips absent', (await s.page.getByRole('tab', { name: /Outstanding balance|Duplicate review/ }).count()) === 0);
  check('view-only: paging still works (3 rows)', (await names(s)).length === 3);
  await finish(s, 'perm');
});

// 7. 390px: no horizontal page scroll with the paging footer on screen.
await run('mobile', async () => {
  const s = await open(RECEPTION, byCursor(ok2, 4000), { width: 390, height: 844 });
  await s.page.getByText('Aarav Mehta').waitFor({ timeout: 10000 });
  await s.page.getByText('Showing 2 of 3.').waitFor({ timeout: 3000 });
  const noScroll = await s.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
  check('390px: no horizontal page scroll', noScroll);
  await finish(s, 'mobile');
});

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
