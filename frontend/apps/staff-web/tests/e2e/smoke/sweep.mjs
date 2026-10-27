// OPS-044 / FEF-050 role-absence sweep, generated over the journeys: each journey's key screen is opened as EVERY RBAC role
// (roles.json + a role with no grants) and every gated control listed below must be ABSENT from the DOM unless the role holds
// all of its permissions (and present when it does, so a screen that simply failed to load cannot pass). Not disabled: absent (NB-054).
// To cover a new control add one row to SPEC; the cases, roles and report rows are generated.
import { ROLES, NO_GRANTS, permsFor } from './core.mjs';
import { APP } from './harness.mjs';
import { JOURNEYS, TENANTS, PROJECTS } from './nightly.mjs';

const settle = async (page) => { await page.waitForLoadState('networkidle').catch(() => {}); await page.waitForTimeout(900); }; // useSyncExternalStore grants mount after hydration
const go = (path) => async ({ page }) => { await page.goto(`${APP}${path}`); await settle(page); };
const quick = (page, name) => page.locator('[class*=quickActions]').getByRole('button', { name, exact: true });

/** { of: journey id, scene, controls: [{ name, find(page), needs: [permissions], any?: [permissions] }] } */
export const SPEC = [
  { of: 'J1', scene: go('/arrivals'), controls: [
    { name: 'Walk-in & token', find: (p) => p.getByRole('button', { name: 'Walk-in & token' }), needs: ['queue:view', 'queue:create', 'patients:view'] }] },
  { of: 'J2', scene: go('/arrivals'), controls: [
    { name: 'Check in (quick action)', find: (p) => quick(p, 'Check in'), needs: ['queue:view', 'queue:create'] },
    { name: 'Move queue', find: (p) => quick(p, 'Move queue'), needs: ['queue:view', 'queue:edit'] }] },
  { of: 'J3', scene: go('/schedule'), controls: [
    { name: '+ New appointment', find: (p) => p.getByRole('button', { name: '+ New appointment' }), needs: ['queue:view', 'queue:create', 'patients:view'] },
    { name: '+ Available slot', find: (p) => p.getByRole('button', { name: '+ Available' }), needs: ['queue:view', 'queue:create', 'patients:view'] }] },
  { of: 'J4', scene: go('/schedule'), controls: [
    { name: 'Waitlist', find: (p) => p.getByRole('button', { name: /Waitlist/ }), needs: ['queue:view', 'queue:edit'] }] },
  { of: 'J5', scene: go('/consult?entry=e1&step=reason'), controls: [
    { name: 'Pregnancy status Record', find: (p) => p.getByLabel('Clinical safety').getByRole('button', { name: /^(Record|Update)$/ }), needs: ['clinical:edit'] }] },
  { of: 'J8', scene: go('/nursing'), controls: [
    { name: 'Capture vitals (row)', find: (p) => p.locator('tbody').getByRole('button', { name: /Capture vitals/ }), needs: ['nursing:view', 'queue:view'], any: ['clinical:create', 'clinical:edit'] },
    { name: 'Mark urgent', find: (p) => p.getByRole('button', { name: 'Mark urgent', exact: true }), needs: ['nursing:view', 'queue:view', 'queue:edit'] }] },
  { of: 'J10', scene: go('/checkout/otc'), controls: [
    { name: 'OTC Checkout', find: (p) => p.getByRole('button', { name: 'Checkout', exact: true }), needs: ['billing:create'] }] },
  { of: 'J11', scene: async ({ page }) => { await go('/consult?entry=e1&step=examine')({ page }); }, controls: [
    { name: 'Tooth chart', find: (p) => p.getByRole('button', { name: 'Tooth chart' }), needs: ['specialty_dental:view'] }] },
  { of: 'J12', scene: async ({ page }) => { await go('/packages')({ page }); await page.getByRole('button', { name: 'Sessions & redemptions' }).click({ timeout: 1500 }).catch(() => {}); await page.waitForTimeout(400); }, controls: [
    { name: 'Redeem', find: (p) => p.getByRole('button', { name: 'Redeem', exact: true }), needs: ['packages:view', 'packages:edit'] }] },
  { of: 'J13', scene: go('/billing'), controls: [
    { name: 'Start day close', find: (p) => p.getByRole('button', { name: /^(Start day close|Close again)$/ }), needs: ['billing:view', 'billing:approve'] }] },
];

export const SWEEP_ROLES = [...Object.keys(ROLES).filter((k) => !k.startsWith('_')), NO_GRANTS];

/** Pseudo-journeys RA-J<n>; the role is a property of the case (c.role), so the runner signs in as that role. */
export const SWEEP_JOURNEYS = SPEC.map((spec) => {
  const base = JOURNEYS.find((j) => j.id === spec.of);
  return {
    id: `RA-${spec.of}`, title: `role-absence sweep over ${spec.of}`, owner: base.owner, budgetMs: 30000, budgetKey: 'sweepMs', mockOnly: true, sweepOf: spec.of,
    prepare: (t, p, a) => base.prepare(t, p, a),
    async run({ page, d, tenant, role }) {
      const t0 = performance.now();
      await spec.scene({ page, d, tenant });
      const held = new Set(permsFor(role));
      const problems = [];
      for (const c of spec.controls) {
        const want = c.needs.every((x) => held.has(x)) && (!c.any || c.any.some((x) => held.has(x)));
        const present = (await c.find(page).count()) > 0;
        if (present && !want) problems.push(`${c.name} is in the DOM for ${role}, which lacks ${c.needs.filter((x) => !held.has(x)).join(', ') || c.any.join(' or ')}`);
        if (!present && want) problems.push(`${c.name} is missing for ${role}, which holds ${c.needs.join(', ')} (the sweep cannot prove absence on a screen that did not render)`);
      }
      if (problems.length) throw new Error(problems.join('\n'));
      return { sweepMs: Math.round(performance.now() - t0) };
    },
  };
});

const DESKTOP = PROJECTS.find((p) => p.key === 'desktop');
export const SWEEP = SWEEP_JOURNEYS.flatMap((j) => SWEEP_ROLES.map((role) => ({ id: `${j.id} ${role}`, journey: j, tenant: TENANTS[0], project: DESKTOP, locale: 'en', role })));

