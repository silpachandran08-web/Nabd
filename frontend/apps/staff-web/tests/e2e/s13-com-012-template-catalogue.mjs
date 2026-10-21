// S13 COM-012 ui: setup > WhatsApp templates shows catalogue-derived (starter) templates with plain names, language and
// status; queued = "waiting to send"; only custom rejected templates offer "Fix & resubmit"; empty and error states; 390px.
// Run: NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3708 node tests/e2e/s13-com-012-template-catalogue.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const noise = (log) => [...log.pageErrors, ...log.console.filter((x) => !/Failed to load resource/.test(x))];
const t = (id, o) => ({ id, name: o.key, key: o.key, source: 'catalogue', language: 'en', category: 'UTILITY', headerText: 'QA Clinic',
  body: 'Hello {{1}}, see you soon.', paramCount: 1, status: 'queued', rejectionReason: null, qualityRating: 'UNKNOWN', updatedAt: '2026-10-21T00:00:00Z', ...o });
const rows = [
  t('1', { key: 'appt_reminder_24h' }),
  t('2', { key: 'appt_confirmed', language: 'hi', status: 'approved', body: 'नमस्ते {{1}}, धन्यवाद।' }),
  t('3', { key: 'doctor_delay', language: 'kn', status: 'rejected', rejectionReason: 'INVALID_FORMAT' }),
  t('4', { key: 'my_custom', name: 'my_custom', source: 'custom', status: 'rejected', rejectionReason: 'INCORRECT_CATEGORY' }),
];
const PERMS = ['setup:view', 'setup:edit'];

async function open(list, viewport) {
  const s = await launch({ viewport });
  await seedClinic(s.page, PERMS);
  await mockApi(s.page, s.log, [['GET', /^\/setup\/whatsapp-templates/, list]]);
  await s.page.goto(`${APP}/setup?tab=whatsapp`); await sleep(1500);
  return s;
}

let s;
try {
  s = await open({ body: rows });
  const body = await s.page.locator('main, body').first().innerText();
  check('A1 catalogue rows use plain names', body.includes('Reminder, 1 day before') && body.includes('Appointment confirmed') && body.includes('Doctor running late'));
  check('A2 starter template tag and languages shown', body.includes('Starter template') && body.includes('Hindi') && body.includes('Kannada'));
  check('A3 queued shows as waiting to send', body.includes('waiting to send'));
  check('A4 Devanagari renders', body.includes('नमस्ते'));
  check('A5 only the custom rejected template offers Fix & resubmit', (await s.page.getByRole('button', { name: /Fix & resubmit/ }).count()) === 1);
  check('A6 custom template keeps its own name', body.includes('my_custom'));
  check('A7 no console errors', noise(s.log).length === 0, JSON.stringify(noise(s.log)));
  await s.browser.close(); s = null;

  s = await open({ body: [] });
  check('B1 empty state', (await s.page.locator('body').innerText()).includes('No templates yet'));
  await s.browser.close(); s = null;

  s = await open({ body: rows }, { width: 390, height: 844 });
  const overflow = await s.page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  check('C1 390px: page does not scroll horizontally', !overflow);
  await s.browser.close(); s = null;

  s = await open({ status: 500, body: { title: 'Server error', detail: 'boom' } });
  check('D1 list failure does not crash the screen', (await s.page.locator('body').innerText()).includes('WhatsApp message templates') || noise(s.log).length === 0);
  await s.browser.close(); s = null;
} catch (e) { check('script', false, String(e)); }
finally { if (s) await s.browser.close(); }
const failed = results.filter((r) => !r.ok);
console.log(`${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
