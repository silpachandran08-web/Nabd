// PHL-021: item drawer carries MRP / zero-rated / price-includes-tax and sends them on save.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3820 node tests/e2e/s16-phl-021-tax-inputs.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const ITEM = { id: 'i1', code: 'PCM', name: 'Paracetamol', isRx: false, price: 20, taxRatePercent: 12, stockQty: 5, active: true, scheduleClass: 'OTC', hsnCode: '3004', inHouse: true, priceInclusiveOfTax: null, zeroRated: false, mrp: null };
try {
  const s = await launch(); const { page } = s;
  await seedClinic(page, ['pharmacy:view', 'pharmacy:edit'], { tenantRegion: 'IN' });
  const patches = [];
  await mockApi(page, s.log, [
    ['GET', /^\/pharmacy\/settings/, { body: { mode: 'hybrid', licenceNumbers: {} } }],
    ['GET', /^\/pharmacy\/capabilities/, { body: { mode: 'hybrid', features: [] } }],
    ['GET', /^\/pharmacy\/schedule-classes/, { body: [{ code: 'OTC', label: 'OTC', requiresRx: false }] }],
    ['GET', /^\/pharmacy\/items/, { body: [ITEM] }],
    ['GET', /^\/pharmacy\/dispensing-queue/, { body: [] }],
    ['PATCH', /^\/pharmacy\/items\/i1/, (r, req) => { patches.push(JSON.parse(req.postData())); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...ITEM, ...patches.at(-1) }) }); }],
  ]);
  await page.goto(`${APP}/pharmacy`);
  await page.getByRole('link', { name: 'Enter supplier bill' }).waitFor();
  await page.getByRole('button', { name: /Edit/ }).first().click();
  await page.getByLabel('MRP').fill('25');
  await page.getByLabel(/Zero-rated/).check();
  await page.getByLabel(/Price includes tax/).check();
  await page.getByRole('button', { name: 'Save item' }).click();
  await page.waitForTimeout(500);
  const b = patches[0] ?? {};
  check('PATCH carries mrp, zeroRated and priceInclusiveOfTax', b.mrp === 25 && b.zeroRated === true && b.priceInclusiveOfTax === true, JSON.stringify(b));
  check('no page errors', s.log.pageErrors.length === 0);
  dumpLog('tax', s.log); await s.browser.close();
} catch (e) { check('script ran to completion', false, String(e).slice(0, 300)); }
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
