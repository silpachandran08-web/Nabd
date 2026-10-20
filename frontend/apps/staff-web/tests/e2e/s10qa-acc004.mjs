// S10 QA ACC-004 (independent): booking refusal copy, nearest-slot hint, J3 two browsers same slot (shared in-process mock "server"). APP=http://localhost:3411
import { chromium } from '/home/claude/ws/qa/node_modules/playwright/index.mjs';
import { launch, seedClinic, mockApi, check, results } from './qa-lib.mjs';
const APP = process.env.APP ?? 'http://localhost:3411';
const D1 = '33333333-3333-3333-3333-333333333331', P1 = '44444444-4444-4444-4444-444444444441', DAY = '2026-10-19';
const S = ['06:00', '06:30', '07:00', '07:30', '08:00'].map((t) => `${DAY}T${t}:00Z`);
const RAW = 'RAW-SERVER-TEXT';
const FULL = ['queue:view', 'queue:create', 'queue:edit', 'patients:view', 'patients:create'];
const day = { date: DAY, today: false, timezone: 'Asia/Riyadh', holiday: null, sessions: [], counts: { appointments: 0, walkIns: 0, freeSlots: 5 }, doctors: [{ id: D1, name: 'Dr. Hana', department: 'General', onLeave: false, leaveReason: null, worksToday: true }] };
const prob = (slug, status) => ({ status, body: { type: `https://api.nabd.health/errors/${slug}`, title: slug, status, detail: slug === 'clinic-closed' ? 'The clinic is closed on 2026-10-19.' : RAW, code: slug.toUpperCase().replace(/-/g, '_') } });
const ali = { id: P1, mrn: 'MRN-1', name: 'Ali Hassan', phone: '+966500000001' };
const defaults = (post, avail) => [['GET', /^\/schedule\/day/, { body: day }], ['GET', /^\/queue$/, { body: [] }], ['GET', /^\/doctors\/[^/]+\/availability/, avail ?? { body: S }],
  ['GET', /^\/patients\?q=ali/, { body: { data: [ali], page: { nextCursor: null } } }], ['GET', /^\/patients\?q=/, { body: { data: [], page: { nextCursor: null } } }], ['POST', /^\/appointments$/, post]];
async function scenario(id, post, fn, { viewport, avail } = {}) {
  const { browser, page, log } = await launch({ viewport });
  try { await seedClinic(page, FULL); await mockApi(page, log, defaults(post, avail)); await page.goto(`${APP}/schedule`, { waitUntil: 'domcontentloaded' }); await fn(page, log);
    check(`${id} zero page errors / console errors`, log.pageErrors.length === 0 && log.console.every((x) => /Failed to load resource/.test(x)), JSON.stringify([...log.pageErrors, ...log.console]).slice(0, 300));
  } catch (e) { check(`${id} scenario ran`, false, String(e).split('\n')[0]); } finally { await browser.close(); }
}
const open = async (page) => { await page.getByRole('button', { name: '+ New appointment' }).click(); const d = page.getByRole('dialog'); await d.locator('#appt-patient').fill('ali'); await d.getByRole('button', { name: /Ali Hassan/ }).click(); await d.locator('#appt-time').selectOption({ label: '10:00' }); return d; };
const book = (d) => d.getByRole('button', { name: /Book appointment|Booking/ });

const copies = {};
for (const [slug, st] of [['slot-taken', 409], ['session-full', 409], ['doctor-on-leave', 400], ['outside-hours', 400], ['off-grid', 400], ['in-past', 400], ['clinic-closed', 400]]) {
  await scenario(`E-${slug}`, prob(slug, st), async (page) => {
    const d = await open(page); await book(d).click(); const t = (await d.getByRole('alert').innerText()).trim(); copies[slug] = t;
    check(`E-${slug}: refusal has a short sentence, never the raw server text`, t.length > 8 && t.length < 160 && !t.includes(RAW), t);
  });
}
const distinct = new Set(Object.values(copies));
check('E-all: each Problem type maps to its own sentence (no generic 409 copy)', distinct.size >= 6, `${distinct.size} distinct of ${Object.keys(copies).length}`);

// J3: two browsers, one shared "server": first POST wins, second gets 409 and is offered the nearest free times
let taken = false; const posts = [];
const shared = (r) => { posts.push(Date.now()); if (!taken) { taken = true; return r.fulfill({ status: 201, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: '{"id":"a1"}' }); }
  return r.fulfill({ status: 409, contentType: 'application/problem+json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(prob('slot-taken', 409).body) }); };
const availShared = (r) => r.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(taken ? S.filter((s) => s !== S[2]) : S) });
const A = await launch(), B = await launch();
try {
  for (const w of [A, B]) { await seedClinic(w.page, FULL); await mockApi(w.page, w.log, defaults(shared, availShared)); await w.page.goto(`${APP}/schedule`, { waitUntil: 'domcontentloaded' }); }
  const dA = await open(A.page), dB = await open(B.page);
  await book(dA).click(); await dA.waitFor({ state: 'detached' });
  check('J3a first browser books the slot (dialog closes)', true);
  await book(dB).click(); await dB.getByRole('alert').waitFor({ timeout: 3000 });
  const t = await dB.getByRole('alert').innerText();
  check('J3b second browser sees "just taken" copy', /just taken/i.test(t), t);
  const alts = dB.locator('[aria-live="polite"] button'); await alts.first().waitFor({ timeout: 3000 });
  const labels = await alts.allInnerTexts();
  check('J3c nearest free slots offered and the taken 10:00 is not among them', labels.length >= 2 && !labels.includes('10:00'), labels.join(','));
  check('J3d exactly two POSTs, one success one refusal', posts.length === 2);
} catch (e) { check('J3 scenario ran', false, String(e).split('\n')[0]); } finally { await A.browser.close(); await B.browser.close(); }
await scenario('M390', { status: 201, body: { id: 'a1' } }, async (page) => {
  const d = await open(page);
  check('390px: dialog fits, no horizontal scroll', !(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)));
  const box = await book(d).boundingBox(); check('390px: Book button is >= 44px tall', box && box.height >= 44, JSON.stringify(box));
}, { viewport: { width: 390, height: 844 } });
const bad = results.filter((r) => !r.ok); console.log(`${results.length - bad.length}/${results.length} passed`); process.exit(bad.length ? 1 : 0);
