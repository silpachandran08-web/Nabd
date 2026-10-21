import { decodeJwtPayload } from "./jwt.ts";
import { clearIntents } from "./idempotency.ts";
import { clearLocalSessionData } from "./idleLock.ts";
// The access token's "permissions" claim (SecurityConfig.java: authoritiesClaimName) already
// carries everything the client needs for nav/redirect — decoding it here avoids a round trip
// to a /me endpoint that doesn't exist for clinic staff (only /platform/auth/me does).
// tenantName/tenantRegion/staffName/roleName are display-only (AuthService.mintTokenPair) — for
// ClinicNav's clinic-info card and signed-in-as footer, never for authorization.
type AccessTokenClaims = {
  sub?: string;
  tenantId?: string;
  permissions?: string[];
  tenantName?: string;
  tenantRegion?: string;
  staffName?: string;
  roleName?: string;
  owner?: boolean;
  pinChange?: boolean;
  exp?: number;
};

export function decodeAccessToken(token: string): AccessTokenClaims | null {
  return decodeJwtPayload<AccessTokenClaims>(token);
}

export function getPermissions(): string[] {
  if (typeof window === "undefined") return [];
  const token = localStorage.getItem("nabd_access_token");
  if (!token) return [];
  return decodeAccessToken(token)?.permissions ?? [];
}

export type Identity = { tenantName: string; tenantRegion: string; staffName: string; roleName: string };

/** The clinic owner (built-in role — AuthService's "owner" claim). Drives landing and the owner
 * sidebar only; every permission check stays on the server. */
export function isOwner(): boolean {
  if (typeof window === "undefined") return false;
  const token = localStorage.getItem("nabd_access_token");
  if (!token) return false;
  return decodeAccessToken(token)?.owner === true;
}

/** Display name for the tenant region (fixed at provisioning: IN, AE, SA or QA; GET /v1/regions has the names). Unknown codes pass through. */
// KSA: display alias for access tokens issued before OPS-061 (they expire; nothing else reads it)
const REGION_LABELS: Record<string, string> = { IN: "India", AE: "United Arab Emirates", SA: "Saudi Arabia", KSA: "Saudi Arabia", QA: "Qatar" };
export function regionLabel(region: string | null | undefined): string {
  return REGION_LABELS[region ?? ""] ?? region ?? "";
}

/** The owner portal's pending-workspace token (sessionStorage, ~5 min, OwnerService) while it is
 * still unexpired. Lets an owner inside a clinic go back to the workspace list without a new PIN
 * only while it lasts; display/routing only, the server re-checks it. */
export function ownerPendingTokenValid(): boolean {
  if (typeof window === "undefined") return false;
  const token = sessionStorage.getItem("nabd_owner_pending_token");
  const exp = token ? decodeAccessToken(token)?.exp : undefined;
  return !!exp && exp * 1000 > Date.now() + 10_000;
}

export function getIdentity(): Identity | null {
  if (typeof window === "undefined") return null;
  const token = localStorage.getItem("nabd_access_token");
  if (!token) return null;
  const claims = decodeAccessToken(token);
  if (!claims?.tenantName || !claims.staffName || !claims.roleName) return null;
  return {
    tenantName: claims.tenantName,
    tenantRegion: claims.tenantRegion ?? "",
    staffName: claims.staffName,
    roleName: claims.roleName,
  };
}

// clinical:edit, not clinical:view, marks a doctor: every real write in the consult workspace (notes,
// prescriptions, allergies, vitals corrections) is gated on clinical:edit on the backend — clinical:view
// is just read access to clinical history, which reception can reasonably be granted too (e.g. for
// billing lookups) without being a clinician.
//
// LIVE-04: login lands on the first sidebar destination the role can actually open, so it never lands
// on a page the nav hides or that answers "no access". Each list mirrors ClinicNav's OWNER_ITEMS /
// DOCTOR_ITEMS / NAV_ITEMS (same order, same grants, plus each page's own gate); keep them in step.
type Destination = [href: string, ...permissions: string[]];
const OWNER_LANDING: Destination[] = [
  ["/overview", "reports:view"], ["/schedule", "queue:view"], ["/billing", "billing:view"], ["/packages", "packages:view"],
  ["/reports", "reports:view"], ["/staff", "staff:view"], ["/setup", "setup:view"],
];
// /my-day and /consult both read the queue (AccessGate / forbidden state without queue:view).
const DOCTOR_LANDING: Destination[] = [
  ["/my-day", "clinical:edit", "queue:view"], ["/patients", "patients:view"], ["/consult", "clinical:edit", "queue:view"],
  ["/prescriptions", "clinical:view", "clinical:edit"], ["/packages", "packages:view"], ["/schedule", "queue:view"],
  ["/my-schedule", "clinical:edit", "queue:view"], ["/encounters", "clinical:view"], ["/follow-ups", "queue:view"],
];
// NAV_ITEMS without /consult: a clinical:edit role gets the doctor list instead.
const STAFF_LANDING: Destination[] = [
  ["/arrivals", "queue:view"], ["/schedule", "queue:view"], ["/patients", "patients:view"], ["/pharmacy", "pharmacy:view"],
  ["/billing", "billing:view"], ["/packages", "packages:view"], ["/staff", "staff:view"], ["/reports", "reports:view"],
  ["/setup", "setup:view"],
];

/** A nursing role: one that can open the nursing worklist. /nursing is gated on nursing:view (AccessGate,
 * and every GET under /v1/nursing), so nursing:create/edit alone cannot use it: landing or navigating
 * such a role there is a "no access" dead end. Checked before the doctor list because clinical:edit and
 * queue:view are held by nurses and doctors alike; nursing:view is the signal only nursing roles carry. */
export function hasNursingGrant(permissions: string[]): boolean {
  return permissions.includes("nursing:view");
}

// The clinic owner holds every grant; their sidebar (and home) is the Owner list, led by the Overview.
// "owner" comes from the staff member's own built-in role (AuthService.mintTokenPair); tokens minted
// before that claim existed simply fall through. Same role selection as ClinicNav's render.
export function landingPathFor(permissions: string[], owner = false): string {
  const first = (list: Destination[]) => list.find(([, ...need]) => need.every((p) => permissions.includes(p)))?.[0];
  const ownerHome = owner ? first(OWNER_LANDING) : undefined;
  if (ownerHome) return ownerHome;
  if (hasNursingGrant(permissions)) return "/nursing";
  if (!owner && permissions.includes("clinical:edit")) return first(DOCTOR_LANDING) ?? "/account";
  return first(STAFF_LANDING) ?? "/account";
}

// Routes with their own identity/nav (platform console, owner's cross-clinic login) or no
// session yet (login, invite acceptance) — the shared clinic shell never renders here.
export const SHELL_SKIP_PREFIXES = ["/login", "/platform", "/accept-invite", "/owner"];

// "exp" is the JWT standard claim (Unix seconds) — for the signed-in popover's "Session expires
// in Xh Ym", not used for any auth decision (the server enforces expiry independently).
export function getSessionExpiresAt(): Date | null {
  if (typeof window === "undefined") return null;
  const token = localStorage.getItem("nabd_access_token");
  if (!token) return null;
  const exp = decodeAccessToken(token)?.exp;
  return exp ? new Date(exp * 1000) : null;
}

export async function signOut(): Promise<void> {
  const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
  const token = localStorage.getItem("nabd_access_token");
  if (token) {
    await fetch(`${API_BASE}/auth/logout`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    }).catch(() => {});
  }
  localStorage.removeItem("nabd_access_token");
  localStorage.removeItem("nabd_refresh_token");
  clearIntents(); // FEF-008: no payment intent outlives the session
  await clearLocalSessionData(); // FEF-005: lock flag + drafts database
}
