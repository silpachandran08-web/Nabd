// PHL-002 / PHL-004: pharmacy mode cards, capability-driven nav, class badges, buy-outside flag.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3814 node tests/e2e/s15-phl-004-pharmacy-mode.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const EDIT = ['pharmacy:view', 'pharmacy:edit', 'setup:view'];
const VIEW = ['pharmacy:view'];
const CLASSES = [
  { code: 'OTC', label: 'Over the counter', requiresRx: false, register: null, duplicateRxCopy: false, retentionYears: null, refillSelfServeAllowed: true },
  { code: 'IN_H', label: 'Schedule H', requiresRx: true, register: null, duplicateRxCopy: false, retentionYears: null, refillSelfServeAllowed: true },
  { code: 'IN_H1', label: 'Schedule H1', requiresRx: true, register: 'IN_H1', duplicateRxCopy: false, retentionYears: null, refillSelfServeAllowed: false },
];
const item = (o) => ({ id: 'i1', code: 'PHM-1', name: 'Fixture otc', isRx: false, hsnCode: null, price: 20, taxRatePercent: 0, stockQty: 9, active: true, drugId: null,
  scheduleClass: 'OTC', register: null, genericName: null, packSize: null, unit: null, inHouse: true, ...o });
const ITEMS = [item({}), item({ id: 'i2', code: 'PHM-2', name: 'Fixture h1', isRx: true, scheduleClass: 'IN_H1', register: 'IN_H1' }),
  item({ id: 'i3', code: 'PHM-3', name: 'Fixture away', inHouse: false })];
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];

function api(o = {}) {
  const st = { mode: o.mode ?? 'hybrid', patches: [], itemPatches: [] };
  const caps = () => ({ mode: st.mode, features: st.mode === 'external' ? [] : st.mode === 'hybrid' ? ['items'] : ['items', 'batches', 'worklist', 'registers', 'procurement', 'offline'] });
  const h = [
    ['GET', /^\/pharmacy\/settings/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ mode: st.mode, licenceNumbers: {} }) })],
    ['PATCH', /^\/pharmacy\/settings/, (r, req) => {
      const b = JSON.parse(req.postData()); st.patches.push(b);
      if (o.lock402 && b.mode === 'in_house') return r.fulfill({ status: 402, contentType: 'application/problem+json', body: JSON.stringify({ type: 'https://nabd.health/problems/entitlement-required', title: 'Entitlement required', detail: 'In-house pharmacy is not part of your plan. The Growth plan includes it.' }) });
      st.mode = b.mode; return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ mode: st.mode, licenceNumbers: {} }) });
    }],
    ['GET', /^\/pharmacy\/capabilities/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(caps()) })],
    ['GET', /^\/pharmacy\/schedule-classes/, { body: CLASSES }],
    ['GET', /^\/pharmacy\/items$/, (r) => o.itemsError ? r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ title: 'x', detail: 'boom' }) })
      : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(o.empty ? [] : ITEMS) })],
    ['PATCH', /^\/pharmacy\/items\/[^/]+$/, (r, req) => { st.itemPatches.push(JSON.parse(req.postData())); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ITEMS[0]) }); }],
    ['GET', /^\/pharmacy\/dispensing-queue/, { body: [] }],
  ];
  return { st, h };
}
async function open(path, perms, o = {}, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { tenantRegion: 'IN' });
  const a = api(o); await mockApi(s.page, s.log, a.h);
  await s.page.goto(`${APP}${path}`);
  return { ...s, ...a };
}
const navLink = (page) => page.locator('nav').getByRole('link', { name: /Pharmacy/ });

try {
  // 1. External: no Pharmacy nav, no item/queue calls, calm explanation.
  {
    const s = await open('/pharmacy', EDIT, { mode: 'external' }); const { page } = s;
    await page.getByText(/not shown in External mode/).waitFor();
    check('External: item master and queue explained, not an error', true);
    check('External: no GET /pharmacy/items or dispensing-queue was made', !s.log.requests.some((r) => /^\/pharmacy\/(items|dispensing-queue)/.test(r.path)), JSON.stringify(s.log.requests.map((r) => r.path)));
    await page.waitForTimeout(500);
    check('External: Pharmacy absent from the nav (not disabled)', (await navLink(page).count()) === 0);
    check('External: no inventory table', (await page.locator('table').count()) === 0);
    check('External: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('external', s.log); await s.browser.close();
  }
  // 2. Hybrid: nav shows, class + register + buy-outside badges, class select in the editor sends scheduleClass.
  {
    const s = await open('/pharmacy', EDIT, { mode: 'hybrid' }); const { page } = s;
    await page.getByRole('cell', { name: 'Fixture h1' }).waitFor();
    check('Hybrid: Pharmacy in the nav', (await navLink(page).count()) >= 1);
    const row = (n) => page.getByRole('row').filter({ hasText: n });
    check('class badge OTC on the plain item', (await row('Fixture otc').innerText()).includes('OTC'));
    check('class badge H1 + register badge on the H1 item', /H1/.test(await row('Fixture h1').innerText()) && /Register · H1/.test(await row('Fixture h1').innerText()));
    check('"Buy outside" on the item not dispensed here', (await row('Fixture away').innerText()).includes('Buy outside'));
    await row('Fixture otc').getByRole('button', { name: 'Edit' }).click();
    await page.getByLabel('Schedule class').selectOption('IN_H');
    await page.getByRole('button', { name: 'Save item' }).click();
    await page.waitForTimeout(300);
    check('editing sends scheduleClass IN_H and inHouse', s.st.itemPatches[0]?.scheduleClass === 'IN_H' && s.st.itemPatches[0]?.inHouse === true, JSON.stringify(s.st.itemPatches));
    check('Hybrid: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('hybrid', s.log); await s.browser.close();
  }
  // 3. Cards: three modes; confirm dialog says nothing is deleted; a 402 shows the plan upsell on the In-house card.
  {
    const s = await open('/pharmacy', EDIT, { mode: 'hybrid', lock402: true }); const { page } = s;
    await page.getByTestId('mode-card-in_house').waitFor();
    check('three mode cards', (await page.locator('[data-testid^="mode-card-"]').count()) === 3);
    await page.getByTestId('mode-card-in_house').click();
    await page.getByText('Nothing is deleted.').waitFor();
    check('confirm dialog states nothing is deleted', true);
    await page.getByLabel('Reason (required)').fill('Need batches');
    await page.getByRole('button', { name: 'Confirm switch' }).click();
    await page.getByText(/The Growth plan includes it/).first().waitFor();
    check('402 shows the plan that unlocks In-house', true);
    await page.getByRole('button', { name: 'Cancel' }).click();
    check('the In-house card keeps the upsell note', (await page.getByTestId('mode-card-in_house').innerText()).includes('Growth plan'));
    check('mode did not change', s.st.mode === 'hybrid');
    check('cards: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    dumpLog('cards', s.log); await s.browser.close();
  }
  // 4. A mode change reaches an open session within 5 s: the nav entry disappears.
  {
    const s = await open('/pharmacy', EDIT, { mode: 'hybrid' }); const { page } = s;
    await navLink(page).first().waitFor();
    s.st.mode = 'external'; const t0 = Date.now();
    await page.waitForFunction(() => ![...document.querySelectorAll('nav a')].some((a) => /Pharmacy/.test(a.textContent ?? '')), null, { timeout: 8000 });
    const ms = Date.now() - t0;
    check('nav drops Pharmacy within ~5 s of the mode change', ms <= 7000, `${ms} ms`);
    dumpLog('poll', s.log); await s.browser.close();
  }
  // 5. View-only: no mode cards, no edit controls.
  {
    const s = await open('/pharmacy', VIEW, { mode: 'hybrid' }); const { page } = s;
    await page.getByRole('cell', { name: 'Fixture h1' }).waitFor();
    check('view-only: mode cards absent', (await page.locator('[data-testid^="mode-card-"]').count()) === 0);
    check('view-only: Edit buttons absent', (await page.getByRole('button', { name: 'Edit' }).count()) === 0);
    check('view-only: mode shown as information', (await page.getByText('Pharmacy mode:').count()) === 1);
    dumpLog('view', s.log); await s.browser.close();
  }
  // 6. Empty and error states.
  {
    const s = await open('/pharmacy', EDIT, { mode: 'hybrid', empty: true }); await s.page.getByText('No pharmacy items yet').waitFor();
    check('empty state', true); await s.browser.close();
    const e = await open('/pharmacy', EDIT, { mode: 'hybrid', itemsError: true }); await e.page.getByRole('button', { name: /retry|try again/i }).first().waitFor();
    check('error state with retry', true); await e.browser.close();
  }
  // 7. 390 px: no horizontal page scroll.
  {
    const s = await open('/pharmacy', EDIT, { mode: 'hybrid' }, { width: 390, height: 800 }); const { page } = s;
    await page.getByRole('cell', { name: 'Fixture h1' }).waitFor();
    const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check('390px: no horizontal page scroll', over <= 0, `overflow ${over}px`);
    check('390px: no console/page errors', noise(s.log).length === 0, noise(s.log).join('|'));
    await s.browser.close();
  }
} catch (e) { console.log('ERROR', e); results.push({ id: 'script', ok: false, note: String(e) }); }
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
