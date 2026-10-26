// PHL-002 / PHL-004 pure rules (unit-tested; the fetching hook is pharmacyCapabilities.ts).
// PHL-002: how a schedule class code is shown on an item row. The codes come from the server (GET /pharmacy/schedule-classes);
// this only picks the short badge. The rules (register, Rx) are the server's, never recomputed here.
export type ScheduleClass = { code: string; label: string; requiresRx: boolean; register: string | null; duplicateRxCopy: boolean };

/** Short badge for a class code: OTC, Rx, H1, X, NDPS, SCD, CD. */
export function classBadge(code: string | null | undefined): string {
  switch (code) {
    case null: case undefined: case "OTC": return "OTC";
    case "IN_H1": return "H1";
    case "IN_X": return "X";
    case "IN_NDPS": return "NDPS";
    case "SA_SEMI_CONTROLLED": case "AE_SCD": return "SCD";
    case "SA_CONTROLLED": case "AE_CD": case "QA_CD": return "CD";
    default: return "Rx";
  }
}

/** Register badge text for classes that need a statutory register (PHL-022/024), else null. */
export function registerBadge(register: string | null | undefined): string | null {
  return register ? `Register · ${classBadge(register)}` : null;
}

// PHL-004: GET /v1/pharmacy/capabilities. The nav is built from it; the server refuses the rest (403 pharmacy-mode-external).
export type PharmacyCapabilities = { mode: string; features: string[] };

/** Unknown capabilities (not loaded, call failed, malformed) never hide anything: the server still enforces the mode. */
export function pharmacyNavAllowed(href: string, caps: PharmacyCapabilities | null): boolean {
  if (href.split("?")[0] !== "/pharmacy" || !caps) return true;
  return caps.features.includes("items");
}
