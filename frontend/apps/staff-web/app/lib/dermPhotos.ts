// CLN-047: pure rules for the dermatology photo timeline (React-free so they are unit-tested). The server repeats every check:
// consent gate (409 PHOTO_CONSENT_REQUIRED), scan (422 UPLOAD_NOT_CLEAN), and its own <= 1.5 MB / EXIF strip.

export type ConsentScope = "record_only" | "record_and_teaching";
export type Side = "left" | "right" | "midline" | "none";
export type Site = { region: string; side: Side; mapPointId?: string | null };
export type DermPhoto = {
  id: string; takenAt: string; queueEntryId: string; visitDate: string | null; site: Site; note: string | null;
  url: string; urlExpiresAt: string; consentBadge: ConsentScope;
};
export type PhotoConsent = { consentId: string; scope: ConsentScope; status: string; signerKind: string; recordedAt: string };
export type Timeline = { consent: PhotoConsent | null; hiddenCount: number; sites: { site: Site; photos: DermPhoto[] }[] };

export const PHOTO_MAX_BYTES = 1_500_000;
export const MAX_EDGE = 2560;
export const QUALITIES = [0.85, 0.75, 0.65] as const;
/** Body regions until the CLN-049 body map exists: plain anatomy, no clinical judgement. */
export const REGIONS = ["scalp", "forehead", "cheek", "nose", "chin", "neck", "chest", "back", "abdomen", "arm", "forearm", "hand",
  "thigh", "leg", "foot", "other"] as const;
export const SIDES: readonly Side[] = ["left", "right", "midline", "none"];

/** Scale (w, h) so the long edge is at most `max`; never upscales. */
export function fitSize(w: number, h: number, max = MAX_EDGE): { width: number; height: number } {
  const k = Math.min(1, max / Math.max(w, h));
  return { width: Math.max(1, Math.round(w * k)), height: Math.max(1, Math.round(h * k)) };
}

/**
 * Re-encodes until the photo fits PHOTO_MAX_BYTES: lower quality first, then 3/4 the size. `encode` draws the (EXIF-oriented)
 * image at (w, h) as JPEG; a canvas re-encode keeps no EXIF, so the location never leaves the device.
 */
export async function compressWith(w: number, h: number, encode: (w: number, h: number, q: number) => Promise<Blob>): Promise<Blob> {
  let { width, height } = fitSize(w, h);
  for (;;) {
    let out: Blob | null = null;
    for (const q of QUALITIES) {
      out = await encode(width, height, q);
      if (out.size <= PHOTO_MAX_BYTES) return out;
    }
    if (Math.max(width, height) < 400) return out as Blob; // the server compresses again anyway
    width = Math.round(width * 0.75); height = Math.round(height * 0.75);
  }
}

/** What 'Add photo' does next: capture consent first, ask for a visit, or open the camera. */
export function addPhotoStep(t: Pick<Timeline, "consent"> | undefined, visitId: string | null): "consent" | "visit" | "camera" {
  if (!t?.consent) return "consent";
  return visitId ? "camera" : "visit";
}

/** Compare picks: tapping a picked photo unpicks it; a third pick replaces the oldest, so any two dates can be compared. */
export function togglePick(picked: string[], id: string): string[] {
  if (picked.includes(id)) return picked.filter((x) => x !== id);
  return [...picked, id].slice(-2);
}

/** The two picked photos in date order (before, after); null until two are picked. */
export function comparePair(photos: DermPhoto[], picked: string[]): [DermPhoto, DermPhoto] | null {
  const p = photos.filter((x) => picked.includes(x.id)).sort((a, b) => a.takenAt.localeCompare(b.takenAt));
  return p.length === 2 ? [p[0], p[1]] : null;
}

/** Signed URLs live 5 minutes: refetch the timeline before opening one that is about to expire. */
export const urlFresh = (p: Pick<DermPhoto, "urlExpiresAt">, now = Date.now(), marginMs = 15_000): boolean =>
  Date.parse(p.urlExpiresAt) - now > marginMs;
