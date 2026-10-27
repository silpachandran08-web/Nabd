"use client";

import { queryOptions, useQuery } from "@tanstack/react-query";
import type { VisitSummaryResponse as VisitSummary } from "@nabd/api-client/generated";
import { getter, key } from "../../lib/query";

/** GET /clinical/visits/{id}/summary: one call for the whole consult card stack (CLN-008). Shared by prefetch and the screen. */
export const visitSummaryQuery = (entryId: string) => queryOptions({
  queryKey: key("visit-summary", { entryId }), staleTime: 30_000,
  queryFn: getter<VisitSummary>(`/clinical/visits/${entryId}/summary`),
});

export const useVisitSummary = (entryId: string | null) => useQuery({ ...visitSummaryQuery(entryId ?? ""), enabled: !!entryId });
