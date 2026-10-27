"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "../../lib/api";
import { useLiveRefresh } from "../../lib/live";
import { usePolling } from "../../lib/usePolling";
import type { FilterId, Message, Thread } from "./inboxLogic";

type Page<T> = { items?: T[]; nextCursor?: string | null };

/** GET /v1/inbox/threads (oldest waiting first). Refreshes on the SSE `messages` topic and by polling while there is no stream. */
export function useThreads(filter: FilterId) {
  const [threads, setThreads] = useState<Thread[] | null>(null);
  const [failed, setFailed] = useState(false);
  const seq = useRef(0);
  const load = useCallback(async () => {
    const my = ++seq.current;
    try {
      const page = await api.get<Page<Thread>>(`/inbox/threads?filter=${filter}&limit=100`);
      if (my !== seq.current) return;
      setThreads(page.items ?? []);
      setFailed(false);
    } catch (e) {
      if (my === seq.current && !(e instanceof ApiError && e.status === 401)) setFailed(true);
    }
  }, [filter]);
  useEffect(() => { setThreads(null); void Promise.resolve().then(load); }, [load]); // eslint-disable-line react-hooks/set-state-in-effect
  useLiveRefresh(["messages"], load);
  usePolling(load, 15_000);
  return { threads, failed, reload: load };
}

/** GET /v1/inbox/threads/{id}/messages, shown oldest first. Marks the thread read up to its newest patient message. */
export function useMessages(threadId: string | null, onRead: () => void) {
  const [messages, setMessages] = useState<Message[] | null>(null);
  const [failed, setFailed] = useState(false);
  const seq = useRef(0);
  const readUpTo = useRef<string>("");
  const load = useCallback(async () => {
    if (!threadId) return;
    const my = ++seq.current;
    try {
      const page = await api.get<Page<Message>>(`/inbox/threads/${threadId}/messages?limit=100`);
      if (my !== seq.current) return;
      const items = page.items ?? [];
      setMessages([...items].reverse());
      setFailed(false);
      const newestIn = items.find((m) => m.direction === "in");
      if (newestIn && readUpTo.current !== `${threadId}:${newestIn.id}`) {
        readUpTo.current = `${threadId}:${newestIn.id}`;
        void api.post(`/inbox/threads/${threadId}/read`, { upToMessageId: newestIn.id }).then(onRead).catch(() => {});
      }
    } catch (e) {
      if (my === seq.current && !(e instanceof ApiError && e.status === 401)) setFailed(true);
    }
  }, [threadId, onRead]);
  useEffect(() => { setMessages(null); setFailed(false); void Promise.resolve().then(load); }, [load]); // eslint-disable-line react-hooks/set-state-in-effect
  useLiveRefresh(["messages"], load);
  usePolling(load, 15_000);
  return { messages, failed, reload: load };
}
