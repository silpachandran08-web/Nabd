// FEF-034: the palette's action registry. An action is offered only when the session holds its grants (and, where set, the role context),
// so a restricted action is absent, never disabled. `run` yields the route to open; the palette never calls the API for an action.
import type { RoleContext } from "./navRegistry";
import { fuzzyMatch } from "./shortcuts.ts";

export type PaletteAction = { id: string; label: string; keywords: string[]; needs: string[]; roles?: RoleContext[]; href: string; hint?: string };

export const ACTIONS: PaletteAction[] = [
  { id: "walkin", label: "Register walk-in", keywords: ["walk-in", "walkin", "token", "check in"], needs: ["queue:create", "patients:view"], href: "/arrivals?action=walkin", hint: "W" },
  { id: "new-patient", label: "Register new patient", keywords: ["new patient", "register", "walk-in new"], needs: ["queue:create", "patients:view", "patients:create"], href: "/arrivals?action=new", hint: "F2" },
  { id: "check-in", label: "Check in today's appointment", keywords: ["check in", "checkin", "arrived"], needs: ["queue:create"], href: "/arrivals?action=checkin", hint: "F4" },
  { id: "checkout", label: "Checkout a patient", keywords: ["checkout", "bill", "invoice"], needs: ["billing:view"], href: "/arrivals?tab=checkout_pending" },
];

/** Actions the session may run that match the query. `nextHref`: the doctor's next waiting patient, when there is one (action "Next patient"). */
export function actionTargets(perms: readonly string[], role: RoleContext | null, query: string, nextHref?: string | null): PaletteAction[] {
  const all: PaletteAction[] = nextHref && role === "doctor" ? [{ id: "next", label: "Next patient", keywords: ["call next", "next"], needs: ["clinical:edit", "queue:view"], href: nextHref, hint: "N" }, ...ACTIONS] : ACTIONS;
  return all.filter((a) => a.needs.every((p) => perms.includes(p)) && (!a.roles || (!!role && a.roles.includes(role))))
    .filter((a) => !query.trim() || [a.label, ...a.keywords].some((s) => fuzzyMatch(query, s)));
}
