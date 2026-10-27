// FEF-050 pure journey matrix (no browser, no imports): the time targets of doc 05 J1-J15 and which journeys run on which
// device / locale. nightly.mjs builds the cases from it and checks its own journeys against it; unit tests read it directly.
export const SLA = { // ms; the measured step is named in nightly.mjs / journeys.mjs (budgetKey)
  J1: 45000, J2: 20000, J3: 30000, J4: 30000, J5: 2000, J6: 500, J7: 10000, J8: 60000, J9: 60000,
  J10: 30000, J11: 90000, J12: 120000, J13: 60000, J14: 10000, J15: 120000,
};
export const JOURNEY_IDS = Object.keys(SLA);
export const DEVICE_JOURNEYS = ['J1', 'J2', 'J5', 'J8', 'J9']; // tablet 768 and phone 375
export const HI_JOURNEYS = ['J1', 'J5', 'J9'];                  // Hindi, desktop
export const DEVICES = { desktop: { width: 1366, height: 768 }, tablet: { width: 768, height: 1024 }, phone: { width: 375, height: 812 } };
