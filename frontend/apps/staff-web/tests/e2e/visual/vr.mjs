// FEF-051 runtime shared by the two specs: one frozen clock for the mocks (Node) and the page, the masks, and the snapshot step.
// Import this module first: it replaces Date in the worker so every mock timestamp and the mock JWT expiry are the same each run.
import { dirname, join } from 'node:path';
import { boundaryCrash, CRASH_SELECTOR } from './screens.mjs';

export const FIXED = Date.parse('2026-03-02T04:30:00.000Z'); // Monday 10:00 IST / 07:30 KSA, inside OPD hours
const RealDate = Date;
globalThis.Date = class extends RealDate {
  constructor(...a) { super(...(a.length ? a : [FIXED])); }
  static now() { return FIXED; }
};

const PW = process.env.PLAYWRIGHT_MODULE;
if (!PW) throw new Error('set PLAYWRIGHT_MODULE to <node_modules>/playwright/index.mjs (the same install whose cli.js runs the suite)');
export const { test, expect } = await import(join(dirname(PW), 'test.mjs'));

// Dynamic regions: times (incl. the top bar "last sync" clock, which follows the last API answer), avatars, media, QR/secret
// blocks, anything a screen marks with data-vr-mask.
const MASK = 'time, [data-vr-mask], [class*="syncTime"], img[alt*="avatar" i], canvas, video, iframe, [data-testid="mfa-secret"], svg[aria-label*="QR" i]';
// Determinism check: VR_STRICT=1 allows zero differing pixels (Playwright's default per-pixel colour tolerance, 0.2, absorbs
// GPU anti-aliasing noise); VR_STRICT=2 also sets that tolerance to 0 (any colour step counts).
const STRICT = { 1: { maxDiffPixels: 0 }, 2: { maxDiffPixels: 0, threshold: 0 } }[process.env.VR_STRICT] ?? {};

export async function settle(page) {
  await page.waitForLoadState('networkidle', { timeout: 2500 }).catch(() => {}); // polling/SSE retries can keep the network busy; the screenshot
  // step below still waits for two identical consecutive frames
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(900); // permission-gated controls mount after hydration (useSyncExternalStore), as in smoke/sweep.mjs
}

/** Collects error-boundary reports of this page (see boundaryCrash); call before page.goto. */
export function watchCrashes(page) {
  const out = [];
  page.on('request', (r) => { const m = boundaryCrash(r.method(), r.url(), r.postData()); if (m) out.push(m); });
  return out;
}

export async function snap(page, name, crashes = []) {
  // a crashed screen never becomes (or is compared against) a baseline: it fails before the screenshot is taken
  const shown = await page.locator(CRASH_SELECTOR).count();
  expect({ reported: crashes, shown }, 'the screen rendered an error boundary: fix the mocks or the app, never approve it').toEqual({ reported: [], shown: 0 });
  await expect(page).toHaveScreenshot(name, { fullPage: true, mask: [page.locator(MASK)], ...STRICT });
}

/** "staff-desktop-dark" -> { app, device, theme } */
export const projectParts = (name) => { const [app, device, theme] = name.split('-'); return { app, device, theme }; };
/** VR_SCREENS=id,id (from select.mjs) limits the run; unset = every screen. */
export const wanted = (id) => !process.env.VR_SCREENS || process.env.VR_SCREENS.split(',').includes(id);
export const localesFrom = (fallback) => (process.env.VR_LOCALES ? process.env.VR_LOCALES.split(',') : fallback);
