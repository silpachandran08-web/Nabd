import { useEffect } from "react";

// Live screens: poll every `ms` while the tab is visible; stop while hidden and reload once when it
// becomes visible again. shortcut: a request already in flight when the tab hides is not aborted, upgrade with per-load sequence + AbortController (FEF-004 race half).
export function usePolling(load: () => unknown, ms: number) {
  useEffect(() => {
    let id: ReturnType<typeof setInterval> | undefined;
    const sync = () => {
      clearInterval(id);
      if (document.visibilityState === "visible") id = setInterval(load, ms);
    };
    const onVisibility = () => {
      sync();
      if (document.visibilityState === "visible") void load();
    };
    sync();
    document.addEventListener("visibilitychange", onVisibility);
    return () => { clearInterval(id); document.removeEventListener("visibilitychange", onVisibility); };
  }, [load, ms]);
}
