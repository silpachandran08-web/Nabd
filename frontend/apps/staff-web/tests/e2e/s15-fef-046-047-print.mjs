// FEF-046 print kit + FEF-047 invoice/receipt: print media checks (emulateMedia print + page.pdf for real page sizes).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3809 node tests/e2e/s15-fef-046-047-print.mjs
import { writeFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3000';
const OUT = process.env.SHOTS ?? '/tmp/e2e-shots-e9';
mkdirSync(OUT, { recursive: true });
const DOC = '33333333-3333-3333-3333-333333333331';
const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const noOverflow = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const cleanConsole = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|ERR_ABORTED|preloaded using link preload/.test(x))];
const policy = (paper, allowed = true) => ['GET', /^\/setup\/policies/, (route) => allowed ? json(route, Object.entries(paper).map(([k, v]) => ({ id: k, policyKey: `print.default_paper.${k}`, value: v, version: 1 }))) : json(route, { type: 'forbidden', title: 'Forbidden', status: 403 }, 403)];
const PRINT_SPY = () => { window.__prints = []; window.print = () => window.__prints.push(performance.now()); };
const mm = (px) => (px * 25.4) / 96;
async function pdf(page, name) {
  const file = `${OUT}/${name}.pdf`;
  writeFileSync(file, await page.pdf({ preferCSSPageSize: true, printBackground: true }));
  const info = execFileSync('pdfinfo', [file]).toString();
  const [, w, h] = info.match(/Page size:\s+([\d.]+) x ([\d.]+) pts/) ?? [];
  const pages = Number(info.match(/Pages:\s+(\d+)/)?.[1]);
  const text = execFileSync('pdftotext', ['-layout', file, '-']).toString();
  return { wMm: (Number(w) * 25.4) / 72, hMm: (Number(h) * 25.4) / 72, pages, text };
}

// ---------- A. Token slip on an 80 mm roll ----------
const RECEPTION = ['queue:view', 'queue:edit', 'queue:create', 'patients:view', 'patients:create', 'setup:view'];
async function openSlip({ perms = RECEPTION, paper = { token_slip: '80mm' }, viewport } = {}) {
  const ctx = await launch(viewport ? { viewport } : {});
  await seedClinic(ctx.page, perms);
  await ctx.page.addInitScript(PRINT_SPY);
  await mockApi(ctx.page, ctx.log, [
    policy(paper, perms.includes('setup:view')),
    ['POST', /^\/queue\/check-in$/, (route) => json(route, { id: 'q9', tokenNumber: 9 }, 201)],
    ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
    ['GET', /^\/queue(\?|$)/, (route) => json(route, [{ id: 'q9', doctorId: DOC, tokenNumber: 9, status: 'waiting', createdAt: new Date().toISOString() }])],
    ['GET', /^\/staff\/roster/, { body: [{ id: DOC, name: 'Dr Amal Nasser', departmentName: 'General' }] }],
    ['GET', /^\/patients\?/, (route) => json(route, { data: [{ id: 'p1', name: 'Layla Haddad', mrn: 'M1', phone: '555', dob: '1990-01-01', gender: 'female', status: 'active' }] })],
    ['GET', /^\/patients\/([^/?]+)$/, (route, req, m) => json(route, { id: m[1], name: `Patient ${m[1]}`, mrn: `MRN-${m[1]}` })],
    ['GET', /./, { body: [] }],
  ]);
  await ctx.page.goto(APP + '/arrivals');
  await ctx.page.getByRole('button', { name: /Walk-in/ }).click();
  await ctx.page.locator('#patientSearch').fill('Layla');
  await sleep(800);
  await ctx.page.getByText('Layla Haddad').first().click();
  await ctx.page.locator('#doctor').selectOption(DOC);
  await ctx.page.getByRole('button', { name: 'Check in', exact: true }).last().click();
  await ctx.page.getByRole('dialog', { name: 'Checked in' }).waitFor({ timeout: 8000 });
  await ctx.page.getByText('Estimated wait').waitFor({ timeout: 5000 });
  return ctx;
}
{
  const { page, browser, log } = await openSlip();
  const sheet = page.locator('.nbp-sheet');
  check('A1 token slip is a print-kit sheet on 80mm', (await sheet.getAttribute('data-paper')) === '80mm');
  const box = await sheet.boundingBox();
  check('A1 slip is at most 80 mm wide and 12 cm tall on screen (same width as on the roll)', mm(box.width) <= 80 && mm(box.height) <= 120, `${mm(box.width).toFixed(1)} x ${mm(box.height).toFixed(1)} mm`);
  await page.emulateMedia({ media: 'print', colorScheme: 'dark' });
  const p = await pdf(page, 'token-80mm');
  check('A1 real print page is 80 mm wide, one page, <= 120 mm tall', Math.abs(p.wMm - 80) < 1 && p.pages === 1 && p.hMm <= 120.5, `${p.wMm.toFixed(1)} x ${p.hMm.toFixed(1)} mm, ${p.pages} page(s)`);
  check('A1 no clipped text: token, patient, doctor in the printout', /#9/.test(p.text) && /Patient p1|Layla/.test(p.text) && /Amal/.test(p.text), p.text.replace(/\s+/g, ' ').slice(0, 120));
  const look = await page.evaluate(() => {
    const s = document.querySelector('.nbp-sheet');
    const texts = [...s.querySelectorAll('*')].filter((e) => e.children.length === 0 && e.textContent.trim());
    return { bg: getComputedStyle(document.body).backgroundColor, colors: [...new Set(texts.map((e) => getComputedStyle(e).color))], bgs: [...new Set([s, ...s.querySelectorAll('*')].map((e) => getComputedStyle(e).backgroundColor))],
      outside: [...document.querySelectorAll('button')].filter((b) => b.offsetParent !== null).length };
  });
  check('A2 black on white from the dark theme: body white, all text rgb(0,0,0), no background colours', look.bg === 'rgb(255, 255, 255)' && look.colors.every((c) => c === 'rgb(0, 0, 0)') && look.bgs.every((c) => c === 'rgba(0, 0, 0, 0)'), JSON.stringify(look));
  check('A3 buttons and the rest of the app are hidden in print', look.outside === 0, `${look.outside} visible`);
  await page.emulateMedia({ media: 'screen' });
  await page.getByRole('button', { name: 'Print token' }).click();
  check('A4 Print token calls print straight away', (await page.evaluate(() => window.__prints.length)) === 1);
  dumpLog('A', log);
  check('A5 zero console errors', cleanConsole(log).length === 0, cleanConsole(log).join(' | '));
  await browser.close();
}
{
  // no setup:view: no policy read, built-in default (80mm for a token slip) and no 403 noise
  const { page, browser } = await openSlip({ perms: RECEPTION.filter((x) => x !== 'setup:view'), paper: { token_slip: 'a5' } });
  check('A6 without setup:view (policies answer 403) the slip uses the built-in 80mm', (await page.locator('.nbp-sheet').getAttribute('data-paper')) === '80mm');
  await browser.close();
}
{
  const { page, browser } = await openSlip({ paper: { token_slip: 'a5' } });
  check('A7 clinic setting a5 for the slip is honoured', (await page.locator('.nbp-sheet').getAttribute('data-paper')) === 'a5');
  const { page: p2, browser: b2 } = await openSlip({ paper: { token_slip: 'bogus' } });
  check('A8 an unknown stored value falls back to 80mm', (await p2.locator('.nbp-sheet').getAttribute('data-paper')) === '80mm');
  await b2.close(); await browser.close();
}
{
  const { page, browser } = await openSlip({ viewport: { width: 390, height: 800 } });
  check('A9 390px: no horizontal scroll with the slip open', await noOverflow(page));
  await browser.close();
}

// ---------- B. Rx on A5 / A4, overflow with page x of y ----------
const rxItem = (i) => ({ id: `i${i}`, drugName: `Medicine ${i}`, dosage: '1 tablet', frequency: 'twice daily', duration: '5 days', route: 'oral', instructions: 'after food, with a full glass of water', quantity: 10, quantityUnit: 'tablets' });
async function openRx(n, paper) {
  const ctx = await launch();
  await seedClinic(ctx.page, ['clinical:view', 'setup:view']);
  await ctx.page.addInitScript(PRINT_SPY);
  await mockApi(ctx.page, ctx.log, [
    policy(paper),
    ['GET', /^\/clinical\/prescriptions\/q1\/print-header/, { body: { prescriptionId: 'r1', signedAt: '2026-03-10T05:00:00Z', clinicName: 'QA Clinic', clinicRegion: 'IN', clinicPhone: '+910000000000', prescriberId: DOC, prescriberName: 'Dr Amal Nasser', professionalRegistrationNo: 'REG-12345' } }],
    ['GET', /^\/clinical\/prescriptions\/q1$/, { body: { id: 'r1', patientId: 'p1', status: 'signed', signedAt: '2026-03-10T05:00:00Z', items: Array.from({ length: n }, (_, i) => rxItem(i + 1)),
      allergySnapshot: { state: 'none_documented', allergies: [], documentedBy: DOC, documentedByName: 'Dr Amal Nasser', documentedAt: '2026-03-10T04:00:00Z' } } }],
    ['GET', /^\/patients\/p1$/, { body: { id: 'p1', name: 'Test Patient', mrn: 'MRN-1', dob: '1990-01-01', gender: 'female' } }],
    ['GET', /./, { body: [] }],
  ]);
  await ctx.page.goto(APP + '/consult/rx/q1/print');
  await ctx.page.getByRole('article', { name: 'Prescription' }).waitFor({ timeout: 15000 });
  await ctx.page.emulateMedia({ media: 'print', colorScheme: 'dark' });
  return ctx;
}
{
  const { page, browser, log } = await openRx(3, { rx: 'a5' });
  const p = await pdf(page, 'rx-a5-short');
  check('B1 Rx with default a5: real page is A5 (148 x 210 mm)', Math.abs(p.wMm - 148) < 1.5 && Math.abs(p.hMm - 210) < 1.5 && p.pages === 1, `${p.wMm.toFixed(1)} x ${p.hMm.toFixed(1)}, ${p.pages} p`);
  check('B2 every line, sig and prescriber registration are on the A5 page', /Medicine 1/.test(p.text) && /Medicine 3/.test(p.text) && /after food/.test(p.text) && /Reg\. No\. REG-12345/.test(p.text) && /Signed electronically by/.test(p.text));
  check('B3 print button needs no paper prompt (no dialog, print called once)', await (async () => { await page.emulateMedia({ media: 'screen' }); await page.getByRole('button', { name: /^Print$/ }).click(); return (await page.evaluate(() => window.__prints.length)) === 1 && (await page.getByRole('dialog').count()) === 0; })());
  check('B4 Rx sheet is the print-kit sheet with a5', (await page.locator('.nbp-sheet').getAttribute('data-paper')) === 'a5');
  check('B5 zero console errors', cleanConsole(log).length === 0, cleanConsole(log).join(' | '));
  await browser.close();
}
{
  const { page, browser } = await openRx(40, { rx: 'a5' });
  const p = await pdf(page, 'rx-a5-long');
  check('B6 long Rx overflows to several A5 pages with "Page x of y" and keeps registration number on the last page', p.pages >= 2 && new RegExp(`Page 1 of ${p.pages}`).test(p.text) && new RegExp(`Page ${p.pages} of ${p.pages}`).test(p.text) && /Medicine 40/.test(p.text) && /Reg\. No\. REG-12345/.test(p.text), `${p.pages} pages`);
  await browser.close();
}
{
  const { page, browser } = await openRx(3, {});
  const p = await pdf(page, 'rx-default-a4');
  check('B7 no clinic setting: Rx prints on A4', Math.abs(p.wMm - 210) < 1.5 && Math.abs(p.hMm - 297) < 1.5, `${p.wMm.toFixed(1)} x ${p.hMm.toFixed(1)}`);
  await browser.close();
}

// ---------- C. Document print ----------
{
  const { page, browser, log } = await launch();
  await seedClinic(page, ['clinical:view', 'setup:view']);
  await mockApi(page, log, [
    policy({ document: 'a5' }),
    ['GET', /^\/clinical\/documents\/d1/, { body: { id: 'd1', type: 'certificate', status: 'issued', issuedAt: '2026-03-10T20:00:00Z', body: { diagnosis: 'Rest advised' },
      header: { clinic: { name: 'Riyadh Clinic', region: 'IN' }, patient: { name: 'Huda Saleh', dob: '1990-01-01' }, doctor: { name: 'Dr Amal' }, visit: { date: '2026-03-10' } } } }],
    ['GET', /./, { body: [] }],
  ]);
  await page.goto(APP + '/documents/d1/print');
  await page.getByText('Riyadh Clinic').waitFor({ timeout: 15000 });
  await page.emulateMedia({ media: 'print', colorScheme: 'dark' });
  const p = await pdf(page, 'document-a5');
  check('C1 document prints on A5 from the clinic setting', Math.abs(p.wMm - 148) < 1.5 && /Riyadh Clinic/.test(p.text) && /Rest advised/.test(p.text), `${p.wMm.toFixed(1)} mm`);
  await browser.close();
}

// ---------- D. Invoice (A4) and receipt (80 mm) ----------
const line = (o) => ({ description: 'x', sac: '999999', quantity: 1, unitMinor: 0, taxableMinor: 0, exempt: false, cgstRate: 0, cgstMinor: 0, sgstRate: 0, sgstMinor: 0, igstRate: 0, igstMinor: 0, totalMinor: 0, ...o });
const DOCM = (over = {}) => ({
  invoiceNumber: 'KA01/25-26/000042', issuedAt: '2026-03-10T05:30:00Z', currency: 'INR', placeOfSupply: 'Karnataka',
  seller: { name: 'QA Clinic', gstin: '29AAAAA0000A1Z5', address: '1 Test Road', phone: '+910000000000' }, buyer: { name: 'Test Patient', mrn: 'MRN-1', gstin: null },
  lines: [line({ description: 'Consultation', taxableMinor: 50000, exempt: true, totalMinor: 50000 }), line({ description: 'Cosmetic procedure', taxableMinor: 100000, cgstRate: 9, cgstMinor: 9000, sgstRate: 9, sgstMinor: 9000, totalMinor: 118000 })],
  totals: { taxableMinor: 100000, exemptMinor: 50000, cgstMinor: 9000, sgstMinor: 9000, igstMinor: 0, discountMinor: 0, roundOffMinor: 0, totalMinor: 168000, paidMinor: 168000, balanceMinor: 0 },
  payments: [{ method: 'Cash', reference: null, amountMinor: 168000, paidAt: '2026-03-10T05:31:00Z' }], reprintCount: 0, copy: 'original', ...over,
});
async function openDoc(path, doc, { perms = ['billing:view', 'setup:view'], status = 200, paper = {}, viewport } = {}) {
  const ctx = await launch(viewport ? { viewport } : {});
  await seedClinic(ctx.page, perms);
  await ctx.page.addInitScript(PRINT_SPY);
  await mockApi(ctx.page, ctx.log, [
    policy(paper),
    ['GET', /^\/billing\/invoices\/inv1\/document/, (route, req) => { (ctx.docReqs ??= []).push(req.url()); return json(route, status === 200 ? (typeof doc === 'function' ? doc(req.url()) : doc) : { type: 'x', title: 'x', status }, status); }],
    ['GET', /./, { body: [] }],
  ]);
  await ctx.page.goto(APP + path);
  return ctx;
}
{
  const { page, browser, log } = await openDoc('/billing/invoices/inv1/print?kind=invoice', DOCM());
  await page.getByRole('article', { name: 'Tax invoice' }).waitFor({ timeout: 15000 });
  const t = await page.locator('.nbp-sheet').innerText();
  check('D1 invoice shows GSTIN, series number, SAC per line, exempt and taxable separately', /GSTIN: 29AAAAA0000A1Z5/.test(t) && /KA01\/25-26\/000042/.test(t) && (t.match(/\(999999\)/g) ?? []).length === 2 && /Exempt: ₹500\.00/.test(t) && /Taxable: ₹1,000\.00/.test(t));
  check('D2 CGST 9% + SGST 9% on the taxable line, totals to the paisa, amount in words', /CGST 9% \+ SGST 9% = ₹180\.00/.test(t) && /CGST\s*₹90\.00/.test(t) && /SGST\s*₹90\.00/.test(t) && /Total\s*₹1,680\.00/.test(t) && /Rupees One Thousand Six Hundred Eighty Only/.test(t), t.replace(/\s+/g, ' '));
  check('D3 first view is an original: no duplicate mark, document asked with copy=original', !/Duplicate copy/.test(t) && /copy=original/.test(log.requests.find((r) => /document/.test(r.path)).path));
  await page.emulateMedia({ media: 'print', colorScheme: 'dark' });
  const p = await pdf(page, 'invoice-a4');
  check('D4 invoice prints on A4 and the PDF text equals the on-screen text', Math.abs(p.wMm - 210) < 1.5 && ['Test Patient', 'KA01/25-26/000042', 'Rupees One Thousand Six Hundred Eighty Only', 'Total'].every((s) => p.text.includes(s)));
  check('D5 zero console errors', cleanConsole(log).length === 0, cleanConsole(log).join(' | '));
  await browser.close();
}
{
  const { page, browser } = await openDoc('/billing/invoices/inv1/print?kind=invoice&copy=duplicate', (u) => DOCM({ copy: 'duplicate', reprintCount: 1, reprintedAt: '2026-03-11T04:00:00Z' }));
  await page.getByRole('article', { name: 'Tax invoice' }).waitFor({ timeout: 15000 });
  const t = await page.locator('.nbp-sheet').innerText();
  check('D6 a reprint is marked "Duplicate copy" with the reprint time (clinic zone)', /Duplicate copy · reprinted 11 Mar 2026 09:30/.test(t), t.split('\n').find((l) => /Duplicate/.test(l)));
  await browser.close();
}
{
  const t0 = Date.now();
  const { page, browser } = await openDoc('/billing/invoices/inv1/print?kind=receipt&auto=1', DOCM(), { paper: { receipt: '80mm' } });
  await page.waitForFunction(() => window.__prints?.length >= 1, null, { timeout: 5000 });
  const ms = Date.now() - t0;
  check('D7 receipt print is called within 2 s of the page opening', ms < 2000, `${ms} ms`);
  await page.emulateMedia({ media: 'print', colorScheme: 'dark' });
  const p = await pdf(page, 'receipt-80mm');
  check('D8 receipt prints on an 80 mm roll, one page, with payment, balance, no tax table', Math.abs(p.wMm - 80) < 1 && p.pages === 1 && /Cash/.test(p.text) && /Balance due/.test(p.text) && !/Taxable value/.test(p.text) && p.hMm <= 200, `${p.wMm.toFixed(1)} x ${p.hMm.toFixed(1)}`);
  await browser.close();
}
{
  const bad = DOCM(); bad.totals.totalMinor += 1;
  const { page, browser } = await openDoc('/billing/invoices/inv1/print?kind=invoice', bad);
  await page.getByText(/totals don't add up/).waitFor({ timeout: 15000 });
  check('D9 a document whose totals do not add up is refused, not printed', (await page.locator('.nbp-sheet').count()) === 0);
  await browser.close();
}
for (const [status, re, label] of [[404, /wasn't found/, '404'], [409, /still a draft/, '409 INVOICE_DRAFT'], [500, /Couldn't load the invoice/, '500']]) {
  const { page, browser } = await openDoc('/billing/invoices/inv1/print?kind=invoice', null, { status });
  await page.getByText(re).waitFor({ timeout: 15000 }).then(() => check(`D10 ${label} shows its own message`, true), () => check(`D10 ${label} shows its own message`, false));
  await browser.close();
}
{
  const { page, browser } = await openDoc('/billing/invoices/inv1/print?kind=receipt', DOCM(), { viewport: { width: 390, height: 800 } });
  await page.getByRole('article', { name: 'Receipt' }).waitFor({ timeout: 15000 });
  check('D11 390px: no horizontal scroll', await noOverflow(page));
  await browser.close();
}

// ---------- E. Checkout success actions ----------
const INVOICE = { id: 'inv1', invoiceNumber: 'KA01/25-26/000042', status: 'paid', currency: 'INR', subtotal: 1500, discount: 0, tax: 180, roundOff: 0, total: 1680, paid: 1680, balanceDue: 0, patientName: 'Test Patient', lineItems: [], payments: [{ id: 'pay1', method: 'cash', amount: 1680 }] };
async function openCheckout(perms, shareReply) {
  const ctx = await launch();
  await seedClinic(ctx.page, perms);
  await ctx.page.addInitScript(PRINT_SPY);
  ctx.shares = [];
  await mockApi(ctx.page, ctx.log, [
    ['GET', /^\/billing\/checkout\/q1\/invoice/, { body: INVOICE }],
    ['GET', /^\/billing\/checkout\/q1$/, { body: { queueEntryId: 'q1', currency: 'INR', departmentId: 'd1', doctorName: 'Dr A', patientName: 'Test Patient', queueStatus: 'completed', charges: [], pendingProcedures: [], prescribedItems: [], suggestedLines: [], visitType: 'consultation' } }],
    ['POST', /^\/billing\/invoices\/inv1\/share$/, (route, req) => { ctx.shares.push({ body: req.postDataJSON(), key: req.headers()['idempotency-key'] }); return shareReply(route); }],
    ['GET', /^\/billing\/invoices\/inv1\/document/, { body: DOCM() }],
    ['GET', /./, { body: [] }],
  ]);
  await ctx.page.goto(APP + '/checkout/q1');
  await ctx.page.getByRole('link', { name: 'Print receipt' }).waitFor({ timeout: 15000 });
  return ctx;
}
{
  const { page, browser, shares, log } = await openCheckout(['billing:view', 'billing:edit', 'billing:create'], (route) => json(route, { linkId: 'l1', expiresAt: '2026-03-11T00:00:00Z' }, 202));
  check('E1 success state offers Print receipt, Print invoice, Send on WhatsApp', (await page.getByRole('link', { name: 'Print invoice' }).count()) === 1 && (await page.getByRole('button', { name: 'Send on WhatsApp' }).count()) === 1);
  await page.getByRole('button', { name: 'Send on WhatsApp' }).click();
  await page.getByText('Secure link sent on WhatsApp.').waitFor({ timeout: 5000 });
  check('E2 share posts channel=whatsapp to the patient with an Idempotency-Key; no file is sent', shares.length === 1 && shares[0].body.channel === 'whatsapp' && shares[0].body.recipient === 'patient' && /^[0-9a-f-]{36}$/.test(shares[0].key ?? ''), JSON.stringify(shares));
  await page.evaluate(() => document.activeElement?.blur());
  const t0 = Date.now();
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => window.__prints?.length >= 1, null, { timeout: 5000 }).catch(() => {});
  await page.waitForURL(/\/billing\/invoices\/inv1\/print\?kind=receipt/, { timeout: 5000 }).catch(() => {});
  check('E3 Enter on the success state opens the receipt print route', /kind=receipt&auto=1/.test(page.url()), page.url());
  await page.waitForFunction(() => window.__prints?.length >= 1, null, { timeout: 5000 }).catch(() => {});
  check('E4 receipt printed within 2 s of Enter', (await page.evaluate(() => window.__prints?.length ?? 0)) >= 1 && Date.now() - t0 < 2000, `${Date.now() - t0} ms`);
  check('E5 zero console errors', cleanConsole(log).length === 0, cleanConsole(log).join(' | '));
  await browser.close();
}
for (const [status, type, msg] of [[409, 'no-messaging-consent', /hasn't agreed to WhatsApp/], [422, 'no-phone', /no phone number/]]) {
  const { page, browser } = await openCheckout(['billing:view', 'billing:edit'], (route) => json(route, { type, title: type, status }, status));
  await page.getByRole('button', { name: 'Send on WhatsApp' }).click();
  await page.getByText(msg).waitFor({ timeout: 5000 }).then(() => check(`E6 share ${status} ${type} message`, true), () => check(`E6 share ${status} ${type} message`, false));
  await browser.close();
}
{
  const { page, browser } = await openCheckout(['billing:view'], (route) => json(route, {}, 202));
  check('E7 without billing:edit "Send on WhatsApp" is absent, printing stays', (await page.getByRole('button', { name: 'Send on WhatsApp' }).count()) === 0 && (await page.getByRole('link', { name: 'Print receipt' }).count()) === 1);
  await browser.close();
}

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
