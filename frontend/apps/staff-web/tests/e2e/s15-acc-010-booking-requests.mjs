// S15 ACC-010: reception's requests inbox (/requests) and the solo doctor's card on My Day.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3802 node tests/e2e/s15-acc-010-booking-requests.mjs
import { launch, seedClinic, mockApi, check, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const DOC = '33333333-3333-3333-3333-333333333333';
const soon = (min) => new Date(Date.now() + min * 60000).toISOString();
const slot = '2026-11-03T04:30:00.000Z'; // 10:00 in the default mocked clinic zone (Asia/Kolkata)
const item = (id, name, extra = {}) => ({ appointmentId: id, patient: { displayName: name, isNew: true, age: 34 }, doctorId: DOC, start: slot, channel: 'whatsapp',
  reviewReason: 'new_patient', triageFlags: [], slaDueAt: soon(8), suggestedAlternative: '2026-11-03T05:00:00.000Z', status: 'requested', ...extra });
const ITEMS = () => [item('r1', 'Asha Rao', { reviewReason: 'triage_flag', triageFlags: ['chest_pain'] }), item('r2', 'Bina Shah'), item('r3', 'Chitra Nair', { reviewReason: 'over_cap', slaDueAt: soon(-3) })];

// A small stateful server: decided requests leave the list; `lost` ids answer 409 ALREADY_DECIDED.
function server(initial, opts = {}) {
  const st = { items: initial, lost: new Set(opts.lost ?? []), failList: !!opts.failList };
  const decide = (verb) => (route, req) => {
    const id = req.url().match(/booking-requests\/([^/]+)\//)[1];
    if (st.lost.has(id)) { st.items = st.items.filter((x) => x.appointmentId !== id); return route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ type: 'already-decided', title: 'Already decided', detail: 'x', code: 'ALREADY_DECIDED' }) }); }
    if (opts.slotTaken && verb === 'accept') return route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ type: 'slot-taken', title: 'Slot', detail: 'x', code: 'SLOT_TAKEN', nearestAlternative: '2026-11-03T05:15:00.000Z' }) });
    if (verb !== 'suggest') st.items = st.items.filter((x) => x.appointmentId !== id);
    return route.fulfill({ status: verb === 'suggest' ? 202 : 200, contentType: 'application/json', body: JSON.stringify(verb === 'suggest' ? { offerId: 'o1', expiresAt: soon(60) } : verb === 'decline' ? { status: 'DECLINED' } : { appointmentId: id, status: 'CONFIRMED' }) });
  };
  const handlers = [
    ['GET', /^\/queue(\?|$)/, { body: [] }], // the shell's reception nav badge
    ['GET', /^\/booking-requests/, (route) => st.failList ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x', title: 'x' }) })
      : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: st.items, nextCursor: null, stats: { autoAccepted: 12, medianReplySeconds: 240 } }) })],
    ['POST', /^\/booking-requests\/[^/]+\/accept$/, decide('accept')],
    ['POST', /^\/booking-requests\/[^/]+\/suggest$/, decide('suggest')],
    ['POST', /^\/booking-requests\/[^/]+\/decline$/, decide('decline')],
    ...(opts.extra ?? []),
  ];
  return { st, handlers };
}

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
async function run(tag, perms, srv, fn, viewport, path = '/requests') {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms, { sub: SELF });
    await mockApi(page, log, srv.handlers);
    await page.goto(APP + path); await sleep(1800);
    await fn({ page, log, st: srv.st });
    check(`${tag} zero console/page errors`, noise(log).length === 0, JSON.stringify(noise(log)));
    if (viewport) check(`${tag} no horizontal scroll at ${viewport.width}px`, await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const REC = ['queue:view', 'queue:create', 'patients:view'];
const posts = (log, verb) => log.requests.filter((r) => r.method === 'POST' && new RegExp(`/${verb}$`).test(r.path));
const list = (page) => page.getByRole('list', { name: 'Pending booking requests' });

await run('happy', REC, server(ITEMS()), async ({ page, log, st }) => {
  const text = await page.locator('main').innerText();
  check('H1 title, stats and the three requests are shown', /Booking requests/.test(text) && /Auto-accepted today\s*12/i.test(text) && /Median reply\s*4 min/i.test(text) && /Asha Rao/.test(text) && /Bina Shah/.test(text) && /Chitra Nair/.test(text), text.slice(0, 300));
  check('H2 each row says why it needs review', /Triage words in the booking/.test(text) && /New patient/.test(text) && /Daily auto-accept limit reached/.test(text));
  check('H3 SLA countdown (left and overdue) and the next free time are shown', /\d+ min left/.test(text) && /Overdue 3 min/.test(text) && /Next free time: 03 Nov 2026 10:30/.test(text), text);
  check('H4 triage request is first and carries the Triage pill', /^Asha Rao/m.test(await list(page).locator('li').first().innerText()) && /Triage/.test(await list(page).locator('li').first().innerText()));
  check('H5 title is "Booking requests"', /Booking requests/.test(await page.title()), await page.title());
  // keyboard only: J moves to the second row, A accepts it
  await page.keyboard.press('j'); await sleep(150);
  check('K1 J moves the selection to the second row', (await list(page).locator('li[aria-current="true"]').innerText()).includes('Bina Shah'));
  await page.keyboard.press('k'); await sleep(100);
  check('K2 K moves back to the first row', (await list(page).locator('li[aria-current="true"]').innerText()).includes('Asha Rao'));
  await page.keyboard.press('j'); await page.keyboard.press('a'); await sleep(700);
  check('K3 A accepts the selected row (POST accept for r2, empty body)', posts(log, 'accept').length === 1 && /booking-requests\/r2\/accept/.test(posts(log, 'accept')[0].path) && (posts(log, 'accept')[0].body ?? '{}') === '{}', JSON.stringify(posts(log, 'accept')));
  check('K4 the accepted row left the list and a confirmation is shown', !/Bina Shah/.test(await list(page).innerText()) && /Accepted/.test(await page.locator('main').getByRole('status').innerText()));
  // suggest with S
  await page.keyboard.press('s'); await sleep(400);
  const dlg = page.getByRole('dialog', { name: 'Suggest other times' });
  check('S1 S opens the suggest dialog with the next free time filled in clinic time', (await dlg.count()) === 1 && (await dlg.locator('input[type=datetime-local]').first().inputValue()) === '2026-11-03T10:30');
  await dlg.getByRole('button', { name: 'Add another time' }).click();
  await dlg.locator('input[type=datetime-local]').nth(1).fill('2026-11-03T11:00');
  await dlg.getByRole('button', { name: 'Send to patient' }).click(); await sleep(600);
  const sug = posts(log, 'suggest');
  check('S2 POST suggest carries the two times as clinic-zone instants', sug.length === 1 && JSON.stringify(JSON.parse(sug[0].body).slots) === JSON.stringify(['2026-11-03T05:00:00.000Z', '2026-11-03T05:30:00.000Z']), sug[0]?.body);
  check('S3 dialog closed and the request is still listed (held)', (await page.getByRole('dialog').count()) === 0 && /Asha Rao/.test(await list(page).innerText()));
  // decline with D
  await page.keyboard.press('j'); await page.keyboard.press('d'); await sleep(400);
  const dd = page.getByRole('dialog', { name: 'Decline this request' });
  await dd.getByRole('button', { name: 'Decline' }).click(); await sleep(200);
  check('D1 declining without a reason is refused in the dialog, nothing sent', /Choose a reason/.test(await dd.innerText()) && posts(log, 'decline').length === 0);
  await dd.getByLabel('Reason').selectOption('doctor_unavailable');
  await dd.getByRole('button', { name: 'Decline' }).click(); await sleep(700);
  check('D2 POST decline carries the coded reason only', posts(log, 'decline').length === 1 && JSON.parse(posts(log, 'decline')[0].body).reasonCode === 'doctor_unavailable' && Object.keys(JSON.parse(posts(log, 'decline')[0].body)).join() === 'reasonCode', posts(log, 'decline')[0]?.body);
  check('D3 the declined row left the list', !/Chitra Nair/.test(await list(page).innerText().catch(() => '')));
}, { width: 1280, height: 900 });

await run('typing', REC, server(ITEMS()), async ({ page, log }) => {
  await page.evaluate(() => { const i = document.createElement('input'); i.id = 'probe'; document.body.appendChild(i); i.focus(); });
  await page.keyboard.type('add'); await sleep(300);
  check('K6 typing a, d in a text field accepts or declines nothing', posts(log, 'accept').length === 0 && (await page.getByRole('dialog').count()) === 0);
});

await run('lost race', REC, server(ITEMS(), { lost: ['r1'] }), async ({ page, log }) => {
  await page.getByRole('button', { name: 'Accept' }).first().click(); await sleep(900);
  const status = await page.locator('main').getByRole('status').innerText();
  check('R1 a 409 ALREADY_DECIDED says someone else decided and the list shows the final state', /Someone else already decided/.test(status) && !/Asha Rao/.test(await list(page).innerText()), status);
});

await run('slot taken', REC, server(ITEMS(), { slotTaken: true }), async ({ page }) => {
  await page.getByRole('button', { name: 'Accept' }).first().click(); await sleep(900);
  check('R2 SLOT_TAKEN keeps the row (rolled back) and says the time is gone', /no longer free/.test(await page.locator('main').getByRole('status').innerText()) && /Asha Rao/.test(await list(page).innerText()));
});

await run('empty', REC, server([]), async ({ page }) => {
  check('E1 empty state', /No requests waiting/.test(await page.locator('main').innerText()) && (await page.getByRole('button', { name: 'Accept' }).count()) === 0);
});

await run('error', REC, server([], { failList: true }), async ({ page, st }) => {
  check('X1 list failure shows an error with Retry', /Couldn.t load the booking requests/.test(await page.locator('main').getByRole('alert').innerText()));
  st.failList = false; st.items = ITEMS();
  await page.getByRole('button', { name: 'Retry' }).click(); await sleep(800);
  check('X2 Retry loads the requests', /Asha Rao/.test(await page.locator('main').innerText()));
});

await run('permission absent', ['queue:view', 'patients:view'], server(ITEMS()), async ({ page, log }) => {
  check('P1 without queue:create the page is the no-access state and nothing is fetched', /You do not have access to Booking requests/.test(await page.locator('main').innerText()) && log.requests.filter((r) => /booking-requests/.test(r.path)).length === 0);
  check('P2 no Booking requests link in the navigation', (await page.getByRole('link', { name: /Booking requests/ }).count()) === 0);
});

await run('nav link', REC, server([]), async ({ page }) => {
  check('N1 reception sees the Booking requests link', (await page.getByRole('link', { name: /Booking requests/ }).count()) >= 1);
});

await run('phone 390', REC, server(ITEMS()), async ({ page }) => {
  const b = await page.getByRole('button', { name: 'Accept' }).first().boundingBox();
  check('M1 Accept is at least 44px high on a phone', b.height >= 44 && b.width >= 44, JSON.stringify(b));
}, { width: 390, height: 844 });

// solo doctor: the same requests as a card on My Day, accepted with one tap
const SOLO = ['queue:view', 'queue:edit', 'queue:create', 'patients:view'];
const myDay = (extra) => [
  ...extra,
  ['GET', new RegExp(`^/doctors/${SELF}/session$`), { body: { sessionId: null, state: 'out', since: null } }],
  ['GET', /^\/queue\?/, { body: [] }],
  ['GET', /^\/appointments\?/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/doctors\/.*\/delay/, { body: [] }],
  ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 5, patientsAhead: 0, avgVisitMinutes: 15, basedOnHistory: false } }],
];
{
  const srv = server(ITEMS());
  srv.handlers = myDay(srv.handlers);
  await run('solo card', SOLO, srv, async ({ page, log }) => {
    const card = page.getByRole('region', { name: 'Booking requests waiting' });
    check('C1 My Day shows the requests card with the waiting requests', (await card.count()) === 1 && /Asha Rao/.test(await card.innerText()), await card.innerText().catch(() => 'none'));
    const b = await card.getByRole('button', { name: 'Accept' }).first().boundingBox();
    check('C2 Accept is a 44px touch target', b.height >= 44);
    await card.getByRole('button', { name: 'Accept' }).first().click(); await sleep(800);
    check('C3 one tap accepts and the row leaves the card', posts(log, 'accept').length === 1 && !/Asha Rao/.test(await card.innerText()));
    check('C4 a link leads to all requests', (await card.getByRole('link', { name: 'See all requests' }).getAttribute('href')) === '/requests');
  }, { width: 390, height: 844 }, '/my-day');
}
{
  const srv = server(ITEMS());
  srv.handlers = myDay(srv.handlers);
  await run('solo card absent', ['queue:view', 'queue:edit', 'patients:view'], srv, async ({ page, log }) => {
    check('C5 without queue:create the card is absent and nothing is fetched', (await page.getByRole('region', { name: 'Booking requests waiting' }).count()) === 0 && log.requests.filter((r) => /booking-requests/.test(r.path)).length === 0);
  }, undefined, '/my-day');
}
