// FIN-017 E2E: wizard Price master step: specialty -> editable starter list -> live billing preview -> save (mocked API).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3810 node s17-fin-017-price-template.mjs
import { launch, seedClinic, mockApi, check, dumpLog, APP } from './qa-lib.mjs';

const STEPS = ['profile', 'preset', 'doctors', 'prices', 'whatsapp', 'tax', 'dpa', 'import', 'go_live'];
const head = (code, name, category, baseAmount, taxCategory, taxRatePercent, sacHsn, displayOrder) => ({ code, name, category, baseAmount, taxCategory, taxRatePercent, sacHsn, displayOrder });
const TEMPLATE = { templateId: '33333333-3333-3333-3333-333333333333', specialty: 'derma', jurisdiction: 'IN', version: 1, reviewed: false,
  reviewNote: 'pending review', currency: 'INR', heads: [
    head('CONS', 'Consultation', 'Consultation', 700, 'exempt', 0, '999312', 10), head('FU', 'Follow-up', 'Consultation', 400, 'exempt', 0, '999312', 20),
    head('LASER', 'Laser session', 'Cosmetic', 4000, 'taxable', 18, '999722', 30), head('PEEL', 'Chemical peel', 'Cosmetic', 3000, 'taxable', 18, '999722', 40)] };
const handlers = (db) => [
  ['GET', /^\/setup\/checklist$/, { body: STEPS.map((step, i) => ({ step, status: i < 3 ? 'done' : i === 3 ? 'current' : 'pending', draft: {}, version: 0 })) }],
  ['GET', /^\/setup\/profile$/, { body: { name: 'Glow Clinic', region: 'IN', timezone: 'Asia/Kolkata', taxIdType: null, taxId: null, whatsappNumber: null, specialties: [] } }],
  ['GET', /^\/setup\/charges$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(db.charges) })],
  ['GET', /^\/setup\/price-templates\?specialty=derma$/, { body: TEMPLATE }],
  ['POST', /^\/setup\/charges\/bulk$/, (r, req) => {
    const body = JSON.parse(req.postData()); db.posts.push({ body, key: req.headers()['idempotency-key'] });
    const have = new Set(db.charges.map((c) => c.code.toUpperCase()));
    const created = body.heads.filter((h) => !have.has(h.code)).map((h) => h.code);
    body.heads.filter((h) => !have.has(h.code)).forEach((h) => db.charges.push({ ...h, active: true }));
    return r.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ created, skipped: body.heads.map((h) => h.code).filter((c) => !created.includes(c)) }) });
  }],
  ['GET', /./, { body: [] }],
];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
const buttons = (page) => page.getByLabel('Billing screen preview').locator('[class*="chargeName"]').allInnerTexts();
let s;
try {
  const db = { charges: [{ code: 'CONS', name: 'Our consult', category: 'Consultation', baseAmount: 999, taxRatePercent: 0, active: true, displayOrder: 1 }], posts: [] };
  s = await launch({ viewport: { width: 1280, height: 900 } });
  await seedClinic(s.page, ['setup:view', 'setup:edit'], { owner: true });
  await mockApi(s.page, s.log, handlers(db));
  await s.page.goto(`${APP}/setup/wizard`, { waitUntil: 'networkidle' });
  await s.page.getByText('Price master').waitFor({ timeout: 15000 });
  check('1 no specialty yet: empty state', await s.page.getByText('No specialty picked yet').count() === 1);

  await s.page.getByRole('button', { name: 'Dermatology' }).click();
  await s.page.getByLabel('Name of LASER').waitFor({ timeout: 5000 });
  const body = await s.page.locator('body').innerText();
  check('2 derma IN: heads pre-filled with categories, SAC and GST badges', /GST 18%/.test(body) && /Exempt/.test(body) && /SAC 999722/.test(body));
  check('3 existing CONS kept: read-only and marked', await s.page.getByLabel('Name of CONS').getAttribute('readonly') !== null && /Already in your price list/.test(body));
  check('4 pending review note shown', /pending review/i.test(body));
  let names = await buttons(s.page);
  check('5 preview shows existing + new buttons in display order', JSON.stringify(names.map((n) => n.split(' GST')[0].split(' Exempt')[0].trim())) === JSON.stringify(['Our consult', 'Follow-up', 'Laser session', 'Chemical peel']), JSON.stringify(names));

  const before = s.log.requests.length;
  const t0 = Date.now();
  await s.page.getByLabel('Name of LASER').fill('Laser (face)');
  await s.page.getByLabel('Billing screen preview').getByText('Laser (face)').waitFor({ timeout: 200 }).then(() => check('6 label edit reaches the preview within 200 ms', Date.now() - t0 < 1000), () => check('6 label edit reaches the preview within 200 ms', false));
  await s.page.getByRole('button', { name: 'Move Laser (face) up' }).click();
  names = await buttons(s.page);
  check('7 reorder reaches the preview', names.findIndex((n) => n.startsWith('Laser (face)')) < names.findIndex((n) => n.startsWith('Follow-up')), JSON.stringify(names));
  await s.page.getByLabel('Include Chemical peel').uncheck();
  check('8 excluded head leaves the preview', !(await buttons(s.page)).some((n) => n.startsWith('Chemical peel')));
  check('9 no API call per edit', s.log.requests.slice(before).length === 0, JSON.stringify(s.log.requests.slice(before)));

  await s.page.getByLabel('Amount of FU').fill('abc');
  check('10 bad amount: Save disabled with a reason', await s.page.getByRole('button', { name: 'Save price list' }).isDisabled());
  await s.page.getByLabel('Amount of FU').fill('450');
  await s.page.getByRole('button', { name: 'Save price list' }).click();
  await s.page.getByRole('status').filter({ hasText: 'added to your price list' }).waitFor({ timeout: 5000 });
  const post = db.posts[0];
  check('11 save posts the edited list with an Idempotency-Key', post && post.key && post.body.heads.length === 3 && post.body.heads.find((h) => h.code === 'FU').baseAmount === 450
    && post.body.heads.find((h) => h.code === 'LASER').name === 'Laser (face)', JSON.stringify(post));
  check('12 result names kept heads', /2 added to your price list, 1 already there/.test(await s.page.locator('body').innerText()));
  check('13 zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('owner', s.log); await s.browser.close();

  // not the owner: lists and preview, but no Save button at all
  s = await launch({ viewport: { width: 390, height: 844 } });
  await seedClinic(s.page, ['setup:view', 'setup:edit'], { owner: false });
  await mockApi(s.page, s.log, handlers({ charges: [], posts: [] }));
  await s.page.goto(`${APP}/setup/wizard`, { waitUntil: 'networkidle' });
  await s.page.getByRole('button', { name: 'Dermatology' }).click();
  await s.page.getByLabel('Name of LASER').waitFor({ timeout: 5000 });
  check('14 non-owner: Save absent', await s.page.getByRole('button', { name: 'Save price list' }).count() === 0);
  check('15 390px: no horizontal page scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
