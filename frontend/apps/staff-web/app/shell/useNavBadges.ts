"use client";
// FEF-052: the one badge resolver. Reads the queries the screens themselves share (FEF-015/016), so the live applier (SSE,
// FEF-020) patches the same cache entries and a badge follows a count change without a reload.
import { useEffect } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { adminOrdersQuery, doctorQueueQuery, prefix, queueQuery } from "../lib/query";
import { selfStaffId } from "../lib/doctorQueries";
import type { BadgeSource, RoleContext } from "./navRegistry";

// Same "waiting" bucket as the Arrivals page's own tab counts (arrivals/page.tsx bucketOf).
export const WAITING_STATUSES = ["checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done"];
const count = <T,>(rows: T[] | undefined, keep: (r: T) => boolean) => (Array.isArray(rows) ? rows.filter(keep).length : undefined);

export function useNavBadges(role: RoleContext | null, perms: readonly string[], pathname: string | null) {
  const me = role ? selfStaffId() : "";
  const q = perms.includes("queue:view");
  const reception = useQuery({ ...queueQuery(), enabled: role === "reception" && q }).data;
  const priority = useQuery({ ...queueQuery(true), enabled: role === "nurse" && q }).data;
  const admin = useQuery({ ...adminOrdersQuery(), enabled: role === "nurse" && perms.includes("nursing:view") }).data;
  const mine = useQuery({ ...doctorQueueQuery(me), enabled: role === "doctor" && !!me && q }).data;
  const qc = useQueryClient();
  // Refresh on every navigation as before; a newer refresh cancels a slower one still in flight (FEF-004).
  useEffect(() => {
    void qc.refetchQueries({ queryKey: prefix("queue"), type: "active" });
    void qc.refetchQueries({ queryKey: adminOrdersQuery().queryKey, type: "active" });
  }, [pathname, qc]);
  const badges: Record<BadgeSource, number | undefined> = {
    waiting: count(reception, (r) => WAITING_STATUSES.includes(r.status ?? "")),
    checkout: count(reception, (r) => r.status === "checkout_pending"),
    myday: count(mine, (r) => (!r.doctorId || r.doctorId === me) && WAITING_STATUSES.includes(r.status ?? "")),
    "nurse-priority": count(priority, () => true),
    "nurse-admin": count(admin, (o) => o.status === "not_started"),
  };
  return { badges, me, mine };
}
