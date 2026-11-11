"use client";
// ACC-035: the signed-in staff member's tour and competency progress (GET /v1/me/onboarding), cached once per session above
// the route (TanStack, never refetched on focus) and updated from each PUT's answer, so the tour card and the help panel agree.
import { useCallback } from "react";
import { queryOptions, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { getter, key } from "../lib/query";
import type { Onboarding } from "./help";

const onboardingQuery = () => queryOptions({
  queryKey: key("me/onboarding"), queryFn: getter<Onboarding>("/me/onboarding"), staleTime: Infinity, refetchOnWindowFocus: false,
});

export function useOnboarding(enabled = true) {
  const qc = useQueryClient();
  const q = useQuery({ ...onboardingQuery(), enabled });
  const complete = useCallback(async (k: string) => {
    // optimistic: a finished tour must not restart while the PUT is in flight (a failed PUT keeps it done for this session)
    qc.setQueryData(onboardingQuery().queryKey, (old) => (old && !old.completed.includes(k) ? { ...old, completed: [...old.completed, k] } : old));
    const next = await api.put<Onboarding>(`/me/onboarding/${encodeURIComponent(k)}`);
    qc.setQueryData(onboardingQuery().queryKey, next);
    return next;
  }, [qc]);
  return { progress: q.data, loading: q.isPending && enabled, failed: q.isError, retry: q.refetch, complete };
}
