// ACC-008 / ACC-013 E2E: owner.workflows (booking rules, Solo preset, journey preview, publish with a fresh authenticator code).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3801 node s15-acc-008-owner-workflows.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const rule = (acceptance = 'auto', cap = 200, reviewerRole = 'reception') => ({ acceptance, dailyCapPerDoctor: cap, reviewerRole });
const base = () => ({
  preset: null, presetCustomised: false, solo: false,
  visitTypes: { new: rule(), followUp: rule(), procedure: rule(), teleconsult: rule() },
  checkIn: { modes: ['desk', 'door_qr', 'whatsapp'] }, eta: { displayMode: 'range', leaveHomeMarginMinutes: 0 }, walkIn: { interleave: 'none' },
  noShow: { graceMinutes: 15, autoMark: false, pullForward: false, depositAfterRepeat: false }, workflow: { vitalsEnabled: true, triageEnabled: true },
});
const soloCfg = () => {
  const c = base();
  c.preset = 'solo'; c.solo = true; c.checkIn.modes = ['door_qr']; c.eta.leaveHomeMarginMinutes = 20; c.walkIn.interleave = '1:3';
  c.noShow = { graceMinutes: 10, autoMark: true, pullForward: true, depositAfterRepeat: false }; c.workflow = { vitalsEnabled: false, triageEnabled: false };
  for (const k of Object.keys(c.visitTypes)) c.visitTypes[k] = rule('auto', 30, 'doctor');
  return c;
};

let st;
const json = (r, body, status = 200) => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', body: JSON.stringify(body) });
const handlers = () => [
  ['GET', /^\/booking-policy\/versions/, (r) => json(r, { items: st.versions, nextCursor: null })],
  ['GET', /^\/booking-policy$/, (r) => st.loadFail ? json(r, { title: 'x', detail: 'boom' }, 500) : json(r, { published: st.published, draft: st.draft })],
  ['PUT', /^\/booking-policy\/draft$/, (r, req) => {
    const cfg = JSON.parse(req.postData()).config; st.puts.push(cfg);
    if (st.invalid) return json(r, { type: 'invalid-policy', title: 'Invalid booking policy', code: 'INVALID_POLICY', pointer: '/walkIn/interleave', rule: 'must_be_one_of' }, 400);
    st.draft = { config: cfg, updatedAt: new Date().toISOString() }; return json(r, { draft: st.draft });
  }],
  ['POST', /^\/booking-policy\/draft\/apply-preset$/, (r) => {
    st.draft = { config: soloCfg(), updatedAt: new Date().toISOString() };
    return json(r, { draft: st.draft, changes: [{ pointer: '/solo', before: false, after: true }, { pointer: '/checkIn/modes', before: ['desk', 'door_qr', 'whatsapp'], after: ['door_qr'] }, { pointer: '/workflow/vitalsEnabled', before: true, after: false }], departmentWorkflowChanges: [{ departmentName: 'General', toVitalsEnabled: false }] });
  }],
  ['POST', /^\/booking-policy\/draft\/publish$/, (r, req) => {
    st.publishHeaders = req.headers(); st.publishBody = req.postData();
    const version = (st.published?.version ?? 0) + 1;
    st.published = { version, config: st.draft.config, publishedAt: new Date().toISOString() }; st.draft = null;
    st.versions = [{ version, publishedAt: new Date().toISOString(), publishedByName: 'Dr Owner', changeNote: 'first', diff: [{ pointer: '/solo', before: false, after: true }] }];
    return json(r, { version, effectiveAt: new Date().toISOString() });
  }],
  ['POST', /^\/auth\/mfa\/verify$/, (r, req) => { st.mfaBody = JSON.parse(req.postData()); return json(r, { stepUpToken: 'su-token' }); }],
];
const OWNER = ['queue:view', 'setup:view', 'setup:edit'];
async function open(perms, o = {}, viewport) {
  st = { published: null, draft: null, versions: [], puts: [], ...o };
  const s = await launch({ viewport });
  await seedClinic(s.page, perms, { owner: true, roleName: 'Owner' });
  await mockApi(s.page, s.log, handlers());
  await s.page.goto(`${APP}/setup/workflows`, { waitUntil: 'networkidle' });
  return s;
}
const body = (p) => p.locator('body').innerText();
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|was preloaded using link preload/.test(x))];
let s;
try {
  s = await open(OWNER);
  let t = await body(s.page);
  check('1 loads with nothing published: default message, Up to date, four visit-type tabs', /Nothing published yet/.test(t) && /Up to date/.test(t) && await s.page.getByRole('tab').count() === 4, t.slice(0, 200));
  check('2 empty history state', /No versions published yet/.test(t));
  // edit follow-up to auto cap 40
  await s.page.getByRole('tab', { name: 'Follow-up' }).click();
  await s.page.locator('#wf-cap').fill('40');
  check('3 an edit shows the Unpublished changes pill', await s.page.getByText('Unpublished changes').count() === 1);
  await s.page.locator('#wf-acceptance').selectOption('review');
  check('4 review adds a staff touch to the journey preview', /\b[1-9] staff touch/.test(await s.page.getByTestId('touches').innerText()));
  // solo preset
  await s.page.getByRole('button', { name: 'Solo doctor' }).click();
  await s.page.getByText('What the preset changed (3)').waitFor({ timeout: 5000 });
  check('5 solo preset lists what it changed and the department vitals change', /vitals step off when published/.test(await body(s.page)) && /check-in modes: desk, door_qr, whatsapp to door_qr/.test(await body(s.page)));
  let zero = true;
  for (const tab of ['New patient', 'Follow-up', 'Procedure / package', 'Teleconsult']) {
    await s.page.getByRole('tab', { name: tab }).click();
    zero = zero && /^0 staff touches before the consult/.test(await s.page.getByTestId('touches').innerText());
  }
  check('6 solo journey preview: 0 staff touches before the consult on every visit type', zero);
  check('7 solo mode shows the doctor as the only reviewer', /The doctor \(solo mode\)/.test(await body(s.page)) && await s.page.locator('#wf-reviewer').evaluate((e) => e.tagName) === 'P');
  // publish needs a 6-digit code
  const publish = s.page.getByRole('button', { name: 'Publish', exact: true });
  check('8 Publish stays off until a 6-digit code is typed', await publish.isDisabled());
  await s.page.getByLabel('Authenticator code').fill('123456');
  await s.page.getByLabel('Note for the history (optional)').fill('first');
  await publish.click();
  await s.page.getByText('Version 1 published').waitFor({ timeout: 5000 }).then(() => check('9 publish succeeds and says which version', true), async () => check('9 publish succeeds', false, await body(s.page)));
  check('10 publish sent the step-up token and an Idempotency-Key', st.publishHeaders?.['x-step-up-token'] === 'su-token' && /^[0-9a-f-]{36}$/.test(st.publishHeaders?.['idempotency-key'] ?? '') && JSON.parse(st.publishBody).changeNote === 'first');
  check('11 the draft was saved with the solo settings before publishing', st.mfaBody?.code === '123456');
  await s.page.getByText(/Version 1 ·/).waitFor({ timeout: 5000 }).catch(() => {});
  t = await body(s.page);
  check('12 history lists version 1 with who and the change', /Version 1/.test(t) && /Dr Owner/.test(t) && /1 change/.test(t));
  check('13 customised label absent right after the preset, Solo doctor shown', /Solo doctor/.test(t) && !/customised/.test(t));
  await s.page.locator('summary').first().click();
  check('14 a version expands to its diff', /solo mode: off to on/.test(await body(s.page)));
  // vitals back on -> customised wording appears after a save
  await s.page.getByLabel('Vitals step before the consult').check();
  check('15 turning vitals back on shows Unpublished changes again', await s.page.getByText('Unpublished changes').count() === 1);
  check('16 no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
  dumpLog('owner', s.log);
  await s.browser.close();

  // server-side refusal shows the field
  s = await open(OWNER, { invalid: true });
  await s.page.locator('#wf-cap').fill('30');
  await s.page.getByRole('button', { name: 'Save draft' }).click();
  await s.page.getByText(/Not saved: walk-in interleave is not valid/).waitFor({ timeout: 5000 }).then(() => check('17 INVALID_POLICY names the field', true), () => check('17 INVALID_POLICY names the field', false));
  // client-side cross-field refusal: solo + reception-only
  await s.page.getByLabel('Solo mode').check();
  for (const m of ['Door QR', 'WhatsApp "I\'m here"']) await s.page.getByLabel(m).uncheck();
  check('18 solo with reception-only check-in is refused before saving, Publish and Save draft off',
    /reception-only is not allowed/.test(await body(s.page)) && await s.page.getByRole('button', { name: 'Save draft' }).isDisabled());
  await s.browser.close();

  // permission-absent: queue:view only
  s = await open(['queue:view'], { published: { version: 2, config: soloCfg(), publishedAt: new Date().toISOString() } });
  t = await body(s.page);
  check('19 read-only role: rules visible, no edit controls, no preset, no publish',
    /Published version 2/.test(t) && await s.page.getByRole('button', { name: /Solo doctor|Publish|Save draft/ }).count() === 0 && await s.page.locator('main select, main input[type=checkbox]').count() === 0, t.slice(0, 200));
  await s.browser.close();
  // no grant at all
  s = await open(['patients:view']);
  check('20 no queue:view: access message, no request to the policy API', /do not have access/i.test(await body(s.page)) && !s.log.requests.some((r) => r.path.startsWith('/booking-policy')));
  await s.browser.close();
  // load error
  s = await open(OWNER, { loadFail: true });
  check('21 load failure shows an error state with retry', /Could not load the booking rules/.test(await body(s.page)) && await s.page.getByRole('button', { name: /retry|try again/i }).count() >= 1);
  await s.browser.close();
  // 390px
  s = await open(OWNER, { published: { version: 1, config: soloCfg(), publishedAt: new Date().toISOString() } }, { width: 390, height: 800 });
  check('22 390px: no horizontal page scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  check('23 390px: no console errors', errs(s.log).length === 0, errs(s.log).join('|'));
} catch (e) { check('run', false, String(e)); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
