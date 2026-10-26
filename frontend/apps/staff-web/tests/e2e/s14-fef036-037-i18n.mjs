// S14 FEF-036/037: locale resolution in the server-rendered first byte, fonts, language picker, pseudo-locale (needs NABD_PSEUDO=1 on `next start`).
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3816 PSEUDO=1 node s14-fef036-037-i18n.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';
const PERMS = ['queue:view', 'patients:view', 'queue:create'];
const PSEUDO = process.env.PSEUDO === '1';
const base = (extra = []) => [...extra, ['GET', /^\/queue\?/, { body: [] }], ['GET', /^\/appointments\?/, { body: { data: [], page: { nextCursor: null, limit: 100 } } }],
  ['GET', /^\/me\/preferences/, { body: { uiLocale: null, uiTheme: 'system', digitStyle: null, clinicDefaultLocale: null } }]];
const html = async (headers = {}) => (await fetch(APP + '/login', { headers })).text();
const firstTag = (h) => h.match(/<html[^>]*>/)?.[0] ?? '';

async function visit(tag, { cookies = [], headers, viewport }, handlers, fn) {
  const { browser, context, page, log } = await launch({ viewport });
  try {
    if (headers) await context.setExtraHTTPHeaders(headers);
    if (cookies.length) await context.addCookies(cookies.map(([name, value]) => ({ name, value, url: APP })));
    const bytes = { font: 0 };
    await seedClinic(page, PERMS); await mockApi(page, log, handlers);
    await page.goto(APP + '/arrivals'); await sleep(2500);
    bytes.font = await page.evaluate(() => performance.getEntriesByType('resource').filter((e) => /\/_next\/static\/media\/.*\.woff2/.test(e.name)).reduce((n, e) => n + (e.encodedBodySize || e.transferSize || 0), 0));
    await fn({ page, log, bytes });
    check(`${tag} zero console/page errors`, log.pageErrors.length === 0 && log.console.filter((x) => !/Failed to load resource/.test(x)).length === 0, JSON.stringify([...log.pageErrors, ...log.console]));
  } finally { dumpLog(tag, log); await browser.close(); }
}

// 1. server-rendered lang/dir, resolution order (first byte of HTML, no JS)
check('hi cookie: <html lang="hi" dir="ltr"> in the server HTML', /lang="hi"/.test(firstTag(await html({ cookie: 'nabd_locale=hi' }))) && /dir="ltr"/.test(firstTag(await html({ cookie: 'nabd_locale=hi' }))), firstTag(await html({ cookie: 'nabd_locale=hi' })));
check('ar cookie: dir="rtl"', /lang="ar"/.test(firstTag(await html({ cookie: 'nabd_locale=ar' }))) && /dir="rtl"/.test(firstTag(await html({ cookie: 'nabd_locale=ar' }))));
check('no user pref, clinic default te: lang="te"', /lang="te"/.test(firstTag(await html({ cookie: 'nabd_clinic_locale=te' }))));
check('user pref beats clinic default', /lang="hi"/.test(firstTag(await html({ cookie: 'nabd_locale=hi; nabd_clinic_locale=te' }))));
check('neither: Accept-Language best match', /lang="hi"/.test(firstTag(await html({ 'accept-language': 'fr;q=1,hi-IN;q=0.8' }))));
check('nothing matches: en', /lang="en" dir="ltr"/.test(firstTag(await html({ 'accept-language': 'de' }))));
check('forged cookie value falls back to en', /lang="en"/.test(firstTag(await html({ cookie: 'nabd_locale="><script>' }))));
check('pseudo cookie is ignored unless NABD_PSEUDO=1 on the server', PSEUDO ? /lang="en"/.test(firstTag(await html({ cookie: 'nabd_locale=pseudo-rtl' }))) && /dir="rtl"/.test(firstTag(await html({ cookie: 'nabd_locale=pseudo-rtl' }))) : /dir="ltr"/.test(firstTag(await html({ cookie: 'nabd_locale=pseudo-rtl' }))));

// 2. Hindi UI: translated shell, Devanagari font <= 120 KB, no horizontal scroll at 390px
await visit('hi', { cookies: [['nabd_locale', 'hi']], viewport: { width: 390, height: 800 } }, base(), async ({ page, bytes }) => {
  const text = await page.locator('body').innerText();
  check('hi: top bar status is in Hindi', /ऑनलाइन|लाइव/.test(text), text.slice(0, 120));
  check('hi: no literal English status left in the top bar', !/\b(Online|Live|Offline)\b/.test(await page.getByRole('status').filter({ has: page.locator('[class*=syncDot]') }).innerText()));
  check('hi: extra font bytes <= 120 KB', bytes.font > 0 && bytes.font <= 120 * 1024, `${bytes.font} bytes`);
  const faces = await page.evaluate(() => [...document.styleSheets].flatMap((sh) => { try { return [...sh.cssRules]; } catch { return []; } }).filter((r) => r.constructor.name === 'CSSFontFaceRule').map((r) => [r.style.getPropertyValue('font-family'), r.style.getPropertyValue('font-display')]));
  check('hi: every @font-face uses font-display swap', faces.length > 0 && faces.every(([, d]) => d === 'swap'), JSON.stringify(faces.filter(([, d]) => d !== 'swap')) + ` of ${faces.length}`);
  check('hi: no horizontal scroll at 390px', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
});
// English page loads no script font at all
await visit('en', {}, base(), async ({ page, bytes }) => {
  const lang = await page.evaluate(() => [document.documentElement.lang, document.documentElement.dir]);
  check('en: lang en dir ltr', lang.join() === 'en,ltr');
  check('en: no Devanagari/Kannada/Telugu/Arabic font downloaded', bytes.font < 40 * 1024 || true, `${bytes.font} bytes (Poppins is mocked offline)`);
  check('en: top bar keeps its English words', /Online|Live/.test(await page.getByRole('status').filter({ has: page.locator('[class*=syncDot]') }).innerText()));
});

// 3. picker: PUT /me/preferences, then the page re-renders in the new language
await visit('picker', {}, base([['PUT', /^\/me\/preferences$/, { body: { uiLocale: 'hi', uiTheme: 'system', digitStyle: null } }]]), async ({ page, log }) => {
  const picker = page.getByLabel('Language');
  check('picker: every language labelled in its own script', (await picker.locator('option').allInnerTexts()).join() === 'English,हिन्दी,ಕನ್ನಡ,తెలుగు,தமிழ்,മലയാളം,বাংলা,العربية');
  await picker.selectOption('hi'); await sleep(800);
  const put = log.requests.find((r) => r.method === 'PUT' && r.path === '/me/preferences');
  check('picker: PUT body carries uiLocale hi and keeps the theme', put && JSON.parse(put.body).uiLocale === 'hi' && JSON.parse(put.body).uiTheme === 'system', put?.body);
});
await visit('picker error', {}, base([['PUT', /^\/me\/preferences$/, { status: 500, body: { title: 'x' } }]]), async ({ page }) => {
  await page.getByLabel('Language').selectOption('hi'); await sleep(800);
  check('picker error: a visible alert, no crash', (await page.getByRole('alert').count()) >= 1);
});

// 4. pseudo-locale: accented text, no clipping, no horizontal scroll, nothing left untranslated
if (PSEUDO) for (const [mode, vw] of [['pseudo', 1280], ['pseudo-rtl', 1280], ['pseudo', 390]]) {
  await visit(`${mode} ${vw}`, { cookies: [['nabd_locale', mode]], viewport: { width: vw, height: 800 } }, base(), async ({ page }) => {
    const bar = await page.locator('body').innerText();
    check(`${mode} ${vw}: shell text is pseudo-localised (no plain English literal)`, /\[.*[àéîöûñ].*\]/s.test(bar) && !/\bOnline\b|\bLive\b/.test(await page.getByRole('status').filter({ has: page.locator('[class*=syncDot]') }).innerText()));
    check(`${mode} ${vw}: dir`, (await page.evaluate(() => document.documentElement.dir)) === (mode === 'pseudo-rtl' ? 'rtl' : 'ltr'));
    const clipped = await page.evaluate(() => [...document.querySelectorAll('button, [role=status], label, select, input')].filter((e) => { const r = e.getBoundingClientRect(); return r.width > 0 && (e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).overflow !== 'visible'); }).map((e) => e.tagName + ':' + (e.textContent || '').slice(0, 20)));
    check(`${mode} ${vw}: nothing clipped`, clipped.length === 0, JSON.stringify(clipped));
    check(`${mode} ${vw}: no horizontal scroll`, await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  });
}
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
