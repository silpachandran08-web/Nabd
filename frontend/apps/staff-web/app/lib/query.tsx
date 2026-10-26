"use client";

// FEF-015 (ADR-06): the clinic app's server-state cache. Keys and staleTimes live in @nabd/api-client/queries;
// this file binds them to the session's tenant and the configured api client.
import { useCallback, useEffect, useState } from "react";
import { QueryClient, QueryClientProvider, queryOptions, useQueryClient } from "@tanstack/react-query";
import { qk, qkPrefix, STALE, type QueryParams } from "@nabd/api-client/queries";
import type { AdministrationOrderResponse, QueueEntryResponse } from "@nabd/api-client/generated";
import { api } from "./api";
import { decodeAccessToken } from "./session";

export function QueryProvider({ children }: { children: React.ReactNode }) {
  // No automatic retries: a failed read shows its error state at once (mutations retry inside the api client).
  // Mutations run even offline (networkMode "always"): they fail fast and roll back with their error message instead
  // of pausing silently and replaying the write when the connection returns.
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { networkMode: "always" } } }));
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function tenantId(): string {
  if (typeof window === "undefined") return "";
  const token = localStorage.getItem("nabd_access_token");
  return (token && decodeAccessToken(token)?.tenantId) || "";
}

export const key = (resource: string, params?: QueryParams) => qk(tenantId(), resource, params);
export const prefix = (resource: string) => qkPrefix(tenantId(), resource);
/** queryFn for a GET; TanStack's AbortSignal cancels it when the query is cancelled or superseded. */
export const getter = <T,>(path: string) => ({ signal }: { signal: AbortSignal }) => api.get<T>(path, { signal });

// Live lists are refreshed by usePolling(useReload(...)) on the pages that show them, not by TanStack's focus
// refetch: that one joins an in-flight request, while a reload must supersede it (FEF-004 latest wins).
export const live = { staleTime: STALE.queue, refetchOnWindowFocus: false } as const;

/** GET /queue[?priority=true]: Arrivals and the reception sidebar badge share one request (same key). */
export const queueQuery = (priority = false) => queryOptions({
  ...live,
  queryKey: key("queue", priority ? { priority: true } : {}),
  queryFn: getter<QueueEntryResponse[]>(priority ? "/queue?priority=true" : "/queue"),
});

/** GET /queue?doctorId=<me>: My Day, the consult queue and the doctor sidebar badge share one request (FEF-016). */
export const doctorQueueQuery = (doctorId: string) => queryOptions({
  ...live,
  queryKey: key("queue", { doctorId }),
  queryFn: getter<QueueEntryResponse[]>(`/queue?doctorId=${encodeURIComponent(doctorId)}`),
});

/** GET /nursing/administration-orders/today: the Nursing page and the nurse sidebar badge share it. */
export const adminOrdersQuery = () => queryOptions({
  ...live,
  queryKey: key("nursing", { list: "administration-orders" }),
  queryFn: getter<AdministrationOrderResponse[]>("/nursing/administration-orders/today"),
});

/** Cancel whatever is in flight under these resources and fetch again; skipped while a mutation is
 * in flight so a poll never paints over an optimistic row. */
export function useReload(...resources: string[]) {
  const qc = useQueryClient();
  const joined = resources.join(",");
  return useCallback(async () => {
    if (qc.isMutating() > 0) return;
    await Promise.all(joined.split(",").map(async (r) => {
      await qc.cancelQueries({ queryKey: prefix(r) });
      await qc.refetchQueries({ queryKey: prefix(r), type: "active" });
    }));
  }, [qc, joined]);
}

/** The value, once it has stopped changing for `ms` (search-as-you-type). */
export function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return settled;
}
