// PLT-059: GET /v1/me/entitlements, pure helpers (unit-tested; the fetching hook is entitlements.ts).
export type Entitlements = {
  modules: { code: string; enabled: boolean }[];
  killedFeatures: string[];
  seatLimit: number | null;
  /** ADM-020: the clinic's lifecycle state (suspended = read-only, overdue = the owner's payment banner). Absent on an older server. */
  tenantStatus?: string | null;
  /** PHL-032, PHL-056: "pharmacy" or "lab" for a pharmacy-only or lab-only tenant; absent on an older backend (treated as a clinic). */
  edition?: string;
};

/** PHL-032: a pharmacy-only tenant sees only these areas (and their sub-routes); the server answers 403 on every clinical or scheduling endpoint. */
export const PHARMACY_EDITION_HREFS = ["/pharmacy", "/checkout/otc", "/billing", "/reports", "/overview", "/setup", "/staff", "/plan", "/compliance", "/account", "/patients"];
export const pharmacyEdition = (ent: Entitlements | null): boolean => ent?.edition === "pharmacy";

/** PHL-056: the editions sold without the clinic modules. A lab-only tenant sees the lab instead of the pharmacy; the server answers 403
 * on every clinical, scheduling and pharmacy endpoint. */
export type StandaloneEdition = "pharmacy" | "lab";
export const EDITION_HREFS: Record<StandaloneEdition, string[]> = {
  pharmacy: PHARMACY_EDITION_HREFS,
  lab: ["/lab", "/billing", "/reports", "/overview", "/setup", "/staff", "/plan", "/compliance", "/account", "/patients"],
};
export const standaloneEdition = (ent: Entitlements | null): StandaloneEdition | null =>
  ent?.edition === "pharmacy" || ent?.edition === "lab" ? ent.edition : null;
export function editionHref(edition: StandaloneEdition, href: string): boolean {
  const path = href.split("?")[0];
  return EDITION_HREFS[edition].some((h) => path === h || path.startsWith(`${h}/`));
}
export const pharmacyHref = (href: string): boolean => editionHref("pharmacy", href);
export function editionAllows(href: string, ent: Entitlements | null): boolean {
  const edition = standaloneEdition(ent);
  return !edition || editionHref(edition, href);
}

// Nav item -> the plan modules that make it useful (any one is enough). Items not listed are never gated by plan.
export const NAV_MODULES: Record<string, string[]> = {
  "/pharmacy": ["pharmacy_inhouse", "pharmacy_external"],
  "/packages": ["packages"],
  "/billing": ["billing"],
};

/** Unknown entitlements (not loaded yet, or the call failed) never hide anything: the server still enforces writes. */
export function navAllowed(href: string, ent: Entitlements | null): boolean {
  const needs = NAV_MODULES[href.split("?")[0]];
  if (!ent || !needs) return true;
  return needs.some((code) => ent.modules.some((m) => m.code === code && m.enabled));
}

const FEATURE_LABEL: Record<string, string> = { whatsapp_bot: "The WhatsApp assistant", pharmacy_inhouse: "In-house pharmacy stock" };

/** One calm sentence for the banner; null when nothing is switched off. */
export function killBanner(ent: Entitlements | null): string | null {
  if (!ent || ent.killedFeatures.length === 0) return null;
  const names = ent.killedFeatures.map((c) => FEATURE_LABEL[c] ?? c.replace(/_/g, " "));
  return `${names.join(", ")} ${names.length > 1 ? "are" : "is"} switched off for your clinic right now. Other features keep working. Contact Nabd support to turn it back on.`;
}

export function seatMeter(used: number, limit: number | null): { label: string; percent: number | null; full: boolean } {
  if (limit == null) return { label: `${used} seats used (no limit)`, percent: null, full: false };
  return { label: `${used} of ${limit} seats used`, percent: Math.min(100, Math.round((used / limit) * 100)), full: used >= limit };
}

