// S15 ACC-023: public /display (pairing, tokens-only projection, liveness stale 60 s / lost 180 s with auto recovery, en+hi/ar, 390px).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3805 node s15-acc023-lobby-display.mjs
import { launch, check, results, sleep, dumpLog, CORS, APP } from './qa-lib.mjs';
const API_RE = /^(http:\/\/localhost:8080|https:\/\/nabd-f0zb\.onrender\.com)\/v1\/display\//;
const proj = (languages = ['en', 'hi']) => ({ v: 1, languages, doctors: [
  { name: 'Dr Asha Rao', nowServing: 12, next: [13, 14, 15], delayMinutes: 15 },
  { name: 'Dr Bob Lee', nowServing: null, next: [], delayMinutes: null } ] });
const sse = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

async function run(tag, { token = 'tenant.dev.secret', pair, stream, languages, viewport, clock } = {}, fn) {
  const { browser, context, page, log } = await launch({ viewport });
  const st = { streamCalls: 0, pairBodies: [], auth: [], urls: [], mode: 'ok' };
  try {
    if (token) await context.addInitScript((t) => { try { localStorage.setItem('nabd_display_token', t); } catch {} }, token);
    if (clock) await page.clock.install();
    await page.route(API_RE, async (route) => {
      const req = route.request(); const p = new URL(req.url()).pathname;
      st.urls.push(req.url());
      if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
      if (p.endsWith('/display/pair')) { st.pairBodies.push(JSON.parse(req.postData())); return pair ? route.fulfill({ headers: CORS, ...pair }) : route.fulfill({ headers: CORS, status: 200, contentType: 'application/json', body: JSON.stringify({ deviceToken: 'tenant.dev.secret', scope: null }) }); }
      if (p.endsWith('/display/stream')) {
        st.streamCalls++; st.auth.push(req.headers()['authorization']);
        if (st.mode === 'down') return route.abort('failed');
        if (stream) return route.fulfill({ headers: CORS, ...stream });
        return route.fulfill({ headers: { ...CORS, 'content-type': 'text/event-stream' }, status: 200, body: sse('projection', proj(languages)) });
      }
      return route.fulfill({ headers: CORS, status: 404, body: '' });
    });
    await page.goto(APP + '/display'); await sleep(1500);
    await fn({ page, log, st });
    check(`${tag}: zero page errors`, log.pageErrors.length === 0, JSON.stringify(log.pageErrors));
  } finally { dumpLog(tag, log); await browser.close(); }
}

await run('paired: token-only projection', {}, async ({ page, st }) => {
  const t = await page.locator('body').innerText();
  check('doctor names, now-serving and next tokens render', /Dr Asha Rao/.test(t) && /T-12/.test(t) && /T-13/.test(t) && /T-15/.test(t));
  check('delay notice in English and Hindi', /Running about 15 min late/.test(t) && /लगभग 15 मिनट की देरी/.test(t));
  check('English and Hindi labels together', /Now serving/.test(t) && /अभी देखे जा रहे हैं/.test(t));
  check('page is left-to-right for en+hi', (await page.evaluate(() => getComputedStyle(document.querySelector('main, body')).direction)) === 'ltr');
  check('the device token goes in the Authorization header, never in a URL', st.auth[0] === 'Display tenant.dev.secret' && st.urls.every((u) => !u.includes('secret')), JSON.stringify(st.auth));
  check('no patient name or phone appears', !/\+\d{8,}|MRN/.test(t));
});
await run('Gulf: English + Arabic, right-to-left', { languages: ['en', 'ar'] }, async ({ page }) => {
  const t = await page.locator('body').innerText();
  check('Arabic delay notice shown', /تأخير حوالي 15 دقيقة/.test(t));
  check('direction is rtl', await page.evaluate(() => [...document.querySelectorAll('[lang=ar]')].length > 0 && [...document.querySelectorAll('[lang=ar]')].every((e) => getComputedStyle(e).direction === 'rtl') && [...document.querySelectorAll('[lang=en]')].every((e) => getComputedStyle(e).direction === 'ltr')));
});
await run('unpaired: pairing form, wrong then right code', { token: null, pair: { status: 400, contentType: 'application/problem+json', body: JSON.stringify({ code: 'INVALID_CODE', title: 'Invalid code' }) } }, async ({ page, st }) => {
  check('pairing screen shown, no stream call', /Pair this screen/.test(await page.locator('body').innerText()) && st.streamCalls === 0);
  await page.getByLabel('Clinic').fill('demo'); await page.getByLabel('Pairing code').fill('123456');
  await page.getByRole('button', { name: /Pair/ }).click(); await sleep(800);
  check('body is {clinic, code}', st.pairBodies[0]?.clinic === 'demo' && st.pairBodies[0]?.code === '123456', JSON.stringify(st.pairBodies));
  check('wrong code message', /wrong or has expired/.test(await page.locator('body').innerText()));
});
await run('locked out: 429', { token: null, pair: { status: 429, contentType: 'application/problem+json', body: JSON.stringify({ code: 'LOCKED', title: 'Locked' }) } }, async ({ page }) => {
  await page.getByLabel('Clinic').fill('demo'); await page.getByLabel('Pairing code').fill('000000');
  await page.getByRole('button', { name: /Pair/ }).click(); await sleep(800);
  check('lockout message (15 minutes)', /Too many wrong codes/.test(await page.locator('body').innerText()));
});
await run('revoked: 401 DEVICE_REVOKED returns to pairing', { stream: { status: 401, contentType: 'application/problem+json', body: JSON.stringify({ code: 'DEVICE_REVOKED', title: 'Revoked' }) } }, async ({ page }) => {
  await sleep(1500);
  check('pairing screen again and the token is cleared', /Pair this screen/.test(await page.locator('body').innerText()) && (await page.evaluate(() => localStorage.getItem('nabd_display_token'))) === null);
});
await run('revoked event on the stream', { stream: { status: 200, headers: { 'content-type': 'text/event-stream' }, body: sse('projection', proj()) + sse('revoked', {}) } }, async ({ page }) => {
  await sleep(1500);
  check('screen unpairs on the revoked event', /Pair this screen/.test(await page.locator('body').innerText()));
});
await run('liveness: stale at 60 s, lost at 180 s, recovers by itself', { clock: true }, async ({ page, st }) => {
  check('live at first: no banner', (await page.getByTestId('liveness').count()) === 0);
  st.mode = 'down';
  await page.clock.fastForward(30_000); await sleep(300);
  check('still live at 30 s', (await page.getByTestId('liveness').count()) === 0);
  await page.clock.fastForward(35_000); await sleep(300);
  check('stale banner after 60 s', /Not updated recently/.test(await page.getByTestId('liveness').innerText()));
  await page.clock.fastForward(125_000); await sleep(300);
  check('connection lost after 180 s', /Connection lost/.test(await page.getByTestId('liveness').innerText()));
  st.mode = 'ok';
  for (let i = 0; i < 8; i++) { await page.clock.fastForward(3_000); await sleep(400); }
  check('banner clears when the stream comes back', (await page.getByTestId('liveness').count()) === 0 && /T-12/.test(await page.locator('body').innerText()));
});
await run('390px', { viewport: { width: 390, height: 800 } }, async ({ page }) => {
  const m = await page.evaluate(() => ({ s: document.documentElement.scrollWidth, c: document.documentElement.clientWidth }));
  check('390px: no horizontal scroll', m.s <= m.c, JSON.stringify(m));
});

const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
