// FEF-016 AC3: the doctor loaders have no raw fetch, no token-decode copy and no hand-written API types.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../app/', import.meta.url));
const src = (p) => readFileSync(root + p, 'utf8');
const FILES = ['components/myday/useMyDay.ts', 'components/doctorlists/api.ts', 'components/myschedule/useMySchedule.ts', '(clinic)/consult/page.tsx', 'components/consult/queue/useConsultQueue.ts', 'components/consult/visit/useVisitLoad.ts', 'components/consult/visit/useAuthedFetch.ts', 'components/consult/RxPrint.tsx'];

for (const f of FILES) {
  test(`AC3 ${f}: 0 raw fetch calls`, () => {
    const s = src(f);
    assert.equal(/\bfetch\(/.test(s.replace(/\/\/.*$/gm, '')), false, 'fetch(');
    assert.equal(/\bapiFetch\b/.test(s), false, 'apiFetch');
  });
  test(`AC3 ${f}: 0 token-decode copies`, () => {
    assert.equal(/decodeAccessToken|decodeJwt|atob\(|JSON\.parse\(\s*atob/.test(src(f)), false);
  });
  // RxPrint is a print view with its own view models (not one of the AC3 loaders). MyDayData is the screen's view model:
  // its members are the generated-derived types above it.
  if (f !== 'components/consult/RxPrint.tsx') test(`AC3 ${f}: 0 hand-written API types (no object-literal type aliases)`, () => {
    const hits = [...src(f).matchAll(/^(?:export )?type (\w+)\s*=\s*\{/gm)].map((m) => m[1]).filter((n) => n !== 'MyDayData');
    assert.deepEqual(hits, []);
  });
}

test('selfStaffId is the one token reader for the doctor screens', () => {
  assert.match(src('lib/doctorQueries.ts'), /decodeAccessToken/);
  assert.match(src('components/myday/useMyDay.ts'), /selfStaffId/);
});

test('My Day reads patients through the batched loader, never one by one', () => {
  const s = src('components/myday/useMyDay.ts');
  assert.match(s, /loadPatients\(/);
  assert.doesNotMatch(s, /\/patients\/\$\{/);
});

test('prefetch is wired on focus and hover (My Day) and on row hover/focus/arrow keys (consult)', () => {
  const d = src('components/myday/MyDayParts.tsx');
  assert.equal((d.match(/onMouseEnter=\{warm/g) ?? []).length, 2);
  assert.equal((d.match(/onFocus=\{warm/g) ?? []).length, 2);
  const c = src('components/consult/queue/QueueList.tsx'); // FEF-030: the queue table moved here
  assert.match(c, /onMouseEnter=\{\(\) => q\.warm\(r\)\} onFocus=\{\(\) => q\.warm\(r\)\} onKeyDown=\{rowArrows\}/);
  assert.match(src('components/consult/queue/useConsultQueue.ts'), /prefetchVisit\(qc, r\.id/);
});

test('opening a visit cancels the previous visit\'s reads (abort)', () => {
  const c = src('components/consult/visit/useVisitLoad.ts'); // FEF-030: the open-visit reads moved here; the workspace unmounts per visit
  assert.match(c, /ctl\.abort\(\)/);
  assert.match(c, /cancelQueries\(\{ queryKey: visitReadKey/);
});
