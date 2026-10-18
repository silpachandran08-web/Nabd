// REC-029 E2E: New appointment dialog (friendly errors, conflict alternatives, quick-create patient, visit type).
// Run: node tests/e2e/s8-rec2-new-appointment.mjs   (app on APP, default http://localhost:3102; API fully mocked)
import { APP, launch, seedClinic, mockApi, check, results, dumpLog, SHOTS } from './qa-lib.mjs';

const D1 = '33333333-3333-3333-3333-333333333331';
const P1 = '44444444-4444-4444-4444-444444444441';
const DAY = '2026-10-19';
const S = ['06:00', '06:30', '07:00', '07:30', '08:00'].map((t) => `${DAY}T${t}:00Z`); // 09:00..11:00 Asia/Riyadh
const RAW = 'RAW-SERVER-TEXT-must-not-show';
const FULL = ['queue:view', 'queue:create', 'queue:edit', 'patients:view', 'patients:create'];
const day = { date: DAY, today: false, timezone: 'Asia/Riyadh', holiday: null, sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 5 },
  doctors: [{ id: D1, name: 'Dr. Hana', department: 'General', onLeave: false, leaveReason: null, worksToday: true }] };
const problem = (slug, status, detail = RAW) => ({ status, body: { type: `https://api.nabd.health/errors/${slug}`, title: slug, status, detail } });
const page1 = (data) => ({ body: { data, page: { nextCursor: null } } });
const ali = { id: P1, mrn: 'MRN-1', name: 'Ali Hassan', phone: '+966500000001' };

/** One scenario = one browser. extra handlers go first so they override the defaults. */
async function scenario(id, { perms = FULL, viewport, extra = [] } = {}, fn) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, [...extra,
      ['GET', /^\/schedule\/day/, { body: day }],
      ['GET', /^\/queue$/, { body: [] }],
      ['GET', /^\/doctors\/[^/]+\/availability/, { body: S }],
      ['GET', /^\/patients\?q=ali/, page1([ali])],
      ['GET', /^\/patients\?q=/, page1([])],
      ['POST', /^\/appointments$/, { status: 201, body: { id: 'a1' } }],
    ]);
    await page.goto(`${APP}/schedule`, { waitUntil: 'domcontentloaded' });
    await fn(page, log);
    const errs = log.pageErrors.length + log.console.filter((x) => !/Failed to load resource/.test(x)).length;
    check(`${id} no console/page errors`, errs === 0, errs ? JSON.stringify([...log.pageErrors, ...log.console]).slice(0, 300) : '');
    dumpLog(id, log);
  } catch (e) {
    check(`${id} scenario ran`, false, String(e).split('\n')[0]);
    await page.screenshot({ path: `${SHOTS}/fail-${id}.png` }).catch(() => {});
  } finally {
    await browser.close();
  }
}
const openDialog = async (page) => { await page.getByRole('button', { name: '+ New appointment' }).click(); return page.getByRole('dialog'); };
const pickAli = async (d) => { await d.locator('#appt-patient').fill('ali'); await d.getByRole('button', { name: /Ali Hassan/ }).click(); };
const bookBtn = (d) => d.getByRole('button', { name: /Book appointment|Booking/ });
const posts = (log, p) => log.requests.filter((r) => r.method === 'POST' && r.path === p);

await scenario('01-happy', {}, async (page, log) => {
  const d = await openDialog(page);
  await pickAli(d);
  await d.locator('#appt-time').selectOption({ label: '10:00' });
  await d.locator('#appt-visit').selectOption('follow_up');
  await page.screenshot({ path: `${SHOTS}/happy-form.png` });
  await bookBtn(d).click();
  await d.waitFor({ state: 'detached' });
  const body = JSON.parse(posts(log, '/appointments')[0].body);
  check('01 books with patient/doctor/slot and follow-up visit type', body.patientId === P1 && body.doctorId === D1 && body.startTime === S[2] && body.isFollowUp === true, JSON.stringify(body));
});

await scenario('02-conflict', {
  extra: (() => { let n = 0; return [
    ['GET', /^\/doctors\/[^/]+\/availability/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(++n === 1 ? S : S.filter((s) => s !== S[2])) })],
    ['POST', /^\/appointments$/, (() => { let k = 0; return (r) => (++k === 1
      ? r.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify(problem('slot-unavailable', 409).body) })
      : r.fulfill({ status: 201, contentType: 'application/json', body: '{"id":"a2"}' })); })()],
  ]; })(),
}, async (page, log) => {
  const d = await openDialog(page);
  await pickAli(d);
  await d.locator('#appt-time').selectOption({ label: '10:00' });
  await bookBtn(d).click();
  const alert = d.getByRole('alert');
  await alert.waitFor();
  const txt = await d.innerText();
  check('02 conflict shows friendly copy, not the raw server text', /just taken/.test(txt) && !txt.includes(RAW), (await alert.innerText()));
  const alts = d.locator('[aria-live="polite"] button');
  await alts.first().waitFor();
  const labels = await alts.allInnerTexts();
  check('02 three nearest free times offered, 10:00 itself excluded', labels.length === 3 && labels[0] === '09:30' && labels[1] === '10:30' && !labels.includes('10:00'), labels.join(','));
  await page.screenshot({ path: `${SHOTS}/conflict-alternatives.png` });
  await alts.first().click();
  check('02 picking an alternative selects that time and clears the error', (await d.locator('#appt-time').inputValue()) === S[1] && (await d.getByRole('alert').count()) === 0);
  await bookBtn(d).click();
  await d.waitFor({ state: 'detached' });
  check('02 rebooking at the alternative succeeds', JSON.parse(posts(log, '/appointments')[1].body).startTime === S[1]);
});

for (const [slug, status, re] of [['session-full', 409, /session is full/], ['slot-in-past', 400, /already passed/], ['clinic-holiday', 400, /closed/], ['internal', 500, /went wrong on our side/], ['forbidden', 403, /permission/]]) {
  await scenario(`03-${slug}`, { extra: [['POST', /^\/appointments$/, problem(slug, status, slug === 'clinic-holiday' ? 'The clinic is closed on 2026-10-19.' : RAW)]] }, async (page) => {
    const d = await openDialog(page);
    await pickAli(d);
    await d.locator('#appt-time').selectOption({ label: '09:00' });
    await bookBtn(d).click();
    const t = await d.getByRole('alert').innerText();
    check(`03 ${slug} (${status}) shows friendly copy`, re.test(t) && !t.includes(RAW), t);
  });
}

await scenario('04-quick-create', {
  extra: [['POST', /^\/patients$/, { status: 201, body: { id: P1, mrn: 'MRN-9', name: 'Noor Salem', phone: '0555', dob: '1990-01-01', gender: 'female' } }]],
}, async (page, log) => {
  const d = await openDialog(page);
  await d.locator('#appt-patient').fill('zzz');
  await d.getByText('No matching patients.').waitFor();
  check('04 no dead-end copy about Live Queue', !/Live Queue/.test(await d.innerText()));
  await d.getByRole('button', { name: 'Register new patient' }).click();
  await d.getByLabel('Patient name').fill('Noor Salem');
  await d.getByLabel('Patient phone').fill('0555');
  await d.getByLabel('Date of birth').fill('1990-01-01');
  await d.getByLabel('Sex').selectOption('female');
  await page.screenshot({ path: `${SHOTS}/quick-create.png` });
  await d.getByRole('button', { name: 'Save patient' }).click();
  await d.getByText('Noor Salem').first().waitFor();
  const body = JSON.parse(posts(log, '/patients')[0].body);
  check('04 quick-create posts name/phone/dob/gender and selects the new patient', body.name === 'Noor Salem' && body.gender === 'female' && body.dob === '1990-01-01', JSON.stringify(body));
  await d.locator('#appt-time').selectOption({ label: '09:00' });
  check('04 booking is enabled for the new patient', await bookBtn(d).isEnabled());
});

await scenario('05-duplicate', {
  extra: [['POST', /^\/patients$/, { status: 409, body: { candidates: [{ patientId: P1, name: 'Noor Salem', phone: '0555', matchScore: 1 }] } }]],
}, async (page) => {
  const d = await openDialog(page);
  await d.locator('#appt-patient').fill('zzz');
  await d.getByRole('button', { name: 'Register new patient' }).click();
  await d.getByLabel('Patient name').fill('Noor S');
  await d.getByLabel('Patient phone').fill('0555');
  await d.getByLabel('Date of birth').fill('1990-01-01');
  await d.getByRole('button', { name: 'Save patient' }).click();
  await d.getByRole('button', { name: /Use existing: Noor Salem/ }).click();
  check('05 duplicate match can be chosen instead of re-registering', (await d.getByText('Noor Salem').count()) > 0 && (await d.getByRole('button', { name: 'Save patient' }).count()) === 0);
});

await scenario('06-minor-boundary', {}, async (page, log) => {
  const d = await openDialog(page);
  await d.locator('#appt-patient').fill('zzz');
  await d.getByRole('button', { name: 'Register new patient' }).click();
  await d.getByLabel('Patient name').fill('Kid');
  await d.getByLabel('Patient phone').fill('0555');
  const y = new Date().getFullYear() - 5;
  await d.getByLabel('Date of birth').fill(`${y}-01-01`);
  await d.getByRole('button', { name: 'Save patient' }).click();
  await d.getByText(/under 18 need a guardian/).waitFor();
  check('06 under-18 is stopped before any POST /patients', posts(log, '/patients').length === 0);
});

await scenario('07-no-register-permission', { perms: ['queue:view', 'queue:create', 'patients:view'] }, async (page) => {
  const d = await openDialog(page);
  await d.locator('#appt-patient').fill('zzz');
  await d.getByText('No matching patients.').waitFor();
  check('07 Register button absent without patients:create', (await d.getByRole('button', { name: 'Register new patient' }).count()) === 0);
  check('07 explains who can help instead of a dead end', /Ask a colleague/.test(await d.innerText()));
});

await scenario('08-no-book-permission', { perms: ['queue:view', 'patients:view'] }, async (page) => {
  await page.getByRole('heading', { name: /Schedule/ }).waitFor();
  check('08 + New appointment absent without queue:create', (await page.getByRole('button', { name: '+ New appointment' }).count()) === 0);
});

await scenario('09-empty-slots', { extra: [['GET', /^\/doctors\/[^/]+\/availability/, { body: [] }]] }, async (page) => {
  const d = await openDialog(page);
  await d.locator('#appt-time option', { hasText: 'No free slots on this day' }).waitFor({ state: 'attached' });
  check('09 empty slots: message plus next step', /Try another date or doctor/.test(await d.innerText()));
});

await scenario('10-slots-error', {
  extra: (() => { let n = 0; return [['GET', /^\/doctors\/[^/]+\/availability/, (r) => (++n === 1
    ? r.fulfill({ status: 500, contentType: 'application/problem+json', body: '{}' })
    : r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(S) }))]]; })(),
}, async (page) => {
  const d = await openDialog(page);
  await d.locator('#appt-time option', { hasText: "Couldn't load free slots" }).waitFor({ state: 'attached' });
  check('10 slots 500 is an error, not "no free slots"', !/No free slots/.test(await d.locator('#appt-time').innerText()));
  await d.getByRole('button', { name: 'Retry' }).click();
  await d.locator('#appt-time option', { hasText: '10:00' }).waitFor({ state: 'attached' });
  check('10 Retry loads the slots', true);
});

await scenario('11-search-error', { extra: [['GET', /^\/patients\?q=/, { status: 500, body: {} }]] }, async (page) => {
  const d = await openDialog(page);
  await d.locator('#appt-patient').fill('ali');
  await d.getByText(/Couldn't search patients/).waitFor();
  check('11 search failure is not reported as "no matching patients"', !/No matching patients/.test(await d.innerText()));
});

await scenario('12-mobile-390', { viewport: { width: 390, height: 800 } }, async (page) => {
  const d = await openDialog(page);
  await d.locator('#appt-patient').fill('zzz');
  await d.getByRole('button', { name: 'Register new patient' }).click();
  const sw = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth]);
  const dw = await d.evaluate((el) => [el.scrollWidth, el.clientWidth]);
  check('12 no horizontal scroll at 390px (page and dialog)', sw[0] <= sw[1] && dw[0] <= dw[1], `page ${sw} dialog ${dw}`);
  const small = await d.locator('input:visible, select:visible, button:visible').evaluateAll((els) => els.filter((e) => e.getBoundingClientRect().height < 43.5).map((e) => e.id || e.getAttribute('aria-label') || e.textContent));
  check('12 controls are 44px tall', small.length === 0, small.join('|'));
  await page.screenshot({ path: `${SHOTS}/mobile-390.png` });
});

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
