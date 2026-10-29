// S16 COM-031 + COM-032: reception's WhatsApp inbox (two panes, filters, session pill, template picker, keys, phone layout) and its
// reply / canned reply / assign / note / bot handover / clinical tag controls. Run (after `next start -p 3818`):
//   NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3818 node tests/e2e/s16-com-031-032-whatsapp-inbox.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
import { axeStep } from './smoke/harness.mjs';

const SELF = '11111111-1111-1111-1111-111111111111', PRIYA = '44444444-4444-4444-4444-444444444444';
const mins = (m) => new Date(Date.now() + m * 60000).toISOString();
const win = (m) => ({ state: m > 0 ? 'open' : 'closed', closesAt: mins(m) });
const base = { patientRef: null, unread: 0, waitingMinutes: 0, mode: 'human', assignedTo: null, optedOut: false, clinical: false, clinicalSuggested: false, language: null };
const LONG = 'I would like to know whether the doctor can see my mother this week because she is not well';
const THREADS = () => [
  { ...base, id: 't4', displayName: '••••4444', lastMessagePreview: 'Are you open on Sunday?', waitingMinutes: 200, window: win(-30), language: 'hi' },
  { ...base, id: 't1', displayName: '••••1111', lastMessagePreview: LONG, unread: 2, waitingMinutes: 45, window: win(210) },
  { ...base, id: 't2', displayName: '••••2222', lastMessagePreview: 'Hello', waitingMinutes: 10, window: win(1200), mode: 'bot', unread: 1 },
  { ...base, id: 't3', displayName: '••••3333', lastMessagePreview: 'I have a rash after the peel', window: win(900), clinical: true, assignedTo: PRIYA },
  { ...base, id: 't5', displayName: '••••5555', lastMessagePreview: 'There is bleeding', window: win(800), clinicalSuggested: true },
];
const TEMPLATES = [
  { id: 'tp1', name: 'slot_back', language: 'hi', category: 'UTILITY', body: 'Hello {{1}}, your slot is {{2}}', paramCount: 2 },
  { id: 'tp2', name: 'we_are_open', language: 'hi', category: 'UTILITY', body: 'We are open every day', paramCount: 0 },
];
const msg = (id, dir, author, text, extra = {}) => ({ id, direction: dir, author, text, templateName: null, status: dir === 'out' ? 'delivered' : null, at: mins(-5), authorStaffRef: null, ...extra });

function server(opts = {}) {
  const st = { threads: opts.threads ?? THREADS(), failList: !!opts.failList, replyError: opts.replyError ?? null, posts: [], gets: [], messages: {
    t1: [msg('m2', 'in', 'patient', 'second message'), msg('m1', 'in', 'patient', LONG), msg('mf', 'out', 'staff', 'Please bring your card', { status: 'failed', authorStaffRef: SELF })],
    t4: [msg('n1', 'in', 'patient', 'Are you open on Sunday?')], t2: [msg('b1', 'in', 'patient', 'Hello'), msg('b2', 'out', 'bot', 'Hi, how can I help?')],
    t3: [msg('c1', 'in', 'patient', 'I have a rash after the peel')], t5: [msg('d1', 'in', 'patient', 'There is bleeding')],
  }, notes: [] };
  const json = (route, body, status = 200) => route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
  const th = (id) => st.threads.find((t) => t.id === id);
  const handlers = [
    ['GET', /^\/queue/, { body: [] }],
    ['GET', /^\/me\/entitlements/, { body: { modules: [{ code: 'whatsapp_booking', enabled: true }], killedFeatures: [], seatLimit: null } }],
    ['GET', /^\/inbox\/threads\?/, (route, req) => { st.gets.push(req.url()); if (st.failList) return json(route, { type: 'x', title: 'x' }, 500);
      const f = new URL(req.url()).searchParams.get('filter'); const items = st.threads.filter((t) => f === 'unread' ? t.unread > 0 : f === 'bot' ? t.mode === 'bot' : f === 'needs_reply' ? t.waitingMinutes > 0 : true);
      return json(route, { items, nextCursor: null }); }],
    ['GET', /^\/inbox\/threads\/([^/]+)\/messages/, (route, req, m) => json(route, { items: st.messages[m[1]] ?? [], nextCursor: null })],
    ['GET', /^\/inbox\/threads\/([^/]+)\/notes/, (route) => json(route, st.notes)],
    ['POST', /^\/inbox\/threads\/([^/]+)\/(read|replies|template-sends|assign|mode|notes|clinical)$/, (route, req, m) => {
      const body = req.postData() ? JSON.parse(req.postData()) : {}; const [, id, verb] = m; st.posts.push({ id, verb, body });
      if (verb === 'read') return route.fulfill({ status: 204, body: '' });
      if (verb === 'replies') {
        if (st.replyError) { if (st.replyError === 'SESSION_WINDOW_CLOSED') th(id).window = win(-1); return json(route, { type: 'x', title: 'x', detail: 'x', code: st.replyError }, st.replyError === 'TEXT_LOOKS_CLINICAL' ? 422 : 409); }
        st.messages[id] = [msg('new' + st.posts.length, 'out', 'staff', body.text, { status: 'sent', authorStaffRef: SELF }), ...st.messages[id]]; th(id).waitingMinutes = 0;
        return json(route, { messageId: 'new', status: 'queued' }, 201);
      }
      if (verb === 'template-sends') { th(id).window = win(1440); return json(route, { messageId: 'tpl', status: 'queued' }, 201); }
      if (verb === 'assign') { th(id).assignedTo = body.staffId; return json(route, th(id)); }
      if (verb === 'mode') { th(id).mode = body.mode; return json(route, th(id)); }
      if (verb === 'clinical') { th(id).clinical = body.clinical; th(id).clinicalSuggested = false; return json(route, th(id)); }
      st.notes = [{ id: 'n' + st.posts.length, outcome: body.outcome, note: body.note ?? null, at: mins(0) }, ...st.notes]; return json(route, st.notes[0], 201);
    }],
    ['GET', /^\/inbox\/canned-replies/, (route, req) => { st.gets.push(req.url()); json(route, [{ id: 'c1', title: 'Opening hours', body: 'हम रोज़ सुबह 9 बजे खुलते हैं', language: 'hi' }]); }],
    ['GET', /^\/inbox\/templates/, (route, req) => { st.gets.push(req.url()); if (opts.noTemplates) return json(route, []); json(route, TEMPLATES); }],
    ['GET', /^\/inbox\/assignees/, (route) => json(route, [{ id: SELF, name: 'Rana Reception' }, { id: PRIYA, name: 'Priya' }])],
    ...(opts.extra ?? []),
  ];
  return { st, handlers };
}

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const REPLY = ['patients:view', 'inbox:reply'];
async function run(tag, perms, srv, fn, viewport, pre) {
  const { browser, page, log } = await launch({ viewport });
  try {
    await seedClinic(page, perms, { sub: SELF });
    await mockApi(page, log, srv.handlers);
    if (pre) await pre(page);
    await page.addInitScript(() => { window.__lcp = 0; try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__lcp = e.startTime; }).observe({ type: 'largest-contentful-paint', buffered: true }); } catch {} });
    await page.goto(APP + '/messages'); await page.getByRole('list', { name: 'Conversation list' }).waitFor({ timeout: 8000 }).catch(() => {}); await sleep(700);
    await fn({ page, log, st: srv.st });
    check(`${tag} zero console/page errors`, noise(log).length === 0, JSON.stringify(noise(log)));
    if (viewport) check(`${tag} no horizontal scroll at ${viewport.width}px`, await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));
  } finally { dumpLog(tag, log); await browser.close(); }
}
const list = (page) => page.getByRole('list', { name: 'Conversation list' });
const posts = (st, verb) => st.posts.filter((p) => p.verb === verb);
const axeOk = async (page, step) => { const e = await axeStep(page, step, []); check(`axe "${step}": 0 critical/serious`, e.violations.length === 0, JSON.stringify(e.violations.map((v) => `${v.id}: ${v.nodes?.[0]}`)).slice(0, 400)); };
const row = (page, name) => list(page).locator('li', { hasText: name });

try {
  await run('desk', REPLY, server(), async ({ page, st }) => {
    const L = await page.getByRole('region', { name: 'Conversations', exact: true }).boundingBox(), T = await page.getByRole('region', { name: 'Conversation', exact: true }).boundingBox();
    check('A1 list and thread render side by side at 1366x768', L && T && T.x >= L.x + L.width - 1 && Math.abs(T.y - L.y) < 40, JSON.stringify({ L, T }));
    const lcp = await page.evaluate(() => window.__lcp);
    check('A2 LCP < 1.5 s (mocked API, production build)', lcp > 0 && lcp < 1500, `${Math.round(lcp)} ms`);
    check('A3 the oldest waiting thread (200 min) is selected and open', (await list(page).locator('[aria-current="true"]').innerText()).includes('••••4444') && /••••4444/.test(await page.getByRole('heading', { level: 2 }).innerText()));
    check('A4 closed window: no composer, "Choose template" instead, pill says Template only', (await page.locator('#inbox-reply').count()) === 0 && (await page.getByRole('button', { name: 'Choose template' }).count()) === 1 && /Template only/.test(await row(page, '••••4444').innerText()));
    const t1 = await row(page, '••••1111').innerText();
    check('A5 closing thread: amber pill with the countdown, waiting chip, unread badge', /Closes in 3 h \d\d min/.test(t1) && /Waiting 45 min/.test(t1) && (await row(page, '••••1111').locator('[class*="pill_closing"]').count()) === 1 && (await row(page, '••••1111').getByLabel('2 unread').count()) === 1, t1);
    check('A6 an open window beyond 4 h reads just "Open"', /\bOpen\b/.test(await row(page, '••••2222').innerText()) && !/Closes in/.test(await row(page, '••••2222').innerText()));
    check('A7 preview is cut at 60 characters', (await row(page, '••••1111').locator('[class*="preview"]').innerText()).length === 60);
    check('A8 clinical thread previews "Clinical message", never its text', /Clinical message/.test(await row(page, '••••3333').innerText()) && !/rash/.test(await row(page, '••••3333').innerText()));
    check('A9 assigned elsewhere: "Assigned to Priya" and Assign to me are shown', await (async () => { await row(page, '••••3333').getByRole('button').click(); await sleep(500); const t = await page.getByRole('region', { name: 'Conversation', exact: true }).innerText(); return /Assigned to Priya/.test(t) && /Assign to me/.test(t); })());
    await axeOk(page, 'inbox desk');

    // template picker for the closed thread, in the patient's language
    await row(page, '••••4444').getByRole('button').click(); await sleep(500);
    await page.getByRole('button', { name: 'Choose template' }).click(); await sleep(500);
    const dlg = page.getByRole('dialog', { name: 'Choose template' });
    check('T1 the picker asks only for the patient language (hi) and lists approved templates', st.gets.some((u) => /\/inbox\/templates\?language=hi/.test(u)) && (await dlg.getByRole('list', { name: 'Approved templates' }).locator('li').count()) === 2);
    await axeOk(page, 'template picker');
    await dlg.getByRole('button', { name: /slot_back/ }).click(); await sleep(200);
    check('T2 one field per variable, with a live preview', (await dlg.locator('input').count()) === 2 && /Hello \{\{1\}\}/.test(await dlg.getByLabel('Preview').innerText()));
    await dlg.getByRole('button', { name: 'Send template' }).click(); await sleep(200);
    check('T3 an empty variable is refused in the dialog, nothing sent', /Fill in every field/.test(await dlg.innerText()) && posts(st, 'template-sends').length === 0);
    await dlg.locator('input').nth(0).fill('Asha'); await dlg.locator('input').nth(1).fill('5 pm');
    check('T4 preview shows the filled text', /Hello Asha, your slot is 5 pm/.test(await dlg.getByLabel('Preview').innerText()));
    await dlg.getByRole('button', { name: 'Send template' }).click(); await sleep(800);
    const ts = posts(st, 'template-sends');
    check('T5 POST template-sends carries the template id and params; dialog closes; the composer takes over', ts.length === 1 && ts[0].body.templateId === 'tp1' && JSON.stringify(ts[0].body.params) === '["Asha","5 pm"]' && (await page.getByRole('dialog').count()) === 0 && (await page.locator('#inbox-reply').count()) === 1, JSON.stringify(ts));

    // reply journey within the window (t1) with a canned reply and a failed message
    await row(page, '••••1111').getByRole('button').click(); await sleep(600);
    check('R1 opening a thread marks it read up to the newest patient message', posts(st, 'read').some((p) => p.id === 't1' && p.body.upToMessageId === 'm2'));
    const pane = await page.getByRole('region', { name: 'Conversation', exact: true }).innerText();
    check('R2 bubbles carry author labels and ticks; a failed message shows the banner with Retry', /Patient/.test(pane) && /You/.test(pane) && /Not delivered/.test(pane) && (await page.getByRole('button', { name: 'Retry' }).count()) === 1);
    await page.getByRole('button', { name: 'Retry' }).click(); await sleep(500);
    check('R3 Retry posts the same text again', posts(st, 'replies').length === 1 && posts(st, 'replies')[0].body.text === 'Please bring your card');
    await page.getByRole('button', { name: 'Opening hours' }).waitFor({ timeout: 3000 }).catch(() => {});
    await page.getByRole('button', { name: 'Opening hours' }).click(); await sleep(200);
    check('R4 a canned reply is inserted for editing, not sent', (await page.locator('#inbox-reply').inputValue()).startsWith('हम रोज़') && posts(st, 'replies').length === 1);
    await page.locator('#inbox-reply').fill('Yes, we are open at 9.'); await page.getByRole('button', { name: 'Send', exact: true }).click(); await sleep(700);
    check('R5 Send posts the text; the textarea clears; the message is listed as You', posts(st, 'replies').length === 2 && posts(st, 'replies')[1].body.text === 'Yes, we are open at 9.' && (await page.locator('#inbox-reply').inputValue()) === '' && /Yes, we are open at 9\./.test(await page.getByRole('list', { name: 'Messages' }).innerText()));
    await page.locator('#inbox-reply').fill('Your HbA1c is 7.2 mg/dL'); await page.getByRole('button', { name: 'Send', exact: true }).click(); await sleep(300);
    check('R6 result-like text warns before any request and offers a secure-link hint', /secure link/.test(await page.getByRole('alert').filter({ hasText: 'secure link' }).first().innerText()) && posts(st, 'replies').length === 2);
    await page.getByRole('button', { name: 'Send anyway' }).click(); await sleep(600);
    check('R7 "Send anyway" posts with overrideClinical', posts(st, 'replies').length === 3 && posts(st, 'replies')[2].body.overrideClinical === true);

    // keyboard
    await page.locator('#inbox-reply').blur(); await page.keyboard.press('j'); await sleep(300);
    check('K1 J moves the selection down the list', (await list(page).locator('[aria-current="true"]').innerText()).includes('••••2222'));
    await page.keyboard.press('k'); await sleep(200);
    check('K2 K moves it back up', (await list(page).locator('[aria-current="true"]').innerText()).includes('••••1111'));
    await page.keyboard.press('r'); await sleep(200);
    check('K3 R puts the cursor in the reply box', await page.evaluate(() => document.activeElement?.id) === 'inbox-reply');
    await page.keyboard.type('jkrt'); await sleep(200);
    check('K4 typing J K R T in the reply box moves nothing and opens nothing', (await page.getByRole('dialog').count()) === 0 && (await list(page).locator('[aria-current="true"]').innerText()).includes('••••1111') && (await page.locator('#inbox-reply').inputValue()) === 'jkrt');
    await page.locator('#inbox-reply').fill(''); await page.locator('#inbox-reply').blur(); await page.keyboard.press('t'); await sleep(500);
    check('K5 T opens the template picker', (await page.getByRole('dialog', { name: 'Choose template' }).count()) === 1);
    await page.keyboard.press('Escape'); await sleep(300);

    // bot handover
    await row(page, '••••2222').getByRole('button').click(); await sleep(500);
    check('B1 bot thread: the bar says the assistant is replying', /Nabd assistant is replying/.test(await page.getByRole('region', { name: 'Conversation', exact: true }).innerText()) && /Nabd assistant - automated/.test(await page.getByRole('list', { name: 'Messages' }).innerText()));
    await page.locator('#inbox-reply').pressSequentially('Hi', { delay: 40 }); await sleep(800);
    const modes = posts(st, 'mode');
    check('B2 the first keystroke switches the thread to human (one request)', modes.length === 1 && modes[0].id === 't2' && modes[0].body.mode === 'human', JSON.stringify(modes));
    await page.getByRole('button', { name: 'Hand back to bot' }).click(); await sleep(500);
    check('B3 Hand back to bot posts mode bot', posts(st, 'mode').length === 2 && posts(st, 'mode')[1].body.mode === 'bot');

    // assign, notes, clinical
    await row(page, '••••3333').getByRole('button').click(); await sleep(400);
    await page.getByRole('button', { name: 'Assign to me' }).click(); await sleep(600);
    check('S1 one click reassigns to me', posts(st, 'assign').length === 1 && posts(st, 'assign')[0].body.staffId === SELF && /Assigned to Rana Reception/.test(await page.getByRole('region', { name: 'Conversation', exact: true }).innerText()));
    await page.getByLabel('Assign chat').selectOption(PRIYA); await sleep(500);
    check('S2 the assign menu hands it to a named colleague', posts(st, 'assign')[1].body.staffId === PRIYA);
    await page.getByRole('button', { name: 'Call notes' }).click(); await sleep(400);
    const nd = page.getByRole('dialog', { name: 'Call notes' });
    await nd.getByRole('button', { name: 'Save note' }).click(); await sleep(200);
    check('N1 a note without an outcome is refused in the dialog', /Choose what happened/.test(await nd.innerText()) && posts(st, 'notes').length === 0);
    await nd.getByLabel('Outcome').selectOption('call_back'); await nd.getByLabel('Note (optional)').fill('after lunch'); await nd.getByRole('button', { name: 'Save note' }).click(); await sleep(700);
    check('N2 the note is posted and listed', posts(st, 'notes').length === 1 && posts(st, 'notes')[0].body.outcome === 'call_back' && /Call back later — after lunch/.test(await nd.innerText()));
    await page.keyboard.press('Escape'); await sleep(300);
    await row(page, '••••5555').getByRole('button').click(); await sleep(400);
    check('C1 a suggested clinical thread offers the tag', (await page.getByRole('button', { name: 'Tag as clinical' }).count()) === 1);
    await page.getByRole('button', { name: 'Tag as clinical' }).click(); await sleep(600);
    check('C2 tagging posts clinical true; the list preview turns to "Clinical message"', posts(st, 'clinical').length === 1 && posts(st, 'clinical')[0].body.clinical === true && /Clinical message/.test(await row(page, '••••5555').innerText()));

    // filters
    await page.getByRole('button', { name: 'Unread', exact: true }).click(); await sleep(600);
    check('F1 the Unread filter asks the server for filter=unread and shows only unread threads', st.gets.some((u) => /filter=unread/.test(u)) && (await list(page).locator('li').count()) === 2, String(await list(page).locator('li').count()));
    await page.getByRole('button', { name: 'Bot', exact: true }).click(); await sleep(600);
    check('F2 the Bot filter asks for filter=bot', st.gets.some((u) => /filter=bot/.test(u)));
  }, { width: 1366, height: 768 });

  // window closes under the receptionist: 409 SESSION_WINDOW_CLOSED -> template path, text kept
  await run('window closed 409', REPLY, server({ replyError: 'SESSION_WINDOW_CLOSED' }), async ({ page, st }) => {
    await row(page, '••••1111').getByRole('button').click(); await sleep(500);
    await page.locator('#inbox-reply').fill('hello'); await page.getByRole('button', { name: 'Send', exact: true }).click(); await sleep(900);
    check('W1 409 SESSION_WINDOW_CLOSED says the window closed and offers the template path', /window just closed/.test(await page.getByRole('region', { name: 'Conversation', exact: true }).innerText()) || (await page.getByRole('button', { name: 'Choose template' }).count()) === 1);
    check('W2 the composer is replaced by Choose template after the reload', (await page.getByRole('button', { name: 'Choose template' }).count()) === 1 && (await page.locator('#inbox-reply').count()) === 0);
  }, { width: 1366, height: 768 });

  await run('server clinical 422', REPLY, server({ replyError: 'TEXT_LOOKS_CLINICAL' }), async ({ page, st }) => {
    await row(page, '••••1111').getByRole('button').click(); await sleep(500);
    await page.locator('#inbox-reply').fill('see you soon'); await page.getByRole('button', { name: 'Send', exact: true }).click(); await sleep(700);
    check('W3 server 422 TEXT_LOOKS_CLINICAL shows the same warning', /secure link/.test(await page.getByRole('alert').filter({ hasText: 'secure link' }).first().innerText()));
  }, { width: 1366, height: 768 });

  await run('no templates', REPLY, server({ noTemplates: true }), async ({ page }) => {
    await page.getByRole('button', { name: 'Choose template' }).click(); await sleep(600);
    check('T6 no approved template: an empty state, no send button', /No approved template for this patient/.test(await page.getByRole('dialog').innerText()) && (await page.getByRole('button', { name: 'Send template' }).count()) === 0);
  });

  await run('opted out', REPLY, server({ threads: [{ ...THREADS()[1], optedOut: true }] }), async ({ page }) => {
    const t = await page.getByRole('region', { name: 'Conversation', exact: true }).innerText();
    check('O1 opted out: pill says Opted out, no composer and no template button', /Opted out/.test(t) && (await page.locator('#inbox-reply').count()) === 0 && (await page.getByRole('button', { name: 'Choose template' }).count()) === 0);
  });

  await run('permission absent', ['patients:view'], server(), async ({ page, st }) => {
    check('P1 read-only: the list and messages show', (await list(page).locator('li').count()) === 5 && (await page.getByRole('list', { name: 'Messages' }).count()) === 1);
    await row(page, '••••1111').getByRole('button').click(); await sleep(400);
    const gone = ['#inbox-reply', 'button:has-text("Choose template")', 'button:has-text("Assign to me")', 'button:has-text("Call notes")', 'button:has-text("Take over")', 'button:has-text("Tag as clinical")', 'button:has-text("Retry")'];
    const counts = await Promise.all(gone.map((s) => page.locator(s).count()));
    check('P2 without inbox:reply every action is absent (not disabled)', counts.every((c) => c === 0), counts.join());
    check('P3 no assignee list was fetched', !st.gets.some((u) => /assignees/.test(u)));
  });
  await run('no access', ['queue:view'], server(), async ({ page, log }) => {
    check('P4 without patients:view the page is the no-access state and nothing is fetched', /You do not have access to WhatsApp inbox/.test(await page.locator('main').innerText()) && log.requests.filter((r) => /inbox/.test(r.path)).length === 0);
  });
  await run('empty', REPLY, server({ threads: [] }), async ({ page }) => {
    check('E1 empty state', /No conversations yet/.test(await page.locator('main').innerText()));
  });
  await run('error', REPLY, server({ failList: true }), async ({ page, st }) => {
    check('X1 list failure shows an error with Retry', /Couldn.t load the conversations/.test(await page.locator('main').getByRole('alert').innerText()));
    st.failList = false; await page.getByRole('button', { name: 'Retry' }).first().click(); await sleep(900);
    check('X2 Retry loads the conversations', (await list(page).locator('li').count()) === 5);
  });

  // the closing countdown moves each minute (fake clock: no 60 s wait)
  await run('minute tick', REPLY, server(), async ({ page }) => {
    const mm = async () => { const t = await row(page, '••••1111').innerText(); const m = /Closes in (\d) h (\d\d) min/.exec(t); return m ? Number(m[1]) * 60 + Number(m[2]) : null; };
    const before = await mm();
    await page.clock.fastForward(61_000); await sleep(400);
    const after = await mm();
    check('M0 the amber countdown drops by a minute after 60 s without a reload', before !== null && after === before - 1, `${before} -> ${after}`);
  }, { width: 1366, height: 768 }, (page) => page.clock.install());

  // phone: list first, tap opens the thread with a Back button
  await run('phone', REPLY, server(), async ({ page }) => {
    check('M1 the list shows first; the thread pane is hidden', (await list(page).isVisible()) && !(await page.getByRole('region', { name: 'Conversation', exact: true }).isVisible()));
    await row(page, '••••1111').getByRole('button').click(); await sleep(500);
    check('M2 opening a thread switches to it, with a Back button; the list is hidden', (await page.getByRole('region', { name: 'Conversation', exact: true }).isVisible()) && !(await list(page).isVisible()) && (await page.getByRole('button', { name: 'Back', exact: true }).isVisible()));
    const b = await page.getByRole('button', { name: 'Back', exact: true }).boundingBox();
    check('M3 Back is a 44px target', b.height >= 44 && b.width >= 44);
    await page.getByRole('button', { name: 'Back', exact: true }).click(); await sleep(300);
    check('M4 Back returns to the list', await list(page).isVisible());
    await axeOk(page, 'inbox phone');
  }, { width: 390, height: 844 });
} catch (e) {
  check('script completed without throwing', false, String(e?.stack ?? e).slice(0, 600));
}
const failed = results.filter((x) => !x.ok); console.log(`\n${results.length - failed.length}/${results.length} passed`); process.exit(failed.length ? 1 : 0);
