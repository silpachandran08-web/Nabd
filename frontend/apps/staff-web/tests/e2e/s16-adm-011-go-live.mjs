// ADM-011 E2E: go-live step in the setup wizard: blockers listed with fix links, good-to-have list, Go live, booking link, poster, owner-only, error, 390px.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3814 node s16-adm-011-go-live.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

let o;
const checks = () => ({
  blockers: [
    { code: 'doctor_schedule', ok: !o.open, fixUrl: '/setup?tab=shifts' },
    { code: 'price_list', ok: true, fixUrl: null },
    { code: 'whatsapp_sender', ok: true, fixUrl: null },
    { code: 'dpa_signed', ok: true, fixUrl: null },
  ],
  niceToHave: [{ code: 'staff_invited', ok: false, note: '2 of 3 invites are still pending; they can join later.' }, { code: 'einvoice_phase2', ok: false, note: 'phase-1 QR invoices work meanwhile' }],
});
const handlers = () => [
  ['GET', /^\/setup\/go-live\/checks$/, o.checksFail ? { status: 500, body: { title: 'x' } } : (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(checks()) })],
  ['POST', /^\/setup\/go-live$/, (r) => {
    o.goHeaders = r.request().headers();
    if (o.goFail) return r.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', detail: 'Some required checks are still open.' }) });
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ liveAt: '2026-10-27T10:00:00Z', bookingUrl: 'https://book.example.test/b/qa-clinic', qrPosterUrl: '/setup/go-live/poster' }) });
  }],
  ['GET', /^\/setup\/go-live\/poster$/, (r) => { o.poster = true; r.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' }); }],
  ['GET', /^\/setup\/checklist/, { body: [{ step: 'go_live', status: 'current' }] }],
  ['GET', /^\/setup\/trial$/, { body: { status: 'active', endsAt: null, daysLeft: null, blockers: [], conversion: null } }],
  ['GET', /^\/setup/, { body: {} }],
];
async function open(perms, extra, opts = {}, viewport) {
  o = opts;
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, extra);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/setup/wizard`, { waitUntil: 'networkidle' });
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
const P = ['setup:view', 'setup:edit'];
let s;
try {
  s = await open(P, { owner: true }, { open: true });
  const t = await s.page.locator('main').innerText();
  check('1 open blocker listed in plain words with a Fix link', /At least one doctor has working hours/.test(t) && await s.page.getByRole('link', { name: 'Fix' }).getAttribute('href') === '/setup?tab=shifts');
  check('2 good-to-have items are listed as not required', /Good to have[\s\S]*Not yet: Staff invited \(2 of 3 invites are still pending/.test(t) && /Not yet: E-invoicing/.test(t));
  check('3 Go live is disabled while a blocker is open', await s.page.getByRole('button', { name: 'Go live' }).isDisabled());
  check('4 no console errors (blocked)', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  s = await open(P, { owner: true }, {});
  const btn = s.page.getByRole('button', { name: 'Go live' });
  check('5 all blockers ok: Go live enabled', await btn.isEnabled());
  await btn.click();
  await s.page.getByText('You are live').waitFor({ timeout: 5000 });
  check('6 success shows the booking link', await s.page.getByRole('link', { name: 'https://book.example.test/b/qa-clinic' }).count() === 1);
  check('7 go-live call carried an Idempotency-Key', /^[0-9a-f-]{36}$/.test(o.goHeaders?.['idempotency-key'] ?? ''));
  const dl = s.page.waitForEvent('download');
  await s.page.getByRole('button', { name: 'Print poster' }).click();
  const d = await dl;
  check('8 poster downloads as a PDF', o.poster === true && d.suggestedFilename() === 'booking-poster.pdf');
  check('9 test-booking link offered', await s.page.getByRole('button', { name: 'Do a test booking first' }).count() === 1);
  check('10 no console errors (live)', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('live', s.log);
  await s.browser.close();

  s = await open(P, { owner: true }, { goFail: true });
  await s.page.getByRole('button', { name: 'Go live' }).click();
  await s.page.getByText('Some required checks are still open.').waitFor({ timeout: 5000 }).then(() => check('11 refusal shows the server reason', true), () => check('11 refusal reason', false));
  await s.browser.close();

  s = await open(['setup:view'], { owner: false }, {});
  check('12 without setup:edit Go live is disabled and says why', await s.page.getByRole('button', { name: 'Go live' }).isDisabled() && /Only the clinic owner can go live/.test(await s.page.locator('main').innerText()));
  await s.browser.close();

  s = await open(P, { owner: true }, { checksFail: true });
  check('13 checks fail: error state with Retry, no Go live', await s.page.getByRole('button', { name: /Retry|Try again/i }).count() >= 1 && await s.page.getByRole('button', { name: 'Go live' }).count() === 0);
  await s.browser.close();

  s = await open(P, { owner: true }, { open: true }, { width: 390, height: 800 });
  check('14 390px: no horizontal page scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  check('15 390px: no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
