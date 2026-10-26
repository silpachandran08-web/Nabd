// FEF-061: patient-web secure links. Run against `next start` of apps/patient-web (default http://localhost:3810):
//   NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3810 node s15-fef-061-patient-links.mjs
// The API (patient-edge / exchange) is mocked at http://localhost:8080/v1 and https://nabd-f0zb.onrender.com/v1.
import { launch, mockApi, check, results, dumpLog, CORS } from './qa-lib.mjs';

const APP = process.env.APP ?? 'http://localhost:3810';
const ID = { doc: 'docLink_0123456789ab', consent: 'consentLink_0123456789', booking: 'bookingLink_012345678', old: 'oldLink_0123456789abcd', gone: 'goneAfterOtp_01234567' };
const PDF = Buffer.from('%PDF-1.1\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF');
const CONSENT = { consentId: 'cns-0001', requester: 'Dr Asha Rao, Sunrise Clinic', categories: ['prescriptions', 'lab_reports', 'new_code_x'], from: '2025-01-01T00:00:00Z', to: '2025-12-31T00:00:00Z', purpose: 'second_opinion', validUntil: '2026-12-01T00:00:00Z', artefactHash: 'sha256-aaa' };
const BOOKING = { doctorName: 'Dr Meera Nair', clinicName: 'Sunrise Clinic', address: 'MG Road', startsAt: '2026-11-05T04:30:00Z', timeZone: 'Asia/Kolkata', status: 'confirmed' };
import { readFileSync } from 'node:fs';
const read = (l) => readFileSync(new URL(`../../../../packages/i18n/messages/${l}/patient.json`, import.meta.url), 'utf8');
const en = read('en');
const cat = (l) => JSON.parse(read(l));
const require_fs = read;

const state = { codeOk: '123456', decisions: [], changedOnce: false, otpRequests: 0 };
const json = (status, body, delay = 0) => ({ status, body, delay });
function handlers() {
  const H = [];
  const link = (re) => new RegExp(`^/patient-links/${re}`);
  H.push(['GET', link(`${ID.old}$`), json(410, { title: 'EXPIRED' })]);
  H.push(['GET', link('[A-Za-z0-9_-]+$'), json(200, { clinicName: 'Sunrise Clinic' }, 300)]);
  H.push(['POST', link('[A-Za-z0-9_-]+/otp$'), (r) => { state.otpRequests++; return r.fulfill({ status: 202, contentType: 'application/json', body: '{"resendAfterSeconds":30}' }); }]);
  H.push(['POST', link('[A-Za-z0-9_-]+/otp/verify$'), (r, req) => JSON.parse(req.postData()).code === state.codeOk
    ? r.fulfill({ status: 200, contentType: 'application/json', body: '{"accessToken":"patient-session-token"}' })
    : r.fulfill({ status: 401, contentType: 'application/problem+json', body: '{"title":"INVALID_CODE"}' })]);
  H.push(['GET', link(`${ID.gone}/content$`), json(410, { title: 'EXPIRED' })]);
  H.push(['GET', link(`${ID.doc}/content$`), json(200, { kind: 'document', contentType: 'application/pdf', fileName: 'rx.pdf' })]);
  H.push(['GET', link(`${ID.doc}/document$`), (r) => r.fulfill({ status: 200, contentType: 'application/pdf', body: PDF })]);
  H.push(['GET', link(`${ID.consent}/content$`), json(200, { kind: 'consent', consent: CONSENT })]);
  H.push(['GET', link(`${ID.booking}/content$`), json(200, { kind: 'booking', booking: BOOKING })]);
  H.push(['POST', /^\/exchange\/consents\/cns-0001\/patient-decision$/, (r, req) => {
    const body = JSON.parse(req.postData());
    state.decisions.push({ auth: req.headers()['authorization'], key: req.headers()['idempotency-key'], ...body });
    if (state.changedOnce) { state.changedOnce = false; return r.fulfill({ status: 409, contentType: 'application/problem+json', body: '{"title":"ARTEFACT_CHANGED"}' }); }
    return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: body.decision === 'approve' ? 'approved' : 'declined' }) });
  }]);
  return H;
}

async function open(path, { viewport = { width: 390, height: 844 }, cdp } = {}) {
  const s = await launch({ viewport });
  await mockApi(s.page, s.log, handlers());
  s.responses = [];
  s.page.on('response', (res) => { if (res.url().startsWith(APP)) s.responses.push({ url: res.url(), cache: res.headers()['cache-control'] ?? null, type: res.request().resourceType() }); });
  await s.page.goto(`${APP}${path}`, { waitUntil: 'domcontentloaded' });
  return s;
}
const finish = async (tag, s) => { dumpLog(tag, s.log); await s.browser.close(); };
const noScroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
const consoleClean = (s) => s.log.pageErrors.length === 0 && s.log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0;
async function verifyOtp(page, code = state.codeOk) {
  await page.getByRole('button', { name: /^(Send code)$/ }).click();
  await page.locator('#otp').fill(code);
  await page.getByRole('button', { name: 'Continue' }).click();
}

// ---- 1. OTP page in four languages: whole page in that language, lang attribute, 390px no scroll
for (const l of ['en', 'hi', 'kn', 'te']) {
  const m = JSON.parse(l === 'en' ? en : require_fs(l));
  const s = await open(`/l/${ID.doc}?lang=${l}`);
  await s.page.locator('h1').waitFor();
  check(`otp-page-${l}`, (await s.page.locator('h1').innerText()) === m.otpTitle && (await s.page.getAttribute('html', 'lang')) === l, await s.page.locator('h1').innerText());
  check(`otp-hint-${l}-has-clinic`, (await s.page.locator('section p').first().innerText()) === m.otpHint.replace('{clinic}', 'Sunrise Clinic'));
  check(`otp-no-horizontal-scroll-${l}`, await noScroll(s.page));
  check(`otp-console-clean-${l}`, consoleClean(s));
  await finish(`otp-${l}`, s);
}

// ---- 2. Telugu: the Noto Sans Telugu face is declared for the page and actually loaded
{
  const m = JSON.parse(require_fs('te'));
  const s = await open(`/l/${ID.consent}?lang=te`);
  await s.page.locator('h1').waitFor();
  await s.page.evaluate(() => document.fonts.ready);
  const faces = await s.page.evaluate(async () => { await Promise.all([...document.fonts].map((f) => f.load().catch(() => null))); return [...document.fonts].map((f) => ({ range: f.unicodeRange, status: f.status })); });
  check('te-telugu-font-loaded', faces.some((f) => /U\+0?C00-0?C7F/i.test(f.range) && f.status === 'loaded'), JSON.stringify(faces.map((f) => f.status)));
  const family = await s.page.evaluate(() => getComputedStyle(document.body).fontFamily);
  check('te-body-font-is-telugu-first', /telu/i.test(family.split(',')[0]), family.split(',')[0]);
  check('te-page-is-telugu', (await s.page.locator('h1').innerText()) === m.otpTitle);
  await verifyOtp(s.page).catch(() => {});
  await finish('te', s);
}

// ---- 3. Consent: gated by OTP, plain-language summary in the locale, decision with the OTP-bound token
{
  const m = JSON.parse(require_fs('te'));
  const s = await open(`/l/${ID.consent}?lang=te`);
  await s.page.locator('h1').waitFor();
  check('consent-approve-absent-before-otp', (await s.page.getByRole('button', { name: m.approve }).count()) === 0 && (await s.page.getByText('Dr Asha Rao').count()) === 0);
  await s.page.getByRole('button', { name: m.otpSend }).click();
  await s.page.locator('#otp').fill('000000');
  await s.page.getByRole('button', { name: m.otpVerify }).click();
  await s.page.locator('main [role=alert]').waitFor();
  check('wrong-otp-shows-error-and-stays-gated', (await s.page.locator('main [role=alert]').innerText()) === m.otpWrong && (await s.page.getByRole('button', { name: m.approve }).count()) === 0);
  await s.page.locator('#otp').fill(state.codeOk);
  await s.page.getByRole('button', { name: m.otpVerify }).click();
  await s.page.getByRole('button', { name: m.approve }).waitFor();
  const text = await s.page.locator('main').innerText();
  check('consent-shows-requester', text.includes('Dr Asha Rao, Sunrise Clinic'));
  check('consent-categories-in-telugu', text.includes(m.cat_prescriptions) && text.includes(m.cat_lab_reports) && text.includes('new code x'));
  check('consent-purpose-in-telugu', text.includes(m.purpose_second_opinion));
  check('consent-range-and-validity', /2025/.test(text) && /2026/.test(text) && text.includes(m.consentRange) && text.includes(m.consentValid));
  check('consent-no-horizontal-scroll', await noScroll(s.page));
  // artefact changed: 409 -> explained, reloadable, decision not recorded as approved
  state.changedOnce = true;
  await s.page.getByRole('button', { name: m.approve }).click();
  await s.page.getByText(m.consentChangedTitle).waitFor();
  check('consent-409-artefact-changed-message', (await s.page.getByRole('button', { name: m.consentReload }).count()) === 1 && (await s.page.getByText(m.consentApproved).count()) === 0);
  await s.page.getByRole('button', { name: m.consentReload }).click();
  await s.page.getByRole('button', { name: m.approve }).waitFor();
  await s.page.getByRole('button', { name: m.approve }).click();
  await s.page.getByText(m.consentApproved).waitFor();
  const d = state.decisions;
  check('decision-sends-bearer-hash-and-idempotency-key', d.length === 2 && d.every((x) => x.auth === 'Bearer patient-session-token' && x.artefactHash === 'sha256-aaa' && x.decision === 'approve' && /^[0-9a-f-]{36}$/.test(x.key)));
  // a reloaded (changed) artefact is a new decision: it must not replay the key whose cached answer was the 409
  check('changed-artefact-gets-a-fresh-idempotency-key', d[0].key !== d[1].key);
  check('buttons-gone-after-decision', (await s.page.getByRole('button', { name: m.decline }).count()) === 0);
  // storage holds nothing
  const store = await s.page.evaluate(() => [JSON.stringify({ ...localStorage }), JSON.stringify({ ...sessionStorage }), document.cookie].join('|'));
  check('token-not-stored', !store.includes('patient-session-token') && store === '{}|{}|', store);
  check('url-is-only-the-opaque-id', new URL(s.page.url()).pathname === `/l/${ID.consent}`);
  check('consent-console-clean', consoleClean(s));
  await finish('consent', s);
}

// ---- 4. Decline path
{
  const s = await open(`/l/${ID.consent}`);
  await verifyOtp(s.page);
  await s.page.getByRole('button', { name: 'Decline' }).click();
  await s.page.getByText(cat('en').consentDeclined).waitFor();
  check('decline-recorded', state.decisions.at(-1).decision === 'decline');
  await finish('decline', s);
}

// ---- 5. Document: renders after OTP (blob preview), light theme default, dark on request
{
  const s = await open(`/l/${ID.doc}`);
  check('theme-light-by-default', (await s.page.getAttribute('html', 'data-theme')) === 'light');
  await verifyOtp(s.page);
  await s.page.locator('iframe').waitFor();
  const src = await s.page.locator('iframe').getAttribute('src');
  check('document-renders-from-blob-url', src?.startsWith('blob:'), src?.slice(0, 20));
  check('document-save-link', (await s.page.getByRole('link', { name: 'Open or save' }).count()) === 1);
  check('document-no-horizontal-scroll', await noScroll(s.page));
  check('document-console-clean', consoleClean(s));
  const bad = s.responses.filter((r) => r.type !== 'font' && !/\/_next\/(static|image)\//.test(r.url) && r.cache !== 'no-store, max-age=0');
  check('every-non-static-response-is-no-store', bad.length === 0, JSON.stringify(bad.slice(0, 3)));
  const urls = s.log.requests.map((r) => r.path).concat(s.responses.map((r) => new URL(r.url).pathname));
  check('urls-carry-only-opaque-ids', urls.every((u) => !/rx\.pdf|prescription|sunrise|patient-session-token/i.test(u)), urls.join(' ').slice(0, 200));
  await finish('document', s);
  const dark = await open(`/l/${ID.doc}?theme=dark`);
  check('theme-dark-on-request', (await dark.page.getAttribute('html', 'data-theme')) === 'dark');
  await finish('dark', dark);
}

// ---- 6. Expiry: the link is old, or it expires after the OTP; same text, no document type
{
  const m = cat('en');
  for (const [name, id, verify] of [['old-link', ID.old, false], ['expired-after-otp', ID.gone, true], ['malformed-id', 'short', false]]) {
    const s = await open(`/l/${id}`);
    if (verify) await verifyOtp(s.page);
    await s.page.getByRole('heading', { name: m.expiredTitle }).waitFor();
    const text = await s.page.locator('main').innerText();
    check(`${name}-says-expired-and-resend`, text.includes(m.expiredTitle) && text.includes(m.expiredHint) && /ask the clinic to resend/i.test(text));
    check(`${name}-reveals-no-document-type`, !/prescription|report|invoice|consent|appointment|booking|lab/i.test(text.replace(/Language/g, '')));
    check(`${name}-no-otp-or-content`, (await s.page.locator('#otp').count()) === 0 && (await s.page.getByRole('button', { name: /Approve|Send code/ }).count()) === 0);
    check(`${name}-console-clean`, consoleClean(s));
    await finish(name, s);
  }
}

// ---- 7. Booking confirmation
{
  const s = await open(`/l/${ID.booking}?lang=hi`);
  await s.page.getByRole('button', { name: cat('hi').otpSend }).click();
  await s.page.locator('#otp').fill(state.codeOk);
  await s.page.getByRole('button', { name: cat('hi').otpVerify }).click();
  await s.page.getByText('Dr Meera Nair').waitFor();
  const text = await s.page.locator('main').innerText();
  check('booking-shows-doctor-place-status', text.includes('MG Road') && text.includes(cat('hi').status_confirmed));
  check('booking-time-in-clinic-zone', /10:00|१०:००/.test(text), text.match(/\d+:\d+/)?.[0]);
  check('booking-no-horizontal-scroll', await noScroll(s.page));
  await finish('booking', s);
}

// ---- 8. API down: visible failure with retry, then recovers
{
  const s = await launch({ viewport: { width: 390, height: 844 } });
  let fail = true;
  await mockApi(s.page, s.log, [['GET', /^\/patient-links\//, (r) => (fail ? r.fulfill({ status: 503, contentType: 'application/problem+json', body: '{"title":"x"}' }) : r.fulfill({ status: 200, contentType: 'application/json', body: '{"clinicName":"Sunrise Clinic"}' }))]]);
  await s.page.goto(`${APP}/l/${ID.doc}`);
  await s.page.locator('main [role=alert]').waitFor();
  check('api-down-shows-error-with-retry', (await s.page.getByRole('button', { name: 'Try again' }).count()) === 1);
  fail = false;
  await s.page.getByRole('button', { name: 'Try again' }).click();
  await s.page.getByRole('button', { name: 'Send code' }).waitFor();
  check('retry-recovers', true);
  await finish('down', s);
}

// ---- 9. Landing page without a link
{
  const s = await open('/');
  check('landing-says-open-your-link', (await s.page.locator('h1').innerText()) === cat('en').landingTitle);
  await finish('landing', s);
}

// ---- 10. Performance on a throttled phone: JS gz < 150 KB, LCP < 3 s (Lighthouse "mobile" network: 1.6 Mbps, 150 ms RTT; 4x CPU)
{
  const s = await launch({ viewport: { width: 360, height: 640 } });
  await mockApi(s.page, s.log, handlers());
  const cdp = await s.context.newCDPSession(s.page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 150, downloadThroughput: (1638 * 1024) / 8, uploadThroughput: (675 * 1024) / 8 });
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  const scripts = new Map();
  cdp.on('Network.responseReceived', (e) => { if (e.type === 'Script') scripts.set(e.requestId, e.response.url); });
  let jsBytes = 0;
  cdp.on('Network.loadingFinished', (e) => { if (scripts.has(e.requestId)) jsBytes += e.encodedDataLength; });
  await s.page.addInitScript(() => { window.__lcp = 0; new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__lcp = e.startTime; }).observe({ type: 'largest-contentful-paint', buffered: true }); });
  await s.page.goto(`${APP}/l/${ID.doc}`, { waitUntil: 'load' });
  await s.page.getByRole('button', { name: 'Send code' }).waitFor();
  await s.page.waitForTimeout(500);
  const lcp = await s.page.evaluate(() => window.__lcp);
  check('perf-total-js-under-150KB-gzip', jsBytes < 150 * 1024, `${(jsBytes / 1024).toFixed(1)} KB over ${scripts.size} scripts`);
  check('perf-lcp-under-3s-on-throttled-3g-class', lcp > 0 && lcp < 3000, `${Math.round(lcp)} ms`);
  await finish('perf', s);
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
