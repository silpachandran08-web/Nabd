// FEF-050 nightly journeys J2-J4, J7, J8, J10-J15 (QA journey ids, doc 05 time targets) and the case matrix:
//   smoke   = FEF-049 (J1, J5, J6, J9; desktop + 390px phone; IN + KSA)          -> PR preview stage 7
//   nightly = smoke + J2-J4, J7, J8, J10-J15 on desktop 1366, J1/J2/J5/J8/J9 on tablet 768 and phone 375,
//             J1/J5/J9 in Hindi on desktop                                     -> journeys-nightly.yml
// New journeys run against the mock back end only (the OPS-036 seed has no fixtures for them yet): on a preview
// target they are reported as skipped, never as passed.
import { CASES as SMOKE_CASES, JOURNEYS as SMOKE_JOURNEYS, TENANTS, PROJECTS, BUDGET_KEY as SMOKE_KEYS, SELF, json, consultMocks, data } from './journeys.mjs';
import { TARGET, permsFor } from './harness.mjs';
import { clinicToken } from '../qa-lib.mjs';
import { ConsultPage } from './pages.mjs';
import { SLA, DEVICE_JOURNEYS, HI_JOURNEYS, DEVICES } from './matrix.mjs';
import { ArrivalsCheckIn, SchedulePage, NursingPage, OtcPage, PackagesDesk, DayClosePage, InvitePage, LoginPage, AccountPage, ProvisioningConsole } from './pages-nightly.mjs';

const today = (t) => new Intl.DateTimeFormat('en-CA', { timeZone: t.timezone }).format(new Date()); // clinic day, not UTC
const iso = (min) => new Date(Date.now() + min * 60000).toISOString();
const must = (ok, msg) => { if (!ok) throw new Error(msg); };
const fail = (r, status, body) => json(r, status, body);

// ---- J2 returning patient check-in; a second check-in is refused -----------------------------------
function checkInMocks(t) {
  const st = { posts: 0 }, start = new Date().toISOString();
  const entry = { id: 'e7', appointmentId: 'ap1', patientId: 'p1', doctorId: 'd1', departmentId: 'dep1', queueDate: today(t), tokenNumber: 7, status: 'checked_in', priority: false, priorityReason: null, createdAt: start, source: 'walk_in', appointmentStatus: 'arrived' };
  return { st, start, h: [
    ['POST', /^\/queue\/check-in/, (r) => (++st.posts === 1 ? json(r, 201, entry)
      : fail(r, 409, { type: 'already-checked-in', title: 'Already checked in', detail: 'This appointment has already been checked in.', code: 'ALREADY_CHECKED_IN', tokenNumber: 7, queueEntryId: 'e7', checkedInAt: start }))],
    ['GET', /^\/queue(\?.*)?$/, { body: [] }],
    ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Synthetic', departmentName: 'General' }] }],
    ['GET', /^\/appointments\?/, { body: { data: [{ id: 'ap1', patientId: 'p1', doctorId: 'd1', startTime: start, status: 'scheduled' }], page: { nextCursor: null, limit: 100 } } }],
    ['GET', /^\/patients\/p1/, { body: { name: t.patient, mrn: 'SYN-1', phone: t.phone } }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5 } }],
    ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
    ['GET', /^\/appointments\/callback-list/, { body: [] }],
  ] };
}

// ---- J3 / J4 schedule day with a free slot, a leave column and a passed slot -------------------
function scheduleMocks(t) {
  const DAY = today(t), P1 = '44444444-4444-4444-4444-444444444441', D1 = '33333333-3333-3333-3333-333333333331', D2 = '33333333-3333-3333-3333-333333333332';
  const S = ['06:00', '06:30', '07:00'].map((x) => `${DAY}T${x}:00Z`);
  const cells = (a, b) => [{ doctorId: D1, kind: a, slotStart: a === 'available' ? S[0] : null, bookings: [] }, { doctorId: D2, kind: b, slotStart: null, bookings: [] }];
  const day = { date: DAY, today: true, timezone: t.timezone, holiday: null, counts: { appointments: 0, walkIns: 0, freeSlots: 3 },
    doctors: [{ id: D1, name: 'Dr. Hana', department: 'General', onLeave: false, leaveReason: null, worksToday: true }, { id: D2, name: 'Dr. Away', department: 'General', onLeave: true, leaveReason: 'Conference', worksToday: true }],
    sessions: [{ label: 'Morning', start: '08:45', end: '09:15', stepMinutes: 15, rows: [{ time: '08:45', cells: cells('past', 'leave') }, { time: '09:00', cells: cells('available', 'leave') }] }] };
  const st = { posts: [], mode: 'offered', joined: new Date(Date.now() - 3600000).toISOString() };
  const entry = () => ({ id: st.mode === 'offered2' ? 'w2' : 'w1', doctorId: D1, patientId: P1, patientName: 'Ali Hassan', joinedAt: st.joined, offeredSlotStart: S[0], bookedAppointmentId: st.mode === 'booked' ? 'a9' : null,
    status: st.mode === 'booked' ? 'booked' : st.mode === 'expired' ? 'expired' : 'offered', offerExpiresAt: st.mode === 'expired' ? iso(-1) : iso(15) });
  const ali = { id: P1, mrn: 'SYN-1', name: 'Ali Hassan', phone: t.phone };
  return { st, S, D1, h: [
    ['GET', /^\/schedule\/day/, { body: day }],
    ['GET', /^\/queue$/, { body: [] }],
    ['GET', /^\/doctors\/[^/]+\/availability/, { body: S }],
    ['GET', /^\/patients\?q=ali/, { body: { data: [ali], page: { nextCursor: null } } }],
    ['GET', /^\/patients\?q=/, { body: { data: [], page: { nextCursor: null } } }],
    ['POST', /^\/appointments$/, (r, req) => { st.posts.push(JSON.parse(req.postData())); return json(r, 201, { id: 'a1' }); }],
    ['GET', /^\/waitlist\?doctorId=([^&]+)/, (r, _q, m) => json(r, 200, decodeURIComponent(m[1]) === D1 ? [entry()] : [])],
    ['POST', /^\/waitlist\/[^/]+\/accept/, (r) => { st.mode = 'booked'; return json(r, 200, {}); }],
    ['GET', /^\/schedule\/week/, { body: { start: DAY, today: DAY, days: [] } }],
  ] };
}

// ---- J7 pregnancy status recorded, the prescription check escalates ---------------------------
function pregnancyMocks(t) {
  const base = consultMocks(t);
  base.st.preg = 'not_documented';
  const status = () => ({ status: base.st.preg, expectedDeliveryDate: null, recordedByName: base.st.preg === 'not_documented' ? null : 'Dr Synthetic', recordedAt: base.st.preg === 'not_documented' ? null : new Date().toISOString() });
  const warn = (name) => (base.st.preg === 'pregnant' ? [{ kind: 'pregnancy', severity: 'severe', message: `Synthetic pregnancy rule fired for ${name}`, matched: name, requiresDecision: true, resolved: false, reasonOptions: [], decision: null }] : []);
  base.h = [
    ['GET', /^\/clinical\/patients\/p1\/pregnancy-status$/, (r) => json(r, 200, status())],
    ['PUT', /^\/clinical\/patients\/p1\/pregnancy-status$/, (r, req) => { base.st.preg = JSON.parse(req.postData()).status; return json(r, 200, { ...status(), recordedBy: SELF }); }],
    ['PATCH', /^\/clinical\/prescriptions\/e1$/, (r, req) => { const items = JSON.parse(req.postData()).items.map((i, n) => ({ ...i, id: `i${n}`, warnings: warn(i.drugName) }));
      return json(r, 200, { id: 'rx1', status: 'draft', signedAt: null, items, unresolvedWarnings: items.reduce((k, i) => k + i.warnings.length, 0) }); }],
    ...base.h];
  return base;
}

// ---- J8 nurse vitals --------------------------------------------------------------------------
function vitalsMocks(t) {
  const st = { saved: null, status: 'vitals_pending' };
  const entry = () => ({ id: 'e1', appointmentId: null, patientId: 'p1', doctorId: 'd1', doctorName: 'Dr Synthetic', departmentId: 'dep1', queueDate: today(t), tokenNumber: 4, status: st.status, priority: false, priorityReason: null, createdAt: new Date(Date.now() - 600000).toISOString(), source: 'walk_in', waitMinutes: 10 });
  const patient = { id: 'p1', mrn: 'SYN-1', name: t.patient, phone: t.phone, dob: '1990-04-02', gender: 'female', allergies: [], chronicConditions: [] };
  return { st, h: [
    ['GET', /^\/nursing\/worklist$/, (r) => json(r, 200, { items: [{ entry: entry(), patient, vitals: st.saved ? { recordedAt: new Date().toISOString(), abnormalFlags: [] } : null }] })],
    ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Synthetic', departmentName: 'General' }] }],
    ['GET', /^\/clinical\/vitals\/patient\/p1\/previous/, { body: { items: [] } }],
    ['PATCH', /^\/clinical\/vitals\/e1$/, (r, req) => { st.saved = JSON.parse(req.postData()); st.status = 'vitals_done'; return json(r, 200, { abnormalFlags: [], recordedAt: new Date().toISOString() }); }],
    ['GET', /./, { body: [] }],
  ] };
}

// ---- J10 OTC: plain sale, Rx-only item blocked until a signed prescription is linked --------------
function otcMocks(t) {
  const CH = [{ id: 'c1', code: 'PH-OTC', name: 'Plain Item', category: 'Pharmacy', baseAmount: 20, followUpAmount: null, taxRatePercent: 0, stockQty: 30, isRx: false },
    { id: 'c2', code: 'PH-RX', name: 'Fixture Drug', category: 'Pharmacy', baseAmount: 50, followUpAmount: null, taxRatePercent: 0, stockQty: 30, isRx: true }];
  const st = { posts: [] };
  return { st, h: [
    ['GET', /^\/billing\/otc-charges/, { body: { currency: t.currency, charges: CH } }],
    ['POST', /^\/billing\/otc-checkout/, (r, req) => { const b = JSON.parse(req.postData()); st.posts.push(b);
      return json(r, 200, { id: 'inv-1', invoiceNumber: 'INV-0001', patientName: 'Walk-in customer', currency: t.currency, subtotal: 20, discount: 0, tax: 0, roundOff: 0, total: 20, paid: 0, balanceDue: 20, status: 'unpaid', payments: [],
        lineItems: b.lineItems.map((l) => ({ chargeCode: l.chargeCode, chargeName: l.chargeCode, category: 'Pharmacy', quantity: l.quantity, unitPrice: 20, taxRatePercent: 0, lineTotal: 20 * l.quantity })) }); }],
  ] };
}

// ---- J11 dental: tooth chart checkpoint, then sign ------------------------------------------------
function dentalMocks(t) {
  const base = consultMocks(t);
  base.st.teeth = [];
  base.h = [
    ['GET', /^\/specialty\/dental\/patients\/p1\/chart$/, (r) => json(r, 200, base.st.teeth)],
    ['PATCH', /^\/specialty\/dental\/patients\/p1\/chart\/(\d+)$/, (r, req, m) => { const tooth = { id: `t${m[1]}`, toothNumber: Number(m[1]), status: JSON.parse(req.postData()).status, note: null, isSupernumerary: false }; base.st.teeth.push(tooth); return json(r, 200, tooth); }],
    ...base.h];
  return base;
}

// ---- J12 packages: nurse redeems and requests a refund, a different person (owner) approves it ------
function packageMocks(t) {
  const st = { consumed: 0, refunds: [], approved: null };
  const inst = () => ({ id: 'i1', patientName: t.patient, packageName: 'Pkg A', invoiceNumber: 'INV-0009', validityEnd: '2027-01-01', status: 'active', invoiceId: 'inv-9',
    items: [{ id: 'it1', name: 'Session', quantityConsumed: st.consumed, quantityTotal: 5, allocatedPrice: 1000, itemType: 'service_session' }],
    events: st.consumed ? [{ eventType: 'redeemed', delta: -1, note: 'Redeemed', actorName: 'Synthetic Nurse', createdAt: new Date().toISOString() }] : [] });
  const preview = () => ({ paid: 5000, usedListValue: st.consumed * 1000, refundAmount: 5000 - st.consumed * 1000, amountOwed: 0, items: [] });
  return { st, h: [
    ['GET', /^\/packages\/instances\/i1\/refund-preview/, (r) => json(r, 200, preview())],
    ['GET', /^\/packages\/instances\/i1$/, (r) => json(r, 200, inst())],
    ['GET', /^\/packages\/instances\/items\/it1/, (r) => json(r, 200, {})],
    ['POST', /^\/packages\/instances\/items\/it1\/redeem/, (r) => { st.consumed++; return json(r, 200, {}); }],
    ['POST', /^\/packages\/instances\/i1\/refund/, (r, req) => { const b = JSON.parse(req.postData()); st.refunds.push({ id: 'r1', instanceId: 'i1', patientName: t.patient, packageName: 'Pkg A', reason: b.reason, usedListValue: preview().usedListValue,
      refundAmount: preview().refundAmount, amountOwed: 0, status: 'pending', creditNoteNumber: null, createdAt: new Date().toISOString(), recomputedAmount: null }); return json(r, 201, st.refunds[0]); }],
    ['POST', /^\/packages\/refunds\/r1\/approve/, (r, req) => { st.approved = JSON.parse(req.postData()); st.refunds[0] = { ...st.refunds[0], status: 'approved', creditNoteNumber: 'CN-000001' }; return json(r, 200, st.refunds[0]); }],
    ['GET', /^\/packages\/refunds/, (r) => json(r, 200, st.refunds)],
    ['GET', /^\/packages\/instances/, (r) => json(r, 200, [inst()])],
    ['GET', /^\/packages\/liability/, { body: { refundsAwaitingApproval: 0 } }],
    ['GET', /^\/packages\/settings/, { body: { priceFloorPercent: 0 } }],
    ['GET', /^\/packages\/expiring-soon/, { body: [] }],
    ['GET', /^\/packages(\?|$)/, { body: [] }],
    ['GET', /^\/staff\/roster/, { body: [] }],
  ] };
}

// ---- J13 day close, unlock with a reason, close again -------------------------------------------
function dayCloseMocks(t) {
  const DAY = today(t);
  const st = { cash: 500, close: null, events: [], posts: [] };
  const summary = () => ({ date: DAY, today: true, currency: t.currency, taxLabel: 'Tax', cashTolerance: 200,
    totals: { billed: 1000, bills: 1, collected: st.cash, payments: 1, tax: 0 },
    methods: [{ method: 'cash', amount: st.cash, transactions: 1 }, { method: 'card', amount: 0, transactions: 0 }, { method: 'upi', amount: 0, transactions: 0 }, { method: 'other', amount: 0, transactions: 0 }],
    outstanding: { amount: 1000 - st.cash, patients: 1, bills: 1 }, pendingCheckouts: 0, unbilled: [], taxBands: [], splitPaymentBills: 0, voidedCount: 0, voidedTotal: 0, close: st.close });
  let seq = 0;
  const ev = (o) => st.events.push({ id: `e${++seq}`, date: DAY, actorName: 'Synthetic Owner', at: new Date().toISOString(), ...o });
  return { st, h: [
    ['GET', /^\/billing\/otc-charges/, { status: 409, body: { type: 'otc-unavailable', title: 'x', detail: 'x' } }],
    ['GET', /^\/billing\/day-close\/history\?/, (r) => json(r, 200, { items: [...st.events].reverse(), nextCursor: null })],
    ['GET', /^\/billing\/day-close(\?date=[\d-]+)?$/, (r) => json(r, 200, summary())],
    ['POST', /^\/billing\/day-close$/, (r, req) => { const b = JSON.parse(req.postData()); st.posts.push(['close', b]);
      st.close = { status: 'closed', expectedCash: st.cash, countedCash: b.countedCash, variance: b.countedCash - st.cash, note: b.note ?? null, closedByName: 'Synthetic Owner', closedAt: new Date().toISOString(), reopenedByName: null, reopenedAt: null, reopenReason: null, closeCount: (st.close?.closeCount ?? 0) + 1 };
      ev({ action: 'close', expectedCash: st.cash, countedCash: b.countedCash, variance: b.countedCash - st.cash, note: b.note ?? null }); return json(r, 200, summary()); }],
    ['POST', /^\/billing\/day-close\/reopen/, (r, req) => { const b = JSON.parse(req.postData()); st.posts.push(['reopen', b]); st.cash += 300; // a cash payment taken while unlocked
      st.close = { ...st.close, status: 'reopened', reopenedByName: 'Synthetic Owner', reopenedAt: new Date().toISOString(), reopenReason: b.reason };
      ev({ action: 'reopen', expectedCash: null, countedCash: null, variance: null, note: b.reason }); return json(r, 200, summary()); }],
  ] };
}

// ---- J14 invite -> PIN -> authenticator -> sign in with TOTP -> break-glass ----------------------
function accessMocks(t) {
  const st = { enrolled: false, logins: 0, verified: false, breakGlass: null };
  const tokens = () => ({ accessToken: clinicToken(permsFor('doctor'), { tenantRegion: t.region, tenantName: t.name }), refreshToken: 'rt-1', expiresIn: 900 });
  return { st, h: [
    ['POST', /^\/staff\/invitations\/inv-1\/accept$/, { body: { setupToken: 'setup-1' } }],
    ['POST', /^\/auth\/mfa\/enroll$/, { body: { secretBase32: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Nabd:synthetic?secret=JBSWY3DPEHPK3PXP' } }],
    ['POST', /^\/auth\/mfa\/confirm$/, (r) => { st.enrolled = true; return json(r, 200, { recoveryCodes: ['AAAA111111', 'BBBB222222'] }); }],
    ['POST', /^\/auth\/login$/, (r) => { st.logins++; return json(r, 200, { challengeId: 'ch-1', method: 'totp', expiresIn: 300 }); }],
    ['POST', /^\/auth\/mfa\/verify$/, (r) => { st.verified = true; return json(r, 200, tokens()); }],
    ['GET', /^\/auth\/sessions/, { body: [] }],
    ['POST', /^\/auth\/break-glass\/activate$/, (r, req) => { st.breakGlass = JSON.parse(req.postData()); return json(r, 200, tokens()); }],
  ] };
}

// ---- J15 operator provisions a tenant to go-live -----------------------------------------------
const STEPS = ['create_tenant', 'migrate_schema', 'seed_masters', 'provision_whatsapp', 'verify_invite_owner', 'go_live'];
function provisioningMocks() {
  const st = { job: null, advances: 0 };
  const me = { id: 'op1', name: 'Synthetic Operator', email: 'op@example.test', role: 'implementation', permissions: ['onboarding_provisioning:view'] };
  return { st, h: [
    ['GET', /^\/platform\/auth\/me$/, { body: me }],
    ['GET', /^\/platform\/regions$/, { body: { items: [] } }],
    ['GET', /^\/platform\/provisioning-jobs$/, (r) => json(r, 200, st.job ? [st.job] : [])],
    ['POST', /^\/platform\/provisioning-jobs$/, (r, req) => { const b = JSON.parse(req.postData());
      st.job = { id: 'j1', tenantSlug: b.tenantSlug, tenantName: b.tenantName, region: b.region || 'IN', status: 'queued', path: b.path ?? 'self_serve', approvedAt: null, createdTenantId: null, steps: [], ownerInviteToken: null, ownerAccountInviteToken: null };
      return json(r, 201, st.job); }],
    ['POST', /^\/platform\/provisioning-jobs\/j1\/advance$/, (r) => { const n = ++st.advances;
      st.job = { ...st.job, status: n >= STEPS.length ? 'done' : 'running', createdTenantId: 'tenant-1', steps: STEPS.slice(0, n).map((s) => ({ stepName: s, status: 'done', errorDetail: null })),
        ownerInviteToken: STEPS[n - 1] === 'verify_invite_owner' ? 'synthetic-invite-token' : null, ownerAccountInviteToken: null };
      return json(r, 200, st.job); }],
    ['GET', /./, { body: [] }],
  ] };
}

const withBase = (tenant, project, attempt, journeyId, mock, front = false) => { const d = data(journeyId, tenant, project, attempt, mock); return front && TARGET === 'mock' ? { ...d, h: [...mock.h, ...d.h] } : d; }; // front: the journey's /schedule/day beats the shell stub

// ---- journeys ----------------------------------------------------------------------------------
export const NIGHTLY_JOURNEYS = [
  { id: 'J2', title: 'returning patient check-in, second check-in refused', role: 'receptionist', owner: '@nabd/engineering', budgetMs: 20000, budgetKey: 'checkInMs', mockOnly: true,
    prepare: (t, p, a) => withBase(t, p, a, 'J2', checkInMocks(t)),
    async run({ page, d, axe }) {
      const a = new ArrivalsCheckIn(page);
      await a.open(); await axe('arrivals');
      const t0 = performance.now();
      await a.checkInBooked(); await a.slip().waitFor();
      const ms = Math.round(performance.now() - t0);
      must(/#7/.test(await a.slip().innerText()), 'token #7 not on the slip');
      await axe('token slip'); await a.closeSlip();
      await a.checkInBooked();
      await a.alerts().filter({ hasText: /Already checked in as T-7 at \d\d:\d\d/ }).first().waitFor();
      must(d.st.posts === 2, `expected 2 check-in POSTs, saw ${d.st.posts}`);
      return { checkInMs: ms };
    } },
  { id: 'J3', title: 'book an appointment; leave and past slots are not offered', role: 'receptionist', owner: '@nabd/engineering', budgetMs: 30000, budgetKey: 'bookingMs', mockOnly: true,
    prepare: (t, p, a) => withBase(t, p, a, 'J3', scheduleMocks(t), true),
    async run({ page, d, axe }) {
      const s = new SchedulePage(page);
      await s.open(); await s.available().first().waitFor(); await axe('schedule');
      must((await s.available().count()) === 1, 'only the working doctor\'s free slot may offer booking');
      for (const [label, re] of [['Doctor leave', /is on leave/], ['Passed', /already passed/]]) {
        await s.cell(label).first().click();
        const dlg = page.getByRole('dialog');
        await dlg.waitFor();
        must(re.test(await dlg.innerText()), `${label}: explanation missing`);
        must((await dlg.getByRole('button', { name: /Book appointment/ }).count()) === 0, `${label} slot offers booking`);
        await page.keyboard.press('Escape'); await dlg.waitFor({ state: 'detached' });
      }
      const t0 = performance.now();
      await s.available().first().click();
      const dlg = await s.pickPatient('ali', 'Ali Hassan');
      await dlg.getByRole('button', { name: /Book appointment|Booking/ }).click();
      await dlg.waitFor({ state: 'detached' });
      const ms = Math.round(performance.now() - t0);
      must(d.st.posts.length === 1 && d.st.posts[0].doctorId === d.D1 && d.st.posts[0].startTime === d.S[0], `booking body ${JSON.stringify(d.st.posts[0])}`);
      return { bookingMs: ms };
    } },
  { id: 'J4', title: 'waitlist offer booked; an offer expires after 15 minutes', role: 'receptionist', owner: '@nabd/engineering', budgetMs: 30000, budgetKey: 'offerMs', mockOnly: true, clock: true,
    prepare: (t, p, a) => withBase(t, p, a, 'J4', scheduleMocks(t), true),
    async run({ page, d, axe }) {
      const s = new SchedulePage(page);
      await s.open();
      await s.waitlistButton().click();
      const book = page.getByRole('button', { name: 'Book offered slot' });
      await book.waitFor(); await axe('waitlist offer');
      must(/^1[45]:\d\d left$/.test((await page.getByTestId('offer-countdown').innerText()).trim()), 'the offer countdown does not start at about 15:00');
      const t0 = performance.now();
      await book.click(); await page.getByRole('button', { name: 'Confirm booking' }).click();
      await page.getByRole('status').filter({ hasText: /Ali Hassan booked at \d\d:\d\d/ }).waitFor();
      const ms = Math.round(performance.now() - t0);
      // expiry: a fresh offer, then 15 minutes pass on the page clock (the server re-offers/expires on read)
      d.st.mode = 'offered2';
      await page.keyboard.press('Escape'); await s.waitlistButton().click();
      await book.waitFor();
      d.st.mode = 'expired';
      await page.clock.fastForward('15:30');
      await page.getByText('Offer expired').waitFor();
      must((await book.count()) === 0, 'an expired offer still offers booking');
      await axe('offer expired');
      return { offerMs: ms };
    } },
  { id: 'J7', title: 'pregnancy recorded, prescription check escalates', role: 'doctor', owner: '@nabd/clinical-owners', budgetMs: 10000, budgetKey: 'escalationMs', mockOnly: true, hazards: ['H-09'],
    prepare: (t, p, a) => withBase(t, p, a, 'J7', pregnancyMocks(t)),
    async run({ page, d, axe }) {
      const c = new ConsultPage(page);
      await c.open(d.entryId); await axe('consult open');
      const strip = page.getByLabel('Clinical safety');
      await strip.getByRole('button', { name: 'Record' }).click();
      await page.getByLabel('Pregnant', { exact: false }).first().check();
      const t0 = performance.now();
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await strip.getByText(/^Pregnant/).waitFor();
      await c.prescribe('Synthdrug');
      await c.rxSave();
      const w = page.getByRole('alert').filter({ hasText: /Pregnancy \/ lactation warning/ }).first();
      await w.waitFor();
      const ms = Math.round(performance.now() - t0);
      must(d.st.preg === 'pregnant', 'the server never received the pregnancy status');
      await axe('pregnancy warning');
      return { escalationMs: ms };
    } },
  { id: 'J8', title: 'nurse captures vitals', role: 'nurse', owner: '@nabd/clinical-owners', budgetMs: 60000, budgetKey: 'vitalsMs', mockOnly: true,
    prepare: (t, p, a) => withBase(t, p, a, 'J8', vitalsMocks(t)),
    async run({ page, d, axe }) {
      const n = new NursingPage(page);
      await n.open(); await n.captureRow().waitFor(); await axe('worklist');
      const t0 = performance.now();
      await n.captureRow().click(); await n.dialog().waitFor();
      await n.fill({ bp: '120/80', pulseBpm: 72, tempCelsius: 36.8, spo2Percent: 98, weightKg: 62 });
      await axe('capture vitals');
      await n.save().click(); await n.dialog().waitFor({ state: 'detached' });
      const ms = Math.round(performance.now() - t0);
      must(d.st.saved && d.st.saved.pulseBpm === 72 && d.st.saved.bpSystolic === 120, `vitals body ${JSON.stringify(d.st.saved)}`);
      await page.getByRole('button', { name: /^Recorded/ }).or(page.getByRole('tab', { name: /^Recorded/ })).first().click();
      await n.viewButton().waitFor(); await axe('vitals recorded');
      return { vitalsMs: ms };
    } },
  { id: 'J10', title: 'OTC sale; Rx-only item blocked without a signed prescription', role: 'receptionist', owner: '@nabd/engineering', budgetMs: 30000, budgetKey: 'otcMs', mockOnly: true, hazards: ['H-04'],
    prepare: (t, p, a) => withBase(t, p, a, 'J10', otcMocks(t)),
    async run({ page, d, axe }) {
      const o = new OtcPage(page);
      await o.open(); await axe('otc');
      const t0 = performance.now();
      await o.item('Plain Item').click(); await o.checkout().click();
      await page.getByText('INV-0001').waitFor();
      const ms = Math.round(performance.now() - t0);
      must(d.st.posts.length === 1 && !('prescriptionItemId' in d.st.posts[0].lineItems[0]), 'plain sale must go without a prescription link');
      await o.open();
      await o.item('Fixture Drug').click();
      await page.getByRole('dialog', { name: /Link prescription/ }).waitFor();
      must(await o.checkout().isDisabled(), 'Rx-only item can be sold without a signed prescription');
      must((await page.getByTestId('rx-wait').innerText()).includes('signed prescription'), 'the block does not name the rule');
      must(d.st.posts.length === 1, 'an Rx-only sale reached the server');
      await axe('rx blocked');
      return { otcMs: ms };
    } },
  { id: 'J11', title: 'dental consult: tooth chart and sign checkpoints', role: 'doctor', owner: '@nabd/clinical-owners', budgetMs: 90000, budgetKey: 'dentalMs', mockOnly: true,
    prepare: (t, p, a) => withBase(t, p, a, 'J11', dentalMocks(t)),
    async run({ page, d, axe }) {
      const c = new ConsultPage(page);
      const t0 = performance.now();
      await c.open(d.entryId);
      await c.step('Examine');
      await page.getByRole('button', { name: 'Tooth chart' }).click(); // checkpoint 1: the chart is updated
      const dlg = page.getByRole('dialog', { name: /Tooth chart/ });
      await dlg.getByLabel('Tooth 16 status').selectOption('decayed');
      for (let i = 0; i < 50 && !d.st.teeth.length; i++) await page.waitForTimeout(100); await axe('tooth chart');
      must(d.st.teeth.some((x) => x.toothNumber === 16 && x.status === 'decayed'), 'tooth 16 was not saved');
      await page.keyboard.press('Escape'); await dlg.waitFor({ state: 'detached' });
      await c.diagnose('Synthetic dental diagnosis'); await c.openClose(); // checkpoint 2: note signed, visit sent on
      const btn = c.signButton(); await btn.waitFor(); await btn.click(); await c.sentToCheckout();
      const ms = Math.round(performance.now() - t0);
      must(d.st.signs === 1 && d.st.moved === 'checkout_pending', `sign POSTs ${d.st.signs}, moved to ${d.st.moved}`);
      return { dentalMs: ms };
    } },
  { id: 'J12', title: 'package redeem and refund with maker-checker', role: 'nurse', owner: '@nabd/engineering', budgetMs: 120000, budgetKey: 'packageMs', mockOnly: true,
    prepare: (t, p, a) => withBase(t, p, a, 'J12', packageMocks(t)),
    async run({ page, d, axe, s, tenant }) {
      const maker = new PackagesDesk(page);
      const t0 = performance.now();
      await maker.open(); await maker.redeemFirst();
      await page.getByText('1/5').first().waitFor();
      await maker.requestRefund('Patient relocated');
      await page.getByText('Patient relocated').first().waitFor(); await axe('refund requested');
      must(d.st.consumed === 1 && d.st.refunds.length === 1, 'redeem or refund request missing');
      must((await page.getByRole('button', { name: 'Review' }).count()) === 0, 'the maker can review their own refund request');
      const checker = new PackagesDesk(await s.second('owner', tenant));
      await checker.open(); await checker.approveFirst();
      await checker.page.getByText(/approved · CN-000001/).waitFor();
      const ms = Math.round(performance.now() - t0);
      must(d.st.approved?.expectedAmount === 4000, `approved amount ${JSON.stringify(d.st.approved)}`);
      return { packageMs: ms };
    } },
  { id: 'J13', title: 'day close, unlock with a reason, close again', role: 'owner', owner: '@nabd/engineering', budgetMs: 60000, budgetKey: 'dayCloseMs', mockOnly: true, clock: true,
    prepare: (t, p, a) => withBase(t, p, a, 'J13', dayCloseMocks(t)),
    async run({ page, d, axe }) {
      const dc = new DayClosePage(page);
      await dc.open(); await page.getByText("This day hasn't been closed yet.").waitFor(); await axe('day close');
      const t0 = performance.now();
      await dc.close(500); await dc.unlock(); await dc.close(800);
      await page.waitForFunction(() => document.querySelectorAll('section[aria-label="Close history"] li').length === 3);
      const ms = Math.round(performance.now() - t0);
      const actions = await dc.history().evaluateAll((els) => els.map((e) => e.getAttribute('data-action')));
      must(JSON.stringify(actions) === '["close","reopen","close"]', `history ${JSON.stringify(actions)}`);
      must(d.st.posts.map((x) => x[0]).join() === 'close,reopen,close' && d.st.posts[2][1].countedCash === 800, 'close/reopen/close not posted in order');
      await axe('day re-closed');
      return { dayCloseMs: ms };
    } },
  { id: 'J14', title: 'invite, authenticator, TOTP sign-in, break-glass', role: 'doctor', owner: '@nabd/security', budgetMs: 10000, budgetKey: 'loginMs', mockOnly: true, anonymous: true, hazards: [],
    prepare: (t, p, a) => withBase(t, p, a, 'J14', accessMocks(t)),
    async run({ page, d, axe }) {
      const inv = new InvitePage(page);
      await inv.accept('inv-1', '482916'); await inv.enrol('123456'); await axe('recovery codes');
      must(d.st.enrolled, 'authenticator enrolment never confirmed');
      await inv.signInNow();
      const login = new LoginPage(page);
      await login.open();
      const t0 = performance.now();
      await login.signIn({ slug: 'synthetic', email: 'doctor@example.test', pin: '482916' });
      await page.locator('#code').waitFor(); await axe('totp challenge');
      await login.verify('123456');
      await page.waitForURL((u) => !/\/login/.test(u.pathname));
      const ms = Math.round(performance.now() - t0);
      must(d.st.verified && d.st.logins === 1, 'sign-in did not go through the TOTP challenge');
      const acct = new AccountPage(page);
      await acct.open(); await acct.breakGlass('Synthetic emergency: covering an unattended patient');
      must(d.st.breakGlass?.reasonText?.length >= 10, 'break-glass sent without a reason');
      await axe('break-glass active');
      return { loginMs: ms };
    } },
  { id: 'J15', title: 'operator provisions a tenant to go-live', role: 'operator', owner: '@nabd/platform', budgetMs: 120000, budgetKey: 'provisionMs', mockOnly: true,
    prepare: (t, p, a) => withBase(t, p, a, 'J15', provisioningMocks()),
    async run({ page, d, axe }) {
      const c = new ProvisioningConsole(page);
      await c.open(); await axe('provisioning');
      const t0 = performance.now();
      await c.create({ slug: 'synthetic-glow', name: 'Synthetic Glow Clinic', brand: 'Glow', owner: 'Olivia Owner', email: 'olivia@example.test', mobile: '+910000000199' });
      await c.run().click();
      await page.getByText('Clinic staff invite link').waitFor();
      await page.getByText('Go live').first().waitFor();
      const ms = Math.round(performance.now() - t0);
      must(d.st.advances === 6 && d.st.job.status === 'done', `advances ${d.st.advances}, status ${d.st.job?.status}`);
      await axe('go live');
      return { provisionMs: ms };
    } },
];

export const JOURNEYS = [...SMOKE_JOURNEYS, ...NIGHTLY_JOURNEYS];
export const BUDGET_KEY = { ...SMOKE_KEYS, ...Object.fromEntries(NIGHTLY_JOURNEYS.map((j) => [j.id, j.budgetKey])) };

// ---- case matrix --------------------------------------------------------------------------------
const DESKTOP = PROJECTS.find((p) => p.key === 'desktop');
const TABLET = { key: 'tablet', viewport: DEVICES.tablet };
const PHONE = { key: 'phone', viewport: DEVICES.phone };
for (const j of JOURNEYS) if (SLA[j.id] !== j.budgetMs) throw new Error(`${j.id}: budget ${j.budgetMs} ms differs from matrix.mjs (${SLA[j.id]} ms)`);
if (PROJECTS.find((p) => p.key === 'desktop').viewport.width !== DEVICES.desktop.width) throw new Error('desktop viewport differs from matrix.mjs');
const jb = (id) => JOURNEYS.find((j) => j.id === id);
const mk = (journey, tenant, project, locale = 'en') => ({ id: `${journey.id} ${tenant.key} ${project.key}${locale === 'en' ? '' : ` ${locale}`}`, journey, tenant, project, locale });

export const SMOKE = SMOKE_CASES.map((c) => ({ ...c, locale: 'en' }));
export const NIGHTLY = [
  ...SMOKE,
  ...NIGHTLY_JOURNEYS.flatMap((j) => TENANTS.map((t) => mk(j, t, DESKTOP))),
  ...DEVICE_JOURNEYS.flatMap((id) => TENANTS.flatMap((t) => [TABLET, PHONE].map((p) => mk(jb(id), t, p)))),
  ...HI_JOURNEYS.flatMap((id) => TENANTS.map((t) => mk(jb(id), t, DESKTOP, 'hi'))),
];
export { TENANTS, PROJECTS };
