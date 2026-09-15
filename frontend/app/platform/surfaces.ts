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

// Only surfaces with a built page get a nav entry here — SSA-02's "no access,
// no button" rule extends to "no page yet, no link yet" for the rest.
// Shared between the Command Centre landing page and the persistent nav bar
// (app/platform/layout.tsx) so the two never drift out of sync.
export const SURFACES: { permission: string; label: string; href: string }[] = [
  { permission: "onboarding_provisioning:view", label: "Tenant Provisioning", href: "/platform/provisioning" },
  { permission: "clinics_fleet:view", label: "Clinic fleet", href: "/platform/fleet" },
  { permission: "billing_revenue:view", label: "Billing & Revenue", href: "/platform/billing" },
  { permission: "pricing_packaging:view", label: "Pricing & Packaging", href: "/platform/plans" },
  { permission: "territories:view", label: "Territories", href: "/platform/territories" },
  { permission: "support_tickets:view", label: "Support tickets", href: "/platform/support" },
  { permission: "support_access:view", label: "Support access", href: "/platform/access" },
  { permission: "audit_compliance:view", label: "Audit log", href: "/platform/audit" },
];

export const ROLE_LABELS: Record<string, string> = {
  super_admin: "Super Admin",
  implementation: "Implementation",
  support_engineer: "Support Engineer",
  billing: "Billing",
  sre: "SRE",
  commercial: "Commercial",
  compliance_dpo: "Compliance / DPO",
};
