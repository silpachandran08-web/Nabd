// FEF-051 PR selection (pure): changed files -> the screens to snapshot. A feature folder selects the screens under it; packages/*
// (tokens, ui, i18n, ...), shared app code and the harness itself select the full matrix; a change to a shared component folder that
// no screen route names also selects everything (safe side).
//   git diff --name-only origin/<base>...HEAD | node select.mjs   ->  {"mode":"all"|"some"|"none","ids":[...]}
import { readFileSync } from 'node:fs';
import { basename } from 'node:path';
import { SCREENS, APPS } from './screens.mjs';

const FULL = [/^frontend\/packages\//, /^frontend\/apps\/staff-web\/tests\/e2e\/visual\//, /^frontend\/(pnpm-lock\.yaml|package\.json)$/];

export function selectScreens(changed, screens = SCREENS) {
  const ids = new Set();
  for (const raw of changed.map((f) => f.trim()).filter(Boolean)) {
    if (FULL.some((re) => re.test(raw))) return { mode: 'all', ids: screens.map((s) => s.id) };
    const f = raw.replace(/^frontend\//, '');
    const app = Object.entries(APPS).find(([, dir]) => f.startsWith(dir + '/'))?.[0];
    if (!app) {
      if (/^apps\/(staff|patient)-web\//.test(f) && !/^apps\/[^/]+\/tests\//.test(f)) {
        // outside app/ (proxy.ts, next.config.ts, package.json, public/): every screen of that app
        const a = f.startsWith('apps/staff-web/') ? 'staff' : 'patient';
        screens.filter((s) => s.app === a).forEach((s) => ids.add(s.id));
      }
      continue;
    }
    const mine = screens.filter((s) => s.app === app);
    const inside = mine.filter((s) => (s.dir + '/').startsWith(f.slice(0, f.lastIndexOf('/') + 1)));
    if (inside.length) { inside.forEach((s) => ids.add(s.id)); continue; }
    // shared folder (components/<feature>/, lib/, shell/, a root file): screens whose route names the feature, else the whole app
    const rel = f.slice(APPS[app].length + 1).split('/');
    const feature = rel.length > 2 && rel[0] === 'components' ? rel[1] : null;
    const named = feature ? mine.filter((s) => s.route.split('/').includes(feature)) : [];
    (named.length ? named : mine).forEach((s) => ids.add(s.id));
    if (!named.length && app === 'staff') return { mode: 'all', ids: screens.map((s) => s.id) }; // staff shell code reaches every screen
  }
  return { mode: ids.size ? 'some' : 'none', ids: [...ids] };
}

if (process.argv[1] && basename(process.argv[1]) === 'select.mjs') {
  console.log(JSON.stringify(selectScreens(readFileSync(0, 'utf8').split('\n'))));
}
