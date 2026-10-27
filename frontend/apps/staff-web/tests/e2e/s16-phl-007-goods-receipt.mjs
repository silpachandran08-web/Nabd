// PHL-007: supplier bill grid. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3820 node tests/e2e/s16-phl-007-goods-receipt.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const ITEMS = [{ id: 'i1', name: 'Paracetamol 500', code: 'PCM', active: true }];
async function open(perms, o = {}) {
  const s = await launch();
  await seedClinic(s.page, perms, { tenantRegion: 'IN' });
  const posts = [];
  await mockApi(s.page, s.log, [
    ['GET', /^\/pharmacy\/items/, o.items ?? { body: ITEMS }],
    ['POST', /^\/pharmacy\/receipts/, (r, req) => { posts.push(JSON.parse(req.postData())); return r.fulfill(o.post ?? { status: 201, contentType: 'application/json', body: JSON.stringify({ receiptId: 'g1', subtotal: 500, batches: [{ batchNo: 'B1', effectivePurchaseRate: 41.67, unitsReceived: 12, stockAfter: 12 }] }) }); }],
  ]);
  await s.page.goto(`${APP}/pharmacy/receipt`);
  return { ...s, posts };
}
try {
  { // no pharmacy:edit -> the grid is absent, no items fetch
    const s = await open(['pharmacy:view']);
    await s.page.getByText(/do not have access/i).waitFor();
    check('no pharmacy:edit: grid absent', (await s.page.getByRole('button', { name: 'Post bill' }).count()) === 0);
    check('no pharmacy:edit: no items request', !s.log.requests.some((r) => /pharmacy\/items/.test(r.path)));
    await s.browser.close();
  }
  { // error state with retry
    const s = await open(['pharmacy:edit'], { items: { status: 500, body: { title: 'x', detail: 'x' } } });
    await s.page.getByRole('button', { name: 'Retry' }).waitFor();
    check('items failure shows the error state', true);
    await s.browser.close();
  }
  { // empty state
    const s = await open(['pharmacy:edit'], { items: { body: [] } });
    await s.page.getByText('No pharmacy items yet').waitFor();
    check('no items shows the empty state', true);
    await s.browser.close();
  }
  { // happy path + guard
    const s = await open(['pharmacy:edit']); const { page } = s;
    await page.getByLabel('Supplier', { exact: true }).fill('Acme Pharma');
    await page.getByLabel('Bill number').fill('B-100');
    await page.getByLabel('Line 1 item').selectOption('i1');
    await page.getByLabel('Line 1 Batch').fill('B1');
    await page.getByLabel('Line 1 Expiry').fill('01/20');
    for (const [l, v] of [['MRP', '60'], ['Rate', '50'], ['Qty', '10'], ['Free', '2']]) await page.getByLabel(`Line 1 ${l}`).fill(v);
    await page.getByRole('button', { name: 'Post bill' }).click();
    await page.getByText(/already expired/).waitFor();
    check('expired batch blocked on the client, nothing posted', s.posts.length === 0 && (await page.getByText(/already expired/).count()) === 1);
    check('effective rate shown (41.67)', (await page.locator('tbody tr').first().innerText()).includes('41.67'));
    await page.getByLabel('Line 1 Expiry').fill('04/45');
    await page.getByRole('button', { name: 'Post bill' }).click();
    await page.getByTestId('grn-done').waitFor();
    check('one POST with the last day of the expiry month', s.posts.length === 1 && s.posts[0].lines[0].expiryDate === '2045-04-30' && s.posts[0].lines[0].freeQty === 2, JSON.stringify(s.posts));
    await page.setViewportSize({ width: 390, height: 800 });
    check('390px: no horizontal page scroll', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    check('no console/page errors', s.log.pageErrors.length === 0 && s.log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, s.log.console.join('|'));
    dumpLog('grn', s.log); await s.browser.close();
  }
} catch (e) { check('script ran to completion', false, String(e).slice(0, 300)); }
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
