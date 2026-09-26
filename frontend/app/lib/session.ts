// The access token's "permissions" claim (SecurityConfig.java: authoritiesClaimName) already
// carries everything the client needs for nav/redirect — decoding it here avoids a round trip
// to a /me endpoint that doesn't exist for clinic staff (only /platform/auth/me does).
// tenantName/tenantRegion/staffName/roleName are display-only (AuthService.mintTokenPair) — for
// ClinicNav's clinic-info card and signed-in-as footer, never for authorization.
type AccessTokenClaims = {
  permissions?: string[];
  tenantName?: string;
  tenantRegion?: string;
  staffName?: string;
  roleName?: string;
  owner?: boolean;
  exp?: number;
};

export function decodeAccessToken(token: string): AccessTokenClaims | null {
  try {
    const payload = token.split(".")[1];
    const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    return JSON.parse(json);
  } catch {
    return null;
  }
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

// First matching permission wins — each role's actual home worklist (DESIGN.md's per-role
// "Today" screen), so login lands staff where their role works instead of everyone hitting
// the owner-facing Setup screen (NB-060-070's bug: handleTokens always did router.replace("/setup")).
//
// Ordered most-exclusive-permission-first, not by role "importance": queue:view is the one
// nearly every clinic-facing role needs just to see the day's arrivals (reception, nurses,
// often doctors too), so it has to be checked last among these or it silently wins over a much
// more specific signal — a Nurse role with both queue:view and nursing:view was landing on
// /arrivals instead of /nursing for exactly this reason.
//
// clinical:edit, not clinical:view: every real write in the consult workspace (notes,
// prescriptions, allergies, vitals) is gated on clinical:edit on the backend — clinical:view is
// just read access to clinical history, which reception can reasonably be granted too (e.g. for
// billing lookups) without being a clinician. Landing a receptionist with that grant on /consult
// instead of /arrivals was exactly this mistake.
const LANDING_RULES: [permission: string, path: string][] = [
  ["nursing:view", "/nursing"],
  ["clinical:edit", "/consult"],
  ["queue:view", "/arrivals"],
  ["reports:view", "/reports"],
  ["setup:view", "/setup"],
];

// The clinic owner holds every grant, so the rules below would land them on /nursing. Their home
// is the Overview ("Today at a glance", DESIGN.md's owner home) — the same page the owner portal
// (owner/workspaces) opens after picking a clinic. "owner" comes from the staff member's own built-in role (AuthService.mintTokenPair);
// tokens minted before that claim existed simply fall through to the rules.
export function landingPathFor(permissions: string[], owner = false): string {
  if (owner && permissions.includes("reports:view")) return "/overview";
  for (const [permission, path] of LANDING_RULES) {
    if (permissions.includes(permission)) return path;
  }
  return "/account";
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
}
