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
// /arrivals instead of /nursing for exactly this reason. nursing:view/clinical:view are narrow
// enough that only the role they actually name would plausibly have them.
const LANDING_RULES: [permission: string, path: string][] = [
  ["nursing:view", "/nursing"],
  ["clinical:view", "/consult"],
  ["queue:view", "/arrivals"],
  ["reports:view", "/reports"],
  ["setup:view", "/setup"],
];

export function landingPathFor(permissions: string[]): string {
  for (const [permission, path] of LANDING_RULES) {
    if (permissions.includes(permission)) return path;
  }
  return "/account";
}

// Routes with their own identity/nav (platform console, owner's cross-clinic login) or no
// session yet (login, invite acceptance) — the shared clinic shell never renders here.
export const SHELL_SKIP_PREFIXES = ["/login", "/platform", "/accept-invite", "/owner"];
