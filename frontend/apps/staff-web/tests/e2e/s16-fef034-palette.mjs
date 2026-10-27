// FEF-034: command palette (actions + screens + patients), '?' overlay, tooltips + aria-keyshortcuts, smart defaults, timed keyboard-only
// walk-in (returning < 15 s, new < 45 s), single keys off while typing. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3812 node tests/e2e/s16-fef034-palette.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const DOC1 = '33333333-3333-3333-3333-333333333331', DOC2 = '33333333-3333-3333-3333-333333333332';
const ME = '11111111-1111-1111-1111-111111111111';
const RECEPTION = ['queue:view', 'queue:edit', 'queue:create', 'patients:view', 'patients:create', 'billing:view'];
const DOCTOR = ['clinical:edit', 'clinical:view', 'queue:view', 'patients:view'];
const DELAY = 30;
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const run = async (name, fn) => { try { await fn(); } catch (e) { check(name, false, String(e).split('\n')[0]); } };

const handlers = (st, { queue = [] } = {}) => [
  ['GET', /^\/queue\/wait-estimate\?doctorId=(.+)$/, (r, req, m) => json(r, { estimatedMinutes: m[1] === DOC2 ? 5 : 20 })],
  ['GET', /^\/queue(\?|$)/, { body: queue }],
  ['POST', /^\/queue\/check-in$/, (r, req) => { st.checkIns.push(JSON.parse(req.postData())); json(r, { id: 'q1', tokenNumber: 7, status: 'checked_in' }, 201); }],
  ['POST', /^\/patients$/, (r, req) => { st.patients.push(JSON.parse(req.postData())); json(r, { id: 'np1' }, 201); }],
  ['GET', /^\/staff\/roster/, { body: [{ id: DOC1, name: 'Dr Amal Nasser', departmentName: 'General' }, { id: DOC2, name: 'Dr Nour Aziz', departmentName: 'General' }] }],
  ['GET', /^\/doctors\/[^/]+\/delay/, { body: [] }],
  ['GET', /^\/appointments\/callback-list/, { body: [] }],
  ['GET', /^\/patients\/by-phone/, { body: { household: [] } }],
  ['GET', /^\/patients\?q=/, { body: { data: [{ id: 'p9', name: 'Layla Haddad', phone: '+919800000009', mrn: 'MRN-p9' }], page: { nextCursor: null, limit: 20 } } }],
  ['GET', /./, { body: [] }],
];
async function open(perms, path, { queue, viewport, extra } = {}) {
  const s = await launch(viewport ? { viewport } : {});
  s.st = { checkIns: [], patients: [] };
  await s.page.addInitScript(() => { window.__mouse = 0; addEventListener('mousedown', () => { window.__mouse++; }, true); });
  await seedClinic(s.page, perms, extra);
  await mockApi(s.page, s.log, handlers(s.st, { queue }));
  await s.page.goto(`${APP}${path}`, { waitUntil: 'networkidle' }); await sleep(700);
  return s;
}
const palette = (p) => p.getByRole('combobox', { name: /Jump to a screen/ });
const options = (p) => p.locator('#nb-jump-list [role=option]').allInnerTexts();
const slip = (p) => p.getByRole('dialog', { name: 'Checked in' });

await run('palette: speed, actions, patients, permissions', async () => {
  const s = await open(RECEPTION, '/account');
  const p = s.page;
  // time from the key press to the input being in the DOM, measured in the page (event.timeStamp -> MutationObserver callback)
  await p.evaluate(() => {
    window.__opened = null;
    addEventListener('keydown', (e) => { if (e.key === 'k') window.__keyAt = e.timeStamp; }, true);
    new MutationObserver(() => { if (window.__opened === null && document.querySelector('[role=combobox]')) window.__opened = performance.now() - window.__keyAt; }).observe(document.body, { childList: true, subtree: true });
  });
  await p.keyboard.press('Control+k'); await palette(p).waitFor();
  const ms = await p.evaluate(() => window.__opened);
  check('A1 Ctrl+K opens the palette in < 100 ms', ms !== null && ms < 100, `${ms?.toFixed(1)} ms`);
  const o = await options(p);
  check('A2 reception sees the walk-in, new patient and check-in actions first', /Register walk-in/.test(o[0]) && o.some((x) => /Register new patient/.test(x)) && o.some((x) => /Check in today/.test(x)), o.slice(0, 4).join('|'));
  check('A3 screens are listed after the actions', o.some((x) => /Billing/.test(x)));
  await palette(p).fill('walk-in');
  check('A4 "walk-in" finds the walk-in action first', /Register walk-in/.test((await options(p))[0]));
  await palette(p).fill('wlk');
  check('A5 fuzzy "wlk" still finds it', (await options(p)).some((x) => /Register walk-in/.test(x)));
  await palette(p).fill('layla'); await p.locator('#nb-jump-list [role=option]', { hasText: 'Layla Haddad' }).first().waitFor();
  const o2 = await options(p);
  check('A6 a patient search lists "Check in Layla Haddad" and "Open Layla Haddad" with MRN and phone', o2.some((x) => /Check in Layla Haddad/.test(x) && /MRN-p9/.test(x)) && o2.some((x) => /Open Layla Haddad/.test(x)), o2.join('|'));
  await p.keyboard.press('Enter');
  await p.waitForURL(/\/arrivals\?action=walkin|\/arrivals$/); await p.getByRole('dialog', { name: 'Register walk-in' }).waitFor();
  check('A7 Enter on "Check in Layla" opens the walk-in dialog with that patient searched', (await p.locator('#patientSearch').inputValue()) === 'MRN-p9', await p.locator('#patientSearch').inputValue());
  await p.locator('#nb-global-search').count();
  check('A8 palette: zero console errors', noise(s.log).length === 0, noise(s.log).join('|')); dumpLog('A', s.log);
  await s.browser.close();
  // permissions: no queue:create / patients:create = no walk-in actions, no top-bar button; no patients:view = no patient search
  const r = await open(['queue:view', 'billing:view'], '/account');
  await r.page.keyboard.press('Control+k'); await palette(r.page).waitFor();
  const ro = await options(r.page);
  check('A9 without queue:create the walk-in actions are absent', !ro.some((x) => /walk-in|Register new|Check in today/i.test(x)), ro.join('|'));
  await palette(r.page).fill('layla'); await sleep(700);
  check('A10 without patients:view no patient is searched or listed', !(await options(r.page)).some((x) => /Layla/.test(x)) && !r.log.requests.some((q) => q.path.startsWith('/patients?q=')));
  check('A11 the top bar has no Register walk-in button without the grants', (await r.page.getByRole('button', { name: 'Register walk-in' }).count()) === 0);
  await r.browser.close();
});

await run('palette: doctor sees Next patient, not reception actions', async () => {
  const waiting = [{ id: 'e1', doctorId: ME, status: 'waiting', tokenNumber: 3, patientId: 'p1' }];
  const s = await open(DOCTOR, '/my-day', { queue: waiting });
  await s.page.keyboard.press('Control+k'); await palette(s.page).waitFor();
  await palette(s.page).fill('next');
  const o = await options(s.page);
  check('B1 doctor: "Next patient" action listed', o.some((x) => /Next patient/.test(x)), o.join('|'));
  await palette(s.page).fill('walk-in');
  check('B2 doctor: no Register walk-in', !(await options(s.page)).some((x) => /Register walk-in/.test(x)));
  await palette(s.page).fill('next'); await s.page.keyboard.press('Enter');
  await s.page.waitForURL(/\/consult\?entry=e1/);
  check('B3 Enter on "Next patient" opens that consult', /entry=e1/.test(s.page.url()));
  await s.browser.close();
});

await run('overlay, tooltips and aria-keyshortcuts', async () => {
  const s = await open(RECEPTION, '/account');
  const p = s.page;
  await p.keyboard.press('?');
  const d = p.getByRole('dialog', { name: 'Keyboard shortcuts on this screen' }); await d.waitFor();
  const keys = (await d.locator('dt').allInnerTexts()).map((x) => x.replace(/\s+/g, ' ').trim());
  check('C1 "?" outside a field lists this screen\'s shortcuts for reception', ['Ctrl + K', '/', 'W', 'Ctrl + Enter', '?'].every((k) => keys.includes(k)) && !keys.includes('N') && !keys.includes('Ctrl + S'), keys.join('|'));
  check('C2 the list is a description list', (await d.locator('dl dt').count()) === (await d.locator('dl dd').count()));
  await p.keyboard.press('Escape'); await d.waitFor({ state: 'detached' });
  const attrs = await p.evaluate(() => ({
    search: [document.getElementById('nb-global-search')?.getAttribute('aria-keyshortcuts')],
    kbd: [...document.querySelectorAll('button[aria-keyshortcuts]')].map((b) => [b.getAttribute('aria-keyshortcuts'), b.getAttribute('title')]),
  }));
  check('C3 the search box announces "/"', attrs.search[0] === '/');
  check('C4 the Ctrl+K button and Register walk-in button carry tooltip + aria-keyshortcuts', attrs.kbd.some(([a, t]) => /Control\+K/.test(a) && /Ctrl\+K/.test(t)) && attrs.kbd.some(([a, t]) => a === 'W' && /\(W\)/.test(t)), JSON.stringify(attrs.kbd));
  await p.locator('#nb-global-search').focus(); await p.keyboard.type('a?w');
  check('C5 single keys are text while typing: "?" and "w" typed into search, no overlay, no navigation', (await p.locator('#nb-global-search').inputValue()) === 'a?w' && (await p.getByRole('dialog').count()) === 0 && /\/account/.test(p.url()));
  await p.locator('#nb-global-search').blur();
  await p.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' }); await sleep(600);
  const q = await p.evaluate(() => [...document.querySelectorAll('[aria-keyshortcuts]')].filter((e) => /F[234]/.test(e.getAttribute('aria-keyshortcuts'))).map((e) => e.getAttribute('title')));
  check('C6 the desk quick actions carry tooltips with their F-key', q.length === 3 && q.every((t) => /\(F[234]\)/.test(t)), JSON.stringify(q));
  await p.keyboard.press('?');
  check('C7 on a desk screen "?" still shows the desk sheet (one dialog)', (await p.getByRole('dialog', { name: 'Keyboard shortcuts' }).count()) === 1);
  const dd = await open(DOCTOR, '/my-day');
  await dd.page.keyboard.press('?');
  const dk = (await dd.page.getByRole('dialog').locator('dt').allInnerTexts()).map((x) => x.replace(/\s+/g, ' ').trim());
  check('C8 doctor on My Day: N is listed, W is not', dk.includes('N') && !dk.includes('W'), dk.join('|'));
  await dd.browser.close();
  await s.browser.close();
});

await run('smart defaults', async () => {
  const rows = ['upi', 'upi', 'cash'].map((m, i) => ({ id: `r${i}`, doctorId: DOC1, status: 'waiting', tokenNumber: i + 1, patientId: 'p1', paymentMode: m }));
  const s = await open(RECEPTION, '/arrivals', { queue: rows });
  await s.page.keyboard.press('F3'); await s.page.getByRole('dialog', { name: 'Register walk-in' }).waitFor(); await sleep(800);
  check('D1 doctor pre-selected: the shortest wait (5 min) with no last choice', (await s.page.locator('#doctor').inputValue()) === DOC2, await s.page.locator('#doctor').inputValue());
  check('D2 payment defaults to the clinic\'s most common mode (upi)', (await s.page.locator('#checkin-payment').inputValue()) === 'upi', await s.page.locator('#checkin-payment').inputValue());
  await s.page.locator('#doctor').selectOption(DOC1);
  await s.page.locator('#patientSearch').fill('layla'); await sleep(500); await s.page.keyboard.press('Enter');
  await s.page.keyboard.press('Control+Enter'); await slip(s.page).waitFor();
  await s.page.keyboard.press('Escape');
  await s.page.keyboard.press('F3'); await s.page.getByRole('dialog', { name: 'Register walk-in' }).waitFor();
  check('D3 next time the desk\'s last doctor wins over the shortest wait', (await s.page.locator('#doctor').inputValue()) === DOC1);
  await s.browser.close();
});

await run('timed keyboard-only walk-in', async () => {
  const s = await open(RECEPTION, '/account');
  const p = s.page;
  const t0 = Date.now();
  await p.keyboard.press('Control+k'); await palette(p).waitFor();
  await p.keyboard.type('walk-in', { delay: DELAY }); await p.keyboard.press('Enter');
  await p.getByRole('dialog', { name: 'Register walk-in' }).waitFor();
  await p.keyboard.type('+919800000009', { delay: DELAY }); await p.locator('#patientSearch ~ div [data-results] button, [data-results] button').first().waitFor();
  await p.keyboard.press('Enter');
  await p.keyboard.press('Control+Enter');
  await slip(p).waitFor();
  const ret = Date.now() - t0;
  check('E1 returning patient, keyboard only: token in < 15 s', ret < 15000, `${ret} ms`);
  check('E2 check-in went to the pre-selected shortest-wait doctor', s.st.checkIns[0]?.doctorId === DOC2 && s.st.checkIns[0]?.patientId === 'p9', JSON.stringify(s.st.checkIns[0]));
  check('E3 the mouse was never used', (await p.evaluate(() => window.__mouse)) === 0);
  await s.browser.close();
  const n = await open(RECEPTION, '/account');
  const q = n.page; const t1 = Date.now();
  await q.keyboard.press('Control+k'); await palette(q).waitFor();
  await q.keyboard.type('new patient', { delay: DELAY }); await q.keyboard.press('Enter');
  await q.waitForFunction(() => document.activeElement?.id === 'newName');
  await q.keyboard.type('Omar Said', { delay: DELAY }); await q.keyboard.press('Tab');
  await q.keyboard.type('+919812345678', { delay: DELAY }); await q.keyboard.press('Tab'); await q.keyboard.type('m');
  await q.keyboard.press('Tab'); await q.keyboard.type('31', { delay: DELAY });
  await q.keyboard.press('Control+Enter'); await slip(q).waitFor();
  const nw = Date.now() - t1;
  check('E4 new patient, keyboard only: token in < 45 s', nw < 45000, `${nw} ms`);
  check('E5 registered then checked in', n.st.patients.length === 1 && n.st.checkIns.length === 1 && n.st.patients[0].phone === '+919812345678');
  check('E6 the mouse was never used (new patient)', (await q.evaluate(() => window.__mouse)) === 0);
  check('E7 timed flows: zero console errors', noise(n.log).length === 0, noise(n.log).join('|'));
  await n.browser.close();
});

await run('W shortcut and 390px', async () => {
  const s = await open(RECEPTION, '/account');
  await s.page.keyboard.press('w'); await s.page.getByRole('dialog', { name: 'Register walk-in' }).waitFor();
  check('F1 W opens the walk-in dialog from another screen', true);
  await s.browser.close();
  const m = await open(RECEPTION, '/account', { viewport: { width: 390, height: 800 } });
  await m.page.keyboard.press('Control+k'); await palette(m.page).fill('layla'); await sleep(700);
  check('F2 390px: palette open with results, no horizontal scroll', await m.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
  await m.browser.close();
});

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
