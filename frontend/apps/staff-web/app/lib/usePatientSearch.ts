"use client";

// FEF-017: the debounced patient search the front desk uses in New appointment, Waitlist and Packages
// (GET /patients?q=&limit=). One cached query per (q, limit); fewer than 2 characters asks nothing.
import { useQuery } from "@tanstack/react-query";
import type { PatientPage, PatientResponse } from "@nabd/api-client/generated";
import { getter, key, useDebounced } from "./query";
import type { Full } from "./gen";

export type PatientHit = Full<Pick<PatientResponse, "id" | "mrn" | "name" | "phone">>;

export function usePatientSearch(query: string, limit: number) {
  const typed = query.trim();
  const q = useDebounced(typed, 250);
  const r = useQuery({
    queryKey: key("patient-search", { q, limit }),
    queryFn: getter<PatientPage>(`/patients?q=${encodeURIComponent(q)}&limit=${limit}`),
    enabled: q.length >= 2, staleTime: 30_000,
  });
  const active = typed.length >= 2;
  const results = (active ? r.data?.data ?? [] : []) as PatientHit[];
  const settled = active && q === typed && r.isSuccess;
  return { results, failed: active && r.isError, empty: settled && results.length === 0 };
}
