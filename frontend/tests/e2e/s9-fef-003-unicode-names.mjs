// FEF-003 E2E: Arabic/Hindi/Kannada/Telugu names render exactly, inside <bdi>. APP=http://localhost:3208, API mocked.
import { launch, seedClinic, mockApi, check, results, dumpLog, APP } from './qa-lib.mjs';

const CASES = [['د. محمد', 'عيادة النور (الرياض)'], ['डॉ. विक्रम', 'विक्रम क्लिनिक'], ['ಡಾ. ರಾವ್', 'ರಾವ್ ಕ್ಲಿನಿಕ್'], ['డా. రెడ్డి', 'రెడ్డి క్లినిక్']];
for (const [i, [staff, clinic]] of CASES.entries()) {
  for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 800 }]) {
    const s = await launch({ viewport });
    await seedClinic(s.page, ['queue:view'], { staffName: staff, tenantName: clinic });
    await mockApi(s.page, s.log, [['GET', /^\/(queue|staff)/, { body: [] }]]);
    await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
    const text = await s.page.locator('bdi').allTextContents();
    check(`${i + 1}/${viewport.width} bdi holds exact clinic + staff name`, text.includes(clinic) && text.includes(staff), JSON.stringify(text));
    if (viewport.width === 390) check(`${i + 1} 390px no horizontal scroll`, await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    check(`${i + 1}/${viewport.width} zero console errors`, !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length);
    dumpLog(`${i + 1}`, s.log);
    await s.browser.close();
  }
}
process.exit(results.every((r) => r.ok) ? 0 : 1);
