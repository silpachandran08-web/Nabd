// FEF-049 smoke journeys J1, J5, J6, J9 (QA journey ids) with their time budgets. Each journey runs per seeded
// tenant (IN, KSA) and project (desktop 1280, mobile 390). Mock target: synthetic data below. Preview target: ids
// from the OPS-036 seed's fixtures file (slot `${journey}:${project}:${attempt}`, so a retry never reuses a signed visit).
import { previewFixtures, TARGET } from './harness.mjs';
import { ArrivalsPage, ConsultPage, CheckoutPage } from './pages.mjs';

// Synthetic only. Phones use the reserved test range of tools/synthetic-data (national number starting 0000: never assignable).
export const TENANTS = [
  { key: 'IN', region: 'IN', name: 'Synthetic Clinic IN', timezone: 'Asia/Kolkata', currency: 'INR', patient: 'Asha Verma', phone: '+910000000101', walkIn: 'Kavya Iyer' },
  { key: 'KSA', region: 'SA', name: 'Synthetic Clinic KSA', timezone: 'Asia/Riyadh', currency: 'SAR', patient: 'Noura Al-Harbi', phone: '+9660000000101', walkIn: 'Reem Al-Qahtani' },
];
export const PROJECTS = [
  { key: 'desktop', viewport: { width: 1280, height: 900 } },
  { key: 'mobile', viewport: { width: 390, height: 844 } },
];
const SELF = '11111111-1111-1111-1111-111111111111'; // the mock JWT's staff id (qa-lib clinicToken)
const json = (r, status, body) => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });

// ---- mock back ends (stateful where the journey writes) ------------------------------------------
// Shell boot of a healthy clinic: tenants.timezone is configured (FEF-011 warns `[clinic-timezone-fallback]` when it
// is not). Smoke roles lack setup:view, so /setup/profile is 403 and the zone comes from /schedule/day, as on the API.
const clinicMocks = (t) => [
  ['GET', /^\/setup\/profile$/, { status: 403, body: { type: 'forbidden', title: 'Forbidden', detail: 'Missing setup:view.' } }],
  ['GET', /^\/schedule\/day(\?.*)?$/, { body: { timezone: t.timezone } }],
];
function arrivalsMocks(t) {
  const st = { checkIns: 0 };
  return { st, h: [
    ['GET', /^\/queue(\?.*)?$/, { body: [] }],
    ['GET', /^\/staff\/roster/, { body: [{ id: 'd1', name: 'Dr Synthetic', departmentName: 'General' }] }],
    ['GET', /^\/patients\?q=/, { body: { data: [] } }],
    ['POST', /^\/patients$/, { status: 201, body: { id: 'p9' } }],
    ['POST', /^\/queue\/check-in$/, (r) => { st.checkIns++; return json(r, 201, { id: 'e9', patientId: 'p9', doctorId: 'd1', tokenNumber: 7, status: 'checked_in', createdAt: new Date().toISOString() }); }],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
    ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
    ['GET', /^\/appointments/, { body: [] }],
  ], doctor: 'Dr Synthetic', region: t.region };
}

function consultMocks(t, { allergy = null } = {}) {
  const entry = { id: 'e1', appointmentId: null, patientId: 'p1', doctorId: SELF, departmentId: 'dep1', queueDate: new Date().toISOString().slice(0, 10), tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString(), source: 'walk_in' };
  const allergies = allergy ? [{ id: 'a1', substance: allergy, severity: 'severe', reaction: null, active: true }] : [];
  const patient = { id: 'p1', mrn: 'SYN-1', name: t.patient, phone: t.phone, dob: '1990-04-02', gender: 'female', status: 'active', allergies: allergies.map((a) => a.substance), chronicConditions: [], activePackages: 0, lastVisitAt: null };
  const st = { note: null, signs: 0, rxSaves: 0, moved: null };
  const warn = (name) => (allergy && name.trim().toLowerCase() === allergy.toLowerCase()
    ? [{ kind: 'allergy', severity: 'severe', message: `recorded allergy ${allergy}`, matched: allergy, requiresDecision: true, resolved: false, reasonOptions: [], decision: null }] : []);
  return { st, h: [
    ['GET', /^\/queue(\?|$)/, (r) => json(r, 200, [{ ...entry, status: st.moved ?? entry.status }])],
    ['GET', /^\/patients\/p1$/, { body: patient }],
    ['GET', /^\/departments\/dep1\/flow/, { body: { steps: [] } }],
    ['GET', /^\/clinical\/patients\/p1\/pregnancy-status$/, { body: { status: 'not_documented', expectedDeliveryDate: null, recordedByName: null, recordedAt: null } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies\/status$/, { body: { state: allergy ? 'recorded' : 'not_assessed', activeCount: allergies.length, documentedByName: null, documentedAt: null } }],
    ['GET', /^\/clinical\/patients\/p1\/allergies$/, { body: allergies }],
    ['GET', /^\/clinical\/notes\/e1$/, (r) => (st.note ? json(r, 200, st.note) : json(r, 404, { title: 'Not found', detail: 'No note yet.' }))],
    ['PATCH', /^\/clinical\/notes\/e1$/, (r, req) => { st.note = { ...(st.note ?? {}), ...JSON.parse(req.postData()), id: 'n1', status: 'draft', signedAt: null, updatedAt: new Date().toISOString() }; return json(r, 200, st.note); }],
    ['POST', /^\/clinical\/notes\/e1\/sign$/, (r) => { st.signs++; st.note = { ...st.note, status: 'signed', signedAt: new Date().toISOString() }; return json(r, 200, st.note); }],
    ['PATCH', /^\/queue\/e1\/status$/, (r, req) => { st.moved = JSON.parse(req.postData()).status; return json(r, 200, { ...entry, status: st.moved }); }],
    // CLN-005: the smoke doctor is a registered clinician with an in-date licence (the preview seed marks it the same way)
    ['GET', /^\/clinical\/prescriptions\/e1\/signing-authority$/, { body: { canSign: true, reason: null } }],
    ['GET', /^\/clinical\/prescriptions\/e1$/, { status: 404, body: { title: 'Not found', detail: 'No prescription yet.' } }],
    ['PATCH', /^\/clinical\/prescriptions\/e1$/, (r, req) => { st.rxSaves++; const items = JSON.parse(req.postData()).items.map((i, n) => ({ ...i, id: `i${n}`, warnings: warn(i.drugName) }));
      return json(r, 200, { id: 'rx1', status: 'draft', signedAt: null, items, unresolvedWarnings: items.reduce((k, i) => k + i.warnings.length, 0) }); }],
    ['GET', /^\/clinical\/vitals\//, { status: 404, body: { title: 'Not found', detail: 'No vitals.' } }],
    ['GET', /./, { body: [] }],
  ], entryId: 'e1', patientName: t.patient };
}

function checkoutMocks(t) {
  const Q = '33333333-3333-4333-8333-333333333333';
  const charge = { id: 'c1', code: 'CONS', name: 'Consultation', category: 'Consultation', baseAmount: 500, followUpAmount: null, taxRatePercent: 0, stockQty: null };
  const st = { invoice: null, bills: 0, payments: [] };
  const view = () => { const paid = st.payments.reduce((s, p) => s + p.amount, 0); return { ...st.invoice, payments: st.payments, paid, balanceDue: st.invoice.total - paid, status: paid >= st.invoice.total ? 'paid' : 'unpaid' }; };
  return { st, h: [
    ['GET', /^\/billing\/checkout\/[^/]+$/, { body: { queueEntryId: Q, patientName: t.patient, doctorName: 'Dr Synthetic', visitType: 'Walk-in', followUpEligible: false, currency: t.currency,
      charges: [charge], pendingProcedures: [], prescribedItems: [], departmentId: 'dep1', queueStatus: 'checkout_pending' } }],
    ['GET', /^\/departments\/[^/]+\/flow/, { body: [] }],
    ['GET', /^\/billing\/checkout\/[^/]+\/invoice$/, (r) => (st.invoice ? json(r, 200, view()) : json(r, 404, { title: 'Not found', detail: 'No invoice yet.' }))],
    ['GET', /^\/billing\/invoices\/inv-1$/, (r) => json(r, 200, view())],
    ['POST', /^\/billing\/checkout\/[^/]+$/, (r) => { st.bills++; st.invoice = { id: 'inv-1', invoiceNumber: 'INV-0001', patientName: t.patient, doctorName: 'Dr Synthetic', currency: t.currency,
      subtotal: 500, discount: 0, tax: 0, roundOff: 0, total: 500, lineItems: [{ chargeCode: 'CONS', chargeName: 'Consultation', category: 'Consultation', quantity: 1, unitPrice: 500, taxRatePercent: 0, lineTotal: 500 }] };
      return json(r, 201, view()); }],
    ['POST', /^\/billing\/invoices\/inv-1\/payments$/, (r, req) => { const b = JSON.parse(req.postData()); st.payments.push({ id: `p${st.payments.length + 1}`, method: b.method, amount: b.amount, recordedAt: new Date().toISOString() }); return json(r, 201, view()); }],
  ], entryId: Q, patientName: t.patient, charge: 'Consultation' };
}

/** Per-journey data: mock fixtures, or the preview seed's slot for this case and attempt. */
function data(journey, tenant, project, attempt, mock) {
  if (TARGET === 'mock') return { ...mock, h: [...clinicMocks(tenant), ...mock.h] };
  const f = previewFixtures(tenant.key);
  if (!f) throw new Error(`SMOKE_FIXTURES has no tenant ${tenant.key}`);
  return { ...f, ...(f.slots?.[`${journey}:${project}:${attempt}`] ?? {}), h: [] };
}

// ---- journeys -----------------------------------------------------------------------------------
export const JOURNEYS = [
  { id: 'J1', title: 'walk-in registration to token', role: 'receptionist', owner: '@nabd/engineering', budgetMs: 45000,
    prepare: (t, p, a) => data('J1', t, p, a, arrivalsMocks(t)),
    async run({ page, d, tenant, axe, attempt }) {
      const arr = new ArrivalsPage(page);
      await arr.open(); await axe('arrivals');
      const who = TARGET === 'mock' ? tenant.walkIn : `${tenant.walkIn} ${Date.now().toString(36)}${attempt}`; // preview: never collide with the seed
      const ms = await (async () => { const t0 = performance.now();
        // preview: a fresh test-range number per run, or the duplicate-patient check (rightly) stops the walk-in
        const phone = TARGET === 'mock' ? '+910000099001' : `${d.walkInPhonePrefix}${String(Date.now() % 1e6).padStart(6, '0')}`;
        const dob = TARGET === 'mock' ? '1988-07-15' : new Date(Date.UTC(1950 + (Date.now() % 50), 0, 1 + (Date.now() % 360))).toISOString().slice(0, 10); // a fresh dob keeps the duplicate check quiet
        await arr.registerWalkIn({ name: who, phone, dob }, d.doctor?.name ?? d.doctor);
        const tok = await arr.token(); if (!/^#\d+$/.test(tok)) throw new Error(`token slip shows "${tok}"`);
        return Math.round(performance.now() - t0); })();
      await axe('token slip');
      if (TARGET === 'mock' && d.st.checkIns !== 1) throw new Error(`expected 1 check-in POST, saw ${d.st.checkIns}`);
      return { walkInMs: ms };
    } },
  { id: 'J5', title: 'consult note sign and send to checkout', role: 'doctor', owner: '@nabd/clinical-owners', budgetMs: 2000,
    prepare: (t, p, a) => data('J5', t, p, a, consultMocks(t)),
    async run({ page, d, axe }) {
      const c = new ConsultPage(page);
      await c.open(d.entryId); await axe('consult open');
      await c.diagnose('Synthetic smoke diagnosis');
      await c.openClose(); await axe('close step');
      const btn = c.signButton(); await btn.waitFor();
      const t0 = performance.now(); await btn.click(); await c.sentToCheckout(); const ms = Math.round(performance.now() - t0);
      await axe('signed');
      if (TARGET === 'mock' && (d.st.signs !== 1 || d.st.moved !== 'checkout_pending')) throw new Error(`sign POSTs ${d.st.signs}, moved to ${d.st.moved}`);
      return { signMs: ms };
    } },
  { id: 'J6', title: 'severe-allergy prescription warning', role: 'doctor', owner: '@nabd/clinical-owners', budgetMs: 500,
    prepare: (t, p, a) => data('J6', t, p, a, consultMocks(t, { allergy: 'Synthamoxil' })),
    async run({ page, d, axe }) {
      const c = new ConsultPage(page);
      await c.open(d.entryId);
      await c.prescribe(d.allergyDrug ?? 'Synthamoxil'); await axe('rx line entered');
      const t0 = performance.now(); await c.rxSave(); await c.allergyWarning().waitFor(); const ms = Math.round(performance.now() - t0);
      const txt = await c.allergyWarning().innerText();
      if (!/Severe/.test(txt)) throw new Error(`warning does not name the severity: ${txt.slice(0, 120)}`);
      await axe('allergy warning');
      return { warningMs: ms };
    } },
  { id: 'J9', title: 'checkout bill and collect', role: 'receptionist', owner: '@nabd/engineering', budgetMs: 60000,
    prepare: (t, p, a) => data('J9', t, p, a, checkoutMocks(t)),
    async run({ page, d, axe }) {
      const co = new CheckoutPage(page);
      const t0 = performance.now();
      await co.open(d.entryId, d.patientName); await axe('checkout open');
      await co.addCharge(d.charge ?? d.chargeName); await co.collect(); await co.paid();
      const ms = Math.round(performance.now() - t0);
      await axe('paid');
      if (TARGET === 'mock' && (d.st.bills !== 1 || d.st.payments.length !== 1)) throw new Error(`bills ${d.st.bills}, payments ${d.st.payments.length}`);
      return { checkoutMs: ms };
    } },
];
/** Which timing each journey's budget applies to. */
export const BUDGET_KEY = { J1: 'walkInMs', J5: 'signMs', J6: 'warningMs', J9: 'checkoutMs' };

/** All cases in a stable order (sharding depends on it). */
export const CASES = JOURNEYS.flatMap((j) => TENANTS.flatMap((t) => PROJECTS.map((p) => ({ id: `${j.id} ${t.key} ${p.key}`, journey: j, tenant: t, project: p }))));
