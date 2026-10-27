// ACC-033: pure helpers of the self check-in screen (React-free, unit-tested in tests/unit/s16-acc033-selfcheckin.test.mjs).

export type Attempt = { id: string; appointmentId: string | null; channel: string; distanceM: number | null; outcome: string; createdAt: string };
export type Config = { checkinCode: string | null; lat: number | null; lng: number | null; geofenceRequired: boolean; geofenceRadiusM: number; walkinAllowed: boolean; whatsappNumber: string | null };
export type Form = { lat: string; lng: string; radius: string; geofence: boolean; walkin: boolean; wa: string };

export const OUTCOME_LABEL: Record<string, string> = {
  checked_in: "Checked in", outside_geofence: "Too far away: sent to the desk", no_appointment: "No appointment today: sent to the desk", duplicate: "Already checked in",
};
export const CHANNEL_LABEL: Record<string, string> = { qr: "Door QR", whatsapp: "WhatsApp", geofence: "Location" };
export const outcomeLabel = (o: string): string => OUTCOME_LABEL[o] ?? o;
export const channelLabel = (c: string): string => CHANNEL_LABEL[c] ?? c;
/** Only failures with a reason the desk can act on get the warning tone. */
export const needsDesk = (o: string): boolean => o === "outside_geofence" || o === "no_appointment";

export function formOf(c: Config | null): Form {
  return {
    lat: c?.lat == null ? "" : String(c.lat), lng: c?.lng == null ? "" : String(c.lng), radius: String(c?.geofenceRadiusM ?? 200),
    geofence: c?.geofenceRequired ?? false, walkin: c?.walkinAllowed ?? false, wa: c?.whatsappNumber ?? "",
  };
}

export type Checked = { ok: true; body: Omit<Config, "checkinCode"> } | { ok: false; error: string };

/** Mirrors the server's rules so the clinic sees the reason before saving. */
export function check(f: Form): Checked {
  const lat = f.lat.trim() === "" ? null : Number(f.lat), lng = f.lng.trim() === "" ? null : Number(f.lng);
  if ((lat === null) !== (lng === null)) return { ok: false, error: "Enter both latitude and longitude, or neither." };
  if (lat !== null && lng !== null && (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)) return { ok: false, error: "Latitude must be between -90 and 90, longitude between -180 and 180." };
  const radius = Number(f.radius);
  if (!Number.isInteger(radius) || radius < 20 || radius > 5000) return { ok: false, error: "The distance must be a whole number of metres from 20 to 5000." };
  if (f.geofence && lat === null) return { ok: false, error: "Set the clinic's location before requiring it." };
  const wa = f.wa.replace(/[\s+-]/g, "");
  if (wa !== "" && !/^[0-9]{8,15}$/.test(wa)) return { ok: false, error: "The WhatsApp number must be 8 to 15 digits with the country code." };
  return { ok: true, body: { lat, lng, geofenceRequired: f.geofence, geofenceRadiusM: radius, walkinAllowed: f.walkin, whatsappNumber: wa === "" ? null : wa } };
}
