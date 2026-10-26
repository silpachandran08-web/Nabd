// ACC-026 E2E: the desk books a slot that is on a live waitlist offer -> plain sentence with the offer's expiry, slots reload, no raw server text.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3801 node s15-acc-026-slot-on-offer.mjs
import { launch, seedClinic, mockApi, check, results, APP } from './qa-lib.mjs';
const D1 = '33333333-3333-3333-3333-333333333331', P1 = '44444444-4444-4444-4444-444444444441', DAY = '2026-10-19';
const S = ['06:00', '06:30', '07:00', '07:30', '08:00'].map((t) => `${DAY}T${t}:00Z`);
const RAW = 'RAW-SERVER-TEXT';
const FULL = ['queue:view', 'queue:create', 'queue:edit', 'patients:view', 'patients:create'];
const day = { date: DAY, today: false, timezone: 'Asia/Riyadh', holiday: null, sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 5 }, doctors: [{ id: D1, name: 'Dr. Hana', department: 'General', onLeave: false, leaveReason: null, worksToday: true }] };
const ali = { id: P1, mrn: 'MRN-1', name: 'Ali Hassan', phone: '+966500000001' };
let availCalls = 0;
const offer = (expiresAt) => ({ status: 409, body: { type: 'https://api.nabd.health/errors/slot-on-offer', title: 'Slot is on offer', status: 409, detail: RAW, code: 'SLOT_ON_OFFER', ...(expiresAt ? { expiresAt } : {}) } });
async function scenario(id, post, viewport) {
  availCalls = 0;
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, FULL);
    await mockApi(page, log, [['GET', /^\/schedule\/day/, { body: day }], ['GET', /^\/queue$/, { body: [] }], ['GET', /^\/doctors\/[^/]+\/availability/, (r) => { availCalls++; r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(S) }); }],
      ['GET', /^\/patients\?q=ali/, { body: { data: [ali], page: { nextCursor: null } } }], ['GET', /^\/patients\?q=/, { body: { data: [], page: { nextCursor: null } } }], ['POST', /^\/appointments$/, post]]);
    await page.goto(`${APP}/schedule`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: '+ New appointment' }).click();
    const d = page.getByRole('dialog');
    await d.locator('#appt-patient').fill('ali'); await d.getByRole('button', { name: /Ali Hassan/ }).click();
    await d.locator('#appt-time').selectOption({ label: '10:00' });
    const before = availCalls;
    await d.getByRole('button', { name: /Book appointment|Booking/ }).click();
    const alert = d.getByRole('alert'); await alert.waitFor({ timeout: 4000 });
    const t = (await alert.innerText()).trim();
    if (id !== 'no-expiry') check(`1 ${id}: refusal names the waitlist hold and a clock time`, /held for a waitlisted patient until \d{2}:\d{2}\./.test(t), t);
    else if (id === 'no-expiry') check('2 no expiry in the answer: generic hold sentence', /held for a waitlisted patient for a few more minutes/.test(t), t);
    check(`${id}: never shows the raw server text`, !t.includes(RAW), t);
    await page.waitForTimeout(500);
    check(`${id}: the free times were reloaded after the refusal`, availCalls > before, `${before} -> ${availCalls}`);
    if (viewport) check('3 390px: no horizontal scroll', !(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)));
    check(`${id}: zero page errors / console errors`, log.pageErrors.length === 0 && log.console.every((x) => /Failed to load resource/.test(x)), JSON.stringify([...log.pageErrors, ...log.console]).slice(0, 300));
  } catch (e) { check(`${id} scenario ran`, false, String(e).split('\n')[0]); } finally { await browser.close(); }
}
await scenario('with-expiry', offer(`${DAY}T06:15:00Z`));
await scenario('no-expiry', offer(null));
await scenario('mobile', offer(`${DAY}T06:15:00Z`), { width: 390, height: 844 });
const bad = results.filter((r) => !r.ok); console.log(`${results.length - bad.length}/${results.length} passed`); process.exit(bad.length ? 1 : 0);
