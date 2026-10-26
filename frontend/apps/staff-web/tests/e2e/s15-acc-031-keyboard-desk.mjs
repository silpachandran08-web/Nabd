// S15 ACC-031: registration and check-in are keyboard-driven. Timed (median of 10): new walk-in < 45 s, returning patient with an
// appointment < 15 s; tab order of the minimum dataset; shortcut sheet in the user's language; smart defaults; permission-absent;
// no layout shift on token issue; 1366x768 and 390px; axe; zero console errors. The mouse is never used (mousedown counter must stay 0).
// Needs `next start` (NABD_PSEUDO=1 not required). Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3808 node tests/e2e/s15-acc-031-keyboard-desk.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
import { axeStep } from './smoke/harness.mjs';

const DOC1 = '33333333-3333-3333-3333-333333333331', DOC2 = '33333333-3333-3333-3333-333333333332';
const RECEPTION = ['queue:view', 'queue:edit', 'queue:create', 'patients:view', 'patients:create'];
const DELAY = 30; // ms per key: a fast typist (~33 keys/s); the budgets below are generous on purpose
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const median = (a) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)];
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];

function handlers({ queueDelay = 0 } = {}) {
  let token = 6;
  return [
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 12 }, delay: queueDelay }],
    ['GET', /^\/queue(\?|$)/, { body: [], delay: queueDelay }],
    ['POST', /^\/queue\/check-in$/, (route) => json(route, { id: `q${++token}`, tokenNumber: token, status: 'checked_in' }, 201)],
    ['POST', /^\/patients$/, (route) => json(route, { id: 'np1' }, 201)],
    ['GET', /^\/staff\/roster/, { body: [{ id: DOC1, name: 'Dr Amal Nasser', departmentName: 'General' }, { id: DOC2, name: 'Dr Nour Aziz', departmentName: 'General' }] }],
    ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
    ['GET', /^\/appointments\/callback-list/, { body: [] }],
    ['GET', /^\/appointments\?/, { body: { data: [
      { id: 'ap1', patientId: 'p9', doctorId: DOC1, status: 'scheduled', startTime: '2026-10-26T10:00:00Z' },
      { id: 'ap2', patientId: 'p8', doctorId: DOC2, status: 'scheduled', startTime: '2026-10-26T11:00:00Z' }], page: { nextCursor: null, limit: 100 } } }],
    ['GET', /^\/patients\/by-phone/, { body: { household: [{ patientId: 'p9', displayName: 'Layla Haddad', age: 31, sex: 'F', relation: 'guardian', lastVisitAt: null }] } }],
    ['GET', /^\/patients\/registry/, { body: { total: 0, counts: { all: 0 }, rows: [], matchedTotal: 0, hasMore: false } }],
    ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }],
    ['GET', /^\/patients\/([^/?]+)\/privacy-notice/, { status: 404, body: {} }],
    ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => json(route, { id: m[1], name: m[1] === 'p9' ? 'Layla Haddad' : 'Omar Said', mrn: `MRN-${m[1]}`, phone: '+919800000009', dob: '1990-01-01', gender: 'female' })],
    ['GET', /^\/patients\?/, { body: { data: [{ id: 'p9', name: 'Layla Haddad', phone: '+919800000009', mrn: 'MRN-p9' }], page: { nextCursor: null, limit: 20 } } }],
    ['GET', /./, { body: [] }],
  ];
}
async function desk({ perms = RECEPTION, viewport, locale, url = '/arrivals', queueDelay } = {}) {
  const s = await launch(viewport ? { viewport } : {});
  if (locale) await s.context.addCookies([{ name: 'nabd_locale', value: locale, url: APP }]);
  await s.page.addInitScript(() => { window.__mouse = 0; addEventListener('mousedown', () => { window.__mouse++; }, true); });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers({ queueDelay }));
  await s.page.goto(APP + url); await s.page.waitForLoadState('networkidle').catch(() => {}); await sleep(900);
  return s;
}
const slip = (page) => page.getByRole('dialog', { name: 'Checked in' });
const mouseCount = (page) => page.evaluate(() => window.__mouse);
const focusedId = (page) => page.evaluate(() => document.activeElement?.id ?? document.activeElement?.tagName);
const axeOk = async (page, step) => { const e = await axeStep(page, step, []); check(`axe "${step}": 0 critical/serious`, e.violations.length === 0, JSON.stringify(e.violations.map((v) => `${v.id}: ${v.nodes?.[0]}`)).slice(0, 400)); };

let s;
try {
  // ---------- A. new walk-in, keyboard only, median of 10
  s = await desk();
  const a = []; let tokenSeen = '';
  for (let i = 0; i < 10; i++) {
    await s.page.keyboard.press('Escape').catch(() => {});
    if (i > 0) { await s.page.reload(); await s.page.waitForLoadState('networkidle').catch(() => {}); await sleep(500); }
    const t0 = Date.now();
    await s.page.keyboard.press('F2');
    await s.page.waitForFunction(() => document.activeElement?.id === 'newName');
    await s.page.keyboard.type(`Layla Haddad ${i}`, { delay: DELAY });
    await s.page.keyboard.press('Tab'); await s.page.keyboard.type('+919812345678', { delay: DELAY });
    await s.page.keyboard.press('Tab'); await s.page.keyboard.type('f');
    await s.page.keyboard.press('Tab'); await s.page.keyboard.type('31', { delay: DELAY });
    await s.page.keyboard.press('F6'); await s.page.keyboard.type('Dr Am'); // change doctor
    await s.page.keyboard.press('Control+Enter');
    await slip(s.page).waitFor();
    a.push(Date.now() - t0);
    tokenSeen = await slip(s.page).innerText();
    if (i === 0) {
      const post = s.log.requests.find((r) => r.method === 'POST' && r.path === '/patients');
      check('A1 the minimum dataset reaches the API (name, phone, gender female, DOB from age 31, no mouse)', post && /Layla Haddad 0/.test(post.body) && /\+919812345678/.test(post.body) && /"gender":"female"/.test(post.body) && /"dob":"19\d\d-01-01"/.test(post.body), post?.body);
      const ci = s.log.requests.find((r) => r.method === 'POST' && r.path === '/queue/check-in');
      check('A2 check-in goes to the typed doctor with the default payment mode', ci && JSON.parse(ci.body).doctorId === DOC1 && JSON.parse(ci.body).paymentMode === 'cash', ci?.body);
    }
  }
  check(`A3 new walk-in -> token < 45 s, median of 10 = ${median(a)} ms (runs: ${a.join(',')})`, median(a) < 45000 && Math.max(...a) < 45000);
  check('A4 the token is shown on the slip', /#\d+/.test(tokenSeen) && /Layla Haddad 9/.test(tokenSeen), tokenSeen);
  check('A5 mouse was never used in the 10 runs', (await mouseCount(s.page)) === 0, String(await mouseCount(s.page)));
  check('A6 a screen-reader announcement names the token and patient', /Token \d+ issued for Layla Haddad 9/.test(await slip(s.page).locator('[role=status]').first().innerText()));
  await axeOk(s.page, 'token slip');
  check('A7 zero console errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  await s.browser.close(); s = null;

  // ---------- B. returning patient with an appointment: F4, type, Enter, Ctrl+Enter
  s = await desk();
  const b = [];
  for (let i = 0; i < 10; i++) {
    if (i > 0) { await s.page.keyboard.press('Escape'); await sleep(200); await s.page.reload(); await s.page.waitForLoadState('networkidle').catch(() => {}); await sleep(500); }
    const t0 = Date.now();
    await s.page.keyboard.press('F4');
    await s.page.waitForFunction(() => document.activeElement?.id === 'checkinFilter');
    await s.page.keyboard.type('Lay', { delay: DELAY });
    await s.page.waitForFunction(() => document.querySelectorAll('[data-results] button').length === 1);
    await s.page.keyboard.press('Enter');
    await s.page.getByRole('dialog').getByText(/appointment with/).waitFor();
    await s.page.keyboard.press('Control+Enter');
    await slip(s.page).waitFor();
    b.push(Date.now() - t0);
    if (i === 0) {
      const ci = s.log.requests.find((r) => r.method === 'POST' && r.path === '/queue/check-in');
      check('B1 check-in is against the booked appointment', ci && JSON.parse(ci.body).appointmentId === 'ap1' && JSON.parse(ci.body).patientId === 'p9', ci?.body);
    }
  }
  check(`B2 find + check in an appointment < 15 s, median of 10 = ${median(b)} ms (runs: ${b.join(',')})`, median(b) < 15000 && Math.max(...b) < 15000);
  check('B3 mouse was never used', (await mouseCount(s.page)) === 0);
  await s.browser.close(); s = null;

  // ---------- C. returning walk-in by phone (F3), by name (Enter picks the first match), arrows, F6, Esc
  s = await desk();
  const tC = Date.now();
  await s.page.keyboard.press('F3');
  await s.page.waitForFunction(() => document.activeElement?.id === 'patientSearch');
  await s.page.keyboard.type('9800000009', { delay: DELAY });
  await s.page.getByRole('group', { name: 'Family with this number' }).waitFor();
  await s.page.keyboard.press('ArrowDown');
  check('C1 ArrowDown moves from the phone box to the first family row', (await s.page.evaluate(() => document.activeElement?.closest('[data-results]') !== null && document.activeElement?.tagName)) === 'BUTTON');
  await s.page.keyboard.press('Enter');
  await s.page.getByRole('button', { name: 'Change' }).waitFor();
  check('C2 the picked patient moves focus to the doctor', (await focusedId(s.page)) === 'doctor');
  await s.page.keyboard.type('Dr Am');
  await s.page.keyboard.press('Control+Enter'); await slip(s.page).waitFor();
  check('C3 returning walk-in from F3 to token under 15 s', Date.now() - tC < 15000, `${Date.now() - tC} ms`);
  await s.page.keyboard.press('Escape'); await sleep(300);
  check('C4 Esc closes the slip', (await s.page.getByRole('dialog').count()) === 0);
  await s.page.keyboard.press('F3'); await s.page.waitForFunction(() => document.activeElement?.id === 'patientSearch');
  await s.page.keyboard.type('Layla', { delay: DELAY });
  await s.page.waitForFunction(() => document.querySelectorAll('[data-results] button').length >= 1);
  await s.page.keyboard.press('Enter');
  check('C5 Enter in the name search picks the first match', (await s.page.getByRole('button', { name: 'Change' }).count()) === 1);
  await s.page.keyboard.press('Escape'); await sleep(300);
  check('C6 Esc closes the dialog', (await s.page.getByRole('dialog').count()) === 0);
  await s.page.keyboard.press('F3'); await s.page.waitForFunction(() => document.activeElement?.id === 'patientSearch');
  await s.page.keyboard.type('what?');
  check('C7 a "?" typed in a field is text, not the shortcut sheet', (await s.page.locator('#patientSearch').inputValue()) === 'what?' && (await s.page.getByRole('dialog').count()) === 1);
  await s.browser.close(); s = null;

  // ---------- D. tab order of the minimum dataset
  s = await desk();
  await s.page.keyboard.press('F2'); await s.page.waitForFunction(() => document.activeElement?.id === 'newName');
  const order = [await focusedId(s.page)];
  for (let i = 0; i < 14; i++) { await s.page.keyboard.press('Tab'); order.push(await focusedId(s.page)); } // the date input has several inner stops: collapse repeats
  const stops = order.filter((x, i) => x !== order[i - 1]);
  check('D1 Tab order: name, mobile, sex, age, DOB, then doctor and payment', stops.slice(0, 5).join() === 'newName,newPhone,newGender,newAge,newDob' && stops.indexOf('doctor') > stops.indexOf('newDob') && stops.indexOf('checkin-payment') > stops.indexOf('doctor'), stops.join());
  check('D2 shortcut buttons announce their keys (aria-keyshortcuts)', await s.page.evaluate(() => document.querySelector('#doctor').getAttribute('aria-keyshortcuts') === 'F6' && document.querySelector('#patientSearch').getAttribute('aria-keyshortcuts') === 'F3'));
  await axeOk(s.page, 'walk-in new-patient form');
  await s.page.keyboard.press('Escape'); await sleep(300);
  await s.page.keyboard.press('F4'); await s.page.waitForFunction(() => document.activeElement?.id === 'checkinFilter');
  await axeOk(s.page, 'appointment picker');
  await s.page.keyboard.press('Escape'); await sleep(300);
  check('D3 board quick actions carry aria-keyshortcuts F2 / F3 / F4', await s.page.evaluate(() => ['F2', 'F3', 'F4'].every((k) => document.querySelector(`[class*=quickActions] [aria-keyshortcuts="${k}"]`))));
  await axeOk(s.page, 'arrivals board');
  await s.browser.close(); s = null;

  // ---------- E. smart defaults: last doctor and payment mode on this desk
  s = await desk();
  await s.page.keyboard.press('F3'); await s.page.waitForFunction(() => document.activeElement?.id === 'patientSearch');
  await s.page.keyboard.type('Layla'); await s.page.waitForFunction(() => document.querySelectorAll('[data-results] button').length >= 1);
  await s.page.keyboard.press('Enter');
  await s.page.keyboard.press('F6'); await s.page.keyboard.type('Dr N'); // second doctor
  await s.page.locator('#checkin-payment').selectOption('card');
  await s.page.keyboard.press('Control+Enter'); await slip(s.page).waitFor();
  await s.page.keyboard.press('Escape'); await sleep(200);
  await s.page.reload(); await s.page.waitForLoadState('networkidle').catch(() => {}); await sleep(600);
  await s.page.keyboard.press('F2'); await s.page.waitForFunction(() => document.activeElement?.id === 'newName');
  check('E1 next walk-in starts with the last-used doctor and payment mode', (await s.page.locator('#doctor').inputValue()) === DOC2 && (await s.page.locator('#checkin-payment').inputValue()) === 'card', `${await s.page.locator('#doctor').inputValue()} / ${await s.page.locator('#checkin-payment').inputValue()}`);
  await s.page.evaluate(() => localStorage.setItem('nabd.desk.lastDoctor', 'someone-from-another-clinic'));
  await s.page.keyboard.press('Escape'); await s.page.reload(); await s.page.waitForLoadState('networkidle').catch(() => {}); await sleep(600);
  await s.page.keyboard.press('F2'); await s.page.waitForFunction(() => document.activeElement?.id === 'newName');
  check('E2 a stored doctor that is not on the roster is ignored', (await s.page.locator('#doctor').inputValue()) === '');
  await s.page.keyboard.press('Control+Enter');
  check('E3 Ctrl+Enter with a missing doctor shows the validation message, no request', /Pick a doctor/.test(await s.page.locator('[role=dialog] [role=alert]').innerText()) && !s.log.requests.some((r) => r.method === 'POST' && r.path === '/patients'));
  await s.browser.close(); s = null;

  // ---------- F. shortcut sheet: en, hi, permission-absent, other desk screens, F2 from another screen
  s = await desk();
  await s.page.keyboard.press('?');
  const sheet = s.page.getByRole('dialog', { name: 'Keyboard shortcuts' });
  await sheet.waitFor();
  const keys = await sheet.locator('dt').allInnerTexts();
  check('F1 "?" shows the sheet with all seven rows', keys.join('|').replace(/\s+/g, ' ') === 'F2|F3|F4|F6|Ctrl + Enter|Esc|?', keys.join('|'));
  check('F2 the sheet is real description-list markup for screen readers', (await sheet.locator('dl dt').count()) === 7 && (await sheet.locator('dl dd').count()) === 7);
  await axeOk(s.page, 'shortcut sheet');
  await s.page.keyboard.press('Escape'); await sleep(200);
  check('F3 Esc closes the sheet', (await s.page.getByRole('dialog').count()) === 0);
  await s.browser.close(); s = null;

  s = await desk({ locale: 'hi' });
  await s.page.keyboard.press('?');
  const hiSheet = s.page.getByRole('dialog', { name: 'कीबोर्ड शॉर्टकट' }); await hiSheet.waitFor();
  const hiText = await hiSheet.innerText();
  check('F4 the sheet is in the user\'s language (hi)', /नया वॉक-इन मरीज़ पंजीकृत करें/.test(hiText) && /डॉक्टर बदलें/.test(hiText) && !/Register a new walk-in/.test(hiText), hiText);
  await s.browser.close(); s = null;

  s = await desk({ perms: ['queue:view'] });
  check('F5 without queue:create the opener buttons are absent', (await s.page.locator('[class*=quickActions]').count()) === 0);
  await s.page.keyboard.press('F2'); await s.page.keyboard.press('F3'); await s.page.keyboard.press('F4'); await sleep(300);
  check('F6 without queue:create F2 / F3 / F4 open nothing', (await s.page.getByRole('dialog').count()) === 0);
  await s.page.keyboard.press('?');
  const lim = await s.page.getByRole('dialog', { name: 'Keyboard shortcuts' }).locator('dt').allInnerTexts();
  check('F7 the sheet lists only what this role can do (no F2/F3/F4)', lim.join('|').replace(/\s+/g, ' ') === 'F6|Ctrl + Enter|Esc|?', lim.join('|'));
  await s.browser.close(); s = null;

  s = await desk({ perms: [...RECEPTION], url: '/patients' });
  await s.page.keyboard.press('?'); await s.page.getByRole('dialog', { name: 'Keyboard shortcuts' }).waitFor();
  check('F8 "?" works on the patient registry too', true);
  await s.page.keyboard.press('Escape'); await sleep(200);
  await s.page.keyboard.press('F2');
  await s.page.waitForURL(/\/arrivals/, { timeout: 8000 }).catch(() => {});
  await s.page.waitForFunction(() => document.activeElement?.id === 'newName', null, { timeout: 8000 }).catch(() => {});
  check('F9 F2 on another desk screen lands on Arrivals with the new-patient form focused', /\/arrivals/.test(s.page.url()) && (await focusedId(s.page)) === 'newName', `${s.page.url()} ${await focusedId(s.page)}`);
  await s.browser.close(); s = null;

  // ---------- G. no layout shift when the token is issued (queue position and wait arrive 900 ms later), 1366x768, 390px
  s = await desk({ queueDelay: 900, viewport: { width: 1366, height: 768 } });
  await s.page.keyboard.press('F3'); await s.page.waitForFunction(() => document.activeElement?.id === 'patientSearch');
  await s.page.keyboard.type('Layla'); await s.page.waitForFunction(() => document.querySelectorAll('[data-results] button').length >= 1);
  await s.page.keyboard.press('Enter'); await s.page.keyboard.press('F6'); await s.page.keyboard.type('Dr Am');
  check('G1 1366x768: the confirm button is inside the viewport without scrolling the page', await s.page.evaluate(() => { const b = document.querySelector('[role=dialog] button[type=submit]'); const r = b.getBoundingClientRect(); return r.bottom <= window.innerHeight && r.top >= 0; }));
  await s.page.keyboard.press('Control+Enter'); await slip(s.page).waitFor();
  const box = async () => s.page.evaluate(() => { const r = (sel) => document.querySelector(sel)?.getBoundingClientRect(); const d = document.querySelector('[role=dialog]').getBoundingClientRect(); const facts = document.querySelector('[role=dialog] dl'); const f = facts.getBoundingClientRect(); const btn = [...document.querySelectorAll('[role=dialog] button')].pop().getBoundingClientRect();
    return { dialogH: Math.round(d.height), dialogTop: Math.round(d.top), factsTop: Math.round(f.top), factsH: Math.round(f.height), btnTop: Math.round(btn.top), busy: facts.getAttribute('aria-busy') }; });
  const early = await box(); await sleep(1800); const late = await box();
  check('G2 token slip does not shift when position and wait arrive', early.busy === 'true' && late.busy === 'false' && early.dialogH === late.dialogH && early.factsTop === late.factsTop && early.factsH === late.factsH && early.btnTop === late.btnTop, JSON.stringify({ early, late }));
  check('G3 1366x768: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
  await s.browser.close(); s = null;

  s = await desk({ viewport: { width: 390, height: 844 } });
  await s.page.keyboard.press('F2'); await s.page.waitForFunction(() => document.activeElement?.id === 'newName');
  check('G4 390px: no horizontal scroll with the new-patient form open', await s.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
  check('G5 390px: form controls are at least 44px high', await s.page.evaluate(() => [...document.querySelectorAll('[role=dialog] form button, [role=dialog] form select, [role=dialog] form input:not([type=checkbox]), [role=dialog] form textarea')].filter((e) => e.offsetParent).every((e) => e.getBoundingClientRect().height >= 44)));
  check('G6 zero console errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  await s.browser.close(); s = null;
} catch (e) {
  check('script completed without throwing', false, String(e?.stack ?? e).slice(0, 600));
  if (s) { dumpLog('x', s.log); await s.browser.close().catch(() => {}); }
}
const failed = results.filter((x) => !x.ok); console.log(`\n${results.length - failed.length}/${results.length} passed`); process.exit(failed.length ? 1 : 0);
