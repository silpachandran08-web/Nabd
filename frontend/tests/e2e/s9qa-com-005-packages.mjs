// S9 QA (COM-005, independent). NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3307 node s9qa-com-005-packages.mjs
import { launch, seedClinic, mockApi, check, results, sleep, APP } from './qa-lib.mjs';
const exp = [{ instanceId: 'i1', patientName: 'Due Patient', packageName: 'Pkg A', quantityTotal: 6, quantityConsumed: 2, expiresOn: '2026-11-05', valueLeft: 100, alertTier: 30, reminderSentForTier: false }];
const inst = (events) => ({ id: 'i1', packageId: 'pk', packageName: 'Pkg A', patientId: 'p', patientName: 'Due Patient', invoiceId: 'inv', invoiceNumber: 'INV-1', soldPrice: 100, soldTax: 0, validityStart: '2026-10-01', validityEnd: '2026-11-05', graceDays: 0, status: 'active', items: [], events });
const events = [
  { eventType: 'expiry_warning_sent', note: '30-day: recorded only, no message sent', delta: null, actorName: 'Rana', createdAt: '2026-10-18T08:00:00Z' },
  { eventType: 'expiry_warning_sent', note: '14-day template', delta: null, actorName: 'Rana', createdAt: '2026-10-10T08:00:00Z' },
];
const H = (post) => [
  ['GET', /^\/packages\/expiring-soon/, { body: exp }], ['GET', /^\/packages\/liability/, { body: {} }], ['GET', /^\/packages\/settings/, { body: { priceFloorPercent: 0 } }],
  ['GET', /^\/packages\/refunds/, { body: [] }], ['GET', /^\/packages\/instances\/i1$/, { body: inst(events) }],
  ['GET', /^\/packages\/instances/, { body: [{ ...inst(null), items: [] }] }],
  ['POST', /send-reminder/, post], ['GET', /^\/packages(\?|$)/, { body: [] }], ['GET', /./, { body: {} }],
];
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload/.test(x))];
const html = (p) => p.evaluate(() => document.documentElement.outerHTML);
async function open(perms, post, viewport) {
  const s = await launch({ viewport }); await seedClinic(s.page, perms); await mockApi(s.page, s.log, H(post));
  await s.page.goto(APP + '/packages', { waitUntil: 'networkidle' }); await s.page.getByRole('button', { name: 'Expiring soon' }).click(); return s;
}
let s;
try {
  s = await open(['packages:view', 'packages:edit'], { status: 200, body: {} }, { width: 390, height: 800 });
  const btn = s.page.getByRole('button', { name: 'Mark reminded', exact: true });
  check('1 button reads exactly "Mark reminded" for a package due in <30 days', await btn.count() === 1);
  console.log(`INFO button height at 390px: ${(await btn.boundingBox()).height}px (44px rule is a brief rule, not an acceptance criterion)`);
  await btn.click();
  await s.page.getByText('Recorded. No message was sent.').waitFor({ timeout: 5000 }).catch(() => {});
  check('3 confirmation "Recorded. No message was sent." in a status region', await s.page.getByRole('status').filter({ hasText: 'Recorded. No message was sent.' }).count() === 1);
  const h = await html(s.page);
  check('4 DOM (text, title, aria-label) has no "Send reminder" anywhere', !/send reminder/i.test(h));
  check('5 no horizontal scroll at 390px', await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await s.browser.close();
  // ledger (desktop viewport)
  s = await open(['packages:view', 'packages:edit'], { status: 200, body: {} });
  await s.page.getByRole('button', { name: 'Active patient packages' }).click();
  await s.page.getByRole('button', { name: 'Open' }).first().click();
  await s.page.getByText('Package ledger').waitFor({ timeout: 5000 });
  const t = await s.page.locator('body').innerText();
  check('6 ledger shows "Marked as reminded (30-day)"', t.includes('Marked as reminded (30-day)'));
  check('7 ledger legacy row shows "Marked as reminded (14-day)"', t.includes('Marked as reminded (14-day)'));
  check('8 ledger event headline does not say "sent" (event_type text)', !/expiry warning sent/i.test(t));
  console.log(`INFO legacy ledger row still prints its raw note "14-day template" under the new label: ${/14-day template/.test(t)} (observation, not an acceptance criterion)`);
  check('10 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  s = await open(['packages:view'], { status: 200, body: {} });
  check('11 read-only role: no reminder button at all', await s.page.getByRole('button', { name: /remind/i }).count() === 0);
  check('12 read-only role: no "Send reminder" text', !/send reminder/i.test(await html(s.page)));
  await s.browser.close();

  s = await open(['packages:view', 'packages:edit'], { status: 500, body: { title: 'x', detail: 'Boom' } });
  await s.page.getByRole('button', { name: 'Mark reminded', exact: true }).click(); await sleep(800);
  check('13 server error: no false "Recorded" confirmation', !/Recorded\. No message/.test(await s.page.locator('body').innerText()));
  await s.browser.close();
} catch (e) { check('run', false, String(e).split('\n')[0]); console.log('  body:', JSON.stringify((await s?.page.locator('body').innerText().catch(() => '')).slice(0, 600))); await s?.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok); console.log(`${results.length - bad.length}/${results.length} passed`); process.exit(bad.length ? 1 : 0);
