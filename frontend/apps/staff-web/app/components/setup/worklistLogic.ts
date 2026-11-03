// PHL-012: pure rules of the In-house dispensing worklist board (no React, so node:test covers them).
import type { PharmacyWorklistEntry, PharmacyWorklistLine } from "@nabd/api-client/generated";
import type { Full } from "../../lib/gen";

export type WorklistLine = Full<PharmacyWorklistLine, "drug">;
export type WorklistEntry = Omit<Full<PharmacyWorklistEntry, "queueEntryId" | "token" | "patientName" | "doctorName" | "claimedBy" | "claimedByName" | "validUntil">, "lines"> & { lines: WorklistLine[] };

/** The SSE resource the pharmacy.worklist topic makes stale (packages/api-client/sse resourcesFor). */
export const WORKLIST_RESOURCE = "pharmacy-worklist";
/** The open statuses the board loads in full; Ready shows the newest dispensed ones only. */
export const OPEN_QUERY = "status=new,in_progress,partially_dispensed&limit=100";
export const READY_QUERY = "status=dispensed&limit=20";
export const MAX_OPEN_PAGES = 5;

export const COLUMNS = [
  { key: "new", title: "New", statuses: ["new"] },
  { key: "progress", title: "In progress", statuses: ["in_progress", "partially_dispensed"] },
  { key: "ready", title: "Ready", statuses: ["dispensed"] },
] as const;

/** Entries of one column, oldest first (first come, first served); the server pages newest first. */
export function columnEntries(entries: readonly WorklistEntry[], statuses: readonly string[]): WorklistEntry[] {
  return entries.filter((e) => statuses.includes(e.status)).sort((a, b) => a.signedAt.localeCompare(b.signedAt) || a.id.localeCompare(b.id));
}

/** Token + first name only on the board (the counter is public). */
export function cardTitle(e: Pick<WorklistEntry, "token" | "patientName">): string {
  const first = (e.patientName ?? "").trim().split(/\s+/)[0] || "Patient";
  return e.token != null ? `T-${e.token} · ${first}` : first;
}

export function lineText(l: WorklistLine): string {
  const done = l.qtyDispensed > 0 ? ` (${l.qtyDispensed} of ${l.qtyPrescribed} given)` : "";
  return `${l.drug ?? "Medicine"} × ${l.qtyPrescribed}${done}`;
}

/** A refused Start: "Taken by <name>" for 409 already-claimed, the expiry line for rx-expired, else the server's words. */
export function claimRefusal(e: { status?: number; detail?: string; problem?: Record<string, unknown> } | null | undefined): string {
  const type = String(e?.problem?.type ?? "");
  if (e?.status === 409 && type.endsWith("/already-claimed")) {
    const name = e.problem?.claimedByName;
    return `Taken by ${typeof name === "string" && name ? name : "another pharmacist"}.`;
  }
  if (type.endsWith("/rx-expired")) return "This prescription has expired and can no longer be dispensed.";
  return e?.detail || "Couldn't start this prescription. Try again.";
}

/** J / K move through the cards in board order (wrapping at the ends); -1 when there are none. */
export function moveFocus(current: number, count: number, key: string): number {
  if (count === 0) return -1;
  if (key === "j" || key === "J") return current < 0 ? 0 : (current + 1) % count;
  if (key === "k" || key === "K") return current < 0 ? count - 1 : (current - 1 + count) % count;
  return current;
}
