// CARRY-12: the baselined axe contrast findings are fixed in the --nb-* tokens and the baseline is empty.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// FEF-022: the --nb-* values moved from app/globals.css to the generated packages/tokens/dist/tokens.css (dark :root is the first block).
const css = readFileSync(new URL('../../../../packages/tokens/dist/tokens.css', import.meta.url), 'utf8');
const tok = (n) => css.match(new RegExp(`--nb-${n}:\\s*([^;]+);`))[1].trim();
const rgb = (c) => c.startsWith('#') ? [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16)) : c.match(/[\d.]+/g).slice(0, 3).map(Number);
const alpha = (c) => (c.startsWith('rgba') ? Number(c.match(/[\d.]+/g)[3]) : 1);
const over = (fg, bg) => { const a = alpha(fg), f = rgb(fg), b = rgb(bg); return f.map((v, i) => a * v + (1 - a) * b[i]); };
const lum = (c) => { const [r, g, b] = c.map((v) => v / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

// The app has one (dark) theme: globals.css says no light theme is approved; print sheets use system colours, not these tokens.
const SURFACES = ['bg-canvas', 'surface-1', 'surface-2', 'surface-3', 'surface-overlay'];
// Backgrounds axe measured in FEF-049 (pill on a tinted/hovered row, safety strip): kept as a regression floor.
const AXE_SEEN = { 'danger-500': ['#313038', '#2e1c23'], 'sync-500': ['#293a4f'] };

for (const [text, tint] of [['danger-500', 'danger-tint'], ['sync-500', 'sync-tint']]) {
  test(`--nb-${text} on --nb-${tint} is >= 4.5:1 over every surface and on the backgrounds axe saw`, () => {
    const fg = rgb(tok(text));
    for (const s of SURFACES) {
      const r = ratio(fg, over(tok(tint), tok(s)));
      assert.ok(r >= 4.5, `${text} on ${tint} over ${s}: ${r.toFixed(2)}`);
      assert.ok(ratio(fg, rgb(tok(s))) >= 4.5, `${text} on bare ${s}`);
    }
    for (const bg of AXE_SEEN[text]) assert.ok(ratio(fg, rgb(bg)) >= 4.5, `${text} on ${bg}`);
  });
}

test('danger stays in the red family', () => {
  const [r, g, b] = rgb(tok('danger-500'));
  assert.ok(r === Math.max(r, g, b) && r - g > 100 && r - b > 100, tok('danger-500'));
});

test('axe-known.json has no entries left (nothing baselined)', () => {
  const { entries } = JSON.parse(readFileSync(new URL('../e2e/smoke/axe-known.json', import.meta.url)));
  assert.deepEqual(entries, []);
});
