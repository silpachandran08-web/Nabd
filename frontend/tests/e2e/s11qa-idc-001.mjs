// Independent QA (S11 IDC-001 UI): a gated clinical read shows 'Record not available', leaks no patient detail anywhere in the
// DOM or tab title (even when the patient lookup itself still answers), and makes no retry storm.
// NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3613 node s11qa-idc-001.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const entry = { id: 'e9', appointmentId: null, patientId: 'p9', doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 7, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: 'p9', mrn: 'MRN-QA-9', name: 'Zainab Secretname', phone: '+910000009999', dob: '1990-05-05', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const gated = { status: 404, body: { type: 'https://api.nabd.health/errors/patient-not-found', title: 'Record not available', detail: 'The requested patient record was not found.' } };
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const PHI = /Zainab|Secretname|MRN-QA-9|0000009999|Token #7/;

// patientLookup: 'gated' | 'ok' (stale/other gate answers 200 while every clinical read is gated)
function handlers(patientLookup) {
  return [
    ['GET', /^\/queue(\?|$)/, { body: [entry] }],
    ['GET', /^\/patients\/p9/, patientLookup === 'ok' ? { body: patient } : gated],
    ['GET', /^\/departments\/d1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\//, gated],
    ['GET', /./, { body: [] }],
  ];
}

async function open(lookup, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, PERMS);
  await mockApi(s.page, s.log, handlers(lookup));
  await s.page.goto(`${APP}/consult?entry=e9&step=reason`, { waitUntil: 'networkidle' });
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|clinic-timezone-fallback|preloaded using link preload|ERR_FAILED|net::/.test(x))];

let s;
try {
  for (const [tag, lookup, vp] of [['A', 'gated', { width: 390, height: 800 }], ['B', 'ok', { width: 1280, height: 900 }]]) {
    s = await open(lookup, vp);
    await s.page.getByText('Record not available').first().waitFor({ timeout: 8000 })
      .then(() => check(`${tag}1 'Record not available' shown (patient lookup ${lookup})`, true), () => check(`${tag}1 'Record not available' shown (patient lookup ${lookup})`, false));
    const after = s.log.requests.length;
    await sleep(5000);
    const extra = s.log.requests.slice(after).map((r) => r.path);
    check(`${tag}2 no further API calls in 5 s after the gate (no retry storm)`, extra.length === 0, extra.join(','));
    const html = await s.page.content();
    check(`${tag}3 no patient name/MRN/phone/token anywhere in the DOM`, !PHI.test(html), (html.match(PHI) || [''])[0]);
    check(`${tag}4 tab title carries no patient detail`, !PHI.test(await s.page.title()), await s.page.title());
    check(`${tag}5 no clinical write controls`, (await s.page.getByRole('button', { name: /Sign|Save|Prescribe|Add allergy/i }).count()) === 0);
    check(`${tag}6 no horizontal scroll`, await s.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
    check(`${tag}7 zero console/page errors`, errs(s.log).length === 0, errs(s.log).join('|'));
    dumpLog(tag, s.log);
    await s.browser.close();
    s = null;
  }
} catch (e) {
  check('script ran to completion', false, String(e).slice(0, 300));
} finally { if (s) await s.browser.close().catch(() => {}); }
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
