"use client";

// FEF-020: the one shared SSE connection (packages/api-client/sse) bound to this app. Deltas patch or invalidate the
// TanStack cache (screens that read it update by themselves); screens that keep their own state subscribe with
// useLiveRefresh. Only active with the cookie-auth BFF (EventSource cannot send a bearer token); otherwise the
// status stays "off" and the screens keep their polling.
import { createContext, useContext, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { createApplier, createLiveStream, LIVE_RESOURCES, type LiveStatus } from "@nabd/api-client/sse";
import { COOKIE_AUTH } from "./bffShim";
import { prefix } from "./query";
import { getPermissions, SHELL_SKIP_PREFIXES } from "./session";

export type LiveState = LiveStatus | "off";
type Listener = (resources: readonly string[]) => void;
type Ctx = { status: LiveState; subscribe(l: Listener): () => void };
const LiveCtx = createContext<Ctx>({ status: "off", subscribe: () => () => {} });

/** Topics this session may read (the server filters too: 403 TOPIC_FORBIDDEN). */
function topicsFor(): string[] {
  const perms = getPermissions();
  const t: string[] = [];
  if (perms.includes("queue:view")) t.push("queue", "doctor-status");
  if (perms.includes("queue:create")) t.push("requests"); // ACC-010: held booking requests
  if (perms.includes("nursing:view")) t.push("nursing");
  if (perms.includes("patients:view")) t.push("messages"); // COM-006: delivery ticks on the patient file
  return t;
}

export function LiveProvider({ children }: { children: React.ReactNode }) {
  const qc = useQueryClient();
  const pathname = usePathname();
  const [status, setStatus] = useState<LiveState>("off");
  const listeners = useRef(new Set<Listener>());
  const [subscribe] = useState(() => (l: Listener) => { listeners.current.add(l); return () => { listeners.current.delete(l); }; });
  const skip = SHELL_SKIP_PREFIXES.some((p) => pathname?.startsWith(p));
  const [topics, setTopics] = useState("");

  // Re-read the grants on navigation (sign-in, role change); the stream restarts only when the topic set changes.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setTopics(COOKIE_AUTH && !skip && localStorage.getItem("nabd_access_token") ? topicsFor().join(",") : "");
  }, [pathname, skip]);

  useEffect(() => {
    if (!topics) { setStatus("off"); return; } // eslint-disable-line react-hooks/set-state-in-effect
    const apply = createApplier(qc, prefix);
    const emit = (r: readonly string[]) => listeners.current.forEach((l) => l(r));
    const stream = createLiveStream({
      url: `/api/events?topics=${topics}`,
      onMessage: (m) => emit(apply(m)),
      onStatus: setStatus,
      onFallbackPoll: () => { LIVE_RESOURCES.forEach((r) => void qc.invalidateQueries({ queryKey: prefix(r) })); emit(LIVE_RESOURCES); },
    });
    return () => { stream.stop(); setStatus("off"); };
  }, [topics, qc]);

  return <LiveCtx.Provider value={{ status, subscribe }}>{children}</LiveCtx.Provider>;
}

export const useLiveStatus = () => useContext(LiveCtx).status;

/** Runs `load` (coalesced, 250 ms) when a live message makes one of `resources` stale. For screens that
 * keep their own state instead of reading the query cache. */
export function useLiveRefresh(resources: readonly string[], load: () => unknown) {
  const { subscribe } = useContext(LiveCtx);
  const loadRef = useRef(load);
  useEffect(() => { loadRef.current = load; });
  const wanted = resources.join(",");
  useEffect(() => {
    let t: ReturnType<typeof setTimeout> | undefined;
    const off = subscribe((r) => {
      if (!r.some((x) => wanted.split(",").includes(x))) return;
      clearTimeout(t);
      t = setTimeout(() => void loadRef.current(), 250);
    });
    return () => { off(); clearTimeout(t); };
  }, [subscribe, wanted]);
}

/** A number that changes whenever `resources` go stale: an effect dependency for fetch-on-change code. */
export function useLiveTick(resources: readonly string[]): number {
  const [n, setN] = useState(0);
  useLiveRefresh(resources, () => setN((x) => x + 1));
  return n;
}
