// FEF-051 patient-web snapshots (nightly): every screen x patient locale, phone and desktop, light and dark (?theme=dark).
// Mocks answer the first read of each link page (synthetic clinic and first names only); the OTP step is where every flow starts.
import { test, settle, snap, projectParts, wanted, FIXED } from './vr.mjs';
import { screensOf, PATIENT_LOCALES } from './screens.mjs';
import { mockApi } from '../qa-lib.mjs';

const clinicName = 'Synthetic Clinic IN';
const expiresAt = new Date(FIXED + 7 * 86400000).toISOString();
const MOCKS = [
  ['GET', /^\/patient-links\/[^/]+$/, { body: { clinicName } }],
  ['GET', /^\/proxy-invites\/[^/]+$/, { body: { clinicName, relation: 'parent', caregiverFirstName: 'Asha', expiresAt } }],
  ['GET', /^\/me\/profile-claims\/[^/]+$/, { body: { clinicName, firstName: 'Kavya', guardianFirstName: 'Asha', opensOn: '2026-03-02', expiresAt, ownPhone: false } }],
  ['GET', /^\/public\/exports\/[^/]+$/, { body: { clinicName, expiresAt, downloadsLeft: 3 } }],
];

for (const screen of screensOf('patient').filter((s) => wanted(s.id))) {
  for (const locale of PATIENT_LOCALES) {
    test(`${screen.id} ${locale}`, async ({ page }, info) => {
      const { theme } = projectParts(info.project.name);
      await page.clock.setFixedTime(FIXED);
      await mockApi(page, { requests: [], console: [], pageErrors: [], unmatched: [] }, MOCKS);
      await page.goto(`${screen.url}?lang=${locale}${theme === 'dark' ? '&theme=dark' : ''}`);
      await settle(page);
      await snap(page, [...screen.id.split('/'), `${locale}.png`]);
    });
  }
}
