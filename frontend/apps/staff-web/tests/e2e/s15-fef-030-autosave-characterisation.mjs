// S15 FEF-030: characterisation of the seven autosave rules of the consult note editor (written against the page BEFORE the split,
// re-run after it; the rules must not change). Rules (saveNote in the old consult/page.tsx, lines 325-418):
//  A1 never save while a conflict is unresolved      A2 never save before the server copy is known
//  A3 offline: keep a local draft, no request        A4 a note signed elsewhere is never overwritten (text kept visible)
//  A5 server changed under a dirty note: pause, ask  A6 a failed save keeps the draft and retries on the next tick
//  A7 success deletes the local draft only if nothing was typed while the save was in flight
// CONSULT_PATH=/consult-legacy runs it against the pre-split copy of the page (baseline, kept only while the split is built).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3806 node s15-fef-030-autosave-characterisation.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const SELF = '11111111-1111-1111-1111-111111111111';
const PERMS = ['queue:view', 'queue:edit', 'patients:view', 'clinical:view', 'clinical:edit'];
const entry = { id: 'e1', appointmentId: null, patientId: 'p1', doctorId: SELF, departmentId: 'd1', queueDate: '2026-10-19', tokenNumber: 3, status: 'in_consult', priority: false, priorityReason: null, createdAt: new Date().toISOString() };
const patient = { id: 'p1', mrn: 'M-1', name: 'Test Patient', phone: '', dob: '1990-01-01', gender: 'female', status: 'active', allergies: [], chronicConditions: [], activePackages: 0, lastVisitAt: null };
const mkNote = (o = {}) => ({ id: 'n1', queueEntryId: 'e1', patientId: 'p1', doctorId: SELF, subjective: 'S0', objective: '', assessment: '', plan: '', diagnosis: '', status: 'draft', signedAt: null, updatedAt: '2026-10-19T08:00:00Z', signedBy: null, ...o });
const json = (r, status, body, type = 'application/json') => r.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : type, body: JSON.stringify(body) });

async function open(state) {
  const s = await launch();
  await seedClinic(s.page, PERMS);
  s.state = state; s.patches = [];
  await mockApi(s.page, s.log, [
    ['GET', /^\/queue(\?|$)/, { status: 200, body: [entry] }],
    ['GET', /^\/patients\/p1/, { status: 200, body: patient }],
    ['GET', /^\/departments\/d1\/flow/, { status: 200, body: { steps: [] } }],
    ['PATCH', /^\/clinical\/notes\/e1$/, async (r, req) => {
      s.patches.push(JSON.parse(req.postData() || '{}'));
      if (state.patchDelay) await sleep(state.patchDelay);
      const f = state.patch?.(s.patches.length);
      if (f && f.status >= 400) return json(r, f.status, { title: 'Injected', detail: 'x' });
      state.server = { ...state.server, ...JSON.parse(req.postData() || '{}'), updatedAt: `2026-10-19T08:0${s.patches.length}:00Z` };
      return json(r, 200, state.server);
    }],
    ['GET', /^\/clinical\/notes\/e1$/, (r) => state.noteFail ? json(r, 500, { title: 'down', detail: 'down' }) : json(r, 200, state.server)],
    ['GET', /^\/clinical\/prescriptions\/e1\/signing-authority$/, { status: 200, body: { canSign: true, reason: null } }],
    ['GET', /^\/clinical\/(vitals|prescriptions)\//, { status: 404, body: {} }],
    ['GET', /./, { status: 200, body: [] }],
  ]);
  await s.page.goto(APP + (process.env.CONSULT_PATH ?? '/consult') + '?entry=e1&step=examine', { waitUntil: 'networkidle' });
  await s.page.locator('#subjective').waitFor({ timeout: 10000 });
  await sleep(400);
  return s;
}
const drafts = (p) => p.evaluate(() => new Promise((res) => {
  const q = indexedDB.open('nabd-consult-drafts', 1);
  q.onupgradeneeded = () => q.result.createObjectStore('drafts', { keyPath: 'entryId' });
  q.onsuccess = () => { const c = q.result.transaction('drafts').objectStore('drafts').count(); c.onsuccess = () => { q.result.close(); res(c.result); }; c.onerror = () => res(-1); };
  q.onerror = () => res(-1);
}));
const until = async (fn, ms = 6000) => { const t = Date.now(); while (Date.now() - t < ms) { if (await fn()) return true; await sleep(150); } return false; };
const has = (p, t) => p.getByText(t).count().then((n) => n > 0);
const type = (p, v) => p.locator('#subjective').fill(v);
const errs = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource|preloaded using link preload|ERR_FAILED|net::|status of 40[34]|status of 500/.test(x))];
const consoleErrors = [];
let s;
try {
  // A3 + A7(success): offline keeps a local draft and sends nothing; on reconnect the server copy is compared, then saved, then the draft is gone.
  s = await open({ server: mkNote() });
  await s.page.context().setOffline(true);
  await type(s.page, 'offline text');
  check('A3a offline edit: pill says Offline · local draft', await until(() => has(s.page, 'Offline · local draft')));
  check('A3b offline: local draft written (IndexedDB)', await until(async () => (await drafts(s.page)) === 1, 4000));
  check('A3c offline: no PATCH sent', s.patches.length === 0);
  await s.page.context().setOffline(false);
  check('A3d reconnect: PATCH carries the offline text', await until(() => s.patches.length === 1) && s.patches[0].subjective === 'offline text');
  check('A7a success with no edits in flight deletes the draft', await until(async () => (await drafts(s.page)) === 0, 4000));
  check('A7b badge says Saved', await until(() => has(s.page, /Saved \d/)) || await s.page.getByText(/^Saved /).count() > 0);
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // A4: signed elsewhere while this device held unsaved text.
  s = await open({ server: mkNote() });
  await s.page.context().setOffline(true);
  await type(s.page, 'text typed offline');
  await until(async () => (await drafts(s.page)) === 1, 4000);
  s.state.server = mkNote({ status: 'signed', signedAt: '2026-10-19T08:30:00Z', subjective: 'signed elsewhere', updatedAt: '2026-10-19T08:30:00Z' });
  await s.page.context().setOffline(false);
  check('A4a orphan notice shown with the local text', await until(() => has(s.page, 'This note was signed in another session')) && await has(s.page, 'text typed offline'));
  check('A4b signed note is never overwritten (no PATCH)', s.patches.length === 0);
  check('A4c editor locked to the server copy', await s.page.locator('#subjective').isDisabled() && (await s.page.locator('#subjective').inputValue()) === 'signed elsewhere');
  check('A4d draft deleted', await until(async () => (await drafts(s.page)) === 0, 4000));
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // A5 + A1: server changed under a dirty note -> conflict, nothing is sent, not even on the next autosave tick.
  s = await open({ server: mkNote() });
  await s.page.context().setOffline(true);
  await type(s.page, 'my version');
  await until(async () => (await drafts(s.page)) === 1, 4000);
  s.state.server = mkNote({ subjective: 'their version', updatedAt: '2026-10-19T08:45:00Z' });
  await s.page.context().setOffline(false);
  check('A5a conflict banner shown', await until(() => has(s.page, 'Saving is paused: this note changed on the server.')));
  check('A5b badge Sync conflict', await has(s.page, 'Sync conflict — review'));
  check('A5c no PATCH on conflict', s.patches.length === 0);
  check('A1a Save draft disabled while the conflict is open', await s.page.getByRole('button', { name: 'Save draft' }).isDisabled());
  await sleep(10800); // one autosave tick
  check('A1b autosave tick sent nothing while the conflict is unresolved', s.patches.length === 0);
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // A2: the server copy never loaded -> editing and saving are locked.
  s = await open({ server: mkNote(), noteFail: true }).catch(() => null);
  if (!s) { check('A2 page opened with a failed note read', false); } else {
    check('A2a error banner "Couldn\'t load the note"', await until(() => has(s.page, /load the note, so editing and saving are locked/)));
    check('A2b textarea disabled', await s.page.locator('#subjective').isDisabled());
    await sleep(10800);
    check('A2c no PATCH ever sent', s.patches.length === 0);
    consoleErrors.push(...errs(s.log)); await s.browser.close();
  }

  // A6: a failed save keeps the draft and the next tick retries.
  s = await open({ server: mkNote(), patch: (n) => (n === 1 ? { status: 500 } : null) });
  await type(s.page, 'retry me');
  await s.page.getByRole('button', { name: 'Save draft' }).click();
  check('A6a failed save: pill says Couldn\'t save — retrying', await until(() => has(s.page, "Couldn't save — retrying")));
  check('A6b failed save: local draft kept', await until(async () => (await drafts(s.page)) === 1, 4000));
  check('A6c next autosave tick retries and succeeds', await until(() => s.patches.length >= 2, 12500) && s.patches[1].subjective === 'retry me');
  check('A6d draft deleted after the retry succeeded', await until(async () => (await drafts(s.page)) === 0, 4000));
  consoleErrors.push(...errs(s.log)); await s.browser.close();

  // A7: typing while a save is in flight -> still dirty, draft kept, final text goes out next.
  s = await open({ server: mkNote(), patchDelay: 1500 });
  await type(s.page, 'first');
  await s.page.getByRole('button', { name: 'Save draft' }).click();
  await until(() => s.patches.length === 1, 3000);
  await type(s.page, 'first and more');
  await sleep(2600);
  check('A7c edit during flight: still Unsaved changes', await has(s.page, 'Unsaved changes'));
  check('A7d edit during flight: draft kept', (await drafts(s.page)) === 1);
  await s.page.getByRole('button', { name: 'Save draft' }).click();
  check('A7e second save carries the final text', await until(() => s.patches.length === 2) && s.patches[1].subjective === 'first and more');
  consoleErrors.push(...errs(s.log)); await s.browser.close();
  s = null;
} catch (e) {
  check('RUN no exception', false, String(e).slice(0, 300));
  if (s) { dumpLog('last', s.log); await s.browser.close().catch(() => {}); }
}
check('Z console/page errors', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
const failed = results.filter((r) => !r.ok).length;
console.log(`\n${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
