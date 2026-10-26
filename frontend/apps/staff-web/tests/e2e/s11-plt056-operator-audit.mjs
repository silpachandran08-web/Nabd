// PLT-056 E2E: audit console page size fixed at 50, values hidden unless compliance_dpo gives a reason; support grant
// form sends ticket + scope + minutes (<= 60).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3521 node s11-plt056-operator-audit.mjs
import { launch, seedPlatform, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const T1 = '11111111-0000-0000-0000-000000000001';
const T2 = '11111111-0000-0000-0000-000000000002';
const me = (role, permissions) => ['GET', /^\/platform\/auth\/me/, { body: { id: 'op1', name: 'Op One', email: 'op@nabd.health', role, permissions } }];
const tenants = ['GET', /^\/platform\/tenants\?/, { body: { data: [{ id: T1, name: 'Clinic One', slug: 'one' }, { id: T2, name: 'Clinic Two', slug: 'two' }], page: { nextCursor: null, limit: 100 } } }];
const entry = (withValues) => ({ id: 1, tenantId: T1, tenantName: 'Clinic One', tenantSlug: 'one', actorType: 'staff', actorName: 'Dr A',
  actorRole: 'Doctor', action: 'patient.update', entityType: 'patient', entityId: null,
  before: withValues ? { status: 'old' } : null, after: withValues ? { status: 'new' } : null, createdAt: '2026-10-01T10:00:00Z' });
const auditPage = (route, req) => {
  const values = new URL(req.url()).searchParams.get('includeValues') === 'true';
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [entry(values)], page: { nextCursor: null, limit: 50 } }) });
};
const clean = (s) => !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length;
const noOverflow = (p) => p.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
const auditReqs = (s) => s.log.requests.filter((r) => r.path.startsWith('/platform/audit-log'));
const AUDIT_PERMS = ['command_centre:view', 'audit_compliance:view'];

// 1. compliance_dpo: page size 50, values hidden, reason gate, then values shown (390px)
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('compliance_dpo', [...AUDIT_PERMS, 'audit_compliance:values']), tenants, ['GET', /^\/platform\/audit-log\?/, auditPage]]);
  await s.page.goto(`${APP}/platform/audit`, { waitUntil: 'networkidle' });
  const first = new URL('http://x' + auditReqs(s)[0].path).searchParams;
  check('1 first search limit=50, no includeValues', first.get('limit') === '50' && !first.has('includeValues'));
  check('1 values hidden by default', (await s.page.getByText('Values hidden').count()) === 1 && (await s.page.getByRole('button', { name: 'View diff' }).count()) === 0);
  check('1 reason prompt present for DPO', (await s.page.locator('#valuesReason').count()) === 1);
  await s.page.getByRole('button', { name: 'Show values' }).click();
  await sleep(300);
  check('1 empty reason refused client-side', (await s.page.getByText('Enter a reason to see before and after values.').count()) === 1 && auditReqs(s).length === 1);
  await s.page.locator('#valuesReason').fill('DSAR-77');
  await s.page.getByRole('button', { name: 'Show values' }).click();
  await sleep(600);
  const second = new URL('http://x' + auditReqs(s).at(-1).path).searchParams;
  check('1 values request carries includeValues + reason, limit still 50', second.get('includeValues') === 'true' && second.get('reason') === 'DSAR-77' && second.get('limit') === '50');
  check('1 values shown after reason', (await s.page.getByRole('button', { name: 'View diff' }).count()) === 1 && (await s.page.getByText('Values hidden').count()) === 0);
  check('1 390px no horizontal scroll', await noOverflow(s.page));
  check('1 zero console errors', clean(s)); dumpLog('1', s.log); await s.browser.close();
}
// 2. super_admin: console works, reason prompt absent
{
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('super_admin', AUDIT_PERMS), tenants, ['GET', /^\/platform\/audit-log\?/, auditPage]]);
  await s.page.goto(`${APP}/platform/audit`, { waitUntil: 'networkidle' });
  check('2 rows listed', (await s.page.getByText('Values hidden').count()) === 1);
  check('2 reason prompt absent for non-DPO', (await s.page.locator('#valuesReason').count()) === 0);
  check('2 zero console errors', clean(s)); await s.browser.close();
}
// 3. sre: server 403 -> role denied, no filters
{
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('sre', ['command_centre:view']), tenants,
    ['GET', /^\/platform\/audit-log\?/, { status: 403, body: { type: 'forbidden', title: 'Forbidden', detail: 'Forbidden' } }]]);
  await s.page.goto(`${APP}/platform/audit`, { waitUntil: 'networkidle' });
  check('3 role denied, no search form', (await s.page.locator('#tenant').count()) === 0 && (await s.page.locator('#valuesReason').count()) === 0);
  await s.browser.close();
}
// 4. empty and 5. error (server problem detail surfaced)
{
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('compliance_dpo', AUDIT_PERMS), tenants,
    ['GET', /^\/platform\/audit-log\?/, { body: { data: [], page: { nextCursor: null, limit: 50 } } }]]);
  await s.page.goto(`${APP}/platform/audit`, { waitUntil: 'networkidle' });
  check('4 empty state', (await s.page.getByText('No matching audit entries').count()) === 1);
  check('4 zero console errors', clean(s)); await s.browser.close();
}
{
  const s = await launch();
  await seedPlatform(s.page);
  await mockApi(s.page, s.log, [me('compliance_dpo', AUDIT_PERMS), tenants,
    ['GET', /^\/platform\/audit-log\?/, { status: 400, body: { type: 'limit-out-of-range', title: 'Limit out of range', detail: 'limit must be between 1 and 100.', code: 'LIMIT_OUT_OF_RANGE' } }]]);
  await s.page.goto(`${APP}/platform/audit`, { waitUntil: 'networkidle' });
  check('5 error state shows server detail', (await s.page.getByText('limit must be between 1 and 100.').count()) === 1);
  await s.browser.close();
}
// 6. support grant: ticket picker per clinic, scope, minutes <= 60; server refusal shown (390px)
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await seedPlatform(s.page);
  let posted = null;
  await mockApi(s.page, s.log, [me('support_engineer', ['command_centre:view', 'support_access:view', 'support_access:request']), tenants,
    ['GET', /^\/platform\/support-access\/grants$/, { body: [] }],
    ['GET', /^\/platform\/support\/tickets/, { body: [
      { id: 'tk1', tenantId: T1, subject: 'Printer setup', status: 'open' },
      { id: 'tk2', tenantId: T2, subject: 'Other clinic ticket', status: 'open' },
      { id: 'tk3', tenantId: T1, subject: 'Old closed', status: 'closed' }] }],
    ['POST', /^\/platform\/support-access\/grants$/, (route, req) => {
      posted = JSON.parse(req.postData());
      if (posted.scope === 'redacted_patient_view') return route.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify({ type: 'scope-invalid', title: 'Scope invalid', detail: 'scope must be config_only or redacted_patient_view.', code: 'SCOPE_INVALID' }) });
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'g1', tenantId: T1, ticketId: 'tk1', scope: posted.scope, operatorId: 'op1', operatorName: 'Op One', operatorRole: 'support_engineer', reason: posted.reason, grantedAt: '2026-10-19T10:00:00Z', expiresAt: '2026-10-19T10:30:00Z', revokedAt: null, active: true }) });
    }]]);
  await s.page.goto(`${APP}/platform/access`, { waitUntil: 'networkidle' });
  await s.page.locator('#tenant').selectOption(T1);
  const opts = await s.page.locator('#ticket option').allInnerTexts();
  check('6 ticket picker lists only this clinic\'s non-closed tickets', opts.length === 2 && opts[1].includes('Printer setup'));
  await s.page.locator('#reason').fill('Printer config');
  await s.page.getByRole('button', { name: 'Request access' }).click();
  await sleep(300);
  check('6 no ticket -> refused before POST', posted === null && (await s.page.getByText('Pick the support ticket this access is for.').count()) === 1);
  await s.page.locator('#ticket').selectOption('tk1');
  await s.page.locator('#minutes').fill('61');
  await s.page.getByRole('button', { name: 'Request access' }).click();
  await sleep(300);
  // the native max=60 constraint blocks submit first; the JS check is the fallback
  const nativeInvalid = await s.page.locator('#minutes').evaluate((el) => !el.checkValidity());
  check('6 61 minutes refused before POST', posted === null && (nativeInvalid || (await s.page.getByText('Duration must be 1 to 60 minutes.').count()) === 1));
  await s.page.locator('#minutes').fill('30');
  await s.page.getByRole('button', { name: 'Request access' }).click();
  await sleep(600);
  check('6 POST carries tenant, ticket, scope, minutes', posted && posted.tenantId === T1 && posted.ticketId === 'tk1' && posted.scope === 'config_only' && posted.minutes === 30);
  check('6 grant listed', (await s.page.getByText('Printer config').count()) >= 1);
  await s.page.locator('#scope').selectOption('redacted_patient_view');
  await s.page.locator('#reason').fill('Again');
  await s.page.getByRole('button', { name: 'Request access' }).click();
  await sleep(600);
  check('6 server refusal detail shown', (await s.page.getByText('scope must be config_only or redacted_patient_view.').count()) === 1);
  check('6 390px no horizontal scroll', await noOverflow(s.page));
  check('6 zero console errors', clean(s)); dumpLog('6', s.log); await s.browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
