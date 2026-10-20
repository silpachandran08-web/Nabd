import { useCallback, useEffect, useRef } from "react";

// FEF-004: each call aborts the previous load and returns a fresh signal. A load must pass the signal
// to its fetches and stop (no setState) once `signal.aborted`; unmount aborts the last one.
export function useLatestLoad() {
  const ref = useRef<AbortController | null>(null);
  useEffect(() => () => ref.current?.abort(), []);
  return useCallback(() => {
    ref.current?.abort();
    ref.current = new AbortController();
    return ref.current.signal;
  }, []);
}
