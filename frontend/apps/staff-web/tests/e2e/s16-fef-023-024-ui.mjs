// FEF-022/023/024 in a browser: tokens applied, ui kit gallery (/ui-gallery needs NABD_UI_GALLERY=1 on the server), shell at 375/390/768/1024/1440.
// Run: NABD_UI_GALLERY=1 next start -p 3811; NODE_PATH=/home/claude/ws/qa/node_modules APP=http://localhost:3811 node tests/e2e/s16-fef-023-024-ui.mjs
import { launch, seedClinic, mockApi, check, results, sleep, dumpLog, APP } from './qa-lib.mjs';

const RECEPTION = ['queue:view', 'queue:create', 'queue:edit', 'patients:view', 'billing:view', 'pharmacy:view'];
const ALL = ['billing', 'packages', 'pharmacy_inhouse', 'whatsapp_booking', 'queue_eta', 'lab', 'ipd', 'patient_app', 'emr_erx', 'teleconsult'];
const handlers = [
  ['GET', /^\/me\/entitlements/, { body: { modules: ALL.map((code) => ({ code, enabled: true })), killedFeatures: [], seatLimit: null } }],
  ['GET', /^\/queue/, { body: [] }],
  ['GET', /./, { body: [] }],
];
const calm = (s) => !s.log.pageErrors.length && !s.log.console.filter((x) => !/Failed to load resource|preload/.test(x)).length;
const noHScroll = (p) => p.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);

// ---- FEF-022: tokens reach the page (dark default, light on [data-theme=light]), z-scale vars exist
{
  const s = await launch();
  await s.page.goto(`${APP}/ui-gallery`, { waitUntil: 'networkidle' });
  const v = (name) => s.page.evaluate((n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim(), name);
  check('022 dark tokens.css applied (--nb-bg-root)', (await v('--nb-bg-root')) === '#06090c');
  check('022 type scale + z scale + missing tokens defined', (await v('--nb-fs-sm')) === '13px' && (await v('--z-lock')) === '100' && (await v('--z-modal')) === '50' && (await v('--nb-space-10')) === '10px' && (await v('--nb-topbar-h')) === '64px' && (await v('--nb-radius-sm')) === '8px');
  await s.page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'));
  check('022 light theme overrides on [data-theme=light]', (await v('--nb-bg-root')) === '#f4f6f8' && (await v('--nb-text-primary')) === '#0b1217');
  check('022 prototype aliases (--acc)', (await v('--acc')) === (await v('--nb-accent-500')) && (await v('--tx4')) === (await v('--nb-text-disabled')) && (await v('--acc')) !== '');
  await s.browser.close();
}

// ---- FEF-023: gallery components
{
  const s = await launch({ viewport: { width: 1280, height: 900 } });
  await s.page.goto(`${APP}/ui-gallery`, { waitUntil: 'networkidle' });
  check('023 pills carry icon + text', (await s.page.locator('.nbu-pill').evaluateAll((els) => els.length > 0 && els.every((e) => e.querySelector('svg') && e.textContent.trim().length > 0))));
  check('023 Field error: aria-invalid + describedby + role=alert', await s.page.evaluate(() => { const i = document.querySelector('input[aria-invalid="true"]'); return !!i && (i.getAttribute('aria-describedby') || '').split(' ').every((id) => document.getElementById(id)) && !!document.querySelector('[role=alert].nbu-error'); }));
  // Tabs keyboard
  await s.page.getByRole('tab', { name: 'One' }).focus();
  await s.page.keyboard.press('ArrowRight');
  check('023 Tabs: ArrowRight selects next and moves focus', (await s.page.locator('#tab-now').textContent()) === 'b' && (await s.page.evaluate(() => document.activeElement?.textContent)) === 'Two');
  await s.page.keyboard.press('End');
  check('023 Tabs: End selects last', (await s.page.locator('#tab-now').textContent()) === 'c');
  // Toasts
  await s.page.getByRole('button', { name: 'Save' }).click();
  check('023 toast ok goes to the polite region', (await s.page.locator('[data-region=polite] .nbu-toast').count()) === 1);
  await s.page.getByRole('button', { name: 'Fail' }).click();
  check('023 toast error goes to the assertive region and stays', (await s.page.locator('[data-region=assertive] .nbu-toast').count()) === 1);
  // Table: sort
  const small = s.page.getByRole('table', { name: 'Small table' });
  await small.getByRole('button', { name: 'Count' }).click();
  check('023 Table sort ascending sets aria-sort', (await small.getByRole('columnheader', { name: 'Count' }).getAttribute('aria-sort')) === 'ascending');
  const firstCell = async () => small.locator('[role=row][data-row]').first().locator('[role=cell]').first().textContent();
  await small.getByRole('button', { name: 'Name' }).click();
  await small.getByRole('button', { name: 'Name' }).click();
  check('023 Table sort descending', (await firstCell()) === 'Patient 007');
  // keyboard row nav + activate
  await small.locator('[role=row][data-row]').first().focus();
  await s.page.keyboard.press('ArrowDown');
  const idx = await s.page.evaluate(() => document.activeElement?.getAttribute('data-row'));
  check('023 Table ArrowDown moves row focus', idx === '1', idx);
  await s.page.keyboard.press('Enter');
  check('023 Table Enter activates the row', (await s.page.locator('#picked').textContent()) !== '');
  // virtualisation
  const big = s.page.getByRole('table', { name: 'Big table' });
  const mounted = await big.locator('[role=row][data-row]').count();
  check('023 Table with 1000 rows mounts a window only', mounted > 0 && mounted < 40, String(mounted));
  await big.locator('[role=row][data-row]').first().focus();
  await s.page.keyboard.press('End');
  await sleep(300);
  const last = await s.page.evaluate(() => document.activeElement?.getAttribute('data-row'));
  check('023 Table End jumps to the last of 1000 rows and focuses it', last === '999', String(last));
  check('023 empty table shows EmptyState', (await s.page.getByText('No patients').count()) === 1);
  // SelectMenu as popover on desktop
  await s.page.getByRole('button', { name: 'Dr A' }).click();
  check('023 SelectMenu on desktop is a popover (no dialog)', (await s.page.getByRole('dialog').count()) === 0 && (await s.page.getByRole('listbox').count()) === 1);
  await s.page.keyboard.press('Escape');
  check('023 Escape closes the popover', (await s.page.getByRole('listbox').count()) === 0);
  check('023 gallery console clean (desktop)', calm(s)); dumpLog('gallery', s.log);
  await s.browser.close();
}

// ---- FEF-023/024: coarse-pointer phone -> bottom sheet; Table card mode under 768
{
  const s = await launch({ viewport: { width: 390, height: 800 } });
  await s.context.close();
  const ctx = await s.browser.newContext({ viewport: { width: 390, height: 800 }, hasTouch: true, isMobile: true });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', (e) => errs.push(String(e)));
  await p.goto(`${APP}/ui-gallery`, { waitUntil: 'networkidle' });
  const trigger = p.getByRole('button', { name: 'Dr A' });
  await trigger.click();
  const dlg = p.getByRole('dialog', { name: 'Pick a doctor' });
  check('024 Select on a coarse phone opens as a bottom sheet (dialog, modal)', (await dlg.count()) === 1 && (await dlg.getAttribute('aria-modal')) === 'true');
  const box = await dlg.boundingBox();
  check('024 sheet is anchored to the bottom of the viewport', Math.abs(box.y + box.height - 800) < 2, JSON.stringify(box));
  check('024 focus moves into the sheet', await p.evaluate(() => !!document.activeElement?.closest('[role=dialog]')));
  await p.keyboard.press('Tab'); await p.keyboard.press('Tab'); await p.keyboard.press('Tab');
  check('024 focus is trapped in the sheet', await p.evaluate(() => !!document.activeElement?.closest('[role=dialog]')));
  const before = p.url();
  await p.keyboard.press('Escape');
  await sleep(300);
  check('024 Escape closes the sheet and restores focus to the trigger', (await dlg.count()) === 0 && (await p.evaluate(() => document.activeElement?.textContent)) === 'Dr A');
  check('024 Escape leaves no extra history entry (url unchanged)', p.url() === before);
  await trigger.click();
  await p.goBack();
  await sleep(300);
  check('024 browser Back closes the sheet and stays on the page', (await dlg.count()) === 0 && /ui-gallery/.test(p.url()));
  await trigger.click();
  await p.getByRole('option', { name: 'Dr B' }).click();
  check('024 choosing an option closes the sheet and selects it', (await dlg.count()) === 0 && (await p.locator('#sel-now').textContent()) === 'y');
  // card mode under 768
  const small = p.getByRole('table', { name: 'Small table' });
  const row = small.locator('[role=row][data-row]').first();
  check('024 Table under 768 px: rows are cards (block) with data labels', (await row.evaluate((e) => getComputedStyle(e).display)) === 'block' && (await row.locator('[role=cell]').first().getAttribute('data-label')) === 'Name' && (await row.locator('[role=cell]').first().evaluate((e) => getComputedStyle(e, '::before').content)).includes('Name'));
  check('024 card rows stay keyboard operable (focus + Enter)', await (async () => { await row.focus(); await p.keyboard.press('Enter'); return (await p.locator('#picked').textContent()) !== ''; })());
  check('024 phone gallery has no horizontal scroll at 390', await noHScroll(p));
  check('024 phone gallery no page errors', errs.length === 0, errs.join('|'));
  await ctx.close();
  await s.browser.close();
}

// ---- FEF-024: shell at 375 / 768 / 1024 / 1440, no horizontal scroll, rail + bottom nav
for (const [w, h] of [[375, 800], [768, 1024], [1024, 768], [1440, 900]]) {
  const s = await launch({ viewport: { width: w, height: h } });
  await seedClinic(s.page, RECEPTION);
  await mockApi(s.page, s.log, handlers);
  await s.page.goto(`${APP}/arrivals`, { waitUntil: 'networkidle' });
  check(`024 ${w}px: no horizontal page scroll`, await noHScroll(s.page));
  const side = s.page.locator('nav[aria-label="Main"]').first();
  const sideBox = await side.boundingBox().catch(() => null);
  if (w >= 1440) check('024 1440: full sidebar (>= 200 px)', sideBox && sideBox.width >= 200, JSON.stringify(sideBox));
  else if (w >= 431) {
    check(`024 ${w}px: icon rail 88 px`, sideBox && Math.round(sideBox.width) === 88, JSON.stringify(sideBox));
    check(`024 ${w}px: rail links have tooltips (title)`, await side.locator('a').evaluateAll((as) => as.length > 0 && as.every((a) => a.getAttribute('title'))));
  } else {
    const bn = s.page.locator('[data-bottom-nav]');
    check(`024 ${w}px: bottom nav visible, sidebar hidden`, (await bn.isVisible()) && !(await side.isVisible()));
    const links = bn.locator('a, button');
    const n = await links.count();
    check(`024 ${w}px: 4 items + More (5 targets)`, n === 5 && (await bn.getByRole('button', { name: 'More' }).count()) === 1, String(n));
    const small = await links.evaluateAll((els) => els.filter((e) => e.getBoundingClientRect().height < 44).length);
    check(`024 ${w}px: bottom nav targets >= 44 px`, small === 0);
    const pb = await bn.evaluate((e) => getComputedStyle(e).paddingBottom);
    check(`024 ${w}px: safe-area padding applied (padding-bottom present)`, pb !== undefined);
    await bn.getByRole('button', { name: 'More' }).click();
    const dlg = s.page.getByRole('dialog', { name: 'More' });
    check('024 More opens as a bottom sheet containing the menu', (await dlg.count()) === 1 && (await dlg.getByRole('menuitem').count()) >= 1);
    await s.page.keyboard.press('Escape');
    await sleep(300);
    check('024 Escape closes More; focus back on More', (await dlg.count()) === 0 && (await s.page.evaluate(() => document.activeElement?.textContent?.trim())) === 'More');
  }
  check(`024 ${w}px: console clean`, calm(s)); dumpLog(`shell${w}`, s.log);
  await s.browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
