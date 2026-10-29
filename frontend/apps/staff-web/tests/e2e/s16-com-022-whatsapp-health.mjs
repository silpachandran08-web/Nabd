// COM-022 E2E (mocked API): WhatsApp health in the whatsapp tab, and the platform senders page. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3816 node s16-com-022-whatsapp-health.mjs
import { launch, seedClinic, seedPlatform, mockApi, check, dumpLog, APP } from './qa-lib.mjs';

const sender = { id: 's1', kind: 'tenant', displayPhone: '+91 90000 00000', displayName: 'Clinic', status: 'connected', rating: 'YELLOW', quality: 'Medium', tier: 'TIER_1K', tierLabel: '1,000 patients a day', throttleMode: 'throttled', throttleReason: 'Patients blocked or reported more than 1.5% of your messages in a day.', blockRate24h: 0.02, blockRate7d: [0, 0, 0.005, 0.01, 0.02, 0.02, 0.02], throttleLine: 0.015, events: [{ at: '2026-10-20T00:00:00Z', kind: 'throttle', text: 'Sending slowed to protect your number.' }] };
let health = { senders: [sender], templates: [{ id: 't1', name: 'appointment_reminder', language: 'en', category: 'UTILITY', status: 'rejected', rejectionReason: 'INVALID_FORMAT' }] }, healthStatus = 200, healthCalls = 0;
const handlers = () => [
  ['GET', /^\/owner\/messaging\/health/, (r) => { healthCalls++; return r.fulfill({ status: healthStatus, contentType: healthStatus >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(healthStatus >= 400 ? { type: 'x', title: 'x', detail: 'Down' } : health) }); }],
  ['GET', /^\/messaging\/templates/, { body: [] }],
  ['GET', /^\/messaging\/.*/, { body: [] }],
];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
async function open(perms, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/setup?tab=whatsapp`, { waitUntil: 'networkidle' });
  return s;
}
let s;
try {
  s = await open(['setup:view', 'setup:edit']);
  const p = s.page;
  await p.getByText('Sending slowed to protect your number.').waitFor({ timeout: 8000 }).then(() => check('1 happy: what-happened list', true), () => check('1 happy', false));
  const t = await p.locator('body').innerText();
  check('2 quality, throttle mode, 1.5% line and template status shown', /Medium/.test(t) && /throttled/.test(t) && /1\.5%/.test(t) && /INVALID_FORMAT/.test(t));
  check('3 chart present with accessible label', await p.getByRole('img', { name: /Block rate over the last 7 days/ }).count() === 1);
  check('4 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  health = { senders: [], templates: [] };
  s = await open(['setup:view']);
  check('5 empty: no number connected', await s.page.getByText('No WhatsApp number is connected yet.').count() === 1);
  await s.browser.close();

  healthStatus = 500;
  s = await open(['setup:view']);
  check('6 error state with retry', await s.page.getByRole('button', { name: 'Retry' }).count() >= 1);
  await s.browser.close(); healthStatus = 200;

  healthCalls = 0;
  s = await open(['templates:view']);
  await s.page.waitForTimeout(800);
  check('7 permission-absent: no health request or card without setup:view', healthCalls === 0 && await s.page.getByText('Template approvals').count() === 0);
  await s.browser.close();

  health = { senders: [sender], templates: [] };
  s = await open(['setup:view'], { width: 390, height: 800 });
  await s.page.getByText('What happened').waitFor({ timeout: 8000 });
  check('8 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await s.browser.close();

  // platform senders page
  s = await launch();
  await seedPlatform(s.page);
  const page = s.page;
  await mockApi(page, s.log, [
    ['GET', /^\/platform\/auth\/me/, { body: { id: 'o', name: 'Op', email: 'o@x', role: 'support', permissions: ['clinics_fleet:view'] } }],
    ['GET', /^\/platform\/senders/, { body: { items: [{ senderAccountId: 'a', tenantName: 'Clinic One', kind: 'tenant', displayPhone: '+91 1', region: 'IN', status: 'connected', rating: 'RED', tier: 'TIER_1K', throttleMode: 'paused', deliveredRate7d: 0.91, sends7d: 120 }], nextCursor: null } }],
  ]);
  await page.goto(`${APP}/platform/senders`, { waitUntil: 'networkidle' });
  await page.getByText('Clinic One').waitFor({ timeout: 8000 }).then(() => check('9 platform senders table', true), () => check('9 platform senders table', false));
  check('10 delivered rate formatted', (await page.locator('body').innerText()).includes('91.0%'));
  await s.browser.close();
} finally { if (s) dumpLog('com022', s.log); }
process.exit(0);
