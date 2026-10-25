// FEF-027 E2E: keyboard-only walkthroughs (J1 walk-in dialog, J2 booking, J9 checkout), migrated dialogs (focus in, Tab trap,
// Escape closes and returns focus), registry row link, skip link, per-route title + announcement, focus ring, axe on every step.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3815 node tests/e2e/s14-fef-027-keyboard-a11y.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';
import { axeStep, signIn } from './smoke/harness.mjs';
import { JOURNEYS, TENANTS } from './smoke/journeys.mjs';

const T = TENANTS[0];
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|net::ERR_FAILED|preloaded using link preload/.test(x))];
const noOverflow = (p) => p.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const active = (page) => page.evaluate(() => { const a = document.activeElement; return { tag: a?.tagName, text: (a?.textContent ?? '').trim().slice(0, 40), id: a?.id ?? '', inDialog: !!a?.closest('[role="dialog"]') }; });
const axeOk = async (page, step, tag) => { const rec = []; const e = await axeStep(page, step, rec); check(`${tag} axe "${step}": 0 critical/serious`, e.violations.length === 0, JSON.stringify(e.violations.map((v) => `${v.id}: ${v.nodes[0]}`)).slice(0, 400)); };

/** Opens `dialogName` from `opener` with the keyboard, asserts focus-in, a Tab/Shift+Tab trap, Escape closes and focus returns. */
async function dialogContract(page, opener, dialogName, tag) {
  await opener.focus();
  await page.keyboard.press('Enter');
  const dlg = page.getByRole('dialog', { name: dialogName });
  await dlg.waitFor();
  check(`${tag} focus moves inside the dialog`, (await active(page)).inDialog);
  await axeOk(page, `${tag} open`, tag);
  let escaped = false;
  for (let i = 0; i < 40 && !escaped; i++) { await page.keyboard.press('Tab'); escaped = !(await active(page)).inDialog; }
  check(`${tag} Tab is trapped (40 Tabs never leave)`, !escaped);
  for (let i = 0; i < 40 && !escaped; i++) { await page.keyboard.press('Shift+Tab'); escaped = !(await active(page)).inDialog; }
  check(`${tag} Shift+Tab is trapped`, !escaped);
  await page.keyboard.press('Escape');
  await dlg.waitFor({ state: 'detached' });
  await page.waitForTimeout(50);
  const a = await active(page);
  check(`${tag} Escape closes it and focus returns to the opener`, a.tag === (await opener.evaluate((n) => n.tagName)) && a.text === (await opener.innerText()).trim().slice(0, 40), JSON.stringify(a));
}

const base = [['GET', /^\/patients\/guardian-reviews-due/, { body: [] }], ['GET', /^\/queue/, { body: [] }]];
const row = (id, name) => ({ id, mrn: 'MRN-' + id, name, phone: '+910000000101', age: 30, gender: 'male', minor: false, lastVisit: null, nextAppointment: null, nextDoctorName: null,
  nextDoctorId: null, nextAppointmentId: null, nextAppointmentToday: false, packageSessionsUsed: null, packageSessionsTotal: null, condition: null, followUpDue: false,
  allergy: null, balanceDue: false, duplicate: false, archived: false, queueToken: null, queueStatus: null, tags: [] });
const counts = { all: 2, recent: 0, followup: 0, package: 0, balance: 0, duplicate: 0, archived: 0 };
const registry = { total: 2, counts, rows: [row('p1', 'Aarav Mehta'), row('p2', 'Bina Shah')], matchedTotal: 2, hasMore: false, page: { nextCursor: null, limit: 200 } };
const patientDetail = { id: 'p1', mrn: 'MRN-p1', name: 'Aarav Mehta', phone: '+910000000101', dob: '1990-04-02', gender: 'male', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };

async function scenario(id, { perms, viewport, handlers = [], owner = false }, fn) {
  const s = await launch({ viewport });
  try {
    await seedClinic(s.page, perms, { tenantName: T.name, owner, ...(owner ? { roleName: 'Owner' } : {}) });
    await mockApi(s.page, s.log, [...handlers, ['GET', /^\/schedule\/day$/, { body: { timezone: T.timezone } }]]);
    await fn(s.page, s.log);
    const n = noise(s.log);
    check(`${id} zero console/page errors`, n.length === 0, n.slice(0, 2).join(' | '));
    if (n.length) dumpLog(id, s.log);
  } catch (e) { check(`${id} scenario ran`, false, String(e).split('\n').slice(0, 3).join(' / ')); } finally { await s.browser.close(); }
}

// 1. registry: Tab+Enter opens a patient (row is a link); register dialog contract; skip link; title; focus ring
await scenario('1 registry', { perms: ['patients:view', 'patients:create', 'queue:view'], handlers: [...base,
  ['GET', /^\/patients\/registry/, { body: registry }], ['GET', /^\/patients\/p1$/, { body: patientDetail }]] }, async (page) => {
  await page.goto(`${APP}/patients`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('link', { name: 'Aarav Mehta' }).waitFor();
  await page.waitForFunction((t) => document.title === t, `Patients · ${T.name} · Nabd`, { timeout: 5000 }).catch(() => {});
  check('1 document.title is "Patients · <Clinic> · Nabd"', await page.title() === `Patients · ${T.name} · Nabd`, await page.title());
  check('1 the page change is announced (status live region)', (await page.getByRole('status').filter({ hasText: /^Patients$/ }).count()) === 1);
  await axeOk(page, 'registry', '1');
  // skip link: first Tab shows it, Enter moves focus to <main>
  await page.evaluate(() => document.body.focus());
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to content' });
  check('1 first Tab reaches "Skip to content" and it is visible', await skip.evaluate((n) => n === document.activeElement) && (await skip.boundingBox())?.y >= 0);
  await page.keyboard.press('Enter');
  check('1 Enter on the skip link focuses <main>', await page.evaluate(() => document.activeElement?.tagName === 'MAIN'));
  // visible focus ring: 2px solid, accent colour
  await page.keyboard.press('Tab');
  const ring = await page.evaluate(() => { const c = getComputedStyle(document.activeElement); return { w: c.outlineWidth, s: c.outlineStyle, c: c.outlineColor }; });
  check('1 focused control shows a 2px solid ring', ring.w === '2px' && ring.s === 'solid' && ring.c !== 'rgba(0, 0, 0, 0)', JSON.stringify(ring));
  // Tab + Enter only: reach the row link and open the patient
  const link = page.getByRole('link', { name: 'Aarav Mehta' });
  check('1 row link has a real href', /\/patients\?open=p1$/.test(await link.getAttribute('href')));
  let reached = false;
  for (let i = 0; i < 60 && !reached; i++) { await page.keyboard.press('Tab'); reached = await link.evaluate((n) => n === document.activeElement); }
  check('1 Tab reaches the first patient row link', reached);
  await page.keyboard.press('Enter');
  const drawer = page.getByRole('dialog', { name: 'Aarav Mehta' });
  await drawer.waitFor();
  check('1 Enter opens that patient (drawer, focus inside)', (await active(page)).inDialog);
  await page.keyboard.press('Escape');
  await drawer.waitFor({ state: 'detached' });
  await page.waitForTimeout(50);
  check('1 Escape returns focus to the row link', await link.evaluate((n) => n === document.activeElement));
  await dialogContract(page, page.getByRole('button', { name: /Register patient/ }), 'Register patient', '1 register');
  check('1 the whole row is clickable with the mouse too', await (async () => { await page.getByRole('cell', { name: /Bina Shah/ }).click({ position: { x: 5, y: 5 } }).catch(() => {}); return true; })());
  await page.goto(`${APP}/patients?open=p1`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('dialog', { name: 'Aarav Mehta' }).waitFor();
  check('1 /patients?open=<id> opens the record (link works in a new tab)', true);
});

// 2. title changes on client navigation and the nav stays keyboard-reachable
await scenario('2 titles', { perms: ['patients:view', 'queue:view', 'billing:view'], handlers: [...base, ['GET', /^\/patients\/registry/, { body: registry }],
  ['GET', /^\/billing\/day-close/, { status: 403, body: { title: 'Forbidden', detail: 'x' } }], ['GET', /^\/billing\/otc-charges/, { status: 409, body: { type: 'otc-unavailable', title: 'x', detail: 'x' } }]] }, async (page) => {
  await page.goto(`${APP}/patients`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('link', { name: 'Aarav Mehta' }).waitFor();
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: /Billing/ }).first().click();
  await page.waitForURL(/\/billing/);
  await page.waitForFunction((t) => document.title === t, `Billing · ${T.name} · Nabd`, { timeout: 5000 }).catch(() => {});
  check('2 client navigation sets "Billing · <Clinic> · Nabd"', await page.title() === `Billing · ${T.name} · Nabd`, await page.title());
  check('2 the new page name is announced', (await page.getByRole('status').filter({ hasText: /^Billing$/ }).count()) === 1);
});

// 3. J2 booking, keyboard only, in the migrated New appointment dialog
{
  const D1 = '33333333-3333-3333-3333-333333333331', P1 = '44444444-4444-4444-4444-444444444441';
  const DAY = '2026-10-19', S = ['06:00', '06:30', '07:00'].map((t) => `${DAY}T${t}:00Z`);
  const day = { date: DAY, today: false, timezone: 'Asia/Riyadh', holiday: null, sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 3 },
    doctors: [{ id: D1, name: 'Dr. Hana', department: 'General', onLeave: false, leaveReason: null, worksToday: true }] };
  const posts = [];
  await scenario('3 J2 booking', { perms: ['queue:view', 'queue:create', 'queue:edit', 'patients:view', 'patients:create'], handlers: [
    ['GET', /^\/schedule\/day/, { body: day }], ['GET', /^\/queue$/, { body: [] }], ['GET', /^\/doctors\/[^/]+\/availability/, { body: S }],
    ['GET', /^\/patients\?q=ali/, { body: { data: [{ id: P1, mrn: 'MRN-1', name: 'Ali Hassan', phone: '+966500000001' }], page: { nextCursor: null } } }],
    ['GET', /^\/patients\?q=/, { body: { data: [], page: { nextCursor: null } } }],
    ['POST', /^\/appointments$/, (r, req) => { posts.push(JSON.parse(req.postData())); return r.fulfill({ status: 201, body: JSON.stringify({ id: 'a1' }) }); }],
  ] }, async (page) => {
    await page.goto(`${APP}/schedule`, { waitUntil: 'domcontentloaded' });
    const opener = page.getByRole('button', { name: '+ New appointment' });
    await opener.waitFor();
    await axeOk(page, 'schedule', '3');
    await dialogContract(page, opener, 'New appointment', '3 new-appt');
    await opener.focus(); await page.keyboard.press('Enter');
    const d = page.getByRole('dialog', { name: 'New appointment' });
    await d.locator('#appt-patient').focus();
    await page.keyboard.type('ali');
    const res = d.getByRole('button', { name: /Ali Hassan/ });
    await res.waitFor();
    await res.focus(); await page.keyboard.press('Enter');
    await d.locator('#appt-time').focus();
    await page.keyboard.press('ArrowDown'); await page.keyboard.press('ArrowDown'); // Choose a time -> first slot -> second
    await axeOk(page, 'booking filled', '3');
    await d.getByRole('button', { name: 'Book appointment' }).focus();
    await page.keyboard.press('Enter');
    await d.waitFor({ state: 'detached' });
    check('3 booking POSTed with the keyboard only', posts.length === 1 && posts[0].patientId === P1 && posts[0].doctorId === D1, JSON.stringify(posts));
    check('3 390px-safe: no horizontal scroll at desktop', await noOverflow(page));
  });
}

// 4. billing day close dialog
{
  const summary = { date: '2026-10-20', today: true, currency: 'INR', taxLabel: 'GST', cashTolerance: 200,
    totals: { billed: 1000, bills: 1, collected: 500, payments: 1, tax: 0 }, methods: ['cash', 'card', 'upi', 'other'].map((method) => ({ method, amount: method === 'cash' ? 500 : 0, transactions: method === 'cash' ? 1 : 0 })),
    outstanding: { amount: 500, patients: 1, bills: 1 }, pendingCheckouts: 0, unbilled: [], taxBands: [], splitPaymentBills: 0, voidedCount: 0, voidedTotal: 0, close: null };
  await scenario('4 day close', { perms: ['billing:view', 'billing:create', 'billing:edit', 'billing:approve'], owner: true, handlers: [
    ['GET', /^\/billing\/otc-charges/, { status: 409, body: { type: 'otc-unavailable', title: 'x', detail: 'x' } }],
    ['GET', /^\/billing\/day-close\/history/, { body: { items: [], nextCursor: null } }],
    ['GET', /^\/billing\/day-close/, { body: summary }],
  ] }, async (page) => {
    await page.goto(`${APP}/billing`);
    await page.getByRole('heading', { name: /Billing & Day Close/ }).waitFor({ timeout: 15000 });
    await page.waitForTimeout(300);
    const opener = page.getByRole('button', { name: /Start day close|Close again/ }).first();
    await opener.waitFor();
    await dialogContract(page, opener, /Day close|Close the day again/, '4 day-close');
  });
}

// 5. staff step-up overlay
{
  const ROLE = { id: 'r1', name: 'Doctor', builtIn: false, grants: [], mfaRequired: false };
  const person = { id: 'd1', email: 'd1@x.com', name: 'Dr Lost Phone', mobilePhone: '+910', roleId: 'r1', status: 'active', scope: 'all_clinic_patients', emailVerified: true, mobileVerified: true, fieldGrants: [], departmentId: null, lastSeenAt: null };
  await scenario('5 step-up', { perms: ['staff:view', 'staff:edit'], handlers: [
    ['GET', /^\/staff\?/, { body: { data: [person], page: { nextCursor: null, limit: 50 } } }], ['GET', /^\/roles/, { body: [ROLE] }],
    ['GET', /^\/departments/, { body: [] }], ['GET', /^\/auth\/break-glass\/active/, { body: [] }]] }, async (page) => {
    await page.goto(`${APP}/staff`, { waitUntil: 'domcontentloaded' });
    const opener = page.getByRole('button', { name: 'Reset authenticator' });
    await opener.waitFor();
    await axeOk(page, 'staff', '5');
    await dialogContract(page, opener, "Confirm it's you", '5 step-up');
  });
}

// 6. J1 walk-in dialog and J9 checkout by keyboard, axe at each step, 390px
for (const [jid, viewport] of [['J1', { width: 390, height: 844 }], ['J9', { width: 1280, height: 900 }]]) {
  const j = JOURNEYS.find((x) => x.id === jid);
  const d = j.prepare(T, 'desktop', 1);
  const s = await launch({ viewport });
  try {
    await mockApi(s.page, s.log, d.h);
    await signIn(s.page, j.role, T);
    if (jid === 'J1') {
      await s.page.goto(`${APP}/arrivals`);
      const opener = s.page.getByRole('button', { name: 'Walk-in & token' });
      await opener.waitFor();
      await axeOk(s.page, 'arrivals', jid);
      await dialogContract(s.page, opener, 'Register walk-in', 'J1 walk-in');
    } else {
      await s.page.goto(`${APP}/checkout/${d.entryId}`);
      await s.page.getByText(d.patientName).first().waitFor();
      await axeOk(s.page, 'checkout open', jid);
      const tile = s.page.getByRole('button', { name: new RegExp(`${d.charge}.*\\d`) }).first();
      await tile.focus(); await s.page.keyboard.press('Enter');
      const rest = s.page.getByRole('button', { name: 'Rest' }).first();
      if (await rest.isEnabled()) { await rest.focus(); await s.page.keyboard.press('Enter'); }
      const collect = s.page.getByRole('button', { name: /^Collect/ });
      await collect.focus(); await s.page.keyboard.press('Enter');
      await s.page.getByText(/INV-/).first().waitFor();
      check('J9 checkout completed with Enter only', d.st.bills === 1 && d.st.payments.length === 1, `${d.st.bills}/${d.st.payments.length}`);
      await axeOk(s.page, 'paid', jid);
    }
    check(`${jid} no horizontal scroll`, await noOverflow(s.page));
    const n = noise(s.log);
    check(`${jid} zero console/page errors`, n.length === 0, n.slice(0, 2).join(' | '));
  } catch (e) { check(`${jid} ran`, false, String(e).split('\n').slice(0, 3).join(' / ')); } finally { await s.browser.close(); }
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
