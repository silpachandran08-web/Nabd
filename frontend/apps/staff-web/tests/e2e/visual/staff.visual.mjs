// FEF-051 staff-web snapshots: every screen x locale, per project (device x theme). Mock back end: the screen's journey mocks
// (smoke/nightly.mjs) when it has a journey, else every unmocked read answers 404 Problem+JSON, so the screen shows its own error
// state (answering "[]" instead crashed 53 of 95 screens into the global error page: most reads expect an object).
import { test, settle, snap, watchCrashes, projectParts, wanted, localesFrom, FIXED } from './vr.mjs';
import { screensOf, STAFF_LOCALES } from './screens.mjs';
import { JOURNEYS, TENANTS } from '../smoke/nightly.mjs';
import { permsFor } from '../smoke/harness.mjs';
import { mockApi, seedClinic, seedPlatform } from '../qa-lib.mjs';
import { clinicMocks } from '../smoke/journeys.mjs';

const T = TENANTS[0]; // IN tenant; the KSA tenant renders the same layouts with other currency/zone strings
const MODULES = ['queue_eta', 'whatsapp_booking', 'emr_erx', 'billing', 'pharmacy_external', 'pharmacy_inhouse', 'lab', 'packages', 'branded_site', 'hr', 'insurance', 'ipd', 'patient_app', 'ai_safety'];
const OPERATOR = ['audit_compliance:view', 'billing_revenue:view', 'billing_revenue:edit', 'billing_revenue:approve', 'clinics_fleet:view', 'command_centre:view', 'onboarding_provisioning:view',
  'platform_health:view', 'pricing_packaging:view', 'support_access:view', 'support_tickets:view', 'territories:view'];
const shell = [
  ['GET', /^\/me\/entitlements$/, { body: { modules: MODULES.map((code) => ({ code, enabled: true })), killedFeatures: [], seatLimit: null } }],
  // ACC-035: tour completion is server state (GET /v1/me/onboarding, StaffOnboardingProgress); every role's tour is done, or its card
  // would cover the first screenshot. Listed before the journey mocks, so it also wins over a journey's catch-all.
  ['GET', /^\/me\/onboarding$/, { body: { completed: ['tour.reception', 'tour.nurse', 'tour.doctor', 'tour.owner'], competencyPassedAt: null } }],
  ['GET', /^\/platform\/auth\/me$/, { body: { id: 'op1', name: 'Synthetic Operator', email: 'op@example.test', role: 'super_admin', permissions: OPERATOR } }],
];

function backEnd(screen) {
  const j = screen.journey && JOURNEYS.find((x) => x.id === screen.journey);
  if (j) return { role: j.role, h: [...shell, ...j.prepare(T, 'desktop', 1).h] };
  return { role: screen.role, h: [...shell, ...clinicMocks(T)] };
}

for (const screen of screensOf('staff').filter((s) => wanted(s.id))) {
  for (const locale of localesFrom(STAFF_LOCALES)) {
    test(`${screen.id} ${locale}`, async ({ page, context, baseURL }, info) => {
      const { theme } = projectParts(info.project.name);
      await context.addCookies([{ name: 'nabd_locale', value: locale, url: baseURL }, { name: 'nabd_theme', value: theme, url: baseURL }]);
      await page.clock.setFixedTime(FIXED);
      const { role, h } = backEnd(screen);
      await mockApi(page, { requests: [], console: [], pageErrors: [], unmatched: [] }, h);
      if (role === 'operator') await seedPlatform(page);
      else if (role !== 'anonymous') await seedClinic(page, permsFor(role), { tenantRegion: T.region, tenantName: T.name, roleName: role, staffName: 'Synthetic Staff', owner: role === 'owner' });
      const crashes = watchCrashes(page);
      await page.goto(screen.url);
      await settle(page);
      await snap(page, [...screen.id.split('/'), `${locale}.png`], crashes);
    });
  }
}
