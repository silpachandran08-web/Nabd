// S14 ACC-029 household picker on Arrivals walk-in (keys 1-9) and ACC-030 duplicate suggestions on /patients
// (compare, merge with step-up, dismiss, undo). Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3802 node tests/e2e/s14-acc-029-030-household-duplicates.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const json = (body, status = 200) => (r) => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const member = (i, rel) => ({ patientId: `m${i}`, displayName: `Member ${i}`, age: 30 - i, sex: 'female', relation: rel, lastVisitAt: i === 1 ? '2026-09-01T00:00:00Z' : null, guardianId: null });

// ---------- ACC-029
const arrivals = (household) => [
  ['GET', /^\/patients\/by-phone/, household],
  ['GET', /^\/patients\?/, { body: { data: [], page: { nextCursor: null, limit: 20 } } }],
  ['GET', /^\/queue(\?.*)?$/, { body: [] }],
  ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Who', departmentName: 'GP' }] }],
  ['GET', /^\/appointments\?/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
  ['GET', /^\/appointments\/callback-list/, { body: [] }],
];
async function walkIn(perms, household, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, arrivals(household));
  await s.page.goto(`${APP}/arrivals`); await sleep(1500);
  await s.page.getByRole('button', { name: /Walk-in/ }).click(); await sleep(500);
  return s;
}
const ARR = ['queue:view', 'queue:create', 'patients:view', 'patients:create'];
const byPhone = (log) => log.requests.filter((r) => r.path.startsWith('/patients/by-phone'));

// ---------- ACC-030
const brief = (id, name, extra = {}) => ({ patientId: id, mrn: `MRN-${id}`, displayName: name, dob: '1990-01-01', sex: 'male', phone: '+919877700051', lastVisitAt: null, ...extra });
const SUG = { suggestionId: 'a1_b1', a: brief('a1', 'Abdulrahman Mohammed'), b: brief('b1', 'Abdulrahman Mohamed', { dob: '1991-02-02' }), score: 0.74, matchedFields: ['phone', 'name'] };
const MERGE = { mergeId: 'mg1', mergedAt: '2026-10-20T10:00:00Z', undoUntil: '2026-11-19T10:00:00Z', duplicate: brief('b9', 'Old Duplicate'), survivor: brief('a9', 'Kept Record') };
const MERGER = ['patients:view', 'patients:create', 'patients:edit', 'patients:delete'];
const registry = { body: { total: 0, counts: { all: 0 }, rows: [], matchedTotal: 0, hasMore: false } };
async function patients(perms, extra, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, [...extra, ['GET', /^\/patients\/registry/, registry], ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }]]);
  await s.page.goto(`${APP}/patients`); await sleep(1200);
  return s;
}
const btn = (page) => page.getByRole('button', { name: 'Possible duplicates' });

let s;
try {
  // ===== ACC-029
  s = await walkIn(ARR, json({ household: [member(1, 'guardian'), member(2, 'child'), member(3, 'child')] }), { width: 390, height: 844 });
  const search = s.page.getByLabel('Patient');
  await search.fill('+91 98777 00001'); await sleep(700);
  const grp = s.page.getByRole('group', { name: 'Family with this number' });
  check('F1 a phone query lists the household with relation, age and last visit', /Member 1/.test(await grp.innerText()) && /parent \/ guardian/.test(await grp.innerText()) && /child/.test(await grp.innerText()) && /last visit 01\/09\/2026/.test(await grp.innerText()), await grp.innerText().catch(() => 'none'));
  check('F2 rows are numbered 1-3', (await grp.locator('kbd').allInnerTexts()).join('') === '123');
  check('F3 one lookup request with the typed phone', byPhone(s.log).length === 1 && decodeURIComponent(byPhone(s.log)[0].path).includes('+91 98777 00001'), JSON.stringify(byPhone(s.log)));
  check('F4 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  check('F5 rows are at least 44px high', (await grp.getByRole('button').first().boundingBox()).height >= 44);
  await s.page.keyboard.press('3'); await sleep(200); // focus is in the search box: a digit is typed, not a pick
  check('F6 digits typed in the search box do not pick a row', (await grp.count()) === 0 || (await search.inputValue()).endsWith('3'));
  await search.fill('+91 98777 00001'); await sleep(600);
  await s.page.locator('body').click({ position: { x: 5, y: 5 } }); await s.page.keyboard.press('2'); await sleep(300);
  check('F7 key 2 outside the field picks the second row', /Member 2/.test(await s.page.getByRole('dialog').innerText()) && (await s.page.getByRole('button', { name: 'Change' }).count()) === 1);
  check('F8 zero console errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  dumpLog('f', s.log); await s.browser.close(); s = null;

  s = await walkIn(ARR, json({ household: [] }));
  await s.page.getByLabel('Patient').fill('+919877700099'); await sleep(600);
  check('E1 an empty household shows no picker', (await s.page.getByRole('group', { name: 'Family with this number' }).count()) === 0);
  await s.browser.close(); s = null;

  s = await walkIn(ARR, json({ title: 'x' }, 500));
  await s.page.getByLabel('Patient').fill('+919877700099'); await sleep(700);
  check('E2 a failed lookup says so and normal search stays', /Couldn't look up the family/.test(await s.page.getByRole('dialog').innerText()));
  await s.browser.close(); s = null;

  s = await walkIn(ARR, json({ title: 'Forbidden' }, 403));
  await s.page.getByLabel('Patient').fill('+919877700099'); await sleep(600);
  check('E3 without patients:view for the lookup the picker is absent, no error shown', !/Couldn't look up/.test(await s.page.getByRole('dialog').innerText()));
  await s.browser.close(); s = null;

  s = await walkIn(ARR, json({ household: [] }));
  await s.page.getByLabel('Patient').fill('Mona'); await sleep(500);
  check('E4 a name query never calls the lookup', byPhone(s.log).length === 0);
  await s.browser.close(); s = null;

  // ===== ACC-030
  s = await patients(['patients:view', 'patients:create', 'patients:edit'], []);
  check('P1 without patients:delete the entry point is absent', (await btn(s.page).count()) === 0);
  check('P2 and nothing is fetched', !s.log.requests.some((r) => r.path.startsWith('/patients/duplicate-suggestions') || r.path.startsWith('/patients/merges')));
  await s.browser.close(); s = null;

  // happy path: compare, merge with step-up, undo
  const posts = [];
  let merged = false;
  s = await patients(MERGER, [
    ['GET', /^\/patients\/duplicate-suggestions/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: merged ? [] : [SUG], nextCursor: null }) })],
    ['GET', /^\/patients\/merges$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: merged ? [MERGE] : [] }) })],
    ['POST', /^\/auth\/mfa\/verify/, (r, req) => { posts.push({ p: 'verify', b: req.postData() }); return JSON.parse(req.postData()).code === '000000'
      ? r.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ title: 'mfa', detail: 'That code is not valid.' }) })
      : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ stepUpToken: 'stepup-token' }) }); }],
    ['POST', /^\/patients\/a1\/merge/, (r, req) => { posts.push({ p: 'merge', b: req.postData(), h: req.headers()['x-step-up-token'] }); merged = true; return r.fulfill({ status: 200, contentType: 'application/json', body: '{}' }); }],
    ['POST', /^\/patients\/merges\/mg1\/undo/, (r, req) => { posts.push({ p: 'undo', h: req.headers()['x-step-up-token'] }); merged = false; return r.fulfill({ status: 204 }); }],
  ], { width: 390, height: 844 });
  await btn(s.page).click(); await sleep(700);
  const d = s.page.getByRole('dialog', { name: 'Possible duplicates' });
  check('D1 the list shows both names and the matched fields', /Abdulrahman Mohammed/.test(await d.innerText()) && /matched: phone, name/.test(await d.innerText()), await d.innerText());
  check('D2 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await d.getByRole('button', { name: /^Compare/ }).click(); await sleep(300);
  const c = s.page.getByRole('dialog', { name: 'Compare records' });
  check('D3 side by side: both records with name, patient ID, DOB, mobile', /MRN-a1/.test(await c.innerText()) && /MRN-b1/.test(await c.innerText()) && /1991-02-02/.test(await c.innerText()));
  check('D4 two radio choices, the first record kept by default', (await c.getByRole('radio').count()) === 2 && await c.getByRole('radio').first().isChecked());
  check('D5 merge button names the kept record', (await c.getByRole('button', { name: 'Merge into Abdulrahman Mohammed' }).count()) === 1);
  await c.getByRole('button', { name: /^Merge into/ }).click(); await sleep(200);
  check('D6 merging asks for the authenticator code and has not merged yet', (await c.getByLabel('Authenticator code').count()) === 1 && !posts.some((p) => p.p === 'merge'));
  await c.getByLabel('Authenticator code').fill('000000'); await c.getByRole('button', { name: 'Confirm merge' }).click(); await sleep(500);
  check('D7 a wrong code shows the error and merges nothing', /not valid/.test(await c.locator('#dup-stepup-error').innerText().catch(() => '')) && !posts.some((p) => p.p === 'merge'));
  await c.getByLabel('Authenticator code').fill('123456'); await c.getByRole('button', { name: 'Confirm merge' }).click(); await sleep(900);
  const m = posts.find((p) => p.p === 'merge');
  check('D8 merge posts the duplicate id with the step-up token', m?.h === 'stepup-token' && JSON.parse(m.b).duplicatePatientId === 'b1', JSON.stringify(m));
  const d2 = s.page.getByRole('dialog', { name: 'Possible duplicates' });
  check('D9 back on the list: notice, suggestion gone, recent merge with undo', /Merged Abdulrahman Mohamed into Abdulrahman Mohammed/.test(await d2.innerText()) && /No duplicate suggestions/.test(await d2.innerText()) && /Old Duplicate/.test(await d2.innerText()), await d2.innerText());
  await d2.getByRole('button', { name: 'Undo merge of Old Duplicate' }).click(); await sleep(200);
  await d2.getByLabel('Authenticator code').fill('123456'); await d2.getByRole('button', { name: 'Undo merge', exact: true }).click(); await sleep(900);
  const u = posts.find((p) => p.p === 'undo');
  check('D10 undo posts with a step-up token', u?.h === 'stepup-token');
  check('D11 after undo the suggestion returns', /matched: phone, name/.test(await s.page.getByRole('dialog', { name: 'Possible duplicates' }).innerText()));
  check('D12 targets are at least 44px', (await s.page.getByRole('dialog').getByRole('button', { name: /^Compare/ }).boundingBox()).height >= 44);
  check('D13 zero console errors', noise(s.log).filter((x) => !/401/.test(x)).length === 0, JSON.stringify(noise(s.log)));
  dumpLog('d', s.log); await s.browser.close(); s = null;

  // 31-day undo -> 409 message, dismiss, empty, error + retry, empty page with cursor
  const dismissed = [];
  s = await patients(MERGER, [
    ['GET', /^\/patients\/duplicate-suggestions/, (r, req) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(new URL(req.url()).searchParams.get('cursor') === 'k1' ? { items: [SUG], nextCursor: null } : { items: [], nextCursor: 'k1' }) })],
    ['GET', /^\/patients\/merges$/, { body: { items: [MERGE] } }],
    ['POST', /^\/auth\/mfa\/verify/, { body: { stepUpToken: 't' } }],
    ['POST', /^\/patients\/merges\/mg1\/undo/, json({ title: 'Undo window passed', detail: 'A merge can be undone for 30 days.', code: 'UNDO_WINDOW_PASSED' }, 409)],
    ['POST', /^\/patients\/duplicate-suggestions\/a1_b1\/dismiss/, (r, req) => { dismissed.push(req.headers()['idempotency-key']); return r.fulfill({ status: 204 }); }],
  ]);
  await btn(s.page).click(); await sleep(800);
  const e = s.page.getByRole('dialog', { name: 'Possible duplicates' });
  check('X1 an empty page with a cursor keeps scanning and shows the next hit', /Abdulrahman Mohammed/.test(await e.innerText()) && s.log.requests.filter((r) => r.path.startsWith('/patients/duplicate-suggestions')).length === 2);
  await e.getByRole('button', { name: 'Undo merge of Old Duplicate' }).click(); await e.getByLabel('Authenticator code').fill('123456'); await e.getByRole('button', { name: 'Undo merge', exact: true }).click(); await sleep(700);
  check('X2 UNDO_WINDOW_PASSED shows the 30-day message', /older than 30 days/.test(await e.innerText()), await e.innerText());
  await e.getByRole('button', { name: /^Compare/ }).click(); await sleep(200);
  await s.page.getByRole('dialog', { name: 'Compare records' }).getByRole('button', { name: 'Not a duplicate' }).click(); await sleep(600);
  check('X3 dismiss posts with an Idempotency-Key and drops the suggestion', dismissed.length === 1 && !!dismissed[0] && /No duplicate suggestions/.test(await s.page.getByRole('dialog', { name: 'Possible duplicates' }).innerText()));
  await s.browser.close(); s = null;

  let calls = 0;
  s = await patients(MERGER, [['GET', /^\/patients\/duplicate-suggestions/, (r) => (++calls === 1 ? r.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"title":"boom"}' }) : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [SUG], nextCursor: null }) }))],
    ['GET', /^\/patients\/merges$/, { body: { items: [] } }]]);
  await btn(s.page).click(); await sleep(700);
  const er = s.page.getByRole('dialog');
  check('X4 a failed load shows the error state', /Couldn't load the suggestions/.test(await er.innerText()));
  await er.getByRole('button', { name: /retry|try again/i }).first().click(); await sleep(600);
  check('X5 retry loads the list', /Abdulrahman Mohammed/.test(await er.innerText()));
  await s.browser.close(); s = null;
} catch (e) {
  check('script ran to completion', false, String(e).split('\n')[0]);
  if (s) dumpLog('last', s.log);
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
