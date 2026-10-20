// S10 QA FEF-005 (independent): idle lock cannot be bypassed, cross-tab lock, per-tenant timeout + 30 s warning, drafts encrypted + deleted on sign-out.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3413 node s10qa-fef-005-idle-lock.mjs
import { launch, seedClinic, mockApi, clinicToken, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const DOC = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit', 'setup:view'];
const NURSE = ['queue:view', 'nursing:view'];
const NAME = 'Zainab Phimarker';
const entry = { id: 'e1', appointmentId: null, patientId: 'p1', doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: 'p1', mrn: 'M-1', name: NAME, phone: '', dob: '1998-03-04', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const ok = (body) => ({ status: 200, body });
const policy = (v) => ['GET', /^\/setup\/policies/, v === 'forbidden' ? { status: 403, body: { title: 'Forbidden' } } : ok(v == null ? [] : [{ id: '1', policyKey: 'session.idle_lock_minutes', value: String(v), version: 1 }])];
const state = { noteSaves: 0, unlockPin: null, unlockStatus: 200 };
const handlers = (pol = null) => [
  policy(pol),
  ['POST', /^\/auth\/refresh/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ accessToken: clinicToken(DOC), refreshToken: 'r2', expiresIn: 900 }) })],
  ['POST', /^\/auth\/unlock/, (r, req) => { state.unlockPin = JSON.parse(req.postData()).pin; return state.unlockStatus === 200 ? r.fulfill({ status: 204, body: '' }) : r.fulfill({ status: state.unlockStatus, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', retryAfterSeconds: 60 }) }); }],
  ['POST', /^\/auth\/logout/, { status: 204 }],
  ['GET', /^\/queue(\?|$)/, ok([entry])],
  ['GET', /^\/patients\/p1/, ok(patient)],
  ['GET', /^\/departments\/d1\/flow/, ok({ steps: [] })],
  ['GET', /^\/clinical\/notes\/e1\/amendments/, ok([])],
  ['GET', /^\/clinical\/notes\/e1/, { status: 404, body: {} }],
  ['PATCH', /^\/clinical\/notes\/e1/, (r) => { state.noteSaves++; return r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'down' }) }); }], // server never accepts => draft stays on device
  ['GET', /^\/clinical\/(vitals|prescriptions)\//, { status: 404, body: {} }],
  ['GET', /./, ok([])],
];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::|status of 500/.test(x))];
const dialog = (p) => p.getByRole('dialog', { name: 'Screen locked' });
const dbs = (p) => p.evaluate(async () => (await indexedDB.databases()).map((d) => d.name));
const rawDrafts = (p) => p.evaluate(() => new Promise((res) => {
  const r = indexedDB.open('nabd-consult-drafts');
  r.onerror = () => res(null);
  r.onsuccess = () => {
    const db = r.result;
    if (!db.objectStoreNames.contains('drafts')) { db.close(); return res([]); }
    const g = db.transaction('drafts').objectStore('drafts').getAll();
    g.onsuccess = () => { res(g.result.map((x) => ({ keys: Object.keys(x).sort(), ivLen: x.iv?.byteLength, ctLen: x.ct?.byteLength, dump: JSON.stringify(x) + new TextDecoder('latin1').decode(x.ct ?? new ArrayBuffer(0)) + new TextDecoder('latin1').decode(x.iv ?? new ArrayBuffer(0)) }))); db.close(); };
  };
}));

async function open(perms, pol, path = '/arrivals', opts = {}) {
  const s = await launch(opts);
  await seedClinic(s.page, perms);
  await s.page.addInitScript((name) => { // PHI watcher: did the patient name EVER reach the DOM in this document?
    window.__phi = 0;
    const chk = () => { if (document.body && document.body.innerText.includes(name)) window.__phi++; };
    new MutationObserver(chk).observe(document, { childList: true, subtree: true, characterData: true });
  }, NAME);
  await mockApi(s.page, s.log, handlers(pol));
  await s.page.goto(APP + path, { waitUntil: 'networkidle' });
  return s;
}
const tabbableOutside = (p) => p.evaluate(() => [...document.querySelectorAll('a[href],button,input,select,textarea,[tabindex]')].filter((e) => !e.closest('[role=dialog]') && !e.closest('[inert]') && !e.closest('nextjs-portal') && e.tabIndex >= 0 && !e.disabled).map((e) => e.outerHTML.slice(0, 60)));

let s;
try {
  // ===== AC1: F5 / new tab while locked => overlay before any PHI =====
  s = await open(DOC, null);
  await s.page.getByText(NAME).first().waitFor({ timeout: 6000 }).catch(() => {});
  check('AC1.0 precondition: unlocked page shows patient name (PHI present when allowed)', await s.page.getByText(NAME).count() > 0);
  await s.page.keyboard.press('Control+l');
  await dialog(s.page).waitFor({ timeout: 3000 });
  const reqBefore = s.log.requests.length;
  await s.page.reload({ waitUntil: 'domcontentloaded' });
  await dialog(s.page).waitFor({ timeout: 4000 });
  await sleep(1500);
  check('AC1.1 F5 while locked: overlay shown', await dialog(s.page).isVisible());
  check('AC1.2 F5 while locked: patient name never entered the DOM after reload', (await s.page.evaluate(() => window.__phi)) === 0, `phi sightings=${await s.page.evaluate(() => window.__phi)}`);
  const after = s.log.requests.slice(reqBefore).filter((r) => /^\/(queue|patients|clinical)/.test(r.path));
  check('AC1.3 F5 while locked: no PHI API request fired', after.length === 0, after.map((r) => r.path).join(','));
  check('AC1.4 lock stored in sessionStorage and localStorage', await s.page.evaluate(() => !!sessionStorage.getItem('nabd_locked_at') && !!localStorage.getItem('nabd_locked_at')));
  // clearing sessionStorage only (as a brand-new tab would have) still locks
  await s.page.evaluate(() => sessionStorage.clear());
  await s.page.reload({ waitUntil: 'domcontentloaded' });
  await dialog(s.page).waitFor({ timeout: 4000 });
  check('AC1.5 fresh-tab simulation (sessionStorage empty): still locked', true);
  // real new tab
  const tabB = await s.context.newPage();
  const logB = { requests: [], console: [], pageErrors: [], unmatched: [] };
  tabB.on('pageerror', (e) => logB.pageErrors.push(String(e)));
  await mockApi(tabB, logB, handlers());
  await tabB.addInitScript((name) => { window.__phi = 0; new MutationObserver(() => { if (document.body && document.body.innerText.includes(name)) window.__phi++; }).observe(document, { childList: true, subtree: true, characterData: true }); }, NAME);
  await tabB.goto(APP + '/arrivals', { waitUntil: 'domcontentloaded' });
  await dialog(tabB).waitFor({ timeout: 4000 });
  await sleep(1200);
  check('AC1.6 brand-new tab opens locked, no PHI in DOM, no PHI request', (await tabB.evaluate(() => window.__phi)) === 0 && logB.requests.filter((r) => /^\/(queue|patients)/.test(r.path)).length === 0);
  // tampering: deleting the flag in this tab's storage is not a bypass for the other tab (broadcast + localStorage); removing both is the user clearing site data -> out of scope
  // ===== AC2: cross-tab lock/unlock within 1 s =====
  await s.page.bringToFront();
  await s.page.getByLabel('PIN').fill('123456');
  const t0 = Date.now();
  await s.page.getByRole('button', { name: 'Unlock' }).click();
  await dialog(s.page).waitFor({ state: 'detached', timeout: 5000 });
  await dialog(tabB).waitFor({ state: 'detached', timeout: 1000 });
  check('AC2.1 unlocking one tab unlocks the other within 1 s of the first unlock', true, `${Date.now() - t0} ms incl. unlock request`);
  check('AC2.2 PIN sent to /auth/unlock', state.unlockPin === '123456');
  await tabB.waitForSelector('#app-root > *', { timeout: 3000 });
  const tl = Date.now();
  await tabB.bringToFront();
  await tabB.keyboard.press('Control+l');
  await dialog(s.page).waitFor({ timeout: 1000 });
  check('AC2.3 locking one tab (Ctrl+L) locks the other within 1 s', true, `${Date.now() - tl} ms`);
  // idle lock in one tab also reaches the other (idle path uses the same persist/broadcast)
  await tabB.close();
  // wrong PIN: distinct errors, still locked
  state.unlockStatus = 401;
  await s.page.getByLabel('PIN').fill('999999');
  await s.page.getByRole('button', { name: 'Unlock' }).click();
  await s.page.getByRole('alert').filter({ hasText: /./ }).first().waitFor({ timeout: 3000 });
  const wrong = (await s.page.getByRole('alert').allInnerTexts()).join('|');
  state.unlockStatus = 423;
  await s.page.getByLabel('PIN').fill('999998');
  await s.page.getByRole('button', { name: 'Unlock' }).click();
  await sleep(800);
  const lockedMsg = (await s.page.getByRole('alert').allInnerTexts()).join('|');
  check('AC2.4 wrong PIN keeps the lock; lockout (423) message differs from wrong-PIN message', await dialog(s.page).isVisible() && wrong !== lockedMsg && lockedMsg.length > 0, `401="${wrong}" 423="${lockedMsg}"`);
  state.unlockStatus = 200;
  check('AC2.5 zero console/page errors (tab A)', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // ===== AC3: Tab never leaves the dialog; root inert; dialog semantics =====
  s = await open(DOC, null);
  await s.page.getByText(NAME).first().waitFor({ timeout: 6000 }).catch(() => {});
  await s.page.keyboard.press('Control+l');
  await dialog(s.page).waitFor({ timeout: 3000 });
  check('AC3.1 #app-root is inert while locked', await s.page.locator('#app-root').evaluate((e) => e.inert === true && e.hasAttribute('inert')));
  check('AC3.2 role=dialog, aria-modal=true, accessible name "Screen locked"', (await dialog(s.page).getAttribute('aria-modal')) === 'true');
  check('AC3.3 PIN input has an accessible label', await s.page.getByLabel('PIN').count() === 1);
  let inside = true, n = 0;
  for (let i = 0; i < 25; i++) { await s.page.keyboard.press('Tab'); n++; inside &&= await s.page.evaluate(() => !!document.activeElement?.closest('[role=dialog]')); }
  for (let i = 0; i < 25; i++) { await s.page.keyboard.press('Shift+Tab'); n++; inside &&= await s.page.evaluate(() => !!document.activeElement?.closest('[role=dialog]')); }
  check('AC3.4 50 Tab / Shift+Tab presses: focus never leaves the dialog', inside, `${n} presses`);
  const outside = await tabbableOutside(s.page);
  check('AC3.5 no focusable PHI control exists outside the dialog / inert subtree', outside.length === 0, outside.join(' || '));
  await s.page.evaluate(() => { const b = document.querySelector('#app-root button, #app-root a[href]'); b && b.focus(); });
  check('AC3.6 programmatic focus() on a control behind the lock is refused', await s.page.evaluate(() => !document.activeElement?.closest('#app-root')));
  const hit = await s.page.evaluate(() => { const e = document.elementFromPoint(innerWidth / 2, innerHeight / 2); return !!e?.closest('[role=dialog]') || getComputedStyle(e.closest('div')).position === 'fixed'; });
  check('AC3.7 a click at the page centre lands on the lock overlay, not the page', hit);
  check('AC3.8 background page is not exposed to AT (inert subtree), dialog exposed', await s.page.evaluate(() => document.querySelector('#app-root').inert && !!document.querySelector('[role=dialog][aria-labelledby]')));
  check('AC3.9 CLAIM-NOT-VERIFIABLE: real screen-reader announcement (proxy = role/aria-modal/labelledby above)', true, 'NOT-RUNNABLE with a real SR');
  await s.page.setViewportSize({ width: 390, height: 800 });
  check('AC3.10 390px: no horizontal scroll, Unlock >= 44px', await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth) && (await s.page.getByRole('button', { name: 'Unlock' }).evaluate((e) => e.getBoundingClientRect().height)) >= 44);
  await s.browser.close();

  // ===== AC4: per-tenant timeout 10 min, 30 s warning, key cancels =====
  async function timing(pol, perms, lockAfterMin, label) {
    const t = await launch();
    await seedClinic(t.page, perms);
    await mockApi(t.page, t.log, handlers(pol));
    await t.page.clock.install();
    await t.page.goto(APP + '/arrivals', { waitUntil: 'networkidle' });
    const warnAt = lockAfterMin * 60_000 - 30_000;
    await t.page.clock.runFor(warnAt - 1500);
    const early = await t.page.getByText(/Screen locks in/).count();
    await t.page.clock.runFor(2500);
    const warn = await t.page.getByText(/Screen locks in/).count();
    const txt = warn ? await t.page.getByText(/Screen locks in/).first().innerText() : '';
    await t.page.clock.runFor(6000);
    const txt2 = await t.page.getByText(/Screen locks in/).count() ? await t.page.getByText(/Screen locks in/).first().innerText() : '';
    const num = (x) => Number((x.match(/(\d+) s/) ?? [])[1]);
    check(`${label} no warning before T-30s; warning at T-30s`, early === 0 && warn === 1, `early=${early} warn=${warn} "${txt}"`);
    check(`${label} countdown counts down`, num(txt2) < num(txt), `"${txt}" -> "${txt2}"`);
    await t.page.keyboard.press('a');
    check(`${label} any key press cancels warning and does not lock`, await t.page.getByText(/Screen locks in/).count() === 0 && await dialog(t.page).count() === 0);
    await t.page.clock.runFor(warnAt - 5000); // not yet warned again (timer restarted by the key)
    check(`${label} idle timer restarted by the key`, await t.page.getByText(/Screen locks in/).count() === 0 && await dialog(t.page).count() === 0);
    await t.page.clock.runFor(40_000);
    await dialog(t.page).waitFor({ timeout: 3000 }).catch(() => {});
    check(`${label} locks after the full timeout without activity`, await dialog(t.page).count() === 1);
    await t.browser.close();
  }
  await timing(10, NURSE, 10, 'AC4a policy 10 min (nurse reads?)');
  await timing(null, DOC, 5, 'AC4b no policy => 5 min default');
  await timing('forbidden', NURSE, 5, 'AC4c policy unreadable (403) => 5 min');
  await timing(2, DOC, 2, 'AC4d boundary policy 2 min');
  await timing(30, DOC, 30, 'AC4e boundary policy 30 min');
  await timing(1, DOC, 5, 'AC4f out-of-range policy 1 => default 5');
  await timing(31, DOC, 5, 'AC4g out-of-range policy 31 => default 5');
  await timing('abc', DOC, 5, 'AC4h junk policy => default 5');

  // ===== AC5/AC6: drafts ciphertext, deleted on every sign-out path =====
  async function withDraft(perms) {
    const t = await open(perms, null, '/consult?entry=e1&step=assess');
    const ta = t.page.locator('#assessment');
    await ta.waitFor({ timeout: 8000 });
    await ta.fill('ZEBRA-CONFIDENTIAL-NOTE-12345');
    for (let i = 0; i < 20 && !(await dbs(t.page)).includes('nabd-consult-drafts'); i++) await sleep(300);
    await sleep(1500);
    return t;
  }
  s = await withDraft(DOC);
  const names = await dbs(s.page);
  check('AC5.0 precondition: typing a note created the drafts database', names.includes('nabd-consult-drafts'), names.join(','));
  const recs = await rawDrafts(s.page);
  check('AC6.1 a raw record exists and has exactly {entryId, iv, ct}', Array.isArray(recs) && recs.length >= 1 && recs.every((r) => r.keys.join() === 'ct,entryId,iv'), JSON.stringify(recs?.map((r) => r.keys)));
  check('AC6.2 raw record contains no note text (neither JSON nor binary view)', recs.length >= 1 && recs.every((r) => !/ZEBRA|CONFIDENTIAL|NOTE-12345/.test(r.dump)));
  check('AC6.3 iv is 12 bytes and ciphertext is longer than the plaintext (GCM tag present)', recs.every((r) => r.ivLen === 12 && r.ctLen > 'ZEBRA-CONFIDENTIAL-NOTE-12345'.length + 16));
  const stor = await s.page.evaluate(() => JSON.stringify({ l: { ...localStorage }, s: { ...sessionStorage } }));
  check('AC6.4 no note text and no key material in web storage', !/ZEBRA/.test(stor) && !/"kty"|"k":|jwk|nabd_draft_key/i.test(stor));
  const keyIdb = (await dbs(s.page)).filter((n) => n !== 'nabd-consult-drafts');
  check('AC6.5 no other IndexedDB database that could hold a key (apart from the app\'s token pair store)', keyIdb.every((n) => /nabd/i.test(n) === false || /pair|session|token/i.test(n)), keyIdb.join(','));
  // reload: key is lost by design => the old record cannot be read; user is warned; no plaintext appears
  await s.page.reload({ waitUntil: 'networkidle' });
  await s.page.locator('textarea').first().waitFor({ timeout: 8000 }).catch(() => {});
  await sleep(1500);
  const afterReload = await s.page.evaluate(() => document.body.innerText);
  check('AC6.6 after reload the old draft is NOT restored as readable text (key was memory-only)', !afterReload.includes('ZEBRA-CONFIDENTIAL'));
  check('AC6.7 after reload the user is told an unsent draft could not be recovered', /could not be recovered|unsent local draft/i.test(afterReload), afterReload.match(/.{0,40}draft.{0,60}/i)?.[0] ?? 'no notice');
  await s.browser.close();

  // sign out via the menu
  s = await withDraft(DOC);
  check('AC5.1 (menu) precondition: drafts DB exists', (await dbs(s.page)).includes('nabd-consult-drafts'));
  await s.page.locator('button').filter({ hasText: 'Rana Reception' }).first().click();
  await s.page.getByRole('button', { name: 'Sign out' }).click();
  await s.page.waitForURL(/\/login/, { timeout: 6000 });
  check('AC5.2 menu sign-out: nabd-consult-drafts does not exist', !(await dbs(s.page)).includes('nabd-consult-drafts'), (await dbs(s.page)).join(','));
  check('AC5.3 tokens + lock flag cleared', await s.page.evaluate(() => !localStorage.getItem('nabd_access_token') && !localStorage.getItem('nabd_refresh_token') && !localStorage.getItem('nabd_locked_at')));
  await s.browser.close();

  // sign out from the lock screen
  s = await withDraft(DOC);
  await s.page.keyboard.press('Control+l');
  await dialog(s.page).waitFor({ timeout: 3000 });
  await s.page.getByRole('button', { name: /Sign out/ }).click();
  await s.page.waitForURL(/\/login/, { timeout: 6000 });
  check('AC5.4 lock-screen sign-out: nabd-consult-drafts does not exist', !(await dbs(s.page)).includes('nabd-consult-drafts'), (await dbs(s.page)).join(','));
  check('AC5.5 lock-screen sign-out: next visit to the app is not locked for the next user', await s.page.evaluate(() => !localStorage.getItem('nabd_locked_at') && !sessionStorage.getItem('nabd_locked_at')));
  // next user logs in on the same browser profile: no draft DB, no lock
  await s.page.goto(APP + '/login', { waitUntil: 'domcontentloaded' });
  check('AC5.6 still no drafts DB after revisiting /login', !(await dbs(s.page)).includes('nabd-consult-drafts'));
  await s.browser.close();

  // forced sign-out (refresh rejected) also clears drafts (extra path, not in the AC text)
  s = await withDraft(DOC);
  await s.page.route(/\/v1\/auth\/refresh/, (r) => r.fulfill({ status: 401, contentType: 'application/problem+json', body: '{"title":"REFRESH_INVALID"}' }));
  await s.page.keyboard.press('Control+l');
  await dialog(s.page).waitFor({ timeout: 3000 });
  await s.page.getByLabel('PIN').fill('123456');
  await s.page.getByRole('button', { name: 'Unlock' }).click();
  await sleep(2500);
  const names2 = await dbs(s.page).catch(() => []);
  check('AC5.7 (extra) session expired during lock: drafts DB state', true, `url=${new URL(s.page.url()).pathname} dbs=${names2.join(',')}`);
  await s.browser.close();
  s = null;
} catch (e) {
  check('script ran to completion', false, String(e).split('\n').slice(0, 3).join(' / '));
  if (s) { dumpLog('last', s.log); console.log('  url:', s.page.url()); }
  await s?.browser.close().catch(() => {});
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
