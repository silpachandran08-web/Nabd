// S15 FEF-038 J1 (sign in, then the Arrivals desk) in Hindi and in the pseudo-locale: every visible string and visible attribute comes from
// the catalogue, directional chevrons mirror under rtl, 390px has no horizontal scroll, zero console errors.
// Needs `next start` with NABD_PSEUDO=1. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3808 node tests/e2e/s15-fef-038-login-arrivals-i18n.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const DOC = '33333333-3333-3333-3333-333333333331';
const PERMS = ['queue:view', 'queue:edit', 'queue:create', 'patients:view', 'patients:create', 'clinical:create', 'billing:view', 'billing:create'];
const NAMES = ['Layla Haddad', 'Omar Said', 'Dr Amal Nasser', 'General', 'Rana Reception', 'QA Clinic', 'Nabd'];
const entry = (n, status, extra = {}) => ({ id: `q${n}`, appointmentId: null, patientId: `p${n}`, doctorId: DOC, departmentId: 'd1', queueDate: '2026-10-20', tokenNumber: n, status,
  priority: n === 2, priorityReason: n === 2 ? 'Severe pain' : null, createdAt: new Date(Date.now() - n * 600000).toISOString(), source: 'walk_in', visitType: n === 1 ? 'appointment' : 'walk_in', ...extra });
const QUEUE = [entry(1, 'waiting'), entry(2, 'vitals_pending'), entry(3, 'checkout_pending'), entry(4, 'in_consult'), entry(5, 'completed'), entry(6, 'no_show')];
const handlers = [
  ['GET', /^\/queue(\?|$)/, { body: QUEUE }],
  ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr Amal Nasser', departmentName: 'General' }] }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
  ['GET', /^\/doctors\/[^/]+\/delay/, { body: [{ active: true, delayMinutes: 20, reason: 'Emergency' }] }],
  ['GET', /^\/appointments\/callback-list/, { body: [{ appointmentId: 'a9', patientName: 'Omar Said', status: 'no_show', startTime: '2026-10-19T10:00:00Z' }] }],
  ['GET', /^\/appointments\?/, { body: { data: [{ id: 'ap1', patientId: 'p9', doctorId: DOC, status: 'scheduled', startTime: '2026-10-26T10:00:00Z' }], page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/patients\/by-phone/, { body: { household: [{ patientId: 'm1', displayName: 'Layla Haddad', age: 31, sex: 'F', relation: 'guardian', lastVisitAt: null }] } }],
  ['GET', /^\/patients\/([^/?]+)\/privacy-notice/, { status: 404, body: {} }],
  ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: m[1], name: m[1] === 'p9' ? 'Layla Haddad' : 'Omar Said', mrn: `MRN-${m[1]}`, phone: '+919800000000', dob: '1990-01-01', gender: 'female' }) })],
  ['GET', /^\/patients\?/, { body: { data: [] } }],
  ['GET', /^\/departments\/[^/]+\/transfer-targets/, { body: [{ departmentId: 'd2', departmentName: 'Dental', doctors: [{ id: 'd2a', name: 'Dr Nour' }] }] }],
  ['GET', /./, { body: [] }],
];

// every visible text node / visible attribute of the page that still contains a plain English word (>=3 ASCII letters) and is not a data value
async function rawEnglish(page, scope = 'main') {
  return page.evaluate(({ scope, names }) => {
    const root = document.querySelector(scope) ?? document.body; const out = [];
    const PROPER = /\b(PIN|UPI|OTP|WhatsApp|SMS|Nabd|MRN|SpO2|BP|ID|mmHg|bpm|kg|cm)\b/g; // product and clinical abbreviations are the same in every language
    const ok = (s) => { const t = s.trim().replace(PROPER, ''); return !/[A-Za-z]{3,}/.test(t) || /^[\u202b]?\[/.test(t) || names.some((n) => t.includes(n)) || /^(MRN-|https?:|#?\d)/.test(t); };
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n; (n = w.nextNode());) {
      const el = n.parentElement; if (!el || ['SCRIPT', 'STYLE', 'OPTION'].includes(el.tagName) && false) continue;
      const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      if (!ok(n.nodeValue)) out.push(n.nodeValue.trim());
    }
    for (const el of root.querySelectorAll('[aria-label],[placeholder],[title]')) for (const a of ['aria-label', 'placeholder', 'title']) { const v = el.getAttribute(a); if (v && !ok(v)) out.push(`${a}=${v}`); }
    return out;
  }, { scope, names: NAMES });
}
const OVERVIEW = ['GET', /^\/reports\/overview$/, { body: { date: '2026-10-26', visits: { total: 3, completed: 1, inFlow: 2 }, collections: { collected: 100, invoices: 1, pendingInvoices: 0 }, checkout: { pending: 0, outstanding: 0 }, packages: { soldToday: 0, sessionsOwed: 0 }, paymentSplit: [], dayClose: { unbilledConsultations: 0, pendingCheckouts: 0, closed: false }, activeStaff: [], staff: { active: 3, suspended: 0, invited: 0, roles: 2 } } }];
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];

async function arrivals(locale, viewport, steps) {
  const { browser, context, page, log } = await launch({ viewport });
  await context.addCookies([{ name: 'nabd_locale', value: locale, url: APP }]);
  await seedClinic(page, PERMS); await mockApi(page, log, handlers);
  try { await page.goto(APP + '/arrivals'); await page.waitForLoadState('networkidle').catch(() => {}); await sleep(1500); await steps(page, log); check(`${locale} ${viewport?.width ?? 1280}: zero console/page errors`, noise(log).length === 0, JSON.stringify(noise(log))); }
  finally { dumpLog(`${locale}-${viewport?.width ?? 1280}`, log); await browser.close(); }
}
const dialogs = [
  ['walk-in', (p) => p.locator('[class*=primaryAction]').click(), 'Escape'],
  ['check-in picker', (p) => p.locator('[class*=quickActions] button').nth(2).click(), 'Escape'],
  ['move queue', (p) => p.locator('[class*=quickActions] button').nth(3).click(), 'Escape'],
  ['notify delay', (p) => p.locator('[class*=quickActions] button').nth(4).click(), 'Escape'],
  ['checkout picker', (p) => p.locator('[class*=quickActions] button').nth(5).click(), 'Escape'],
  ['priority', (p) => p.locator('tbody tr').nth(0).locator('button').first().click(), 'Escape'],
  ['record vitals', (p) => p.locator('tbody tr').nth(1).locator('button').first().click(), 'Escape'],
  ['detail rail', (p) => p.locator('tbody tr').nth(0).locator('td').nth(1).click(), 'Escape'],
];

// ---------- hi
await arrivals('hi', { width: 1280, height: 900 }, async (page) => {
  const text = await page.locator('main').innerText();
  check('hi: heading, tabs and quick actions are Hindi', /आज · आगमन/.test(text) && /प्रतीक्षा में/.test(text) && /वॉक-इन और टोकन/.test(text) && /चेकआउट बाकी/.test(text), text.slice(0, 200));
  check('hi: <html lang="hi">', (await page.evaluate(() => document.documentElement.lang)) === 'hi');
  check('hi: plural-free count line uses the catalogue ("बोर्ड पर 6")', /बोर्ड पर 6/.test(text));
  check('hi: no raw English on the board', (await rawEnglish(page)).length === 0, JSON.stringify(await rawEnglish(page)));
  await page.locator('[class*=primaryAction]').click(); await sleep(500);
  check('hi: walk-in dialog is Hindi', /वॉक-इन पंजीकृत करें/.test(await page.getByRole('dialog').innerText()));
  check('hi: no raw English in the walk-in dialog', (await rawEnglish(page, '[role=dialog]')).length === 0, JSON.stringify(await rawEnglish(page, '[role=dialog]')));
  check('hi: Dialog close button is labelled in Hindi', (await page.getByRole('dialog').getByRole('button', { name: 'बंद करें' }).count()) === 1);
});
// ---------- login in hi
{
  const { browser, context, page, log } = await launch({ viewport: { width: 1280, height: 900 } });
  await context.addCookies([{ name: 'nabd_locale', value: 'hi', url: APP }]);
  await mockApi(page, log, [['*', /./, { body: [] }]]);
  try {
    await page.goto(APP + '/login'); await sleep(1500);
    const t = await page.locator('main').innerText();
    check('hi login: title, labels and buttons are Hindi', /Nabd में साइन इन करें/.test(t) && /क्लिनिक कोड/.test(t) && /PIN भूल गए/.test(t) && /WhatsApp OTP/.test(t), t);
    check('hi login: no raw English', (await rawEnglish(page, 'main')).length === 0, JSON.stringify(await rawEnglish(page, 'main')));
    await page.getByRole('button', { name: /PIN भूल गए/ }).click(); await sleep(300);
    check('hi login: forgot-PIN view is Hindi', /अपना PIN रीसेट करें/.test(await page.locator('main').innerText()));
    check('hi login: zero console errors', noise(log).length === 0, JSON.stringify(noise(log)));
  } finally { dumpLog('hi-login', log); await browser.close(); }
}
// ---------- pseudo: every dialog of the desk
for (const mode of ['pseudo', 'pseudo-rtl']) {
  await arrivals(mode, { width: 1280, height: 900 }, async (page) => {
    const raw = await rawEnglish(page);
    check(`${mode}: board shows no raw English literal`, raw.length === 0, JSON.stringify(raw));
    check(`${mode}: dir`, (await page.evaluate(() => document.documentElement.dir)) === (mode === 'pseudo-rtl' ? 'rtl' : 'ltr'));
    for (const [name, open] of dialogs) {
      await open(page).catch(() => {}); await sleep(500);
      const dlg = page.getByRole('dialog');
      if (await dlg.count()) { const r = await rawEnglish(page, '[role=dialog]'); check(`${mode}: ${name} dialog has no raw English`, r.length === 0, JSON.stringify(r)); await page.keyboard.press('Escape'); await sleep(250); }
      else check(`${mode}: ${name} dialog opened`, false, 'no dialog');
    }
    check(`${mode}: no horizontal scroll at 1280`, await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
  });
}
await arrivals('pseudo', { width: 390, height: 844 }, async (page) => {
  check('pseudo 390: no horizontal scroll', await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
  check('pseudo 390: no raw English', (await rawEnglish(page)).length === 0, JSON.stringify(await rawEnglish(page)));
});
// ---------- login in pseudo
{
  const { browser, context, page, log } = await launch({ viewport: { width: 390, height: 844 } });
  await context.addCookies([{ name: 'nabd_locale', value: 'pseudo', url: APP }]);
  await mockApi(page, log, [['*', /./, { body: [] }]]);
  try {
    await page.goto(APP + '/login'); await sleep(1500);
    const r = (await rawEnglish(page, 'main'));
    check('pseudo login 390: no raw English', r.length === 0, JSON.stringify(r));
    check('pseudo login 390: no horizontal scroll', await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
    check('pseudo login: zero console errors', noise(log).length === 0, JSON.stringify(noise(log)));
  } finally { dumpLog('pseudo-login', log); await browser.close(); }
}
// ---------- overview chevrons mirror in rtl
{
  const { browser, context, page, log } = await launch({ viewport: { width: 1280, height: 900 } });
  await context.addCookies([{ name: 'nabd_locale', value: 'pseudo-rtl', url: APP }]);
  await seedClinic(page, ['reports:view', 'queue:view', 'patients:view', 'billing:view'], { owner: true }); await mockApi(page, log, [OVERVIEW, ['*', /./, { body: [] }]]);
  try {
    await page.goto(APP + '/overview'); await sleep(2000);
    const tr = await page.evaluate(() => [...document.querySelectorAll('svg[data-flip]')].map((s) => getComputedStyle(s).transform));
    check('rtl overview: chevron icons exist and are mirrored (matrix(-1,...))', tr.length >= 1 && tr.every((t) => /^matrix\(-1,/.test(t)), JSON.stringify(tr));
    await page.close(); const p2 = await context.newPage(); await context.clearCookies(); await context.addCookies([{ name: 'nabd_locale', value: 'pseudo', url: APP }]);
    await seedClinic(p2, ['reports:view', 'queue:view', 'patients:view', 'billing:view'], { owner: true }); await mockApi(p2, log, [OVERVIEW, ['*', /./, { body: [] }]]);
    await p2.goto(APP + '/overview'); await sleep(2000);
    const tr2 = await p2.evaluate(() => [...document.querySelectorAll('svg[data-flip]')].map((s) => getComputedStyle(s).transform));
    check('ltr overview: chevron icons are not mirrored', tr2.length >= 1 && tr2.every((t) => t === 'none'), JSON.stringify(tr2));
  } finally { dumpLog('overview', log); await browser.close(); }
}
const failed = results.filter((r) => !r.ok); console.log(`\n${results.length - failed.length}/${results.length} passed`); process.exit(failed.length ? 1 : 0);
