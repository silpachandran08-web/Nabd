// COM-005 E2E: packages/reports never claim a reminder was sent. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3205 node s9-com005-mark-reminded.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const exp = [
  { instanceId: 'i1', patientName: 'Test Patient', packageName: 'Pkg A', quantityTotal: 6, quantityConsumed: 2, expiresOn: '2026-12-01', valueLeft: 100, alertTier: 30, reminderSentForTier: false },
  { instanceId: 'i2', patientName: 'Other Patient', packageName: 'Pkg B', quantityTotal: 4, quantityConsumed: 1, expiresOn: '2026-12-02', valueLeft: 50, alertTier: 30, reminderSentForTier: true },
];
let remindStatus = 200;
const handlers = () => [
  ['GET', /^\/packages\/expiring-soon/, { body: exp }],
  ['GET', /^\/packages\/liability/, { body: {} }],
  ['GET', /^\/packages\/settings/, { body: { priceFloorPercent: 0 } }],
  ['GET', /^\/packages\/refunds/, { body: [] }],
  ['GET', /^\/packages\/instances/, { body: [] }],
  ['POST', /^\/packages\/instances\/i1\/send-reminder/, (r) => r.fulfill({ status: remindStatus, contentType: 'application/json', body: '{}' })],
  ['GET', /^\/packages(\?|$)/, { body: [] }],
  ['GET', /^\/reports\/packages/, { body: { expiredWithValue: [{ instanceId: 'x1', patientName: 'Exp Patient', packageName: 'P', expiredOn: '2026-01-01', remainingValue: 10 }] } }],
  ['GET', /^\/(reports|staff)/, { body: {} }],
];
async function open(perms, path, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}${path}`, { waitUntil: 'networkidle' });
  return s;
}
const body = (p) => p.locator('body').innerText();
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
let s;
try {
  s = await open(['packages:view', 'packages:edit'], '/packages');
  await s.page.getByRole('button', { name: 'Expiring soon' }).click();
  let t = await body(s.page);
  check('1 no "Send reminder" text on packages page', !/send reminder/i.test(t));
  check('2 button reads Mark reminded (only for the unreminded row)', await s.page.getByRole('button', { name: 'Mark reminded' }).count() === 1);
  check('3 done row reads "Marked reminded", not "Sent"', /Marked reminded/.test(t) && !/\bSent\b/.test(t));
  await s.page.getByRole('button', { name: 'Mark reminded' }).click();
  await s.page.getByText('Recorded. No message was sent.').waitFor({ timeout: 5000 }).then(() => check('4 confirmation says Recorded. No message was sent.', true), () => check('4 confirmation', false));
  check('5 request hit send-reminder once', s.log.requests.filter((r) => /send-reminder/.test(r.path)).length === 1);
  check('6 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  s = await open(['packages:view'], '/packages');
  await s.page.getByRole('button', { name: 'Expiring soon' }).click();
  check('7 permission-absent: no Mark reminded button without packages:edit', await s.page.getByRole('button', { name: /reminded/i }).count() === 0);
  await s.browser.close();

  remindStatus = 400;
  s = await open(['packages:view', 'packages:edit'], '/packages');
  await s.page.getByRole('button', { name: 'Expiring soon' }).click();
  await s.page.getByRole('button', { name: 'Mark reminded' }).click();
  await s.page.waitForTimeout(800);
  check('8 error: no "No message was sent" confirmation when the call fails', !/Recorded\. No message was sent/.test(await body(s.page)));
  await s.browser.close();

  s = await open(['packages:view', 'packages:edit', 'billing:view', 'reports:view'], '/reports', { width: 390, height: 800 });
  t = await body(s.page);
  check('9 reports page has no "Reminder sent"/"Send reminder"', !/reminder sent|send reminder/i.test(t));
  check('10 390px: no horizontal scroll on reports', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  dumpLog('reports', s.log);
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
