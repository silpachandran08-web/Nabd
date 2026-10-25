// COM-006 E2E: Patient file > Messages tab. Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3811 node s14-com006-patient-messages.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const PID = '33333333-3333-3333-3333-333333333333';
const row = { id: PID, mrn: 'MRN-1', name: 'Test Patient', phone: '+91 ••••• 4412', age: 40, gender: 'female', minor: false, lastVisit: null, nextAppointment: null,
  nextDoctorName: null, nextDoctorId: null, nextAppointmentId: null, nextAppointmentToday: false, packageSessionsUsed: null, packageSessionsTotal: null,
  condition: null, followUpDue: false, allergy: null, balanceDue: false, duplicate: false, archived: false, queueToken: null, queueStatus: null, tags: [] };
const detail = { id: PID, mrn: 'MRN-1', name: 'Test Patient', phone: '+919800004412', dob: '1985-01-01', gender: 'female', age: 40, allergies: [], isMinor: false, guardianId: null, guardianName: null, guardianConsentGrantedAt: null };
const m = (id, status, minsAgo, extra = {}) => ({ id, channel: 'whatsapp', purpose: 'Reminds about a booked appointment', templateName: 'appt_reminder', status, statusAt: new Date(Date.now() - minsAgo * 60000).toISOString(), ...extra });
const three = [m('a', 'read', 5), m('b', 'delivered', 60), m('c', 'failed', 120, { failureReason: 'Not on WhatsApp' })];
const many = (from, n) => Array.from({ length: n }, (_, i) => m(`p${from + i}`, 'sent', 200 + from + i));

let messagesHandler;
const handlers = () => [
  ['GET', /^\/patients\/registry/, { body: { total: 1, counts: {}, rows: [row], matchedTotal: 1, hasMore: false, page: { nextCursor: null } } }],
  ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }],
  ['GET', new RegExp(`^/patients/${PID}/messages`), (r, req) => messagesHandler(r, req)],
  ['GET', new RegExp(`^/patients/${PID}$`), { body: detail }],
  ['GET', /^\/(queue|clinical|staff|schedule|specialty|billing)/, { body: [] }],
];
const ok = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
async function open(perms, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, perms);
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/patients`, { waitUntil: 'networkidle' });
  await s.page.getByText('Test Patient').first().click();
  await s.page.getByRole('button', { name: 'Messages' }).waitFor({ timeout: 8000 });
  return s;
}
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
let s;
try {
  // 1 happy path: three rows, ticks, reason, request shape
  messagesHandler = ok({ items: three, nextCursor: null });
  s = await open(['patients:view']);
  await s.page.getByRole('button', { name: 'Messages' }).click();
  await s.page.getByTestId('message-row').first().waitFor({ timeout: 5000 });
  check('1 three messages listed', await s.page.getByTestId('message-row').count() === 3);
  const t = await s.page.locator('body').innerText();
  check('2 purpose, status words and plain failure reason shown', /Reminds about a booked appointment/.test(t) && /Read/.test(t) && /Delivered/.test(t) && /Failed/.test(t) && /Not on WhatsApp/.test(t));
  const tones = await s.page.locator('[data-tone]').evaluateAll((els) => els.map((e) => e.getAttribute('data-tone') + ':' + e.textContent));
  check('3 ticks: blue double for read, grey double for delivered, red ! for failed', JSON.stringify(tones) === JSON.stringify(['blue:✓✓', 'grey:✓✓', 'red:!']), JSON.stringify(tones));
  check('4 no phone number or template variables in the tab', !/9800004412|SECRET/.test(await s.page.getByRole('region', { name: 'Messages' }).innerText()));
  check('5 request is GET /patients/{id}/messages', s.log.requests.some((r) => r.method === 'GET' && r.path === `/patients/${PID}/messages`));
  check('6 zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  await s.browser.close();

  // 2 pagination: first page has nextCursor, Load older appends and then disappears
  messagesHandler = (r, req) => ok(new URL(req.url()).searchParams.get('cursor') === 'CUR1' ? { items: many(50, 5), nextCursor: null } : { items: many(0, 50), nextCursor: 'CUR1' })(r);
  s = await open(['patients:view']);
  await s.page.getByRole('button', { name: 'Messages' }).click();
  await s.page.getByTestId('message-row').first().waitFor({ timeout: 5000 });
  check('7 first page shows 50 and a load-older button', await s.page.getByTestId('message-row').count() === 50 && await s.page.getByRole('button', { name: 'Load older messages' }).count() === 1);
  const btn = await s.page.getByRole('button', { name: 'Load older messages' }).boundingBox();
  check('8 load-older target is at least 44px tall', btn.height >= 44, String(btn.height));
  await s.page.getByRole('button', { name: 'Load older messages' }).click();
  await s.page.waitForFunction(() => document.querySelectorAll('[data-testid=message-row]').length === 55);
  check('9 second page appended (55) and the button is gone', await s.page.getByRole('button', { name: 'Load older messages' }).count() === 0);
  check('10 cursor sent on the second request', s.log.requests.some((r) => r.path.includes('cursor=CUR1')));
  await s.browser.close();

  // 3 empty
  messagesHandler = ok({ items: [], nextCursor: null });
  s = await open(['patients:view']);
  await s.page.getByRole('button', { name: 'Messages' }).click();
  await s.page.getByText('No messages sent to this patient yet.').waitFor({ timeout: 5000 }).then(() => check('11 empty state', true), () => check('11 empty state', false));
  await s.browser.close();

  // 4 error then retry
  let fail = true;
  messagesHandler = (r) => fail ? r.fulfill({ status: 500, contentType: 'application/problem+json', body: '{}' }) : ok({ items: three, nextCursor: null })(r);
  s = await open(['patients:view']);
  await s.page.getByRole('button', { name: 'Messages' }).click();
  await s.page.getByText("Couldn't load this patient's messages.").waitFor({ timeout: 5000 }).then(() => check('12 error state with retry', true), () => check('12 error state', false));
  fail = false;
  await s.page.getByRole('button', { name: 'Retry' }).click();
  await s.page.getByTestId('message-row').first().waitFor({ timeout: 5000 }).then(() => check('13 retry loads the list', true), () => check('13 retry', false));
  await s.browser.close();

  // 5 permission: endpoint 403 -> the tab shows nothing (no error, no data)
  messagesHandler = (r) => r.fulfill({ status: 403, contentType: 'application/problem+json', body: '{"title":"Forbidden"}' });
  s = await open(['patients:view']);
  await s.page.getByRole('button', { name: 'Messages' }).click();
  await s.page.waitForTimeout(600);
  const body403 = await s.page.locator('body').innerText();
  check('14 403: no rows and no error text', await s.page.getByTestId('message-row').count() === 0 && !/Couldn't load this patient's messages/.test(body403));
  await s.browser.close();

  // 6 390px: no horizontal scroll with a long failure reason
  messagesHandler = ok({ items: [m('z', 'failed', 3, { failureReason: 'Not delivered by WhatsApp to protect the patient experience', purpose: 'A very long purpose text that must wrap instead of pushing the page sideways' })], nextCursor: null });
  s = await open(['patients:view'], { width: 390, height: 800 });
  await s.page.getByRole('button', { name: 'Messages' }).click();
  await s.page.getByTestId('message-row').first().waitFor({ timeout: 5000 });
  check('15 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  check('16 390px: zero console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('com006', s.log);
  await s.browser.close();
} catch (e) {
  check('script completed', false, String(e));
  if (s) await s.browser.close();
}
process.exit(results.every((r) => r.ok) ? 0 : 1);
