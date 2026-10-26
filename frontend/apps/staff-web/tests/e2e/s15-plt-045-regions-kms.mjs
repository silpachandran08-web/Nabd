// PLT-045 E2E: the territories (platform.regions) screen shows each region's KMS key age and last rotation.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3820 node s15-plt-045-regions-kms.mjs
import { launch, seedPlatform, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const me = (role, permissions) => ['GET', /^\/platform\/auth\/me/, { body: { id: 'op1', name: 'Op One', email: 'op@nabd.health', role, permissions } }];
const DAY = 86_400_000;
const iso = (daysAgo) => new Date(Date.now() - daysAgo * DAY).toISOString();
const region = (code, extra = {}) => ({ region: code, clinicCount: 3, activeClinicCount: 2, userCount: 9, mrrCents: 120000, currency: code === 'IN' ? 'INR' : 'SAR',
  taxIdTypes: [], planMix: [], newClinicsLast30d: 1, ...extra });
const clean = (s) => !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length;
const noOverflow = (p) => p.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
const PERMS = ['command_centre:view', 'territories:view'];

// 1. happy path at 390px: IN rotated 10 days ago (key created 400 days ago), SA has no tenant key yet
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('super_admin', PERMS),
    ['GET', /^\/platform\/territories/, { body: [region('IN', { kmsKeyCreatedAt: iso(400), kmsKeyRotatedAt: iso(10) }), region('SA')] }]]);
  await s.page.goto(`${APP}/platform/territories`, { waitUntil: 'networkidle' });
  const inText = await s.page.getByTestId('kms-IN').innerText();
  check('1 IN shows key age from the last rotation (10 days) and the rotation date', /Key age 10 days/.test(inText) && new RegExp(iso(10).slice(0, 10)).test(inText), inText.replace(/\n/g, ' | '));
  const saText = await s.page.getByTestId('kms-SA').innerText();
  check('1 SA without a key says so', /No tenant key yet/.test(saText), saText.replace(/\n/g, ' | '));
  check('1 390px no horizontal scroll', await noOverflow(s.page));
  check('1 zero console errors', clean(s)); dumpLog('1', s.log); await s.browser.close();
}
// 2. never rotated: age counts from creation, rotation reads "never"
{
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('super_admin', PERMS), ['GET', /^\/platform\/territories/, { body: [region('IN', { kmsKeyCreatedAt: iso(42), kmsKeyRotatedAt: null })] }]]);
  await s.page.goto(`${APP}/platform/territories`, { waitUntil: 'networkidle' });
  const t = await s.page.getByTestId('kms-IN').innerText();
  check('2 age from creation and rotation "never"', /Key age 42 days/.test(t) && /last rotation never/.test(t), t.replace(/\n/g, ' | '));
  await s.browser.close();
}
// 3. empty and 4. error and 5. server 403 -> role denied
{
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('super_admin', PERMS), ['GET', /^\/platform\/territories/, { body: [] }]]);
  await s.page.goto(`${APP}/platform/territories`, { waitUntil: 'networkidle' });
  check('3 empty state', (await s.page.getByText('No coverage yet').count()) === 1 && (await s.page.getByTestId('kms-IN').count()) === 0);
  await s.browser.close();
}
{
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('super_admin', PERMS), ['GET', /^\/platform\/territories/, { status: 500, body: { type: 'internal', title: 'x', detail: 'boom' } }]]);
  await s.page.goto(`${APP}/platform/territories`, { waitUntil: 'networkidle' });
  check('4 error state offers retry', (await s.page.getByRole('button', { name: /retry|try again/i }).count()) >= 1);
  await s.browser.close();
}
{
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('support_engineer', ['command_centre:view']), ['GET', /^\/platform\/territories/, { status: 403, body: { type: 'forbidden', title: 'Forbidden', detail: 'Forbidden' } }]]);
  await s.page.goto(`${APP}/platform/territories`, { waitUntil: 'networkidle' });
  check('5 403 shows role denied, no KMS section', (await s.page.locator('[data-testid^="kms-"]').count()) === 0 && (await s.page.getByText(/not available|do not have access|role/i).count()) >= 1);
  await s.browser.close();
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
