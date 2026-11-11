// FEF-051 Playwright Test config: projects = app x device x theme; tests = screen x locale (staff.visual.mjs, patient.visual.mjs).
//   PLAYWRIGHT_MODULE=<nm>/playwright/index.mjs node <nm>/playwright/cli.js test -c frontend/apps/staff-web/tests/e2e/visual/visual.config.mjs \
//     [--project='staff-*'] [--shard=1/4] [--update-snapshots]
// Env: VR_STAFF_URL (default http://localhost:3031), VR_PATIENT_URL (http://localhost:3032), VR_SCREENS (ids from select.mjs),
// VR_LOCALES (staff; default en,hi), VR_STRICT=1|2 (zero-pixel compare), VR_WORKERS, VR_OUT. How to run and approve diffs: docs/ops/ci.md (visual regression).
import { DEVICES, STAFF_DEVICES, PATIENT_DEVICES, THEMES } from './screens.mjs';

const STAFF = process.env.VR_STAFF_URL ?? 'http://localhost:3031';
const PATIENT = process.env.VR_PATIENT_URL ?? 'http://localhost:3032';
const OUT = process.env.VR_OUT ?? 'vr-results';
const project = (app, device, theme) => ({
  name: `${app}-${device}-${theme}`, testMatch: `${app}.visual.mjs`,
  use: { baseURL: app === 'staff' ? STAFF : PATIENT, viewport: DEVICES[device], deviceScaleFactor: 1, colorScheme: theme, reducedMotion: 'reduce',
    timezoneId: 'Asia/Kolkata', locale: 'en-IN',
    // stable rasterisation: software GL, sRGB, no hinting (fewer GPU anti-aliasing differences between runs)
    launchOptions: { args: ['--disable-gpu', '--force-color-profile=srgb', '--font-render-hinting=none'], ...(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {}) } },
});

const config = {
  testDir: '.',
  // Baselines per OS (font rendering differs): CI (linux) baselines are the reviewed ones, in git LFS (.gitattributes).
  snapshotPathTemplate: '{testDir}/baselines/{platform}/{projectName}/{arg}{ext}',
  outputDir: `${OUT}/artifacts`,
  fullyParallel: true,
  workers: Number(process.env.VR_WORKERS ?? 2),
  retries: 0, // a snapshot that needs a retry is flaky: fix the mask or the mock instead
  timeout: 60000,
  expect: { toHaveScreenshot: { maxDiffPixelRatio: 0.001, animations: 'disabled', caret: 'hide', scale: 'css', timeout: 15000 } }, // > 0.1% fails
  reporter: [['list'], ['html', { outputFolder: `${OUT}/report`, open: 'never' }], ['json', { outputFile: `${OUT}/results.json` }]],
  projects: [
    ...STAFF_DEVICES.flatMap((d) => THEMES.map((t) => project('staff', d, t))),
    ...PATIENT_DEVICES.flatMap((d) => THEMES.map((t) => project('patient', d, t))),
  ],
};

export default config;
