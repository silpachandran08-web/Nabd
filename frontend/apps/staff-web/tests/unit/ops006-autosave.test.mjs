// OPS-006 (DP-27): the consult autosave sends exactly one save after a 3 s pause, and at the latest 30 s into a long burst.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const { createAutosave, AUTOSAVE_IDLE_MS, AUTOSAVE_MAX_MS } = await import("../../app/lib/autosave.ts");

function fake() {
  let now = 0, id = 0;
  const q = new Map();
  const timers = {
    now: () => now,
    set: (fn, ms) => { q.set(++id, { at: now + ms, fn }); return id; },
    clear: (h) => q.delete(h),
  };
  const advance = (ms) => {
    const end = now + ms;
    for (;;) {
      const next = [...q.entries()].filter(([, v]) => v.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      now = next[1].at; q.delete(next[0]); next[1].fn();
    }
    now = end;
  };
  return { timers, advance };
}

test("defaults are idle 3 s and max 30 s", () => {
  assert.equal(AUTOSAVE_IDLE_MS, 3000);
  assert.equal(AUTOSAVE_MAX_MS, 30000);
});

test("a pause of 3 s sends exactly one save", () => {
  const f = fake(); let saves = 0;
  const a = createAutosave(() => saves++, { timers: f.timers });
  a.touch(); f.advance(1000); a.touch(); f.advance(2999);
  assert.equal(saves, 0);
  f.advance(1);
  assert.equal(saves, 1);
  f.advance(60_000);
  assert.equal(saves, 1, "no further save without a new edit");
  assert.equal(a.pending(), false);
});

test("typing without a 3 s pause still saves once at 30 s", () => {
  const f = fake(); const at = [];
  const a = createAutosave(() => at.push(f.timers.now()), { timers: f.timers });
  for (let t = 0; t < 30_000; t += 1000) { a.touch(); f.advance(1000); } // an edit every second
  assert.deepEqual(at, [30_000]);
});

test("a burst after a save starts a fresh 30 s window; cancel drops a pending save", () => {
  const f = fake(); let saves = 0;
  const a = createAutosave(() => saves++, { timers: f.timers, idleMs: 100, maxMs: 250 });
  a.touch(); f.advance(100); assert.equal(saves, 1);
  a.touch(); a.cancel(); f.advance(1000); assert.equal(saves, 1);
});

test("the consult note editor (split out of the consult page by FEF-030) uses the debounce and no 10 s interval", () => {
  const s = readFileSync("app/components/consult/note/useNoteEditor.ts", "utf8");
  assert.match(s, /createAutosave/);
  assert.doesNotMatch(s, /AUTOSAVE_MS|setInterval\(\(\) => \{\s*if \(dirtyRef/);
});
