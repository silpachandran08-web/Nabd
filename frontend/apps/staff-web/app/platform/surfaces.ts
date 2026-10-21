import type { IconName } from "../components/ui/Icon";

// Matches GET /v1/platform/auth/me (PlatformAuthController) — permissions
// already come back as "<surface>:view" strings from PlatformPermissions.
export type OperatorProfile = {
  id: string;
  name: string;
  email: string;
  role: string;
  permissions: string[];
};

export const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export type Surface = {
  /** "" = every signed-in operator (Command Centre has no surface permission of its own). */
  permission: string;
  label: string;
  /** Short label for the collapsed / narrow nav rail. */
  short: string;
  href: string;
  icon: IconName;
  section: string;
};

// Only surfaces with a built page get a nav entry here — SSA-02's "no access,
// no button" rule extends to "no page yet, no link yet" for the rest.
// Shared between the Command Centre landing page and the persistent nav bar
// (app/platform/layout.tsx) so the two never drift out of sync.
// Order and grouping follow the wireframe's NAV.platform (Today / Customers / Money / Service /
// Governance); Growth and the unbuilt Service surfaces join when their pages exist.
export const SURFACES: Surface[] = [
  { permission: "", label: "Command Centre", short: "Today", href: "/platform", icon: "chart", section: "Today" },
  { permission: "clinics_fleet:view", label: "Clinics", short: "Clinics", href: "/platform/fleet", icon: "building", section: "Customers" },
  { permission: "onboarding_provisioning:view", label: "Onboarding", short: "Onboard", href: "/platform/provisioning", icon: "plusSquare", section: "Customers" },
  { permission: "territories:view", label: "Territories", short: "Territories", href: "/platform/territories", icon: "globe", section: "Customers" },
  { permission: "billing_revenue:view", label: "Billing & Revenue", short: "Billing", href: "/platform/billing", icon: "wallet", section: "Money" },
  { permission: "pricing_packaging:view", label: "Pricing & Packaging", short: "Pricing", href: "/platform/plans", icon: "sparkle", section: "Money" },
  { permission: "support_tickets:view", label: "Support Tickets", short: "Tickets", href: "/platform/support", icon: "lifebuoy", section: "Service" },
  { permission: "support_access:view", label: "Support Access", short: "Access", href: "/platform/access", icon: "shieldUser", section: "Service" },
  { permission: "audit_compliance:view", label: "Audit & Compliance", short: "Audit", href: "/platform/audit", icon: "scroll", section: "Governance" },
];

export function visibleSurfaces(permissions: string[]): Surface[] {
  return SURFACES.filter((s) => s.permission === "" || permissions.includes(s.permission));
}

export function groupBySection(surfaces: Surface[]): { section: string; items: Surface[] }[] {
  const groups: { section: string; items: Surface[] }[] = [];
  for (const s of surfaces) {
    const g = groups.find((x) => x.section === s.section);
    if (g) g.items.push(s);
    else groups.push({ section: s.section, items: [s] });
  }
  return groups;
}

export const ROLE_LABELS: Record<string, string> = {
  super_admin: "Super Admin",
  implementation: "Implementation",
  support_engineer: "Support Engineer",
  billing: "Billing",
  sre: "SRE",
  commercial: "Commercial",
  compliance_dpo: "Compliance / DPO",
};
