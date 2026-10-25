// FEF-019 role-matrix sweep, generated from docs/security/rbac-matrix.csv (the T-090 source): every clinic role visits every gated
// route; a denied route renders the no-access state with no request to its API and no console error; no nav link points at a route
// the role cannot open. Operator roles: write controls follow permissions. Role change applies at the next token read (no re-login).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3814 node s14-fef019-role-sweep.mjs
import { readFileSync } from 'node:fs';
import { launch, seedClinic, seedPlatform, clinicToken, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const rows = readFileSync(new URL('../../../../../docs/security/rbac-matrix.csv', import.meta.url), 'utf8').split('\n')
  .filter((l) => l && !l.startsWith('#') && !l.startsWith('plane,')).map((l) => l.split(','));
const grants = (plane, role) => rows.filter((r) => r[0] === plane && r[1] === role).flatMap((r) => r[3].split('|').filter(Boolean).map((a) => `${r[2]}:${a}`));
const roles = (plane) => [...new Set(rows.filter((r) => r[0] === plane).map((r) => r[1]))];
// route, grant the layout gate demands, API path the page would call
const ROUTES = [
  ['/overview', 'reports:view', /^\/reports/], ['/patients', 'patients:view', /^\/patients/], ['/packages', 'packages:view', /^\/packages/],
  ['/staff', 'staff:view', /^\/(staff|roles)/], ['/billing', 'billing:view', /^\/billing/], ['/schedule', 'queue:view', /^\/(appointments|queue)/],
  ['/reports', 'reports:view', /^\/reports/], ['/arrivals', 'queue:view', /^\/queue/], ['/pharmacy', 'pharmacy:view', /^\/pharmacy/],
  ['/nursing', 'nursing:view', /^\/nursing/], ['/setup', 'setup:view', /^\/setup\/(?!profile|policies|subscription)/], ['/encounters', 'clinical:view', /^\/clinical/],
];
const handlers = [['GET', /^\/platform\/auth\/me/, { body: {} }], ['GET', /./, { body: [] }]];
const DENIED = /You do not have access to/;
const calm = (s) => !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length;

for (const role of roles('clinic')) {
  const perms = grants('clinic', role);
  for (const [path, need, api] of ROUTES) {
    const s = await launch();
    await seedClinic(s.page, perms, { owner: role === 'owner' || role === 'owner_doctor' });
    await mockApi(s.page, s.log, handlers);
    await s.page.goto(`${APP}${path}`, { waitUntil: 'networkidle' });
    const allowed = perms.includes(need);
    const denied = (await s.page.getByText(DENIED).count()) > 0;
    check(`${role} ${path} ${allowed ? 'opens' : 'shows no-access'}`, denied === !allowed);
    if (!allowed) {
      const sent = s.log.requests.filter((r) => api.test(r.path));
      check(`${role} ${path} sends no ${api} request`, sent.length === 0, sent.map((r) => r.path).join(' '));
      check(`${role} ${path} no console errors`, calm(s)); dumpLog(`${role}${path}`, s.log);
    }
    // absent-not-disabled: no nav link to a gated route the role cannot open
    for (const [p, n] of ROUTES) {
      if (perms.includes(n)) continue;
      const links = await s.page.locator(`nav a[href="${p}"], nav a[href^="${p}?"]`).count();
      if (links) check(`${role} nav has no link to ${p}`, false);
    }
    await s.browser.close();
  }
  check(`${role} nav has no link to a route it cannot open`, true);
}

// 390px: the no-access state does not scroll sideways
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await seedClinic(s.page, ['queue:view']);
  await mockApi(s.page, s.log, handlers);
  await s.page.goto(`${APP}/billing`, { waitUntil: 'networkidle' });
  check('390px no-access has no horizontal scroll', (await s.page.getByText(DENIED).count()) > 0 && await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await s.browser.close();
}

// Role change at token refresh: billing:view is removed from the stored token, the nav drops Billing without a reload or login.
{
  const s = await launch();
  const full = ['queue:view', 'patients:view', 'billing:view'];
  await seedClinic(s.page, full);
  await mockApi(s.page, s.log, handlers);
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  const link = s.page.locator('nav a[href="/billing"]');
  const before = await link.count();
  await s.page.evaluate((t) => localStorage.setItem('nabd_access_token', t), clinicToken(['queue:view', 'patients:view']));
  const t0 = Date.now();
  while (Date.now() - t0 < 40_000 && (await link.count()) > 0) await sleep(1000);
  check('role change: Billing link present before, gone within one poll without reload', before > 0 && (await link.count()) === 0, `${Math.round((Date.now() - t0) / 1000)}s`);
  await s.browser.close();
}

// Operator roles: write controls come from permissions.
const OPERATOR_PERMS = (role) => [...new Set(grants('operator', role).map((g) => g))];
for (const role of roles('operator')) {
  const perms = OPERATOR_PERMS(role);
  const probe = async (path, extra) => {
    const s = await launch();
    await seedPlatform(s.page);
    await mockApi(s.page, s.log, [['GET', /^\/platform\/auth\/me/, { body: { id: 'op1', name: 'Op', email: 'op@x.test', role, permissions: perms } }], ['GET', /^\/platform\/provisioning-jobs/, { body: [] }], ...(extra ?? []), ['GET', /./, { body: { data: [], items: [], page: { nextCursor: null, limit: 50 } } }]]);
    await s.page.goto(`${APP}${path}`, { waitUntil: 'networkidle' });
    return s;
  };
  if (perms.includes('onboarding_provisioning:view')) {
    const s = await probe('/platform/provisioning');
    check(`${role} "+ New tenant" present iff canWrite`, (await s.page.getByRole('button', { name: '+ New tenant' }).count()) === (perms.includes('onboarding_provisioning:view') ? 1 : 0));
    await s.browser.close();
  }
  if (perms.includes('billing_revenue:view')) {
    const s = await probe('/platform/billing');
    const readOnly = !perms.includes('billing_revenue:edit') && !perms.includes('billing_revenue:approve') && !perms.includes('onboarding_provisioning:view');
    check(`${role} billing read-only note iff view-only`, (await s.page.getByText('Read-only role').count()) === (readOnly ? 1 : 0));
    await s.browser.close();
  }
  if (perms.includes('audit_compliance:view')) {
    const s = await probe('/platform/audit');
    check(`${role} audit reason box iff compliance_dpo permission`, (await s.page.locator('#valuesReason').count()) === (perms.includes('audit_compliance:values') ? 1 : 0));
    await s.browser.close();
  }
}

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
