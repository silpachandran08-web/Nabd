// FEF-010: nonce CSP in ENFORCE mode blocks injected script, reports it, refuses foreign framing; first-party pages run clean.
// Run against `NABD_CSP_MODE=enforce next start` with its stdout in SERVER_LOG:
//   NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3506 SERVER_LOG=/path/next.log node tests/e2e/s11-fef-010-csp.mjs
import { readFileSync, mkdtempSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import https from 'node:https';
import http from 'node:http';
import { chromium } from '/home/claude/ws/qa/node_modules/playwright/index.mjs';
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const XSS = `<script>alert(1)</script><img src=x onerror="window.__xss=1">`;
const row = { id: 'p1', mrn: 'MRN-1', name: XSS, phone: '+919876543210', age: 30, gender: 'female', minor: false, lastVisit: null, nextAppointment: null,
  nextDoctorName: null, nextDoctorId: null, nextAppointmentId: null, nextAppointmentToday: false, packageSessionsUsed: null, packageSessionsTotal: null,
  condition: null, followUpDue: false, allergy: null, balanceDue: false, duplicate: false, archived: false, queueToken: null, queueStatus: null, tags: [] };
const api = (name) => [
  ['GET', /^\/patients\/registry/, { body: { total: 1, counts: { all: 1 }, rows: [{ ...row, name }], matchedTotal: 1, hasMore: false } }],
  ['GET', /^\/patients\/guardian-reviews-due/, { body: [] }],
];
// Record CSP violations the page sees, before any app script runs.
const watch = (page) => page.addInitScript(() => { window.__csp = []; document.addEventListener('securitypolicyviolation', (e) => window.__csp.push({ d: e.effectiveDirective, b: e.blockedURI, disp: e.disposition })); });
const cspNoise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const serverLog = () => { try { return readFileSync(process.env.SERVER_LOG, 'utf8'); } catch { return ''; } };

// 1. Header + nonce on a real navigation.
{
  const s = await launch();
  try {
    const a = await s.page.goto(APP + '/login');
    const b = await s.page.goto(APP + '/login');
    const ca = a.headers()['content-security-policy'] ?? '', cb = b.headers()['content-security-policy'] ?? '';
    const n = (c) => c.match(/'nonce-([^']+)'/)?.[1];
    check('enforce: Content-Security-Policy header (not report-only) on HTML', !!ca && !a.headers()['content-security-policy-report-only'], ca.slice(0, 80));
    check('fresh nonce per navigation', !!n(ca) && n(ca) !== n(cb));
    check('script-src has no unsafe-inline / unsafe-eval', !/unsafe-(inline|eval)/.test(ca.split(';').find((d) => d.trim().startsWith('script-src')) ?? 'x'));
    const scriptNonces = await s.page.evaluate(() => [...document.scripts].map((x) => x.nonce));
    check('every Next script carries the nonce', scriptNonces.length > 0 && scriptNonces.every((x) => x === n(cb)), `${scriptNonces.length} scripts`);
    check('login renders under enforce (form present)', await s.page.locator('form').count() > 0);
  } finally { dumpLog('headers', s.log); await s.browser.close(); }
}

// 2. First-party pages: zero CSP violations, zero console errors (clinic + platform + phone width).
for (const [tag, url, seed, viewport] of [['clinic /patients', '/patients', true], ['platform /platform/login', '/platform/login', false], ['390px /login', '/login', false, { width: 390, height: 844 }]]) {
  const s = await launch({ viewport });
  try {
    await watch(s.page);
    if (seed) { await seedClinic(s.page, ['patients:view']); await mockApi(s.page, s.log, api('Asha Rao')); }
    await s.page.goto(APP + url); await sleep(1500);
    const v = await s.page.evaluate(() => window.__csp);
    check(`${tag}: zero CSP violations from first-party code`, v.length === 0, JSON.stringify(v));
    check(`${tag}: zero console/page errors`, cspNoise(s.log).length === 0, JSON.stringify(cspNoise(s.log)));
    if (viewport) check(`${tag}: no horizontal scroll`, await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  } finally { dumpLog(tag, s.log); await s.browser.close(); }
}

// 3. Stored XSS: payload in a patient name. React renders it as text; an injected inline handler is blocked by CSP and reported.
// Chromium registers Reporting-Endpoints only on HTTPS responses, so this case goes through a throwaway TLS front
// (self-signed cert generated now, never stored) on HTTPS_PORT. --short-reporting-delay flushes the report batch promptly (as WPT does).
const HTTPS_PORT = Number(process.env.HTTPS_PORT ?? 4506);
const certDir = mkdtempSync(tmpdir() + '/fef010-');
execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=localhost', '-days', '1', '-keyout', certDir + '/k.pem', '-out', certDir + '/c.pem'], { stdio: 'ignore' });
const target = new URL(APP);
const front = https.createServer({ key: readFileSync(certDir + '/k.pem'), cert: readFileSync(certDir + '/c.pem') }, (req, res) => {
  const up = http.request({ host: target.hostname, port: target.port, path: req.url, method: req.method, headers: req.headers }, (r) => { res.writeHead(r.statusCode, r.headers); r.pipe(res); });
  up.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(up);
});
await new Promise((r) => front.listen(HTTPS_PORT, r));
const SECURE_APP = `https://localhost:${HTTPS_PORT}`;
{
  const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium', args: ['--short-reporting-delay', '--ignore-certificate-errors'] });
  const page = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
  const s = { browser, page, log: { requests: [], console: [], pageErrors: [], unmatched: [] } };
  let dialogs = 0;
  try {
    s.page.on('dialog', (d) => { dialogs++; d.dismiss(); });
    await watch(s.page);
    await seedClinic(s.page, ['patients:view']);
    await mockApi(s.page, s.log, api(XSS));
    await s.page.goto(SECURE_APP + '/patients?q=secret-query'); await sleep(1500);
    check('stored payload shown as text, not markup', await s.page.getByText('<script>alert(1)</script>', { exact: false }).count() > 0);
    // Simulate a sink that bypasses React escaping (the attack CSP exists for).
    await s.page.evaluate((p) => { const d = document.createElement('div'); d.innerHTML = p; document.body.appendChild(d); }, XSS);
    await sleep(500);
    check('injected inline handler did NOT run', (await s.page.evaluate(() => window.__xss)) === undefined);
    check('no alert dialog', dialogs === 0);
    const v = await s.page.evaluate(() => window.__csp);
    check('browser raised an enforce script-src violation', v.some((x) => x.d.startsWith('script-src') && x.disp === 'enforce'), JSON.stringify(v));
    let line = null;
    for (let i = 0; i < 90 && !line; i++) { line = serverLog().split('\n').find((l) => l.includes('"csp.violation"') && l.includes('/patients')); if (!line) await sleep(1000); }
    check('report received by /api/csp-report', !!line, line ?? 'no report within 90 s');
    check('report has no query string or payload', !!line && !line.includes('secret-query') && !line.includes('alert') && !line.includes('__xss'), line ?? '');
  } finally { dumpLog('xss', s.log); await s.browser.close(); front.close(); }
}

// 3b. Control: same injection with CSP bypassed DOES run, so the check above can tell blocked from inert.
{
  const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  try {
    const page = await (await browser.newContext({ bypassCSP: true })).newPage();
    await page.goto(APP + '/login');
    await page.evaluate((p) => { const d = document.createElement('div'); d.innerHTML = p; document.body.appendChild(d); }, XSS);
    await sleep(500);
    check('control: without CSP the same payload executes', (await page.evaluate(() => window.__xss)) === 1);
  } finally { await browser.close(); }
}

// 4. Framed by another origin: refused.
{
  const s = await launch();
  try {
    await s.page.route('http://attacker.test/**', (r) => r.fulfill({ contentType: 'text/html', body: `<iframe id="f" src="${APP}/login" width="600" height="400"></iframe>` }));
    await s.page.goto('http://attacker.test/'); await sleep(2000);
    const f = s.page.frames().find((x) => x !== s.page.mainFrame());
    const loaded = f ? await f.evaluate(() => !!document.querySelector('form')).catch(() => false) : false;
    check('cross-origin iframe refused (frame-ancestors none / X-Frame-Options DENY)', !loaded, f ? f.url() : 'no frame');
  } finally { await s.browser.close(); }
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
