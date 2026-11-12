"use client";
// ACC-035: the signed-in staff member's tour and competency progress (GET /v1/me/onboarding), cached once per session above
// the route (TanStack, never refetched on focus) and updated from each PUT's answer, so the tour card and the help panel agree.
import { useCallback } from "react";
import { queryOptions, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { getter, key } from "../lib/query";
import { asOnboarding, type Onboarding } from "./help";

// A body of the wrong shape is a failed read (error state), so the tour just does not start and the shell keeps working.
const read = getter<unknown>("/me/onboarding");
const onboardingQuery = () => queryOptions({
  queryKey: key("me/onboarding"),
  queryFn: async (ctx: { signal: AbortSignal }): Promise<Onboarding> => {
    const p = asOnboarding(await read(ctx));
    if (!p) throw new Error("unexpected /me/onboarding answer");
    return p;
  },
  staleTime: Infinity, refetchOnWindowFocus: false,
});

export function useOnboarding(enabled = true) {
  const qc = useQueryClient();
  const q = useQuery({ ...onboardingQuery(), enabled });
  const complete = useCallback(async (k: string) => {
    // optimistic: a finished tour must not restart while the PUT is in flight (a failed PUT keeps it done for this session)
    qc.setQueryData(onboardingQuery().queryKey, (old) => (old && !old.completed.includes(k) ? { ...old, completed: [...old.completed, k] } : old));
    const next = asOnboarding(await api.put<unknown>(`/me/onboarding/${encodeURIComponent(k)}`));
    if (!next) throw new Error("unexpected /me/onboarding answer"); // keeps the optimistic state; callers show their save error
    qc.setQueryData(onboardingQuery().queryKey, next);
    return next;
  }, [qc]);
  return { progress: q.data, loading: q.isPending && enabled, failed: q.isError, retry: q.refetch, complete };
}
