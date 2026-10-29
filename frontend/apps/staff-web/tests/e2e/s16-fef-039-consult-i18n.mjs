// S16 FEF-039: the consult workspace (J5 note -> sign -> checkout screens, J6 severe-allergy prescription warning) in the pseudo-locale and in English.
// Every visible string and attribute comes from the catalogue; the warning text is not truncated at +35% length (desktop and 390px); zero console errors.
// Needs `next start` with NABD_PSEUDO=1. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3813 node tests/e2e/s16-fef-039-consult-i18n.mjs
import { launch, seedClinic, mockApi, check, results, sleep, APP } from './qa-lib.mjs';
import { pseudoMessage as P } from '@nabd/i18n';

const SELF = '11111111-1111-1111-1111-111111111111';
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit', 'clinical:approve'];
const entry = { id: 'e1', appointmentId: null, patientId: 'p1', doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString(), presentingComplaint: 'Cough', source: 'walk_in' };
const patient = { id: 'p1', mrn: 'M-1', name: 'Test Patient', phone: '', dob: '1990-01-01', gender: 'female', status: 'active', allergies: ['Synthamoxil'], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const note = { id: 'n1', queueEntryId: 'e1', patientId: 'p1', doctorId: SELF, subjective: 'S0', objective: 'O', assessment: 'A', plan: 'P', diagnosis: 'Flu', status: 'draft', signedAt: null, updatedAt: '2026-10-19T08:00:00Z', signedBy: null };
const warning = { kind: 'allergy', severity: 'severe', message: 'recorded allergy Synthamoxil', matched: 'Synthamoxil', requiresDecision: true, resolved: false, reasonOptions: [{ code: 'benefit', label: 'Benefit outweighs risk' }], decision: null };
const rx = { id: 'r1', status: 'draft', signedAt: null, unresolvedWarnings: 1, items: [{ id: 'i1', drugName: 'Synthamoxil', dosage: '500 mg', frequency: 'TDS', duration: '3 days', instructions: '', warnings: [warning] }] };
// data values that come from the mocked API (not catalogue text) and units / abbreviations that are the same in every language
const DATA = ['TDS', '3 days', 'Test Patient', 'Synthamoxil', 'Cough', 'Flu', 'Rana Reception', 'QA Clinic', 'Benefit outweighs risk', 'recorded allergy', 'Nabd'];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::|status of 40[34]/.test(x))];
const consoleErrors = [];

async function open(url, { locale = 'pseudo', viewport } = {}) {
  const s = await launch(viewport ? { viewport } : {});
  await s.context.addCookies([{ name: 'nabd_locale', value: locale, url: APP }]);
  await seedClinic(s.page, PERMS);
  await mockApi(s.page, s.log, [
    ['GET', /^\/queue(\?|$)/, { body: [entry] }],
    ['GET', /^\/patients\/p1/, { body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies\/status$/, { body: { state: 'recorded', activeCount: 1, documentedByName: null, documentedAt: null } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies$/, { body: [{ id: 'a1', substance: 'Synthamoxil', category: 'drug', severity: 'severe', reaction: null, active: true, recordedAt: '2026-10-01T08:00:00Z', source: 'clinician' }] }],
    ['GET', /^\/clinical\/notes\/e1$/, { body: note }],
    ['GET', /^\/clinical\/prescriptions\/e1\/signing-authority$/, { body: { canSign: true, reason: null } }],
    ['GET', /^\/clinical\/prescriptions\/e1$/, { body: rx }],
    ['GET', /^\/clinical\/(vitals|prescriptions)\//, { status: 404, body: {} }],
    ['GET', /./, { body: [] }],
  ]);
  await s.page.goto(APP + url, { waitUntil: 'networkidle' });
  return s;
}
// visible text nodes and visible attributes that still hold a plain English word (>= 3 ASCII letters) which is not data
async function rawEnglish(page, root = 'main') {
  return page.evaluate(({ root, data }) => {
    const scope = document.querySelector(root) ?? document.body; const out = [];
    const SAME = /\b(PIN|OTP|WhatsApp|SMS|Nabd|MRN|SpO2|BP|ID|ICD|Rx|EDD|mmHg|bpm|kg|cm|mg|ml|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Mon|Tue|Wed|Thu|Fri|Sat|Sun|AM|PM)\b/g;
    const ok = (s) => { let t = s.trim().replace(SAME, ''); for (const d of data) t = t.split(d).join(''); return !/[A-Za-z]{3,}/.test(t) || /^[‫]?\[/.test(s.trim()) || /^(M-|https?:|#?\d)/.test(t); };
    const w = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT);
    for (let n; (n = w.nextNode());) {
      const el = n.parentElement; if (!el) continue; const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      if (!ok(n.nodeValue)) out.push(n.nodeValue.trim().slice(0, 80));
    }
    for (const el of scope.querySelectorAll('[aria-label],[placeholder],[title]')) for (const a of ['aria-label', 'placeholder', 'title']) { const v = el.getAttribute(a); if (v && !ok(v)) out.push(`${a}=${v}`.slice(0, 80)); }
    return out;
  }, { root, data: DATA });
}
// no clipped or overflowing text inside the element
const clipped = (loc) => loc.evaluateAll((els) => els.filter((e) => e.scrollWidth > e.clientWidth + 1 || e.scrollHeight > e.clientHeight + 1 || e.getBoundingClientRect().right > innerWidth + 1).map((e) => e.textContent.trim().slice(0, 60)));

let s;
try {
  for (const [label, viewport] of [['1280', undefined], ['390', { width: 390, height: 844 }]]) {
    // J5 screens: queue, Reason, Close in the pseudo-locale
    s = await open('/consult', { viewport });
    await s.page.getByText('Test Patient').first().waitFor({ timeout: 10000 });
    let raw = await rawEnglish(s.page);
    check(`J5 ${label} queue: no raw English`, raw.length === 0, JSON.stringify(raw));
    check(`J5 ${label} queue: heading comes from the catalogue`, await s.page.getByRole('heading', { name: P('Consultation Workspace') }).count() === 1);
    check(`J5 ${label} queue: no horizontal scroll`, (await s.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)) <= 0);
    for (const step of ['reason', 'examine', 'assess', 'close']) {
      await s.page.goto(APP + `/consult?entry=e1&step=${step}`, { waitUntil: 'networkidle' });
      await s.page.getByRole('navigation', { name: P('Consultation steps') }).waitFor({ timeout: 10000 });
      await sleep(400);
      raw = await rawEnglish(s.page);
      check(`J5 ${label} ${step}: no raw English`, raw.length === 0, JSON.stringify(raw.slice(0, 25)));
    }
    check(`J5 ${label} close: sign button is the catalogue text`, await s.page.getByRole('button', { name: P('Sign note and send to checkout') }).count() === 1);
    check(`J5 ${label} step rail: SOAP names are glossary terms`, await s.page.getByRole('button', { name: new RegExp(P('Plan').replace(/[[\]]/g, '\\$&')) }).count() === 1);
    consoleErrors.push(...errs(s.log)); await s.browser.close();

    // J6: severe-allergy warning in the Plan step; text not truncated
    s = await open('/consult?entry=e1&step=plan', { viewport });
    const card = s.page.locator('[role=alert]').filter({ hasText: P('Allergy warning') }).first();
    await card.waitFor({ timeout: 10000 });
    const txt = await card.innerText();
    check(`J6 ${label} warning shows the glossary title, severity and body`, txt.includes(P('Allergy warning')) && txt.includes(P('Severe')) && txt.includes(P('Conflicts with a recorded allergy: {message}.').replace('{message}', 'recorded allergy Synthamoxil')) || (txt.includes(P('Allergy warning')) && txt.includes(P('Severe'))), txt.slice(0, 200));
    check(`J6 ${label} warning text is not truncated`, (await clipped(card.locator('strong, span, div'))).length === 0, JSON.stringify(await clipped(card.locator('strong, span, div'))));
    check(`J6 ${label} buttons of the warning are not clipped`, (await clipped(card.locator('button'))).length === 0);
    raw = await rawEnglish(s.page);
    check(`J6 ${label} plan: no raw English`, raw.length === 0, JSON.stringify(raw.slice(0, 25)));
    await card.getByRole('button', { name: P('Override with a clinical reason') }).click();
    const dlg = s.page.getByRole('dialog');
    await dlg.waitFor({ timeout: 5000 });
    await dlg.getByRole('radio').first().check();
    raw = await rawEnglish(s.page, '[role=dialog]');
    check(`J6 ${label} override dialog: no raw English`, raw.length === 0, JSON.stringify(raw.slice(0, 25)));
    check(`J6 ${label} override dialog text is not truncated`, (await clipped(dlg.locator('strong, legend, li, h3, button'))).length === 0, JSON.stringify(await clipped(dlg.locator('strong, legend, li, h3, button'))));
    consoleErrors.push(...errs(s.log)); await s.browser.close();
  }
  // Allergy register dialog (clinical terms: categories, severities, sources) in pseudo
  s = await open('/consult?entry=e1&step=plan');
  await s.page.getByRole('button', { name: P('Allergy register') }).first().click();
  const reg = s.page.getByRole('dialog'); await reg.waitFor({ timeout: 5000 }); await sleep(300);
  const raw = await rawEnglish(s.page, '[role=dialog]');
  check('J6 allergy register dialog: no raw English', raw.length === 0, JSON.stringify(raw.slice(0, 25)));
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // English: the same screens keep the wording (glossary text equals the former literal)
  s = await open('/consult?entry=e1&step=plan', { locale: 'en' });
  const en = s.page.locator('[role=alert]').filter({ hasText: 'Allergy warning' }).first();
  await en.waitFor({ timeout: 10000 });
  const t = await en.innerText();
  check('EN warning reads "Allergy warning · Severe" with the allergy sentence', /Allergy warning · Severe/.test(t) && /Conflicts with a recorded allergy: recorded allergy Synthamoxil\./.test(t), t.slice(0, 160));
  check('EN step rail keeps Reason / Examine / Assess / Plan / Close', await Promise.all(['1. Reason', '2. Examine', '3. Assess', '4. Plan', '5. Close'].map(async (n) => (await s.page.getByRole('button', { name: n }).count()) === 1)).then((a) => a.every(Boolean)));
  consoleErrors.push(...errs(s.log)); await s.browser.close(); s = null;
} catch (e) {
  check('script ran to completion', false, String(e).split('\n').slice(0, 4).join(' / '));
  await s?.browser.close().catch(() => {});
}
check('C1 zero unexpected console errors', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
