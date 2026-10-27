// PLT-059: GET /v1/me/entitlements, pure helpers (unit-tested; the fetching hook is entitlements.ts).
export type Entitlements = {
  modules: { code: string; enabled: boolean }[];
  killedFeatures: string[];
  seatLimit: number | null;
};

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

