"use client";

// FEF-046: the clinic's default paper for a document type (policy print.default_paper.<type>, a4 | a5 | 80mm).
// Reading policies needs setup:view; anyone without it, or when the read fails, gets the built-in default,
// so the print button never asks and never blocks.
import { useQuery } from "@tanstack/react-query";
import { policyKey, resolvePaper, type DocType, type Paper } from "@nabd/ui";
import type { PolicyResponse } from "@nabd/api-client/generated";
import { api } from "./api";
import { key } from "./query";
import { can } from "./permissions";

export function usePrintPaper(type: DocType): Paper {
  const q = useQuery({
    queryKey: key("print-policies"),
    enabled: can("setup:view"),
    staleTime: 5 * 60_000,
    retry: false,
    queryFn: ({ signal }) => api.get<PolicyResponse[]>("/setup/policies", { signal }),
  });
  return resolvePaper(type, q.data?.find((p) => p.policyKey === policyKey(type))?.value);
}
