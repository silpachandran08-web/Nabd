// FEF-020: what a live message does to the query cache. React-free and tenant-agnostic: the caller passes the
// tenant-scoped key prefix builder (["t", tenantId, resource]). Payloads carry no PHI ({type,id,v} plus a few
// scalars), so a delta patches only a field it carries; otherwise the active query refetches (invalidate).
import type { LiveMessage } from "./stream.ts";

export type CacheLike = {
  getQueriesData(f: { queryKey: readonly unknown[] }): [readonly unknown[], unknown][];
  setQueryData(key: readonly unknown[], updater: (old: unknown) => unknown): unknown;
  invalidateQueries(f: { queryKey: readonly unknown[] }): unknown;
};

/** Resources that live messages keep fresh; a snapshot (connect, resume gap) invalidates all of them. */
export const LIVE_RESOURCES = ["queue", "wait-estimate", "doctor-delay", "nursing", "roster", "doctor-status", "patient-messages", "desk-exceptions", "pharmacy-worklist"] as const;

/** Event type (without the .vN suffix) -> the cache resources it makes stale. */
export function resourcesFor(type: string): readonly string[] {
  if (type.startsWith("queue.")) return ["queue", "wait-estimate"];
  if (type.startsWith("nursing.")) return ["nursing"];
  if (type.startsWith("doctor.status")) return ["roster", "doctor-delay", "wait-estimate", "doctor-status"];
  if (type.startsWith("comms.message.")) return ["patient-messages"]; // COM-006
  if (type.startsWith("scheduling.booking.") || type.startsWith("scheduling.appointment.")) return ["booking-requests"]; // ACC-010
  if (type.startsWith("desk.exception.")) return ["desk-exceptions"]; // ACC-032
  if (type.startsWith("billing.discount.")) return ["discount-approvals"]; // FIN-018: desk banner + owner inbox
  if (type.startsWith("pharmacy.worklist.")) return ["pharmacy-worklist"]; // PHL-012: the In-house dispensing worklist
  return [];
}

const PATCHABLE = ["status"] as const; // scalar fields a queue delta may carry

export function createApplier(cache: CacheLike, prefix: (resource: string) => readonly unknown[]) {
  const seen = new Map<string, number>(); // "type:id" -> highest v applied
  const touch = (resource: string) => cache.invalidateQueries({ queryKey: prefix(resource) });

  return function apply(m: LiveMessage): readonly string[] {
    if (m.event === "snapshot") {
      seen.clear();
      LIVE_RESOURCES.forEach(touch);
      return LIVE_RESOURCES;
    }
    const { type, id, doctorId, v } = m.data as { type?: unknown; id?: unknown; doctorId?: unknown; v?: unknown };
    if (typeof type !== "string") return [];
    const entity = `${type.replace(/\.v\d+$/, "")}:${String(id ?? doctorId ?? "")}`;
    if (typeof v === "number") {
      const known = seen.get(entity);
      if (known !== undefined && v <= known) return []; // older or duplicate: ignored
      seen.set(entity, v);
    }
    const resources = resourcesFor(type);
    const patch: Record<string, unknown> = {};
    for (const f of PATCHABLE) if (typeof m.data[f] === "string") patch[f] = m.data[f];
    let patched = false;
    if (type.startsWith("queue.") && typeof id === "string" && Object.keys(patch).length) {
      for (const [key, data] of cache.getQueriesData({ queryKey: prefix("queue") })) {
        if (!Array.isArray(data) || !data.some((r) => r?.id === id)) continue;
        cache.setQueryData(key, (old) => (old as { id?: string }[]).map((r) => (r.id === id ? { ...r, ...patch } : r)));
        patched = true;
      }
    }
    // A patched row is current for the fields we know; the rest of the queue (position, wait) still moved.
    for (const r of resources) if (!(patched && r === "queue")) touch(r);
    return resources;
  };
}
