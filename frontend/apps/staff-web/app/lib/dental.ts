// CLN-050: tooth numbering for display, chair-side preset helpers. React-free so they are unit-tested.
// Teeth are always stored and sent as FDI (11-48); Universal (1-32) is a display choice only.

export type Numbering = "FDI" | "UNIVERSAL";
export type DentalPreset = { code: string; label: string; la: boolean; drugQuery?: string | null };

/** Chart order: upper right to upper left, then lower right to lower left (FDI). */
export const FDI_TEETH = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28,
  48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38];

/** Standard Universal mapping for permanent teeth: 18 -> 1 ... 28 -> 16, 38 -> 17 ... 48 -> 32. Null for a non-FDI code. */
export function fdiToUniversal(fdi: number): number | null {
  const q = Math.floor(fdi / 10), p = fdi % 10;
  if (q < 1 || q > 4 || p < 1 || p > 8) return null;
  return q === 1 ? 9 - p : q === 2 ? 8 + p : q === 3 ? 25 - p : 24 + p;
}

export function universalToFdi(u: number): number | null {
  if (!Number.isInteger(u) || u < 1 || u > 32) return null;
  return u <= 8 ? 19 - u : u <= 16 ? 12 + u : u <= 24 ? 55 - u : 16 + u;
}

/** The label a tooth shows. A supernumerary tooth is stored at its nearest FDI tooth: in Universal it reads that number + 50
 * (the ADA convention, 51-82); in FDI it reads "S" + the near tooth. */
export function toothLabel(fdi: number, numbering: Numbering, supernumerary = false): string {
  if (numbering === "UNIVERSAL") {
    const u = fdiToUniversal(fdi);
    if (u === null) return String(fdi);
    return String(supernumerary ? u + 50 : u);
  }
  return supernumerary ? `S${fdi}` : String(fdi);
}

/** Upper-case code from a label, for a new preset ("Next: obturation" -> "NEXT_OBTURATION"). */
export function presetCode(label: string): string {
  return label.normalize("NFKD").replace(/[^A-Za-z0-9]+/g, "_").replace(/^_+|_+$/g, "").toUpperCase().slice(0, 40) || "PRESET";
}
