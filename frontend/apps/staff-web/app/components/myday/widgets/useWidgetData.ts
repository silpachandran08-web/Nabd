"use client";

import { useQuery } from "@tanstack/react-query";
import { ApiError } from "../../../lib/api";
import { getter, key } from "../../../lib/query";
import { hidesWidget } from "../../../(clinic)/specialty/specialtyModel";

export type WidgetData<T> = { state: "hidden" } | { state: "loading" } | { state: "error"; retry: () => void } | { state: "ok"; data: T };

/** CLN-043: one widget's own read. Every widget mounts at once, so their reads run in parallel (no waterfall); a 403 (not
 * entitled / no permission) or 404 (data source not deployed) hides the widget silently instead of showing an error. */
export function useWidgetData<T>(id: string, path: string): WidgetData<T> {
  const q = useQuery({ queryKey: key("myday-widget", { id, path }), queryFn: getter<T>(path), staleTime: 30_000 });
  if (q.isPending) return { state: "loading" };
  if (q.isError) return hidesWidget(q.error instanceof ApiError ? q.error.status : undefined) ? { state: "hidden" } : { state: "error", retry: () => void q.refetch() };
  return { state: "ok", data: q.data };
}
