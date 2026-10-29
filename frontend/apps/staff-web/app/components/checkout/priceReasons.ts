"use client";

// FIN-003 / FIN-018: coded reasons for a price override, free-text charge or discount come from the shared ACC-007 list,
// kind 'discount' (GET /v1/reason-codes?kind=discount), in the viewer's language. The server validates the same list.
import { useQuery } from "@tanstack/react-query";
import { useLocale } from "next-intl";
import { getter, key } from "../../lib/query";
import type { ReasonItem } from "../../lib/reasonPicker";

export function usePriceReasons(): { items: ReasonItem[]; loading: boolean; error: boolean; label: (code: string | null | undefined) => string } {
  const locale = useLocale();
  const q = useQuery({
    queryKey: key("reason-codes", { kind: "discount", locale }),
    queryFn: getter<{ items: ReasonItem[] }>(`/reason-codes?kind=discount&locale=${encodeURIComponent(locale)}`),
    staleTime: 5 * 60_000,
  });
  const items = q.data?.items ?? [];
  return { items, loading: q.isPending, error: q.isError, label: (code) => items.find((r) => r.code === code)?.label ?? (code ?? "") };
}
