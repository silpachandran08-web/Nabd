// OPS-006 (DP-27): consult-note autosave timing. One save when typing pauses for idleMs, and never later than maxMs after
// the first unsaved edit of a burst, so a long dictation still reaches the server. Pure: timers are injectable for tests.
export type AutosaveTimers = {
  set: (fn: () => void, ms: number) => unknown;
  clear: (handle: unknown) => void;
  now: () => number;
};

export const AUTOSAVE_IDLE_MS = 3_000;
export const AUTOSAVE_MAX_MS = 30_000;

const realTimers: AutosaveTimers = {
  set: (fn, ms) => setTimeout(fn, ms),
  clear: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
  now: () => Date.now(),
};

export function createAutosave(
  flush: () => void,
  opts: { idleMs?: number; maxMs?: number; timers?: AutosaveTimers } = {},
) {
  const idleMs = opts.idleMs ?? AUTOSAVE_IDLE_MS;
  const maxMs = opts.maxMs ?? AUTOSAVE_MAX_MS;
  const t = opts.timers ?? realTimers;
  let handle: unknown = null;
  let burstStart: number | null = null;

  const cancel = () => {
    if (handle !== null) t.clear(handle);
    handle = null;
    burstStart = null;
  };

  /** Call on every edit. */
  const touch = () => {
    const now = t.now();
    if (burstStart === null) burstStart = now;
    if (handle !== null) t.clear(handle);
    const wait = Math.max(0, Math.min(idleMs, burstStart + maxMs - now));
    handle = t.set(() => {
      handle = null;
      burstStart = null;
      flush();
    }, wait);
  };

  return { touch, cancel, pending: () => handle !== null };
}
