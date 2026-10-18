// S9 QA FEF-003 E2E (independent). APP=http://localhost:3306
import { launch, seedClinic, mockApi, check, results, APP } from './qa-lib.mjs';

const CASES = [
  ['د. محمد', 'عيادة النور (الرياض)'], ['डॉ. विक्रम', 'विक्रम क्लिनिक'], ['ಡಾ. ರಾವ್', 'ರಾವ್ ಕ್ಲಿನಿಕ್'], ['డా. రెడ్డి', 'రెడ్డి క్లినిక్'],
  ['Dr. محمد (MD)!', 'Clinic عيادة، 1.'], // mixed script + trailing punctuation
];
const MOJI = /Ø|Ù|à¤|à²|à°|Ã/;
for (const [i, [staff, clinic]] of CASES.entries()) {
  for (const vp of [{ width: 1280, height: 900 }, { width: 390, height: 800 }]) {
    for (const route of ['/arrivals', '/staff']) {
      const s = await launch({ viewport: vp });
      await seedClinic(s.page, ['queue:view', 'staff:manage', 'staff:view'], { staffName: staff, tenantName: clinic });
      await mockApi(s.page, s.log, [['GET', /^\/staff/, { body: { data: [], page: { nextCursor: null } } }], ['GET', /.*/, { body: [] }]]);
      await s.page.goto(APP + route, { waitUntil: 'networkidle' });
      const id = `${i + 1} ${vp.width}${route}`;
      const bdis = await s.page.locator('bdi').allTextContents();
      check(`${id} clinic name in <bdi> exactly`, bdis.includes(clinic), JSON.stringify(bdis));
      check(`${id} staff name in <bdi> exactly`, bdis.includes(staff), JSON.stringify(bdis));
      const body = await s.page.evaluate(() => document.body.innerText);
      check(`${id} no mojibake in page`, !MOJI.test(body));
      // names are never rendered as bare text outside a <bdi>
      const bare = await s.page.evaluate(([a, b]) => [...document.querySelectorAll('body *')].filter((e) => !e.closest('bdi') && !e.querySelector('*') && [a, b].includes(e.textContent.trim())).length, [staff, clinic]);
      check(`${id} no bare (non-bdi) name element`, bare === 0, String(bare));
      const iso = await s.page.evaluate(() => { const b = document.querySelector('bdi'); return b ? getComputedStyle(b).unicodeBidi : null; });
      check(`${id} bdi isolates bidi`, /isolate/.test(iso ?? ''), String(iso));
      check(`${id} no horizontal scroll`, await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      check(`${id} zero console/page errors`, !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length, JSON.stringify([s.log.pageErrors, s.log.console]));
      await s.browser.close();
    }
  }
}
// corrupt token claims: app must not crash (falls back, no pageerror)
{
  const s = await launch();
  await s.page.addInitScript(() => { localStorage.setItem('nabd_access_token', 'h.@@@.s'); localStorage.setItem('nabd_refresh_token', 'r'); });
  await mockApi(s.page, s.log, [['*', /.*/, { status: 401, body: {} }]]);
  await s.page.goto(APP + '/arrivals', { waitUntil: 'networkidle' });
  check('corrupt token: no uncaught page error', !s.log.pageErrors.length, JSON.stringify(s.log.pageErrors));
  await s.browser.close();
}
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length}/${results.length} passed`);
process.exit(bad.length ? 1 : 0);
