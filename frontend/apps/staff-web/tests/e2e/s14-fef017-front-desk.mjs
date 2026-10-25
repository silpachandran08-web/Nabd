// FEF-017: front-desk and money screens (patients, schedule, billing, pharmacy, packages) on the shared client.
// AC1 registry cursor paging, AC3 server validation errors next to the field + a role=alert summary, AC4 the waitlist
// countdown re-renders only its own cell. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3812 node s14-fef017-front-desk.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, sleep } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const RECEPTION = ['patients:view', 'patients:create', 'patients:edit', 'queue:view', 'queue:create', 'queue:edit', 'billing:view'];
const D1 = '33333333-3333-3333-3333-333333333331';
const P1 = '44444444-4444-4444-4444-444444444441';
const DAY = '2026-10-19';
const SLOTS = ['06:00', '06:30', '07:00'].map((t) => `${DAY}T${t}:00Z`);
const day = { date: DAY, today: false, timezone: 'Asia/Riyadh', holiday: null, sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 3 },
  doctors: [{ id: D1, name: 'Dr. Hana', department: 'General', onLeave: false, leaveReason: null, worksToday: true }] };
const problem = (status, errors, detail = 'Validation failed') => ({ status, body: { type: 'https://api.nabd.health/errors/validation', title: 'Bad request', status, detail, errors } });
const row = (id, name) => ({ id, mrn: 'MRN-' + id, name, phone: '+911234567890', age: 30, gender: 'male', minor: false, lastVisit: null, nextAppointment: null,
  nextDoctorName: null, nextDoctorId: null, nextAppointmentId: null, nextAppointmentToday: false, packageSessionsUsed: null, packageSessionsTotal: null,
  condition: null, followUpDue: false, allergy: null, balanceDue: false, duplicate: false, archived: false, queueToken: null, queueStatus: null, tags: [] });
const rows = (from, n) => Array.from({ length: n }, (_, i) => row('p' + (from + i), `Patient ${String(from + i).padStart(3, '0')}`));
const counts = { all: 120, recent: 0, followup: 0, package: 0, balance: 0, duplicate: 0, archived: 0 };
const regPage = (from, n, next) => ({ total: 120, counts, rows: rows(from, n), matchedTotal: 120, hasMore: !!next, page: { nextCursor: next, limit: 50 } });

const run = async (name, fn) => { try { await fn(); } catch (e) { check(name, false, String(e).split('\n')[0]); } };
async function open(path, perms, handlers, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers);
  await s.page.route('**/fake-poppins-*.woff2', (r) => r.fulfill({ status: 200, contentType: 'font/woff2', body: '' }));
  await s.page.goto(APP + path, { waitUntil: 'domcontentloaded' });
  return s;
}
async function finish(s, tag, allow = () => false) {
  const errs = [...s.log.pageErrors, ...s.log.console.filter((x) => x.startsWith('[error]') && !allow(x)), ...s.log.unmatched.map((u) => 'unmocked ' + u)];
  check(`${tag}: zero console/page errors`, errs.length === 0, errs.slice(0, 2).join(' | '));
  if (errs.length) dumpLog(tag, s.log);
  await s.browser.close();
}
const noHScroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
/** AC3 on one field: message beside the input (aria-describedby points at it), input aria-invalid, summary role=alert names it. */
async function expectFieldError(tag, scope, input, message, summaryRoot = scope) {
  const el = scope.locator(input);
  check(`${tag}: input is aria-invalid`, (await el.getAttribute('aria-invalid')) === 'true');
  const id = await el.getAttribute('aria-describedby');
  const msg = id ? await scope.page().locator('#' + id.split(' ')[0]).innerText().catch(() => '') : '';
  check(`${tag}: message beside the field (aria-describedby)`, msg.includes(message), msg);
  const summary = await summaryRoot.locator('[role="alert"]').allInnerTexts();
  check(`${tag}: summary with role=alert lists it`, summary.some((t) => t.includes(message)), summary.join(' | '));
}

// 1. AC1: registry pages by cursor while scrolling; no "first 200" wording.
await run('registry', async () => {
  const s = await open('/patients', RECEPTION, [
    ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }], ['GET', /^\/queue/, { body: [] }],
    ['GET', /^\/patients\/registry/, (r, req) => {
      const c = new URL(req.url()).searchParams.get('cursor');
      const body = c === 'CUR2' ? regPage(100, 20, null) : c === 'CUR1' ? regPage(50, 50, 'CUR2') : regPage(0, 50, 'CUR1');
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    }],
  ]);
  await s.page.getByText('Patient 000').waitFor({ timeout: 15000 });
  check('registry: first page is 50 rows', (await s.page.locator('tbody tr').count()) === 50);
  await s.page.getByText('Showing 50 of 120.').waitFor({ timeout: 5000 });
  await s.page.getByRole('button', { name: 'Load more' }).scrollIntoViewIfNeeded();
  await s.page.getByText('Patient 099').waitFor({ timeout: 8000 });
  await s.page.getByText('Patient 119').waitFor({ timeout: 8000 });
  check('registry: all 120 rows after two cursor pages', (await s.page.locator('tbody tr').count()) === 120);
  const calls = s.log.requests.filter((r) => r.path.startsWith('/patients/registry')).map((r) => r.path);
  check('registry: pages fetched by cursor, in order, once each', calls.filter((c) => c.includes('cursor=CUR1')).length === 1 && calls.filter((c) => c.includes('cursor=CUR2')).length === 1, calls.join(' , '));
  check('registry: no "first 200" wording, no Load more at the end', !(await s.page.content()).match(/first 200|showing first/i) && (await s.page.getByRole('button', { name: 'Load more' }).count()) === 0);
  check('registry: every request carried the bearer token (shared client)', s.log.requests.every((r) => r.auth && r.auth.startsWith('Bearer ')));
  await finish(s, 'registry');
});

// 2. AC3 + double submit: Register patient dialog.
await run('register', async () => {
  const posts = [];
  const s = await open('/patients', RECEPTION, [
    ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }], ['GET', /^\/queue/, { body: [] }],
    ['GET', /^\/patients\/registry/, { body: regPage(0, 2, null) }],
    ['POST', /^\/patients$/, async (r, req) => {
      posts.push({ key: req.headers()['idempotency-key'], body: req.postData() });
      await sleep(700);
      return r.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify(problem(400, [{ field: 'phone', message: 'is not a valid India number' }, { field: 'name', message: 'is required' }]).body) });
    }],
  ]);
  await s.page.getByText('Patient 000').waitFor({ timeout: 15000 });
  await s.page.getByRole('button', { name: /Register patient/ }).click();
  const d = s.page.getByRole('dialog');
  await d.locator('#reg-name').fill('X');
  await d.locator('#reg-phone').fill('123');
  await d.locator('#reg-dob').fill('1990-01-01');
  await d.locator('#reg-gender').selectOption('male');
  await d.getByRole('button', { name: 'Register patient' }).dblclick();
  await d.locator('#reg-phone-error').waitFor({ timeout: 5000 });
  check('register: a double click sends ONE request', posts.length === 1, String(posts.length));
  check('register: the request carries an Idempotency-Key', /^[0-9a-f-]{36}$/.test(posts[0]?.key ?? ''), posts[0]?.key);
  await expectFieldError('register phone', d, '#reg-phone', 'Mobile is not a valid India number.');
  await expectFieldError('register name', d, '#reg-name', 'is required');
  check('register: dialog stays open on a refusal', await d.isVisible());
  await d.locator('#reg-phone').fill('+919876543210');
  check('register: editing a field clears its error', (await d.locator('#reg-phone-error').count()) === 0 && (await d.locator('#reg-phone').getAttribute('aria-invalid')) === null);
  // Same body again keeps the same key (user retry); a changed body gets a new one.
  await d.getByRole('button', { name: 'Register patient' }).click();
  await d.locator('#reg-name-error').waitFor({ timeout: 5000 });
  check('register: a retry with a changed body gets a new Idempotency-Key', posts.length === 2 && posts[1].key !== posts[0].key);
  await finish(s, 'register', (x) => x.includes('status of 400'));
});

// 3. AC4: the waitlist countdown re-renders only its own cell; a lapsed offer reloads the list.
const entry = (ms) => ({ id: 'w1', doctorId: D1, patientId: P1, patientName: 'Ali Hassan', joinedAt: '2026-10-19T05:00:00Z', status: 'offered',
  offeredSlotStart: SLOTS[2], offerExpiresAt: new Date(Date.now() + ms).toISOString(), bookedAppointmentId: null });
const SCHED = [['GET', /^\/schedule\/day/, { body: day }], ['GET', /^\/queue$/, { body: [] }]];
await run('waitlist-tick', async () => {
  const s = await open('/schedule', RECEPTION, [...SCHED, ['GET', /^\/waitlist\?/, { body: [entry(60000)] }]]);
  await s.page.getByRole('button', { name: /Waitlist/ }).click();
  const d = s.page.getByRole('dialog');
  const cd = d.getByTestId('offer-countdown');
  await cd.waitFor({ timeout: 10000 });
  const t1 = await cd.innerText();
  const stats = await s.page.evaluate(() => new Promise((resolve) => {
    const root = document.querySelector('[role="dialog"]');
    let inside = 0, outside = 0;
    const obs = new MutationObserver((list) => {
      for (const m of list) {
        const el = m.target.nodeType === 3 ? m.target.parentElement : m.target;
        if (el?.closest('[data-testid="offer-countdown"]')) inside++; else outside++;
      }
    });
    obs.observe(root, { subtree: true, childList: true, characterData: true, attributes: true });
    setTimeout(() => { obs.disconnect(); resolve({ inside, outside }); }, 3300);
  }));
  const t2 = await cd.innerText();
  check('waitlist: the countdown ticks (text changed over 3 s)', t1 !== t2, `${t1} -> ${t2}`);
  check('waitlist: only the countdown cell changed in the DOM (0 other mutations)', stats.outside === 0 && stats.inside >= 2, JSON.stringify(stats));
  check('waitlist: one GET per doctor while ticking (no polling reload)', s.log.requests.filter((r) => r.path.startsWith('/waitlist?')).length === 1);
  check('waitlist: Book offered slot is offered while the offer is open', await d.getByRole('button', { name: 'Book offered slot' }).isVisible());
  await finish(s, 'waitlist-tick');
});
await run('waitlist-lapse', async () => {
  let n = 0;
  const s = await open('/schedule', RECEPTION, [...SCHED, ['GET', /^\/waitlist\?/, (r) => r.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify(++n === 1 ? [entry(2200)] : [{ ...entry(0), status: 'expired', offerExpiresAt: null, offeredSlotStart: null }]) })]]);
  await s.page.getByRole('button', { name: /Waitlist/ }).click();
  const d = s.page.getByRole('dialog');
  await d.getByRole('button', { name: 'Book offered slot' }).waitFor({ timeout: 10000 });
  await d.getByText('Offer expired').waitFor({ timeout: 8000 });
  check('waitlist: when the offer lapses the list is read again', n >= 2, String(n));
  check('waitlist: the Book button is gone once the offer lapsed', (await d.getByRole('button', { name: 'Book offered slot' }).count()) === 0);
  await finish(s, 'waitlist-lapse');
});
await run('waitlist-empty-error', async () => {
  let fail = true;
  const s = await open('/schedule', RECEPTION, [...SCHED, ['GET', /^\/waitlist\?/, (r) => (fail
    ? r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', detail: 'x' }) })
    : r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }))]]);
  await s.page.getByRole('button', { name: /Waitlist/ }).click();
  const d = s.page.getByRole('dialog');
  await d.getByText("Couldn't load the waitlist.").waitFor({ timeout: 10000 });
  fail = false;
  await d.getByRole('button', { name: /Try again|Retry/ }).click();
  await d.getByText('Nobody is on the waitlist').waitFor({ timeout: 8000 });
  check('waitlist: error state then empty state', true);
  await finish(s, 'waitlist-empty-error', (x) => x.includes('status of 500'));
});

// 4. AC3: New appointment (field error beside the time) + schedule states.
await run('new-appointment', async () => {
  const s = await open('/schedule', RECEPTION, [...SCHED, ['GET', /^\/doctors\/[^/]+\/availability/, { body: SLOTS }],
    ['GET', /^\/patients\?q=ali/, { body: { data: [{ id: P1, mrn: 'MRN-1', name: 'Ali Hassan', phone: '+966500000001' }], page: { nextCursor: null } } }],
    ['POST', /^\/appointments$/, problem(400, [{ field: 'startTime', message: 'must be on the 15-minute grid' }])]]);
  await s.page.getByRole('button', { name: '+ New appointment' }).click();
  const d = s.page.getByRole('dialog');
  await d.locator('#appt-patient').fill('ali');
  await d.getByRole('button', { name: /Ali Hassan/ }).click();
  await d.locator('#appt-time').selectOption({ index: 1 });
  await d.getByRole('button', { name: 'Book appointment' }).click();
  await d.locator('#appt-startTime-error').waitFor({ timeout: 5000 });
  await expectFieldError('appointment time', d, '#appt-time', 'must be on the 15-minute grid');
  await finish(s, 'new-appointment', (x) => x.includes('status of 400'));
});
await run('schedule-states', async () => {
  let fail = true;
  const s = await open('/schedule', ['queue:view'], [['GET', /^\/schedule\/day/, (r) => (fail
    ? r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', detail: 'x' }) })
    : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(day) }))], ['GET', /^\/queue$/, { body: [] }]], { width: 390, height: 800 });
  await s.page.getByText("Couldn't load the schedule. Try again.").waitFor({ timeout: 10000 });
  fail = false;
  await s.page.getByRole('button', { name: /Try again|Retry/ }).click();
  await s.page.getByText('Dr. Hana').first().waitFor({ timeout: 8000 });
  check('schedule: error then retry shows the day', true);
  check('schedule: queue:view only has no New appointment / Waitlist buttons', (await s.page.getByRole('button', { name: /New appointment|Waitlist/ }).count()) === 0);
  check('schedule: 390 px has no horizontal page scroll', await noHScroll(s.page));
  await finish(s, 'schedule-states', (x) => x.includes('status of 500'));
});

// 5. AC3: billing day close.
const summary = { date: DAY, today: true, currency: 'INR', taxLabel: 'GST', cashTolerance: 50, pendingCheckouts: 0, splitPaymentBills: 0, unbilled: [], close: null,
  totals: { billed: 1000, bills: 2, collected: 900, payments: 2, tax: 100 },
  methods: [{ method: 'cash', amount: 500, transactions: 1 }, { method: 'card', amount: 400, transactions: 1 }, { method: 'upi', amount: 0, transactions: 0 }, { method: 'other', amount: 0, transactions: 0 }],
  outstanding: { amount: 100, patients: 1, bills: 1 }, taxBands: [{ ratePercent: 18, taxable: 900, tax: 100 }] };
await run('billing', async () => {
  const posts = [];
  const s = await open('/billing', ['billing:view', 'billing:approve', 'billing:create'], [
    ['GET', /^\/billing\/day-close\/history/, { body: { items: [], nextCursor: null } }],
    ['GET', /^\/billing\/day-close/, { body: summary }],
    ['GET', /^\/billing\/otc-charges/, { status: 409, body: { title: 'External mode', detail: 'x' } }],
    ['POST', /^\/billing\/day-close$/, async (r, req) => { posts.push(req.postData()); await sleep(500); return r.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify(problem(400, [{ field: 'countedCash', message: 'must be 0 or more' }]).body) }); }],
  ]);
  await s.page.locator('#counted-cash').fill('500');
  await s.page.getByRole('button', { name: 'Start day close' }).click();
  const d = s.page.getByRole('dialog');
  await d.getByRole('button', { name: 'Close the day' }).dblclick();
  await d.locator('#close-countedCash-error').waitFor({ timeout: 5000 });
  check('billing: a double click on Close the day sends ONE request', posts.length === 1, String(posts.length));
  check('billing: the cash-count refusal shows beside the count and in a role=alert summary',
    (await d.locator('#close-countedCash-error').innerText()).includes('must be 0 or more') && (await d.locator('[role="alert"]').allInnerTexts()).some((t) => t.includes('Counted cash')));
  check('billing: Counter sale absent when the otc probe is refused', (await s.page.getByRole('link', { name: 'Counter sale' }).count()) === 0);
  await finish(s, 'billing', (x) => x.includes('status of 400') || x.includes('status of 409'));
});
await run('billing-perm-error', async () => {
  const s = await open('/billing', ['billing:view'], [['GET', /^\/billing\/day-close/, { status: 500, body: { title: 'x', detail: 'x' } }]], { width: 390, height: 800 });
  await s.page.getByRole('alert').getByText("Couldn't load today's billing. Try again.").waitFor({ timeout: 10000 });
  check('billing: error state', true);
  check('billing: view-only role has no day-close controls', (await s.page.getByRole('button', { name: /Start day close|Unlock day/ }).count()) === 0);
  await finish(s, 'billing-perm-error', (x) => x.includes('status of 500'));
});

// 6. AC3: packages form; pharmacy item form.
const PKG = ['packages:view', 'packages:create', 'packages:edit', 'queue:view'];
const pkgBase = [['GET', /^\/packages\/instances/, { body: [] }], ['GET', /^\/packages\/refunds/, { body: [] }], ['GET', /^\/packages\/expiring-soon/, { body: [] }],
  ['GET', /^\/packages\/liability/, { body: { activePatientPackages: 0, sessionsOwed: 0, remainingListValue: 0, remainingAllocatedValue: 0, inGracePeriod: 0, expiringIn30Days: 0, potentialExpiryLoss: 0, refundsAwaitingApproval: 0 } }],
  ['GET', /^\/packages\/settings/, { body: { priceFloorPercent: 70 } }], ['GET', /^\/staff\/roster/, { body: [] }], ['GET', /^\/packages$/, { body: [] }]];
await run('packages', async () => {
  const s = await open('/packages', PKG, [...pkgBase, ['POST', /^\/packages$/, problem(400, [{ field: 'name', message: 'must be unique' }, { field: 'price', message: 'must be above the price floor' }])]]);
  await s.page.getByRole('button', { name: 'New package' }).click();
  const f = s.page.locator('form');
  await f.locator('label:text-is("Package name") + input').fill('Glow');
  await f.locator('label:text-is("Name") + input').first().fill('Facial');
  await f.getByRole('button', { name: 'Save as draft' }).click();
  await f.locator('#pkf-name-error').waitFor({ timeout: 5000 });
  await expectFieldError('package name', f, 'label:text-is("Package name") + input', 'must be unique');
  check('packages: the price refusal also shows beside the price', (await f.locator('#pkf-price-error').innerText()).includes('above the price floor'));
  check('packages: view+create+edit role has no Package liability tab', (await s.page.getByRole('button', { name: 'Package liability' }).count()) === 0 && (await s.page.getByText('Package liability').count()) === 0);
  await finish(s, 'packages', (x) => x.includes('status of 400'));
});
await run('packages-403', async () => {
  const s = await open('/packages', ['queue:view'], [['GET', /^\/packages/, { status: 403, body: { title: 'Forbidden', detail: 'no' } }], ['GET', /^\/staff\/roster/, { body: [] }]]);
  await s.page.getByText("doesn't have access to Treatment Packages").waitFor({ timeout: 10000 });
  check('packages: 403 shows the no-access state', true);
  await finish(s, 'packages-403', (x) => x.includes('status of 403'));
});
await run('pharmacy', async () => {
  const s = await open('/pharmacy', ['pharmacy:view', 'pharmacy:edit'], [
    ['GET', /^\/pharmacy\/settings/, { body: { mode: 'hybrid' } }], ['GET', /^\/pharmacy\/items/, { body: [] }], ['GET', /^\/pharmacy\/dispensing-queue/, { body: [] }],
    ['POST', /^\/pharmacy\/items$/, problem(400, [{ field: 'price', message: 'must not be negative' }])]]);
  await s.page.locator('#ph-name').fill('Sunscreen');
  await s.page.locator('#ph-price').fill('-5');
  await s.page.getByRole('button', { name: 'Add item' }).click();
  await s.page.locator('#ph-price-error').waitFor({ timeout: 5000 });
  await expectFieldError('pharmacy price', s.page, '#ph-price', 'must not be negative', s.page.locator('main'));
  await finish(s, 'pharmacy', (x) => x.includes('status of 400'));
});

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
