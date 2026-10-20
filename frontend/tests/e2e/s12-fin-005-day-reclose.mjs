// FIN-005 / J13: owner closes the day, unlocks it with a reason, closes it again; both closes show in the history.
// Past-day picker, permission-absent, empty / error history, 390px, zero console errors.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3703 node tests/e2e/s12-fin-005-day-reclose.mjs
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const TODAY = '2026-10-20';
const YESTERDAY = '2026-10-19';
const OWNER = ['billing:view', 'billing:create', 'billing:edit', 'billing:approve'];
const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];

function summary(date, cash, close) {
  return {
    date, today: date === TODAY, currency: 'INR', taxLabel: 'GST', cashTolerance: 200,
    totals: { billed: 1000, bills: 1, collected: cash, payments: 1, tax: 0 },
    methods: [{ method: 'cash', amount: cash, transactions: 1 }, { method: 'card', amount: 0, transactions: 0 },
      { method: 'upi', amount: 0, transactions: 0 }, { method: 'other', amount: 0, transactions: 0 }],
    outstanding: { amount: 1000 - cash, patients: 1, bills: 1 }, pendingCheckouts: 0, unbilled: [], taxBands: [], splitPaymentBills: 0,
    voidedCount: 0, voidedTotal: 0, close,
  };
}

/** Stateful server for one or more days: close / reopen / re-close, history newest first. */
async function open(perms, opts = {}) {
  const s = await launch({ viewport: opts.viewport });
  await seedClinic(s.page, perms, { tenantRegion: 'IN', owner: true, roleName: 'Owner' });
  const days = { [TODAY]: { cash: 500, close: null, events: [] }, [YESTERDAY]: { cash: 0, close: null, events: [] }, ...(opts.days ?? {}) };
  const st = { posts: [], gets: [] };
  let seq = 0;
  const at = () => `2026-10-20T1${seq}:00:00Z`;
  await mockApi(s.page, s.log, [
    ['GET', /^\/billing\/otc-charges/, { status: 409, body: { type: 'otc-unavailable', title: 'x', detail: 'x' } }],
    ['GET', /^\/billing\/day-close\/history\?(.*)$/, (r, req, m) => {
      const q = new URLSearchParams(m[1]);
      st.gets.push(`history ${q.get('from')}..${q.get('to')}`);
      if (opts.historyError) return r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x', title: 'x', detail: 'x' }) });
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ items: [...days[q.get('from')].events].reverse(), nextCursor: null }) });
    }],
    ['GET', /^\/billing\/day-close(?:\?date=([\d-]+))?$/, (r, req, m) => {
      const d = m[1] ?? TODAY;
      st.gets.push(`summary ${d}`);
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(summary(d, days[d].cash, days[d].close)) });
    }],
    ['POST', /^\/billing\/day-close$/, (r, req) => {
      const b = JSON.parse(req.postData());
      st.posts.push({ path: 'close', b });
      const d = days[b.date];
      const n = (d.close?.closeCount ?? 0) + 1;
      seq++;
      d.close = { status: 'closed', expectedCash: d.cash, countedCash: b.countedCash, variance: b.countedCash - d.cash, note: b.note, closedByName: 'Omar Owner',
        closedAt: at(), reopenedByName: null, reopenedAt: null, reopenReason: null, closeCount: n };
      d.events.push({ id: `e${seq}`, date: b.date, action: 'close', expectedCash: d.cash, countedCash: b.countedCash, variance: b.countedCash - d.cash, note: b.note, actorName: 'Omar Owner', at: at() });
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(summary(b.date, d.cash, d.close)) });
    }],
    ['POST', /^\/billing\/day-close\/reopen/, (r, req) => {
      const b = JSON.parse(req.postData());
      st.posts.push({ path: 'reopen', b });
      const d = days[b.date];
      d.cash += 300; // a cash payment taken while unlocked: the re-close must count it
      seq++;
      d.close = { ...d.close, status: 'reopened', reopenedByName: 'Omar Owner', reopenedAt: at(), reopenReason: b.reason };
      d.events.push({ id: `e${seq}`, date: b.date, action: 'reopen', expectedCash: null, countedCash: null, variance: null, note: b.reason, actorName: 'Omar Owner', at: at() });
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(summary(b.date, d.cash, d.close)) });
    }],
  ]);
  await s.page.goto(`${APP}/billing`);
  await s.page.getByRole('heading', { name: /Billing & Day Close/ }).waitFor({ timeout: 15000 });
  return { ...s, st, days };
}

const historyRows = (page) => page.locator('section[aria-label="Close history"] li');

async function closeDay(page, counted) {
  await page.getByLabel('Enter counted cash').fill(String(counted));
  await page.getByRole('button', { name: /^(Start day close|Close again)$/ }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Close the day' }).click();
  await page.getByText('Day closed', { exact: true }).waitFor({ timeout: 5000 });
}

async function unlock(page) {
  await page.getByRole('button', { name: 'Unlock day' }).click();
  const dlg = page.getByRole('dialog');
  await dlg.getByLabel('Missing bill found afterwards').check();
  await dlg.getByRole('button', { name: 'Unlock day' }).click();
  await dlg.waitFor({ state: 'detached', timeout: 5000 });
}

try {
  { // J13 happy: close -> reopen -> close again, both closes visible, expected cash recomputed
    const { page, st, log, browser } = await open(OWNER);
    await page.getByText("This day hasn't been closed yet.").waitFor({ timeout: 5000 });
    check('empty: history says the day was never closed', true);
    await closeDay(page, 500);
    check('J13: first close posted with today\'s date', st.posts[0]?.path === 'close' && st.posts[0].b.date === TODAY && st.posts[0].b.countedCash === 500, JSON.stringify(st.posts[0]));
    await unlock(page);
    check('J13: reopen posted with reason and date', st.posts[1]?.path === 'reopen' && st.posts[1].b.date === TODAY && st.posts[1].b.reason === 'Missing bill found afterwards');
    const again = page.getByRole('button', { name: 'Close again' });
    check('J13: reopened day offers "Close again"', await again.isVisible());
    await closeDay(page, 800);
    check('J13: re-close posted', st.posts[2]?.path === 'close' && st.posts[2].b.countedCash === 800);
    await page.waitForFunction(() => document.querySelectorAll('section[aria-label="Close history"] li').length === 3, null, { timeout: 5000 });
    const actions = await historyRows(page).evaluateAll((els) => els.map((e) => e.getAttribute('data-action')));
    check('J13: history shows both closes and the unlock, newest first', JSON.stringify(actions) === '["close","reopen","close"]', JSON.stringify(actions));
    const txt = (await page.locator('section[aria-label="Close history"]').innerText()).replace(/\s+/g, ' ');
    check('J13: both closes keep their own expected cash (800 recomputed, 500 original)', /800\.00 \/ ₹800\.00/.test(txt) && /500\.00 \/ ₹500\.00/.test(txt), txt);
    check('J13: unlock reason visible in history', txt.includes('Missing bill found afterwards'));
    const recon = (await page.locator('section[aria-label="Cash reconciliation"]').innerText()).replace(/\s+/g, ' ');
    check('J13: reconciliation shows recomputed expected cash 800', /Expected cash ₹800\.00/.test(recon), recon);
    check('J13: no console errors', noise(log).length === 0, JSON.stringify(noise(log)));
    dumpLog('j13', log); await browser.close();
  }
  { // boundary: a reopened PAST day — the picker loads it, "Close again" posts that date; future days are not pickable
    const reopenedYesterday = { cash: 0, close: { status: 'reopened', expectedCash: 0, countedCash: 0, variance: 0, note: null, closedByName: 'Omar Owner',
      closedAt: '2026-10-19T20:00:00Z', reopenedByName: 'Omar Owner', reopenedAt: '2026-10-20T08:00:00Z', reopenReason: 'Cash count entered incorrectly', closeCount: 1 },
      events: [{ id: 'y1', date: YESTERDAY, action: 'close', expectedCash: 0, countedCash: 0, variance: 0, note: null, actorName: 'Omar Owner', at: '2026-10-19T20:00:00Z' },
        { id: 'y2', date: YESTERDAY, action: 'reopen', expectedCash: null, countedCash: null, variance: null, note: 'Cash count entered incorrectly', actorName: 'Omar Owner', at: '2026-10-20T08:00:00Z' }] };
    const { page, st, log, browser } = await open(OWNER, { days: { [YESTERDAY]: reopenedYesterday } });
    const picker = page.getByLabel('Day to view');
    check('boundary: picker max is today (no future close)', (await picker.getAttribute('max')) === TODAY);
    await picker.fill(YESTERDAY);
    await page.getByRole('button', { name: 'Close again' }).waitFor({ timeout: 5000 });
    check('past day: summary and history fetched for that date', st.gets.includes(`summary ${YESTERDAY}`) && st.gets.includes(`history ${YESTERDAY}..${YESTERDAY}`), JSON.stringify(st.gets));
    await closeDay(page, 0);
    check('past day: re-close posted with the picked date', st.posts.at(-1)?.b.date === YESTERDAY, JSON.stringify(st.posts.at(-1)));
    const three = await page.waitForFunction(() => document.querySelectorAll('section[aria-label="Close history"] li').length === 3, null, { timeout: 5000 }).then(() => true, () => false);
    check('past day: history now has two closes and the unlock', three);
    check('past day: no console errors', noise(log).length === 0, JSON.stringify(noise(log)));
    await browser.close();
  }
  { // permission absent: billing:view only -> no close / close again / unlock controls, history still shown
    const closedDays = { [TODAY]: { cash: 500, close: { status: 'reopened', expectedCash: 500, countedCash: 500, variance: 0, note: null, closedByName: 'Omar Owner',
      closedAt: '2026-10-20T18:00:00Z', reopenedByName: 'Omar Owner', reopenedAt: '2026-10-20T19:00:00Z', reopenReason: 'x', closeCount: 1 },
      events: [{ id: 'a', date: TODAY, action: 'close', expectedCash: 500, countedCash: 500, variance: 0, note: null, actorName: 'Omar Owner', at: '2026-10-20T18:00:00Z' }] } };
    const { page, log, browser } = await open(['billing:view'], { days: closedDays });
    await historyRows(page).first().waitFor({ timeout: 5000 });
    const n = await page.getByRole('button', { name: /Start day close|Close again|Unlock day/ }).count();
    check('no billing:approve: close / close again / unlock absent', n === 0, String(n));
    check('no billing:approve: counted-cash entry absent', (await page.getByLabel('Enter counted cash').count()) === 0);
    check('no billing:approve: history visible', (await historyRows(page).count()) === 1);
    check('no billing:approve: no console errors', noise(log).length === 0, JSON.stringify(noise(log)));
    await browser.close();
  }
  { // error: history fails -> visible alert, page still usable
    const { page, browser } = await open(OWNER, { historyError: true });
    await page.getByText("Couldn't load the close history.").waitFor({ timeout: 5000 });
    check('error: history failure shown as an alert', await page.getByRole('alert').filter({ hasText: 'close history' }).isVisible());
    check('error: close action still available', await page.getByRole('button', { name: 'Start day close' }).isVisible());
    await browser.close();
  }
  { // 390px: no horizontal scroll with the picker and history
    const { page, browser } = await open(OWNER, { viewport: { width: 390, height: 800 } });
    await page.getByText("This day hasn't been closed yet.").waitFor({ timeout: 5000 });
    check('390: no horizontal scroll', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
    await browser.close();
  }
} catch (e) { check('script ran without throwing', false, String(e)); }
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
