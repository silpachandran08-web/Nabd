import { itemVisible, type NavItem } from "../../shell/navRegistry.ts";
import type { Entitlements } from "../../lib/entitlementRules.ts";

// Matches GET /v1/platform/auth/me (PlatformAuthController) — permissions
// already come back as "<surface>:view" strings from PlatformPermissions.
export type OperatorProfile = {
  id: string;
  name: string;
  email: string;
  role: string;
  permissions: string[];
};

// FEF-059: the operator console's typed role nav, on the shared NavItem model and gate (shell/navRegistry itemVisible:
// every grant required, a planned screen only when its module is entitled). Pure, unit-tested; the sidebar, rail,
// bottom nav, Command Centre and the direct-URL gate (PlatformShell) all read it.
// Order: the spec's prototype ids for the platform role (S17_SPECS FEF-059 "prototype", source "prototype/js/core.js
// NB.ROLES"; NB.ROLES itself is not in the repo), then the console screens that exist but are not in that list.
export const PLATFORM_PROTOTYPE_ORDER = ["tenants", "onboard", "regions", "health", "support", "revenue"];
/** The spec's "support:grant": the operator authority to request a support-access grant (PlatformPermissions, PLT-056). */
export const SUPPORT_GRANT = "support_access:request";
const CP = "Platform & Trust (control-plane)";
export const PLATFORM_NAV: NavItem[] = [
  { id: "tenants", href: "/platform/fleet", label: "Clinics", short: "Clinics", icon: "building", perms: ["clinics_fleet:view"], owner: CP },
  { id: "onboard", href: "/platform/provisioning", label: "Onboarding", short: "Onboard", icon: "plusSquare", perms: ["onboarding_provisioning:view"], owner: CP },
  { id: "regions", href: "/platform/territories", label: "Territories", short: "Territories", icon: "globe", perms: ["territories:view"], owner: CP },
  // No screen or API yet: absent until a module for it is entitled (the operator session carries none today).
  { id: "health", href: "/platform/health", label: "Platform health", short: "Health", icon: "pulse", perms: ["platform_health:view"], owner: "Data & Infra", planned: { module: ["platform_health"] } },
  { id: "support", href: "/platform/support", label: "Support tickets", short: "Tickets", icon: "lifebuoy", perms: ["support_tickets:view"], owner: CP },
  { id: "supportAccess", href: "/platform/access", label: "Support access", short: "Access", icon: "shieldUser", perms: ["support_access:view", SUPPORT_GRANT], owner: CP },
  { id: "revenue", href: "/platform/billing", label: "Billing & Revenue", short: "Billing", icon: "wallet", perms: ["billing_revenue:view"], owner: CP },
  { id: "senders", href: "/platform/senders", label: "WhatsApp Senders", short: "Senders", icon: "bolt", perms: ["clinics_fleet:view"], owner: CP },
  { id: "pricing", href: "/platform/plans", label: "Pricing & Packaging", short: "Pricing", icon: "sparkle", perms: ["pricing_packaging:view"], owner: CP },
  { id: "audit", href: "/platform/audit", label: "Audit & Compliance", short: "Audit", icon: "scroll", perms: ["audit_compliance:view"], owner: CP },
  { id: "command", href: "/platform", label: "Command Centre", short: "Today", icon: "chart", perms: ["command_centre:view"], owner: CP },
];

/** Operator plan entitlements: /platform/auth/me carries none yet (needs backend), so planned screens stay absent. */
export const OPERATOR_ENTITLEMENTS: Entitlements | null = null;

export const platformNavFor = (perms: readonly string[], ent: Entitlements | null = OPERATOR_ENTITLEMENTS): NavItem[] =>
  PLATFORM_NAV.filter((i) => itemVisible(i, perms, ent));

/** Role home: the first screen of the operator's nav (Command Centre when nothing else is held). */
export const platformHomeFor = (perms: readonly string[], ent: Entitlements | null = OPERATOR_ENTITLEMENTS): string =>
  platformNavFor(perms, ent)[0]?.href ?? "/platform";

/** The nav item a console path belongs to (longest href match); undefined for paths outside the nav (login, unknown). */
export function platformItemFor(path: string): NavItem | undefined {
  return PLATFORM_NAV.filter((i) => path === i.href || (i.href !== "/platform" && path.startsWith(`${i.href}/`)))
    .sort((a, b) => b.href.length - a.href.length)[0];
}

/** Direct-URL gate: a path of a nav item the operator may not see gets the no-access state. */
export function platformRouteAllowed(path: string, perms: readonly string[], ent: Entitlements | null = OPERATOR_ENTITLEMENTS): boolean {
  const item = platformItemFor(path);
  return !item || itemVisible(item, perms, ent);
}

/** Phone bottom nav: the top four destinations; the rest (and Log out) go behind More. */
export const splitBottomNav = <T,>(items: T[]): { primary: T[]; more: T[] } => ({ primary: items.slice(0, 4), more: items.slice(4) });

/** Operator profile query. Its key sits outside the clinic cache's ["t", tenantId, ...] space, so the sessions never share an entry.
 * Permissions are fixed by the operator JWT until the next sign-in, so it never goes stale; logout removes the whole prefix. */
export const PLATFORM_QK = ["platform"] as const;
export const platformMeKey = [...PLATFORM_QK, "me"] as const;

export const ROLE_LABELS: Record<string, string> = {
  super_admin: "Super Admin",
  implementation: "Implementation",
  support_engineer: "Support Engineer",
  billing: "Billing",
  sre: "SRE",
  commercial: "Commercial",
  compliance_dpo: "Compliance / DPO",
};
