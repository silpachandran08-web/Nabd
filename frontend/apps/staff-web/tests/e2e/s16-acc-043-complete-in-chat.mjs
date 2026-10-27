// S16 ACC-043: "Complete in chat" in the patient file (patients:edit only). Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3807 node s16-acc-043-complete-in-chat.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const PID = '33333333-3333-3333-3333-333333333333';
const row = { id: PID, mrn: 'MRN-1', name: 'Test Patient', phone: '+91 ••••• 4412', age: 40, gender: 'female', minor: false, lastVisit: null, nextAppointment: null,
  nextDoctorName: null, nextDoctorId: null, nextAppointmentId: null, nextAppointmentToday: false, packageSessionsUsed: null, packageSessionsTotal: null,
  condition: null, followUpDue: false, allergy: null, balanceDue: false, duplicate: false, archived: false, queueToken: null, queueStatus: null, tags: [] };
const detail = { id: PID, mrn: 'MRN-1', name: 'Test Patient', phone: '+919800004412', dob: '1985-01-01', gender: 'female', age: 40, allergies: [], isMinor: false, guardianId: null, guardianName: null, guardianConsentGrantedAt: null };
const problem = (status, detail) => ({ status, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x', title: 'x', detail }) });

async function run(tag, perms, completeReply, fn, viewport) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, [
      ['GET', /^\/patients\/registry/, { body: { total: 1, counts: {}, rows: [row], matchedTotal: 1, hasMore: false, page: { nextCursor: null } } }],
      ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }],
      ['POST', new RegExp(`^/patients/${PID}/complete-in-chat`), (r) => completeReply ? r.fulfill(completeReply) : r.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ requested: true }) })],
      ['GET', new RegExp(`^/patients/${PID}$`), { body: detail }],
      ['GET', /^\/(queue|clinical|staff|schedule|specialty|billing)/, { body: [] }],
    ]);
    await page.goto(`${APP}/patients`, { waitUntil: 'networkidle' });
    await page.getByText('Test Patient').first().click(); await sleep(1200);
    await fn({ page, log });
    check(`${tag}: zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const btn = (page) => page.getByRole('button', { name: 'Complete in chat' });

await run('happy', ['patients:view', 'patients:edit'], null, async ({ page, log }) => {
  check('button present with patients:edit', (await btn(page).count()) === 1);
  await btn(page).click(); await sleep(700);
  const req = log.requests.find((r) => r.method === 'POST' && r.path === `/patients/${PID}/complete-in-chat`);
  check('POST /patients/{id}/complete-in-chat sent once', !!req && log.requests.filter((r) => r.method === 'POST' && /complete-in-chat/.test(r.path)).length === 1);
  check('confirmation shown, button gone', /Sent on WhatsApp/.test(await page.locator('body').innerText()) && (await btn(page).count()) === 0);
});
await run('permission absent', ['patients:view'], null, async ({ page, log }) => {
  check('without patients:edit the button is absent', (await btn(page).count()) === 0);
  check('and no complete-in-chat call is made', !log.requests.some((r) => /complete-in-chat/.test(r.path)));
});
await run('error shows the server reason and allows a retry', ['patients:view', 'patients:edit'], problem(409, 'The patient has not agreed to WhatsApp messages.'), async ({ page }) => {
  await btn(page).click(); await sleep(700);
  check('reason shown in an alert', /has not agreed to WhatsApp messages/.test((await page.getByRole('alert').allInnerTexts()).join(' ')));
  check('button still there for another try', (await btn(page).count()) === 1);
});
await run('390px', ['patients:view', 'patients:edit'], null, async ({ page }) => {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal scroll', m.s <= m.c, JSON.stringify(m));
  check('390px: button is reachable and at least 36px high', (await btn(page).boundingBox()).height >= 36);
}, { width: 390, height: 800 });

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
