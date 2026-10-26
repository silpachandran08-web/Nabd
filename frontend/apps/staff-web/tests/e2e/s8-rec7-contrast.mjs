// WB-FEF-006: tertiary/disabled text contrast + 12px minimum on reception screens.
// Run against `next start` on PORT (default 3107). Mocks the API; no backend needed.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launch, seedClinic, mockApi, check, results, sleep } from './qa-lib.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '../..');
const APP = process.env.APP ?? 'http://localhost:3000';
const SHOTS = '/home/claude/ws/reports/shots-rec7';
const SHOT = (p, n) => p.screenshot({ path: `${SHOTS}/${n}.png` });

// ---- 1. token contrast (dark is the only theme in globals.css; assert that so a light theme forces an update here)
const css = readFileSync(join(ROOT, 'app/globals.css'), 'utf8');
const tok = (n) => css.match(new RegExp(`--nb-${n}:\\s*(#[0-9a-fA-F]{6})`))?.[1];
const lum = (h) => { const c = [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((x) => (x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4)); return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]; };
const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
check('T0 only the dark theme exists (no light/data-theme overrides)', !/prefers-color-scheme|data-theme[^{]*\{[^}]*--nb-(text|bg|surface|border)/.test(css)); // FEF-027's light focus-ring override is not a theme
const surfaces = ['bg-root', 'bg-canvas', 'bg-nav', 'surface-1', 'surface-2', 'surface-3', 'surface-overlay'];
for (const s of surfaces) {
  const r = ratio(tok('text-tertiary'), tok(s));
  check(`T1 tertiary ${tok('text-tertiary')} on ${s} ${tok(s)} >= 4.5`, r >= 4.5, r.toFixed(2));
}
const rd = ratio(tok('text-disabled'), tok('surface-1'));
check(`T2 disabled ${tok('text-disabled')} on surface-1 >= 3`, rd >= 3, rd.toFixed(2));

// ---- 2. no font-size < 12px in the reception-screen styles (print blocks excluded)
const SCOPE = ['app/clinicNav.module.css', 'app/topBar.module.css', 'app/(clinic)/schedule/schedule.module.css', 'app/(clinic)/patients/patients.module.css', 'app/components/arrivals/arrivals.module.css',
  'app/(clinic)/billing/billing.module.css', 'app/(clinic)/checkout/checkout.module.css', 'app/components/ui/ui.module.css', 'app/components/arrivals/checkin.module.css'];
const stripPrint = (s) => { let out = '', i = 0; for (;;) { const j = s.indexOf('@media print', i); if (j < 0) return out + s.slice(i); out += s.slice(i, j); let d = 0, k = s.indexOf('{', j); for (; k < s.length; k++) { if (s[k] === '{') d++; else if (s[k] === '}' && --d === 0) break; } i = k + 1; } };
const small = [];
for (const f of SCOPE) for (const m of stripPrint(readFileSync(join(ROOT, f), 'utf8')).matchAll(/font-size:\s*([\d.]+)px/g)) if (+m[1] < 12) small.push(`${f}:${m[1]}px`);
check('T3 no font-size < 12px in reception CSS modules', small.length === 0, small.join(', '));
check('T3b patients/page.tsx has no inline fontSize < 12px', !/fontSize:\s*"(\d|1[01])(\.\d+)?px"/.test(readFileSync(join(ROOT, 'app/(clinic)/patients/page.tsx'), 'utf8')));

// ---- 3. browser
const axeSrc = readFileSync(join(ROOT, 'node_modules/axe-core/axe.min.js'), 'utf8');
const ok = (body) => ({ status: 200, body });
const PERMS = ['queue:view', 'queue:create', 'queue:edit', 'patients:view', 'patients:create', 'billing:view', 'billing:create', 'billing:edit'];
const DOC = 'd0000000-0000-0000-0000-000000000001', DEPT = 'e0000000-0000-0000-0000-000000000001';
const entry = (n, status, extra = {}) => ({ id: `q000000${n}-0000-0000-0000-000000000000`, appointmentId: null, patientId: `p000000${n}-0000-0000-0000-000000000000`, doctorId: DOC, departmentId: DEPT,
  queueDate: '2026-10-18', tokenNumber: n, status, priority: n === 2, priorityReason: n === 2 ? 'elderly' : null, createdAt: '2026-10-18T05:00:00Z', source: 'walk_in', visitType: 'walk_in', presentingComplaint: 'Tooth pain', ageYears: 30 + n, gender: 'female', ...extra });
const queue = [entry(1, 'waiting'), entry(2, 'in_consult'), entry(3, 'checkout_pending'), entry(4, 'completed')];
const dayClose = { date: '2026-10-18', today: true, currency: 'INR', taxLabel: 'GST', cashTolerance: 50, totals: { billed: 5000, bills: 4, collected: 4000, payments: 4, tax: 300 },
  methods: [{ method: 'cash', amount: 1000, transactions: 1 }, { method: 'card', amount: 1000, transactions: 1 }, { method: 'upi', amount: 2000, transactions: 2 }, { method: 'other', amount: 0, transactions: 0 }], outstanding: { amount: 1000, patients: 1, bills: 1 }, pendingCheckouts: 0, unbilled: [], taxBands: [], splitPaymentBills: 0, close: null, voidedCount: 1, voidedTotal: 150 };
const sched = { date: '2026-10-18', today: true, timezone: 'Asia/Riyadh', holiday: null, doctors: [{ id: DOC, name: 'Dr Asha', department: 'Dental', onLeave: false, leaveReason: null, worksToday: true }],
  sessions: [{ label: 'Morning', start: '09:00', end: '10:00', stepMinutes: 30, rows: [
    { time: '09:00', cells: [{ doctorId: DOC, kind: 'booked', slotStart: '09:00', bookings: [{ appointmentId: 'a1', queueEntryId: null, patientId: 'p1', patientName: 'Ravi Kumar', visitType: 'appointment', time: '09:00', token: null, status: 'scheduled' }] }] },
    { time: '09:30', cells: [{ doctorId: DOC, kind: 'free', slotStart: '09:30', bookings: [] }] }] }], counts: { appointments: 1, walkIns: 0, freeSlots: 1 } };
const registry = { total: 1, counts: { all: 1 }, rows: [{ id: 'p1', mrn: 'MRN-001', name: 'Ravi Kumar', phone: '+919800000001', age: 34, gender: 'male', minor: false, lastVisit: '2026-10-01', nextAppointment: '2026-10-20T09:00:00Z',
  nextDoctorName: 'Dr Asha', nextDoctorId: DOC, nextAppointmentId: 'a1', nextAppointmentToday: false, packageSessionsUsed: 1, packageSessionsTotal: 4, condition: 'Hypertension', followUpDue: true,
  allergy: null, balanceDue: true, duplicate: false, archived: false, queueToken: null, queueStatus: null, tags: ['VIP'] }] };
const patientById = (m) => ({ id: m[1], name: `Patient ${m[1].slice(0, 2)}`, mrn: `MRN-${m[1].slice(1, 2)}`, phone: '+919800000000', dob: '1990-01-01', gender: 'female' });
const handlers = (q = queue, over = []) => [...over,
  ['GET', /^\/queue(\?|$)/, ok(q)], ['GET', /^\/staff\/roster/, ok([{ id: DOC, name: 'Dr Asha', departmentName: 'Dental' }])],
  ['GET', /^\/patients\/registry/, ok(registry)], ['GET', /^\/patients\/([^/?]+)$/, (r, _q, m) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(patientById(m)) })],
  ['GET', /^\/queue\/wait-estimate/, ok({ estimatedMinutes: 12 })], ['GET', /^\/doctors\/[^/]+\/delay/, ok([])],
  ['GET', /^\/schedule\/day/, ok(sched)], ['GET', /^\/billing\/day-close/, ok(dayClose)], ['GET', /^\/billing\/otc-charges/, { status: 409, body: {} }]];

async function visit(tag, path, { perms = PERMS, q, over, w = 1440, h = 900, fn }) {
  const { browser, page, log } = await launch({ viewport: { width: w, height: h } });
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, handlers(q, over));
    await page.goto(APP + path); await sleep(2000);
    const out = await fn(page, log);
    const errs = [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
    check(`${tag} zero console/page errors`, errs.length === 0, errs.slice(0, 3).join(' | '));
    return out;
  } finally { await browser.close(); }
}
// every visible element that directly owns text, with its computed font-size
const minFont = (page) => page.evaluate(() => {
  let min = 99, who = '';
  const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let n; (n = w.nextNode());) {
    if (!n.textContent.trim()) continue;
    const el = n.parentElement; if (!el || ['SCRIPT', 'STYLE', 'NOSCRIPT'].includes(el.tagName)) continue;
    const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    const r = el.getBoundingClientRect(); if (!r.width || !r.height) continue;
    const fs = parseFloat(cs.fontSize); if (fs < min) { min = fs; who = `${el.tagName}.${el.className} "${n.textContent.trim().slice(0, 30)}"`; }
  }
  return { min, who };
});
const axe = async (page) => { await page.evaluate(axeSrc); return page.evaluate(async () => (await axe.run(document, { runOnly: ['color-contrast'] })).violations.flatMap((v) => v.nodes.map((n) => n.target.join(' ') + ' :: ' + (n.any[0]?.message ?? '')))); };
const noHScroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

// happy path per screen @1440: min font >= 12 and axe color-contrast clean
const screens = [['arrivals', '/arrivals', 'Walk-in'], ['schedule', '/schedule', 'Ravi'], ['patients', '/patients', 'Ravi'], ['billing', '/billing', 'Outstanding']];
for (const [name, path, marker] of screens) {
  await visit(`S-${name}@1440`, path, { fn: async (page) => {
    const body = await page.locator('body').innerText();
    check(`S-${name} renders data`, body.includes(marker), body.slice(0, 80).replace(/\n/g, ' | '));
    const f = await minFont(page); check(`S-${name} min text size >= 12px`, f.min >= 12, `${f.min}px ${f.who}`);
    const v = await axe(page); check(`S-${name} axe color-contrast 0 violations`, v.length === 0, v.slice(0, 4).join(' || '));
    await SHOT(page, name);
  } });
}
// boundary: drawer / dialog text (patients drawer opens on row click; arrivals check-in dialog)
await visit('S-checkin-dialog', '/arrivals', { fn: async (page) => {
  await page.getByRole('button', { name: 'Check in' }).first().click(); await sleep(800);
  const f = await minFont(page); check('S-checkin-dialog min text size >= 12px', f.min >= 12, `${f.min}px ${f.who}`);
  const v = await axe(page); check('S-checkin-dialog axe color-contrast 0 violations', v.length === 0, v.slice(0, 4).join(' || '));
} });
// empty state
await visit('E-arrivals-empty', '/arrivals', { q: [], fn: async (page) => {
  check('E-arrivals empty state shown', /No patients here yet/.test(await page.locator('body').innerText()));
  const f = await minFont(page); check('E-arrivals empty min text >= 12px', f.min >= 12, `${f.min}px ${f.who}`);
  const v = await axe(page); check('E-arrivals empty axe 0 violations', v.length === 0, v.slice(0, 4).join(' || '));
} });
// error state (500)
await visit('X-arrivals-500', '/arrivals', { over: [['GET', /^\/queue(\?|$)/, { status: 500, body: { title: 'x', detail: 'y' } }]], fn: async (page) => {
  check('X-arrivals 500 shows error + Retry', /Couldn.t load today.s arrivals/.test(await page.locator('body').innerText()));
  const v = await axe(page); check('X-arrivals error axe 0 violations', v.length === 0, v.slice(0, 4).join(' || '));
} });
// 403 / permission-absent: no queue:view -> no-access state, no action controls
await visit('P-arrivals-no-perm', '/arrivals', { perms: ['patients:view'], fn: async (page) => {
  const body = await page.locator('body').innerText();
  check('P-arrivals no-access state', /No access to the arrivals board|You do not have access to arrivals/.test(body));
  check('P-arrivals Check in / Walk-in controls absent', (await page.getByRole('button', { name: /Check in|Walk-in/ }).count()) === 0);
  const v = await axe(page); check('P-arrivals no-access axe 0 violations', v.length === 0, v.slice(0, 4).join(' || '));
} });
await visit('P-arrivals-view-only', '/arrivals', { perms: ['queue:view', 'patients:view'], fn: async (page) => {
  check('P-arrivals view-only: Check in absent (not disabled)', (await page.getByRole('button', { name: 'Check in' }).count()) === 0);
} });
// 390px
for (const [name, path] of screens) {
  await visit(`M-${name}@390`, path, { w: 390, h: 800, fn: async (page) => {
    check(`M-${name} no horizontal page scroll @390`, await noHScroll(page));
    const f = await minFont(page); check(`M-${name} min text size >= 12px @390`, f.min >= 12, `${f.min}px ${f.who}`);
    const v = await axe(page); check(`M-${name} axe color-contrast 0 violations @390`, v.length === 0, v.slice(0, 4).join(' || '));
    if (name === 'arrivals') await SHOT(page, 'arrivals-390');
  } });
}
await visit('M-arrivals@1024', '/arrivals', { w: 1024, h: 800, fn: async (page) => {
  check('M-arrivals no horizontal page scroll @1024', await noHScroll(page));
  const f = await minFont(page); check('M-arrivals min text >= 12px @1024', f.min >= 12, `${f.min}px ${f.who}`);
} });

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
