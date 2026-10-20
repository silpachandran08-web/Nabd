// Independent QA (S12 FIN-005): day close UI. AED tolerance from the summary (boundary 20.00 / 20.01), re-close of a
// reopened day with history, server refusals shown (DAY_ALREADY_CLOSED race, variance note), permission-absent, non-owner approver.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3811 node tests/e2e/s12qa-fin-005.mjs
import { launch, seedClinic, mockApi, check, results, APP } from './qa-lib.mjs';

const TODAY = '2026-10-20';
const APPROVER = ['billing:view', 'billing:create', 'billing:edit', 'billing:approve'];
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const summary = (d) => ({
  date: d.date, today: d.date === TODAY, currency: d.currency, taxLabel: 'VAT', cashTolerance: d.tolerance,
  totals: { billed: 300, bills: 1, collected: d.cash, payments: 1, tax: 0 },
  methods: [{ method: 'cash', amount: d.cash, transactions: 1 }, { method: 'card', amount: 0, transactions: 0 },
    { method: 'upi', amount: 0, transactions: 0 }, { method: 'other', amount: 0, transactions: 0 }],
  outstanding: { amount: 0, patients: 0, bills: 0 }, pendingCheckouts: 0, unbilled: [], taxBands: [], splitPaymentBills: 0,
  voidedCount: 0, voidedTotal: 0, close: d.close,
});
const problem = (type, detail) => ({ status: type === 'variance-note-required' ? 400 : 409, body: { type: `https://api.nabd.health/errors/${type}`, title: 'x', detail } });

async function open(perms, extra, o = {}) {
  const s = await launch({ viewport: o.viewport });
  await seedClinic(s.page, perms, { tenantRegion: o.region ?? 'AE', ...extra });
  const d = { date: TODAY, currency: o.currency ?? 'AED', tolerance: o.tolerance ?? 20, cash: o.cash ?? 100, close: o.close ?? null, events: o.events ?? [] };
  const st = { posts: [] };
  await mockApi(s.page, s.log, [
    ['GET', /^\/setup\/profile$/, { body: { timezone: 'Asia/Dubai', region: o.region ?? 'AE' } }],
    ['GET', /^\/billing\/otc-charges/, { status: 409, body: { type: 'otc-unavailable', title: 'x', detail: 'x' } }],
    ['GET', /^\/billing\/day-close\/history/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [...d.events].reverse(), nextCursor: null }) })],
    ['GET', /^\/billing\/day-close(\?.*)?$/, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(summary(d)) })],
    ['POST', /^\/billing\/day-close$/, (r, req) => {
      const b = JSON.parse(req.postData()); st.posts.push(['close', b]);
      if (o.closeFail) { const p = problem(o.closeFail, o.closeFail === 'day-already-closed' ? `${TODAY} is already closed. Unlock it first to change anything.` : 'The cash count is off by more than 20.00. Add a note explaining the difference.');
        return r.fulfill({ status: p.status, contentType: 'application/problem+json', body: JSON.stringify(p.body) }); }
      const n = (d.close?.closeCount ?? 0) + 1;
      d.close = { status: 'closed', expectedCash: d.cash, countedCash: b.countedCash, variance: b.countedCash - d.cash, note: b.note ?? null, closedByName: 'Mona Manager',
        closedAt: `2026-10-20T1${n}:00:00Z`, reopenedByName: null, reopenedAt: null, reopenReason: null, closeCount: n };
      d.events.push({ id: `c${n}`, date: TODAY, action: 'close', expectedCash: d.cash, countedCash: b.countedCash, variance: b.countedCash - d.cash, note: b.note ?? null, actorName: 'Mona Manager', at: d.close.closedAt });
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(summary(d)) });
    }],
    ['POST', /^\/billing\/day-close\/reopen/, (r, req) => {
      const b = JSON.parse(req.postData()); st.posts.push(['reopen', b]);
      d.cash += 50;
      d.close = { ...d.close, status: 'reopened', reopenedByName: 'Mona Manager', reopenedAt: '2026-10-20T15:00:00Z', reopenReason: b.reason };
      d.events.push({ id: `r${d.events.length}`, date: TODAY, action: 'reopen', expectedCash: null, countedCash: null, variance: null, note: b.reason, actorName: 'Mona Manager', at: '2026-10-20T15:00:00Z' });
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(summary(d)) });
    }],
  ]);
  await s.page.goto(`${APP}/billing`);
  await s.page.getByRole('heading', { name: /Billing & Day Close/ }).waitFor({ timeout: 15000 });
  await s.page.waitForTimeout(500);
  return { ...s, st, d };
}

const startClose = async (page, counted) => {
  await page.getByLabel('Enter counted cash').fill(String(counted));
  await page.getByRole('button', { name: /^(Start day close|Close again)$/ }).click();
  return page.getByRole('dialog');
};

let s;
try {
  // AC3 UI: AED tenant shows AED amounts and the 20 tolerance; exactly 20.00 off needs no note, 20.01 does
  s = await open(APPROVER, { owner: true, roleName: 'Owner' });
  let dlg = await startClose(s.page, 120);
  const label = await dlg.locator('label[for="close-note"]').innerText();
  check('U1 AED: variance of exactly 20.00 -> note optional, submit enabled', /optional/.test(label) && await dlg.getByRole('button', { name: 'Close the day' }).isEnabled(), label);
  await dlg.getByRole('button', { name: 'Cancel' }).click();
  dlg = await startClose(s.page, 120.01);
  const label2 = await dlg.locator('label[for="close-note"]').innerText();
  check('U2 AED: 20.01 off -> note required and names AED 20.00', /required/.test(label2) && /AED\s?20\.00/.test(label2), label2);
  check('U3 AED: submit disabled until the note is written', await dlg.getByRole('button', { name: 'Close the day' }).isDisabled());
  await dlg.locator('#close-note').fill('Float miscounted');
  check('U4 AED: note unlocks submit', await dlg.getByRole('button', { name: 'Close the day' }).isEnabled());
  const kpi = (await s.page.locator('main').innerText()).replace(/\s+/g, ' ');
  check('U5 AED: amounts shown in AED, not ₹/INR', /AED/.test(kpi) && !/₹/.test(kpi), kpi.slice(0, 200));
  check('U6 no console errors', noise(s.log).length === 0, noise(s.log).join(' | '));
  await s.browser.close();

  // AC1 UI: non-owner manager with billing:approve closes, unlocks, closes again; history lists both closes + unlock
  s = await open(APPROVER, { owner: false, roleName: 'Clinic Manager' });
  dlg = await startClose(s.page, 100);
  await dlg.getByRole('button', { name: 'Close the day' }).click();
  await s.page.getByText('Day closed', { exact: true }).waitFor({ timeout: 5000 });
  await s.page.getByRole('button', { name: 'Unlock day' }).click();
  const rdlg = s.page.getByRole('dialog');
  check('U7 unlock without a reason is not possible', await rdlg.getByRole('button', { name: 'Unlock day' }).isDisabled());
  await rdlg.getByRole('radio').first().check();
  await rdlg.getByRole('button', { name: 'Unlock day' }).click();
  await rdlg.waitFor({ state: 'detached', timeout: 5000 });
  check('U8 reopened day offers "Close again", not "Start day close"', await s.page.getByRole('button', { name: 'Close again' }).count() === 1
    && await s.page.getByRole('button', { name: 'Start day close' }).count() === 0);
  dlg = await startClose(s.page, 150);
  check('U9 re-close dialog is titled as a second close', /again/i.test(await dlg.getByRole('heading').innerText()));
  await dlg.getByRole('button', { name: 'Close the day' }).click();
  await s.page.getByText('Day closed', { exact: true }).waitFor({ timeout: 5000 });
  await s.page.waitForFunction(() => document.querySelectorAll('section[aria-label="Close history"] li').length === 3, null, { timeout: 5000 }).catch(() => {});
  const items = await s.page.locator('section[aria-label="Close history"] li').count();
  const hist = (await s.page.locator('section[aria-label="Close history"]').innerText().catch(() => '')).replace(/\s+/g, ' ');
  check('U10 history shows 3 entries (close, unlock, close) after re-close', items === 3, hist);
  check('U11 re-close posted the recomputed count, both closes keep their own expected cash', s.st.posts.filter(([k]) => k === 'close').length === 2 && /150/.test(hist) && /100/.test(hist), hist);
  await s.browser.close();

  // server refusals are shown, not swallowed: DAY_ALREADY_CLOSED (another owner won the race) and VARIANCE_NOTE_REQUIRED
  for (const [fail, re] of [['day-already-closed', /already closed/], ['variance-note-required', /Add a note/]]) {
    s = await open(APPROVER, { owner: true }, { closeFail: fail });
    dlg = await startClose(s.page, 100);
    await dlg.getByRole('button', { name: 'Close the day' }).click();
    const msg = await s.page.getByRole('alert').filter({ hasText: re }).first().innerText({ timeout: 5000 }).catch(() => '');
    check(`U12 ${fail}: server detail shown to the user`, re.test(msg), msg);
    await s.browser.close();
  }

  // permission absent: billing:view only -> no close / unlock / counted cash, picker and history still there
  s = await open(['billing:view'], { owner: false }, { close: { status: 'closed', expectedCash: 100, countedCash: 100, variance: 0, note: null, closedByName: 'Mona', closedAt: '2026-10-20T18:00:00Z', reopenedByName: null, reopenedAt: null, reopenReason: null, closeCount: 1 },
    events: [{ id: 'a', date: TODAY, action: 'close', expectedCash: 100, countedCash: 100, variance: 0, note: null, actorName: 'Mona', at: '2026-10-20T18:00:00Z' }] });
  check('U13 billing:view only: no Start / Close again / Unlock controls', await s.page.getByRole('button', { name: /Start day close|Close again|Unlock day/ }).count() === 0);
  check('U14 billing:view only: no counted-cash input', await s.page.getByLabel('Enter counted cash').count() === 0);
  check('U15 billing:view only: history visible', await s.page.locator('section[aria-label="Close history"] li').count() === 1);
  await s.browser.close();

  s = await open(APPROVER, { owner: true }, { viewport: { width: 390, height: 844 } });
  check('U16 390px: no horizontal scroll', await s.page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));
} catch (e) { check('run', false, String(e).split('\n').slice(0, 3).join(' ')); }
finally { if (s) await s.browser.close().catch(() => {}); }
const bad = results.filter((r) => !r.ok);
console.log(`${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
