// PHL-043: lab orders from the consult: shapes and rules (pure: unit-tested in tests/unit/s27-phl043-lab-orders.test.mjs).
import type { Entitlements } from "./entitlementRules";
import type { LabPanel, LabTest } from "./labCatalogue";

export type Priority = "routine" | "urgent" | "stat";
export const PRIORITIES: Priority[] = ["routine", "urgent", "stat"];
export type DestinationKind = "inhouse" | "external";
export const DESTINATIONS: DestinationKind[] = ["inhouse", "external"];

/** One thing a doctor can order: a test or a panel of the clinic's catalogue. */
export type Orderable = { kind: "test" | "panel"; id: string; code: string; name: string; price: number; testIds: string[] };
export type Favourite = { kind: "test" | "panel"; id: string; code: string; name: string; price: number; orders: number };
export type OrderCharge = { chargeCode: string; name: string; amount: number };
export type FastingNotice = "requested" | "blocked" | "template_missing";
export type OrderPlaced = { orderId: string; accessionHint: string; charges: OrderCharge[]; fastingNotice?: FastingNotice | null };
export type OrderItem = { id: string; testId: string; code: string; name: string; panelId?: string | null; panelName?: string | null; status: string; price: number };
export type LabOrder = {
  id: string; accessionHint: string; patientId: string; queueEntryId?: string | null; priority: Priority; destinationKind: string; status: string;
  clinicalNote?: string | null; fastingHours?: number | null; orderedAt: string; total: number; cancellable: boolean; items: OrderItem[];
};
export type Cancelled = { orderId: string; status: string; chargesRemoved: number; chargesAlreadyBilled: number };

/**
 * What the consult's Tests card is for this user and clinic.
 *  wait:     the first entitlements answer has not arrived: nothing is mounted and nothing is requested
 *  unknown:  the entitlements call failed: say so with a manual retry (never an automatic one)
 *  orders:   the clinic has the lab module and the user may order (lab:order) and read the catalogue (lab:view)
 *  external: the clinic has no lab module: only an order sheet for an outside lab, for whoever writes the visit (clinical:edit)
 *  none:     nothing to offer: the card is absent
 */
export type TestsMode = "wait" | "unknown" | "orders" | "external" | "none";
export function testsMode(ent: Entitlements | null, settled: boolean, perms: readonly string[]): TestsMode {
  const writer = perms.includes("clinical:edit");
  const orderer = perms.includes("lab:order") && perms.includes("lab:view");
  if (!writer && !orderer) return "none";
  if (!settled) return "wait";
  if (!ent) return "unknown";
  const lab = ent.modules.some((m) => m.code === "lab" && m.enabled);
  if (lab) return orderer ? "orders" : "none";
  return writer ? "external" : "none";
}

/** The catalogue as the typeahead's client index: active tests and panels only. */
export function catalogueIndex(tests: readonly LabTest[], panels: readonly LabPanel[]): Orderable[] {
  return [
    ...panels.filter((p) => p.active).map((p): Orderable => ({ kind: "panel", id: p.id, code: p.code, name: p.name, price: Number(p.price), testIds: p.tests.map((t) => t.id) })),
    ...tests.filter((t) => t.active).map((t): Orderable => ({ kind: "test", id: t.id, code: t.code, name: t.name, price: Number(t.price), testIds: [t.id] })),
  ];
}

/** Typeahead over the client index: a code that starts with the text first, then a name word that starts with it, then anything containing it. */
export function searchCatalogue(index: readonly Orderable[], text: string, limit = 8): Orderable[] {
  const q = text.trim().toLowerCase();
  if (!q) return [];
  const rank = (o: Orderable): number => {
    const code = o.code.toLowerCase(), name = o.name.toLowerCase();
    if (code.startsWith(q)) return 0;
    if (name.split(/[\s(),/-]+/).some((w) => w.startsWith(q))) return 1;
    return code.includes(q) || name.includes(q) ? 2 : -1;
  };
  return index.map((o) => ({ o, r: rank(o) })).filter((x) => x.r >= 0)
    .sort((a, b) => a.r - b.r || a.o.name.localeCompare(b.o.name)).slice(0, limit).map((x) => x.o);
}

/** The picked item that already holds one of `o`'s tests (the server refuses a test ordered twice), or null. */
export function clash(picked: readonly Orderable[], o: Orderable): Orderable | null {
  return picked.find((p) => p.testIds.some((id) => o.testIds.includes(id))) ?? null;
}

export const orderTotal = (picked: readonly Orderable[]): number => Math.round(picked.reduce((s, o) => s + o.price, 0) * 100) / 100;

export type OrderForm = { picked: Orderable[]; priority: Priority; destination: DestinationKind; fasting: boolean; fastingHours: string; note: string };
export const emptyOrder = (): OrderForm => ({ picked: [], priority: "routine", destination: "inhouse", fasting: false, fastingHours: "", note: "" });

const hoursOk = (v: string): boolean => /^\d{1,2}$/.test(v.trim()) && Number(v) >= 1 && Number(v) <= 24;
export type OrderError = "items" | "fasting" | "note";
export function orderError(f: OrderForm): OrderError | null {
  if (f.picked.length === 0 || f.picked.length > 30) return "items";
  if (f.fasting && !hoursOk(f.fastingHours)) return "fasting";
  if (f.note.trim().length > 500) return "note";
  return null;
}

export function orderBody(f: OrderForm, patientId: string, queueEntryId: string): Record<string, unknown> {
  return {
    patientId, queueEntryId,
    items: f.picked.map((o) => (o.kind === "panel" ? { panelId: o.id } : { testId: o.id })),
    priority: f.priority, destination: { kind: f.destination },
    ...(f.note.trim() ? { clinicalNote: f.note.trim() } : {}),
    ...(f.fasting ? { fastingHours: Number(f.fastingHours) } : {}),
  };
}

/** Which sentence tells the doctor what happened to the fasting notice; null when the order needs none. */
export function fastingMessage(n: FastingNotice | null | undefined): "fastingSent" | "fastingBlocked" | "fastingNoTemplate" | null {
  return n === "requested" ? "fastingSent" : n === "blocked" ? "fastingBlocked" : n === "template_missing" ? "fastingNoTemplate" : null;
}

/** STAT first, then urgent, then routine (the server sends them so; this keeps a just-placed order in its place before the refetch). */
export function byPriority<T extends { priority: Priority; orderedAt: string }>(orders: readonly T[]): T[] {
  const rank = (p: Priority) => (p === "stat" ? 0 : p === "urgent" ? 1 : 2);
  return [...orders].sort((a, b) => rank(a.priority) - rank(b.priority) || a.orderedAt.localeCompare(b.orderedAt));
}

// ---- the order sheet of a clinic without the lab module: the doctor types the tests ----
export type SheetForm = { tests: string; labName: string; priority: Priority; fasting: boolean; fastingHours: string; note: string };
export const emptySheet = (): SheetForm => ({ tests: "", labName: "", priority: "routine", fasting: false, fastingHours: "", note: "" });
/** One test per line; blank lines dropped. */
export const sheetTests = (text: string): string[] => text.split("\n").map((l) => l.trim()).filter(Boolean);
export type SheetError = "tests" | "testLength" | "fasting" | "note";
export function sheetError(f: SheetForm): SheetError | null {
  const tests = sheetTests(f.tests);
  if (tests.length === 0 || tests.length > 30) return "tests";
  if (tests.some((t) => t.length > 120)) return "testLength";
  if (f.fasting && !hoursOk(f.fastingHours)) return "fasting";
  if (f.note.trim().length > 500) return "note";
  return null;
}
export function sheetBody(f: SheetForm): Record<string, unknown> {
  return {
    tests: sheetTests(f.tests), priority: f.priority,
    ...(f.labName.trim() ? { labName: f.labName.trim() } : {}),
    ...(f.note.trim() ? { clinicalNote: f.note.trim() } : {}),
    ...(f.fasting ? { fastingHours: Number(f.fastingHours) } : {}),
  };
}

export const CANCEL_REASON_MAX = 200;
export const cancelReasonOk = (v: string): boolean => v.trim().length > 0 && v.trim().length <= CANCEL_REASON_MAX;
