// ADM-007 E2E: nine-step resumable setup wizard (mocked API with server-side state, so "another device" is a second page).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3810 node s15-adm-007-setup-wizard.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const STEPS = ['profile', 'preset', 'doctors', 'prices', 'whatsapp', 'tax', 'dpa', 'import', 'go_live'];
const TITLES = ['Clinic profile', 'Clinic type', 'Doctors', 'Prices', 'WhatsApp', 'Tax', 'DPA', 'Import', 'Go live'];
const server = (statuses, drafts = {}) => ({
  items: STEPS.map((step, i) => ({ step, status: statuses[i] ?? 'pending', draft: drafts[step] ?? {}, version: drafts[step] ? 1 : 0, updatedAt: '2026-10-26T00:00:00Z' })),
  calls: [], dpaOk: false, draftConflict: false,
});
const current = (db) => { // the first pending step is the current one
  const first = db.items.find((i) => i.status === 'pending' || i.status === 'current');
  db.items.forEach((i) => { if (i.status === 'current') i.status = 'pending'; });
  if (first) db.items.find((i) => i.step === first.step).status = 'current';
};
const handlers = (db) => [
  ['GET', /^\/setup\/checklist$/, (r) => { current(db); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(db.items) }); }],
  ['GET', /^\/setup\/profile$/, { body: { name: 'Glow Clinic', region: 'IN', timezone: 'Asia/Kolkata', taxIdType: null, taxId: null, whatsappNumber: null, specialties: [] } }],
  ['PATCH', /^\/setup\/profile$/, (r, req) => r.fulfill({ status: 200, contentType: 'application/json', body: req.postData() })],
  ['PUT', /^\/setup\/checklist\/([a-z_]+)\/draft$/, (r, req, m) => {
    const body = JSON.parse(req.postData()); db.calls.push({ step: m[1], ...body });
    if (db.draftConflict) return r.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ title: 'VERSION_CONFLICT' }) });
    const it = db.items.find((i) => i.step === m[1]); it.draft = body.draft; it.version = body.version + 1;
    return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ step: m[1], version: it.version }) });
  }],
  ['POST', /^\/setup\/checklist\/([a-z_]+)\/(complete|skip)$/, (r, req, m) => {
    db.calls.push({ step: m[1], action: m[2] });
    if (m[1] === 'dpa' && (m[2] === 'skip' || !db.dpaOk)) return r.fulfill({ status: 422, contentType: 'application/problem+json', body: JSON.stringify({ title: 'STEP_INVALID', detail: 'Accept the data processing agreement first.' }) });
    db.items.find((i) => i.step === m[1]).status = m[2] === 'complete' ? 'done' : 'skipped';
    return r.fulfill({ status: 204, body: '' });
  }],
  ['GET', /^\/setup\/dpa/, { body: { version: 'v1', marketCode: 'IN', html: '<p>x</p>', dataGate: 'demo_only', blockers: [] } }],
  ['GET', /./, { body: [] }],
];
const PERMS = ['setup:view', 'setup:edit'];
async function open(db, perms = PERMS, viewport = { width: 1280, height: 900 }) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { owner: true });
  await mockApi(s.page, s.log, handlers(db));
  await s.page.goto(`${APP}/setup/wizard`, { waitUntil: 'networkidle' });
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
const statuses = async (page) => page.locator('[data-status]').evaluateAll((els) => els.map((e) => [e.textContent.trim(), e.getAttribute('data-status')]));
let s;
try {
  // 1 new tenant: nine steps in order, first is current
  let db = server([]);
  s = await open(db);
  let st = await statuses(s.page);
  check('1 nine steps listed in order', JSON.stringify(st.map((x) => x[0])) === JSON.stringify(TITLES), JSON.stringify(st.map((x) => x[0])));
  check('2 new tenant: first step current, rest pending', st[0][1] === 'current' && st.slice(1).every((x) => x[1] === 'pending'));
  check('3 shows "about N minutes left"', /about \d+ minutes left/.test(await s.page.locator('body').innerText()));
  await s.browser.close();

  // 2 four steps done elsewhere: a phone (390px) opens on the fifth
  db = server(['done', 'done', 'done', 'done']);
  s = await open(db, PERMS, { width: 390, height: 844 });
  st = await statuses(s.page);
  check('4 phone sees the same four done and the fifth current', st.slice(0, 4).every((x) => x[1] === 'done') && st[4][1] === 'current', JSON.stringify(st.map((x) => x[1])));
  check('5 phone opens on step 5 of 9 (WhatsApp)', /Step 5 of 9: WhatsApp/.test(await s.page.locator('body').innerText()));
  check('6 390px: no horizontal page scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));

  // 3 draft saved with the version, then Continue completes and moves on
  await s.page.getByLabel('WhatsApp number').fill('+919800000000');
  await s.page.getByRole('button', { name: 'Continue' }).click();
  await s.page.getByText(/Step 6 of 9/).waitFor({ timeout: 5000 });
  const put = db.calls.find((c) => c.step === 'whatsapp' && c.draft);
  check('7 leaving a step saves its draft with the version read (0)', put && put.version === 0 && put.draft.whatsappNumber === '+919800000000', JSON.stringify(put));
  check('8 step completed on the server', db.calls.some((c) => c.step === 'whatsapp' && c.action === 'complete'));

  // 4 skip tax, it is marked skipped and keeps the draft; reopen shows it
  await s.page.getByLabel('Tax ID', { exact: true }).fill('TEMP-1');
  await s.page.getByRole('button', { name: 'Skip' }).click();
  await s.page.getByText(/Step 7 of 9/).waitFor({ timeout: 5000 });
  st = await statuses(s.page);
  check('9 skipped step is marked skipped', st[5][1] === 'skipped', JSON.stringify(st[5]));
  check('10 skipping saved the draft too', db.calls.some((c) => c.step === 'tax' && c.draft?.taxId === 'TEMP-1'));

  // 5 DPA: no Skip; Mark done refused with a visible reason
  check('11 DPA step has no Skip button', await s.page.getByRole('button', { name: 'Skip' }).count() === 0);
  await s.page.getByRole('button', { name: 'Mark done' }).click();
  await s.page.getByRole('alert').filter({ hasText: 'Accept the data processing agreement first.' }).waitFor({ timeout: 5000 }).then(() => check('12 DPA done without a signature is refused visibly', true), () => check('12 DPA refusal shown', false));
  st = await statuses(s.page);
  check('13 DPA stays pending', st[6][1] !== 'done');

  // 6 reopen a skipped step from the rail: the draft comes back from the server
  await s.page.getByRole('button', { name: 'Tax', exact: true }).click();
  const fresh = await open(db, PERMS, { width: 1280, height: 900 }); // "laptop" loads the saved state
  await fresh.page.getByRole('button', { name: 'Tax', exact: true }).click();
  check('14 reopened skipped step shows its saved draft on another device', await fresh.page.getByLabel('Tax ID', { exact: true }).inputValue() === 'TEMP-1');
  await fresh.browser.close();
  check('15 zero console errors (phone session)', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('phone', s.log); await s.browser.close();

  // 7 version conflict from another device
  db = server([], {}); db.draftConflict = true;
  s = await open(db);
  await s.page.getByLabel('Clinic name').fill('Changed');
  await s.page.getByRole('button', { name: 'Clinic type' }).click();
  await s.page.getByRole('alert').filter({ hasText: 'changed on another device' }).waitFor({ timeout: 5000 }).then(() => check('16 409 on a draft tells the owner another device saved first', true), () => check('16 409 message', false));
  await s.browser.close();

  // 8 permission absent
  db = server([]);
  s = await open(db, []);
  check('17 without setup:view the wizard is not shown', await s.page.getByText(/do not have access/i).count() >= 1 && await s.page.getByRole('button', { name: 'Skip' }).count() === 0);
  check('18 and no checklist request was made', !s.log.requests.some((r) => r.path.startsWith('/setup/checklist')));
  await s.browser.close();

  // 9 go live lists blockers
  db = server(['done', 'done', 'pending', 'done', 'pending', 'done', 'pending', 'skipped']);
  s = await open(db);
  await s.page.getByRole('button', { name: 'Go live' }).click();
  const body = await s.page.locator('body').innerText();
  check('19 go-live lists the open blockers', /No doctor added/.test(body) && /WhatsApp not connected/.test(body) && /Data processing agreement not signed/.test(body));
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
