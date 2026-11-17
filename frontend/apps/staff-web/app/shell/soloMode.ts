// FEF-056: is this clinic in solo mode (ACC-013 booking policy `solo`: the doctor runs the clinic without a front desk)? Read once at
// sign-in, not by the shell on every page (the shell fetches no feature data), and remembered per clinic so a reload keeps the solo shell.
// shortcut: a policy change made after sign-in applies at the next sign-in; push it from the publish response if owners ask for it sooner.
import { api } from "../lib/api";
import { rolesFor } from "./navRegistry";

const KEY = "nabd_solo_mode"; // value = the clinic (tenant) id that is in solo mode

export function soloMode(tenantId: string | null): boolean {
  try { return !!tenantId && localStorage.getItem(KEY) === tenantId; } catch { return false; }
}

/** Refreshes the flag for an owner who is also the doctor (the only session the solo shell is for). A failed or slow read keeps the last answer. */
export async function loadSoloMode(tenantId: string | null, perms: readonly string[], owner: boolean): Promise<boolean> {
  if (!tenantId || !rolesFor(perms, owner, true).includes("solo")) return false;
  const r = await api.get<{ published: { config?: { solo?: boolean } } | null }>("/booking-policy", { keepSession: true, signal: AbortSignal.timeout(3000) }).catch(() => null);
  if (r) {
    try { if (r.published?.config?.solo === true) localStorage.setItem(KEY, tenantId); else localStorage.removeItem(KEY); } catch { /* blocked storage: no solo shell */ }
  }
  return soloMode(tenantId);
}
