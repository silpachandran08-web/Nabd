// The access token's "permissions" claim (SecurityConfig.java: authoritiesClaimName) already
// carries everything the client needs for nav/redirect — decoding it here avoids a round trip
// to a /me endpoint that doesn't exist for clinic staff (only /platform/auth/me does).
type AccessTokenClaims = { permissions?: string[] };

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

// First matching permission wins — each role's actual home worklist (DESIGN.md's per-role
// "Today" screen), so login lands staff where their role works instead of everyone hitting
// the owner-facing Setup screen (NB-060-070's bug: handleTokens always did router.replace("/setup")).
const LANDING_RULES: [permission: string, path: string][] = [
  ["queue:view", "/arrivals"],
  ["nursing:view", "/nursing"],
  ["clinical:view", "/consult"],
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
