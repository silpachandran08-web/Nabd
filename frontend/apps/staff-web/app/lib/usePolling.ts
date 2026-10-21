import { useEffect } from "react";
import { useLiveStatus } from "./live";

// Live screens: poll every `ms` while the tab is visible; stop while hidden and reload once when it
// becomes visible again. FEF-020: once the shared SSE stream is up (or deliberately paused, or in its own 60 s
// fallback) the stream keeps the screen fresh, so this poll only runs while there is no stream ("off") or it
// is still connecting.
export function usePolling(load: () => unknown, ms: number) {
  const status = useLiveStatus();
  const idle = status !== "off" && status !== "connecting";
  useEffect(() => {
    if (idle) return;
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
  }, [load, ms, idle]);
}
