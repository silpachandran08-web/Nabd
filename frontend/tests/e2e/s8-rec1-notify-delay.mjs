// REC-020: Notify delay dialog with presets, preview and delivery ladder.
// Run (server on :3101): node tests/e2e/s8-rec1-notify-delay.mjs
import { launch, seedClinic, mockApi, check, sleep, dumpLog } from '/home/claude/ws/qa/t3/qa-lib.mjs';

const APP = 'http://localhost:3101';
const SHOTS = '/home/claude/ws/reports/shots-rec1';
const DOC = '33333333-3333-3333-3333-333333333331';
const DOC2 = '33333333-3333-3333-3333-333333333332';
const PAT = '44444444-4444-4444-4444-444444444441';
const entry = (n, doctorId, status = 'waiting') => ({
  id: `55555555-5555-5555-5555-55555555555${n}`, appointmentId: null, patientId: PAT, doctorId, departmentId: '66666666-6666-6666-6666-666666666666',
  queueDate: '2026-10-18', tokenNumber: n, status, priority: false, priorityReason: null, createdAt: '2026-10-18T05:00:00Z', source: 'walk_in',
});
const roster = [{ id: DOC, name: 'Dr Amal Nasser', departmentName: 'General' }, { id: DOC2, name: 'Dr Idle Doc', departmentName: 'General' }];
const EDIT = ['queue:view', 'queue:edit', 'patients:view'];
const VIEW = ['queue:view', 'patients:view'];

async function run(tag, perms, opts, fn) {
  const { browser, page, log } = await launch(opts.viewport ? { viewport: opts.viewport } : {});
  const bad = [];
  page.on('response', (r) => { if (r.status() >= 400) bad.push(r.status() + ' ' + r.url()); });
  log.bad = bad;
  const state = { active: null, post: null, posts: [] };
  try {
    await seedClinic(page, perms);
    await mockApi(page, log, [
      ['GET', /^\/queue(\?|$)/, { body: opts.queue ?? [entry(1, DOC), entry(2, DOC)] }],
      ['GET', /^\/appointments\/callback-list/, { body: [] }],
      ['GET', /^\/staff\/roster/, { body: opts.roster ?? roster }],
      ['GET', /^\/queue\/wait-estimate/, { body: { estimatedMinutes: 10 } }],
      ['GET', /^\/patients\//, { body: { name: 'Test Patient', mrn: 'MRN1' } }],
      ['GET', /^\/doctors\/[^/]+\/delay/, (route, req, m) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state.active && m.input.includes(DOC + '/') ? [state.active] : []) })],
      ['POST', /^\/doctors\/[^/]+\/delay$/, (route, req) => {
        state.posts.push(JSON.parse(req.postData()));
        if (opts.postStatus) return route.fulfill({ status: opts.postStatus, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x', title: 'Nope', detail: opts.postDetail ?? 'Server said no.' }) });
        const b = JSON.parse(req.postData());
        state.active = { id: 'd1', doctorId: DOC, delayMinutes: b.delayMinutes, reason: b.reason, active: true };
        return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(state.active) });
      }],
    ]);
    await page.goto(APP + '/arrivals');
    await page.waitForLoadState('networkidle').catch(() => {});
    await sleep(1200);
    await fn(page, log, state);
    dumpLog(tag, log);
    if (bad.length) console.log(`  [${tag}] >=400:`, [...new Set(bad)]);
    return log;
  } finally { await browser.close(); }
}
const dlg = (page) => page.getByRole('dialog');
const noConsoleErrors = (log) => log.pageErrors.length === 0 && log.console.every((x) => /Failed to load resource/.test(x)) && log.bad.every((x) => /fake-poppins/.test(x)) /* build uses mocked fonts */ && log.unmatched.length === 0;
const openDialog = async (page) => {
  await page.getByRole('button', { name: 'Notify delay' }).click();
  await dlg(page).getByRole('button', { name: 'Dr Amal Nasser' }).click();
};
const submit = (page) => dlg(page).getByRole('button', { name: 'Announce delay' });

// 1. happy path: presets, preview, ladder, POST body, sidebar refresh
const log1 = await run('H', EDIT, {}, async (page, log, state) => {
  await openDialog(page);
  const d = dlg(page);
  await d.waitFor();
  const presets = await d.getByRole('group').getByRole('button').allInnerTexts();
  check('H1 presets 10/15/30/45/60 shown', presets.join(',') === '10 min,15 min,30 min,45 min,60 min', presets.join(','));
  check('H2 default 15 preset pressed + input 15', (await d.getByRole('button', { name: '15 min' }).getAttribute('aria-pressed')) === 'true' && (await d.locator('#delayMinutes').inputValue()) === '15');
  await d.getByRole('button', { name: '30 min' }).click();
  check('H3 preset click fills input and presses it', (await d.locator('#delayMinutes').inputValue()) === '30' && (await d.getByRole('button', { name: '30 min' }).getAttribute('aria-pressed')) === 'true' && (await d.getByRole('button', { name: '15 min' }).getAttribute('aria-pressed')) === 'false');
  await d.locator('#delayReason').fill('Emergency case');
  const prev = await d.getByLabel('Delay preview').innerText();
  check('H4 preview shows minutes, reason and affected patients', /Running 30m late — Emergency case/.test(prev) && /2 waiting patients are affected/.test(prev), prev.replace(/\n+/g, ' | '));
  const ladder = await d.getByLabel('How the delay is delivered').innerText();
  check('H5 ladder: sidebar live, patient message honestly not available', /Queue sidebar/.test(ladder) && /Patient message \(WhatsApp, then SMS\): not available yet/.test(ladder), ladder.replace(/\n+/g, ' | '));
  await page.screenshot({ path: SHOTS + '/H-dialog-1280.png' });
  await d.locator('#delayMinutes').fill('20');
  check('H6 typed custom value unpresses presets', (await d.locator('[aria-pressed="true"]').count()) === 0);
  await d.locator('#delayMinutes').fill('30');
  await submit(page).click();
  await dlg(page).waitFor({ state: 'detached' });
  check('H7 POST body is the existing contract {delayMinutes, reason}', JSON.stringify(state.posts) === JSON.stringify([{ delayMinutes: 30, reason: 'Emergency case' }]), JSON.stringify(state.posts));
  await sleep(800);
  const side = await page.locator('body').innerText();
  check('H8 sidebar shows the new delay after reload', /Running 30m late — Emergency case/.test(side));
});
check('H9 no console/page errors (happy)', noConsoleErrors(log1), JSON.stringify([...log1.console, ...log1.pageErrors]));

// 2. boundaries
await run('B', EDIT, {}, async (page) => {
  await openDialog(page);
  const d = dlg(page);
  const inp = d.locator('#delayMinutes');
  for (const [v, okExpected] of [['0', false], ['-5', false], ['1.5', false], ['1', true], ['600', true]]) {
    await inp.fill(v);
    const enabled = await submit(page).isEnabled();
    check(`B ${v} min -> submit ${okExpected ? 'enabled' : 'disabled'}`, enabled === okExpected);
  }
  await inp.fill('0');
  check('B invalid shows inline message and preview prompt', /whole number of minutes/.test(await d.innerText()) && /Pick or enter the delay/.test(await d.getByLabel('Delay preview').innerText()));
  await inp.fill('');
  check('B empty -> submit disabled, no scolding', !(await submit(page).isEnabled()) && !/whole number of minutes/.test(await d.innerText()));
});

// 3. sidebar "Announce delay" entry shows the same dialog; singular wording; no waiting patients
await run('S', EDIT, { queue: [entry(1, DOC)] }, async (page) => {
  await page.getByRole('button', { name: 'Announce delay' }).first().click();
  const t = await dlg(page).getByLabel('Delay preview').innerText();
  check('S1 sidebar entry opens presets dialog; singular wording', (await dlg(page).getByRole('button', { name: '45 min' }).count()) === 1 && /1 waiting patient is affected/.test(t), t.replace(/\n+/g, ' | '));
});
await run('S2', EDIT, { queue: [] }, async (page) => {
  await openDialog(page);
  const t = await dlg(page).getByLabel('Delay preview').innerText();
  check('S2 no waiting patients wording', /No patients are waiting for this doctor/.test(t), t.replace(/\n+/g, ' | '));
});

// 4. permission absent
await run('P', VIEW, {}, async (page) => {
  const body = await page.locator('body').innerText();
  check('P1 queue:view only: no Notify delay / Announce delay controls rendered', (await page.getByRole('button', { name: /Notify delay|Announce delay|Clear delay/ }).count()) === 0 && /Dr Amal Nasser/.test(body));
});

// 5. empty roster
await run('E', EDIT, { roster: [] }, async (page) => {
  await page.getByRole('button', { name: 'Notify delay' }).click();
  check('E1 empty roster: picker shows empty state', /No doctors on the roster/.test(await dlg(page).innerText()));
});

// 6. errors
for (const [status, detail] of [[500, 'Boom from server.'], [403, 'Not allowed to do that.']]) {
  await run(`X${status}`, EDIT, { postStatus: status, postDetail: detail }, async (page) => {
    await openDialog(page);
    await submit(page).click();
    await sleep(600);
    const d = dlg(page);
    check(`X${status} error shown in dialog, dialog stays open, form re-usable`, (await d.count()) === 1 && (await d.getByRole('alert').count()) >= 1 && /couldn.t finish|isn.t allowed/.test(await d.innerText()) && await submit(page).isEnabled(), (await d.innerText()).replace(/\n+/g, ' | ').slice(-160));
  });
}

// 7. 390px
await run('M', EDIT, { viewport: { width: 390, height: 844 } }, async (page, log) => {
  await openDialog(page);
  await dlg(page).getByLabel('Delay preview').waitFor();
  const w = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
  const small = await dlg(page).getByRole('button', { name: /min$/ }).evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().height)).filter((h) => h < 44));
  const dlgBox = await dlg(page).boundingBox();
  check('M1 390px: no horizontal page scroll, dialog inside viewport', w.sw <= w.cw && dlgBox.x >= 0 && dlgBox.x + dlgBox.width <= 390, JSON.stringify({ ...w, dlgBox }));
  check('M2 preset buttons are >= 44px tall', small.length === 0, JSON.stringify(small));
  await page.screenshot({ path: SHOTS + '/M-dialog-390.png' });
  check('M3 no console/page errors (390)', noConsoleErrors(log), JSON.stringify([...log.console, ...log.pageErrors]));
});

const failed = (await import('/home/claude/ws/qa/t3/qa-lib.mjs')).results.filter((r) => !r.ok);
console.log(`\n${failed.length === 0 ? 'ALL PASS' : failed.length + ' FAILED'}`);
process.exit(failed.length ? 1 : 0);
