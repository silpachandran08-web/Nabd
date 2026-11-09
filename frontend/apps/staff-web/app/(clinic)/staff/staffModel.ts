// FEF-032: the Staff & Access screen's types, labels and role-grid helpers (moved unchanged from the old single page).
import { decodeJwtPayload } from "../../lib/jwt";
import type { PillTone } from "../../components/ui/StatusPill";

// Matches GET /v1/staff (StaffController) and GET /v1/roles (RoleController).
export type Staff = {
  id: string;
  email: string;
  name: string;
  mobilePhone: string;
  roleId: string;
  status: "invited" | "active" | "suspended";
  scope: string;
  lastSeenAt: string | null;
  professionalRegistrationNo?: string | null;
  // OWN-034 (read-only, derived server-side from stored credentials). Optional: absent on an older backend.
  loginMethod?: string;
  loginFactors?: string[];
  clinician?: boolean; // PLT-016: drives doctor pickers instead of role names
};
export type StaffPage = { data: Staff[]; page: { nextCursor: string | null; limit: number } };
export type ModuleGrant = {
  module: string; view: boolean; create: boolean; edit: boolean; delete: boolean;
  approve: boolean; refundDiscount: boolean; export: boolean;
};
export type Role = { id: string; name: string; builtIn: boolean; grants: ModuleGrant[]; mfaRequired: boolean; presetCode?: string | null; presetVersion?: number | null };
// PLT-016: rows the clinician backfill could not decide (GET /v1/staff/clinician-review).
export type ClinicianReview = { staffId: string; name: string; roleName: string; reason: string; clinician: boolean };
export const REVIEW_REASON: Record<string, string> = {
  registration_without_doctor_role: "Has a registration number but no doctor role",
  doctor_role_without_registration: "Doctor role but no registration number",
};
export type BreakGlassGrant = { grantId: string; staffName: string; reasonCode: string; activatedAt: string; expiresAt: string };
export type Department = { id: string; name: string; isDefault: boolean; active: boolean };

export type ActionKey = "view" | "create" | "edit" | "delete" | "approve" | "refundDiscount" | "export";
export type Grid = Record<string, Record<ActionKey, boolean>>;
// mfaRequired and grants for modules this grid has no row for are carried through unchanged: the role
// PATCH replaces the whole role, so omitting them would silently switch MFA off / drop access.
export type Builder = { mode: "create" | "edit" | "view"; sourceId?: string; name: string; grid: Grid; mfaRequired: boolean; extraGrants: ModuleGrant[] };

// NB-049's module list — matches what every controller's @PreAuthorize actually checks.
export const MODULES: { key: string; label: string }[] = [
  { key: "patients", label: "Patients" },
  { key: "queue", label: "Queue & Appointments" },
  { key: "clinical", label: "Clinical Records" },
  { key: "billing", label: "Billing & Payments" },
  { key: "packages", label: "Packages" },
  { key: "pharmacy", label: "Pharmacy" },
  { key: "reports", label: "Reports" },
  { key: "staff", label: "Staff & Access" },
  { key: "setup", label: "Clinic Setup" },
  { key: "specialty_dental", label: "Specialty (Dental)" },
  { key: "nursing", label: "Nursing" },
  { key: "departments", label: "Departments" },
];
export const ACTIONS: { key: ActionKey; label: string }[] = [
  { key: "view", label: "View" },
  { key: "create", label: "Create" },
  { key: "edit", label: "Edit" },
  { key: "delete", label: "Delete" },
  { key: "approve", label: "Approve" },
  { key: "refundDiscount", label: "Refund" },
  { key: "export", label: "Export" },
];

export function gridFromGrants(grants: ModuleGrant[]): Grid {
  const grid = {} as Grid;
  for (const m of MODULES) {
    const g = grants.find((x) => x.module === m.key);
    grid[m.key] = Object.fromEntries(ACTIONS.map((a) => [a.key, g ? g[a.key] : false])) as Record<ActionKey, boolean>;
  }
  return grid;
}

export function extraGrantsOf(role: Role): ModuleGrant[] {
  return role.grants.filter((g) => !MODULES.some((m) => m.key === g.module));
}

export function grantsFromGrid(grid: Grid): ModuleGrant[] {
  return MODULES.filter((m) => ACTIONS.some((a) => grid[m.key][a.key])).map((m) => ({ module: m.key, ...grid[m.key] }));
}

export const SCOPE_LABELS: Record<string, string> = {
  own_patients_only: "Own patients only",
  all_clinic_patients: "All clinic patients",
};

export const STATUS_PILL: Record<Staff["status"], { tone: PillTone; label: string }> = {
  invited: { tone: "info", label: "Invited" },
  active: { tone: "ok", label: "Active" },
  suspended: { tone: "danger", label: "Suspended" },
};

// GET /v1/staff loginMethod codes (StaffService.loginMethod). Unknown codes render as-is.
export const LOGIN_METHOD_LABELS: Record<string, string> = {
  authenticator: "Authenticator",
  authenticator_setup_required: "Authenticator (setup pending)",
  pin_or_whatsapp_otp: "PIN / WhatsApp OTP",
  pin: "PIN",
  whatsapp_otp: "WhatsApp OTP",
  owner_account: "Owner account",
  invite_pending: "Invite pending",
  none: "Not set",
};

// Short relative time for "Last active"; the absolute time stays in the cell's title.
export function relativeTime(iso: string): string {
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} h ago`;
  const days = Math.floor(hrs / 24);
  return days < 30 ? `${days} d ago` : new Date(iso).toLocaleDateString();
}

export function decodePermissions(token: string): string[] {
  const claims = decodeJwtPayload<{ permissions?: unknown }>(token);
  return Array.isArray(claims?.permissions) ? claims.permissions : [];
}

// Restriction summary (wireframe staff table), derived only from what the API returns:
// invite state, patient scope and the role's billing grant. Built-in (owner) role = full access.
export function restrictionOf(s: Staff, roles: Role[]): string {
  if (s.status === "invited") return "Pending first sign-in";
  const role = roles.find((r) => r.id === s.roleId);
  // Without the role's grants the restriction is unknown; never summarise it as "None".
  if (!role) return "—";
  if (role.builtIn) return "Full access";
  const parts: string[] = [];
  if (s.scope === "own_patients_only") parts.push(SCOPE_LABELS.own_patients_only);
  else if (s.scope !== "all_clinic_patients" && s.scope) parts.push(SCOPE_LABELS[s.scope] ?? s.scope);
  const billing = role.grants.find((g) => g.module === "billing");
  if (billing?.view && !billing.refundDiscount) parts.push("No refund, no discount");
  return parts.length ? parts.join(" · ") : "None";
}

export const roleNameOf = (roles: Role[], roleId: string): string => roles.find((r) => r.id === roleId)?.name ?? "—";
