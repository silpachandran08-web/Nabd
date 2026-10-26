// FEF-052/053/054: the ONE nav registry. Role context -> items, each with its grants, plan module, badge source and the squad
// that owns the screen. Pure (unit-tested): ClinicNav, the Ctrl+K palette and the planned-screen pages all read this.
import type { IconName } from "../components/ui/Icon";
import { navAllowed, type Entitlements } from "../lib/entitlementRules.ts";

export type RoleContext = "owner" | "doctor" | "nurse" | "reception";
export type BadgeSource = "waiting" | "checkout" | "myday" | "nurse-priority" | "nurse-admin";
export type NavItem = {
  id: string; href: string; label: string; short: string; icon: IconName;
  perms: string[]; // every grant is required
  owner: string; // squad that fills the screen
  badge?: BadgeSource;
  /** Planned screen: shown only when the plan explicitly enables one of these modules (half-built features stay invisible). */
  planned?: { module: string[] };
  keywords?: string[]; // Ctrl+K only
  active?: (path: string, tab: string | null) => boolean;
};

export const ROLE_LABEL: Record<RoleContext, string> = { owner: "Owner", doctor: "Doctor", nurse: "Nurse", reception: "Reception" };
export const MY_DAY_HREF = "/my-day";

const ACC = "Patient Access", CLN = "Clinical", MON = "Money & Pharmacy", OWN = "Owner & Admin";
const tabActive = (tab: string | undefined) => (p: string, t: string | null) => p === "/nursing" && (tab ?? "vitals") === (t ?? "vitals");

const OWNER: NavItem[] = [
  { id: "overview", href: "/overview", label: "Overview", short: "Overview", icon: "home", perms: ["reports:view"], owner: OWN, active: (p) => p === "/overview" },
  { id: "operations", href: "/schedule", label: "Clinic Operations", short: "Clinic", icon: "calendar", perms: ["queue:view"], owner: ACC, active: (p) => p === "/schedule" || p === "/arrivals" },
  { id: "billing", href: "/billing", label: "Billing & Day Close", short: "Billing", icon: "receipt", perms: ["billing:view"], owner: MON, active: (p) => p === "/billing" || p.startsWith("/checkout") },
  { id: "packages", href: "/packages", label: "Treatment Packages", short: "Packages", icon: "package", perms: ["packages:view"], owner: MON },
  { id: "reports", href: "/reports", label: "Reports", short: "Reports", icon: "chart", perms: ["reports:view"], owner: OWN },
  { id: "staff", href: "/staff", label: "Staff & Access", short: "Staff", icon: "shield", perms: ["staff:view"], owner: OWN },
  { id: "setup", href: "/setup", label: "Clinic Setup", short: "Setup", icon: "settings", perms: ["setup:view"], owner: OWN,
    active: (p, t) => p.startsWith("/setup") && t !== "licences" && t !== "subscription" },
  { id: "audit", href: "/setup?tab=licences", label: "Compliance & Audit", short: "Audit", icon: "shield", perms: ["setup:view"], owner: OWN, active: (p, t) => p === "/setup" && t === "licences" },
  { id: "support", href: "/compliance", label: "Support access", short: "Support", icon: "shield", perms: ["compliance:view"], owner: OWN },
  { id: "plan", href: "/setup?tab=subscription", label: "Plan & Modules", short: "Plan", icon: "list", perms: ["setup:view"], owner: OWN, active: (p, t) => p === "/setup" && t === "subscription" },
];

// Prototype NB.ROLES.reception order, then the generic staff screens a front-desk role also holds.
const RECEPTION: NavItem[] = [
  { id: "arrivals", href: "/arrivals", label: "Today / Arrivals", short: "Today", icon: "home", perms: ["queue:view"], owner: ACC, badge: "waiting" },
  { id: "requests", href: "/requests", label: "Booking requests", short: "Requests", icon: "inbox", perms: ["queue:create"], owner: ACC }, // ACC-010 ships the screen; it needs the booking permission
  { id: "calendar", href: "/schedule", label: "Queue & Appointments", short: "Queue", icon: "calendar", perms: ["queue:view"], owner: ACC },
  { id: "patients", href: "/patients", label: "Patients", short: "Patients", icon: "users", perms: ["patients:view"], owner: ACC },
  { id: "checkin", href: "/checkin", label: "Self check-in", short: "Check-in", icon: "qr", perms: ["queue:view"], owner: ACC, planned: { module: ["queue_eta"] } },
  { id: "display", href: "/display", label: "Display", short: "Display", icon: "list", perms: ["queue:view"], owner: ACC, planned: { module: ["queue_eta"] } },
  { id: "messages", href: "/messages", label: "WhatsApp inbox", short: "Inbox", icon: "inbox", perms: ["patients:view"], owner: ACC, planned: { module: ["whatsapp_booking"] } },
  { id: "billing", href: "/billing", label: "Checkout & Billing", short: "Billing", icon: "receipt", perms: ["billing:view"], owner: MON, badge: "checkout", active: (p) => p.startsWith("/billing") || p.startsWith("/checkout") },
  { id: "pharmacy", href: "/pharmacy", label: "Pharmacy", short: "Pharmacy", icon: "syringe", perms: ["pharmacy:view"], owner: MON, keywords: ["stock", "stock & expiry", "expiry", "inventory"] },
  { id: "packages", href: "/packages", label: "Treatment Packages", short: "Packages", icon: "package", perms: ["packages:view"], owner: MON },
  { id: "staff", href: "/staff", label: "Staff & Access", short: "Staff", icon: "shield", perms: ["staff:view"], owner: OWN },
  { id: "reports", href: "/reports", label: "Owner Insights", short: "Insights", icon: "chart", perms: ["reports:view"], owner: OWN },
  { id: "setup", href: "/setup", label: "Setup", short: "Setup", icon: "settings", perms: ["setup:view"], owner: OWN },
  { id: "account", href: "/account", label: "My account", short: "Account", icon: "user", perms: [], owner: OWN },
];

// Prototype NB.ROLES.doctor order; the existing doctor screens keep their place around the planned ones.
const DOCTOR: NavItem[] = [
  { id: "myday", href: MY_DAY_HREF, label: "My Day", short: "My Day", icon: "sun", perms: ["clinical:edit", "queue:view"], owner: CLN, badge: "myday" },
  { id: "consult", href: "/consult", label: "Consultation Workspace", short: "Consult", icon: "stethoscope", perms: ["clinical:edit", "queue:view"], owner: CLN },
  { id: "patients", href: "/patients", label: "Patients", short: "Patients", icon: "users", perms: ["patients:view"], owner: CLN },
  { id: "results", href: "/results", label: "Results", short: "Results", icon: "pulse", perms: ["clinical:view"], owner: "Diagnostics & Interop", planned: { module: ["lab"] } },
  { id: "inpatients", href: "/inpatients", label: "Ward rounds", short: "Rounds", icon: "building", perms: ["clinical:view"], owner: "Hospital (Phase 5)", planned: { module: ["ipd"] } },
  { id: "rx", href: "/prescriptions", label: "Prescriptions & Templates", short: "Rx sets", icon: "syringe", perms: ["clinical:view", "clinical:edit"], owner: CLN, keywords: ["favourites", "rx"] },
  { id: "schedule", href: "/schedule", label: "Schedule", short: "Schedule", icon: "calendar", perms: ["queue:view"], owner: ACC },
  { id: "my-schedule", href: "/my-schedule", label: "My Schedule", short: "Hours", icon: "clock", perms: ["clinical:edit", "queue:view"], owner: ACC },
  { id: "encounters", href: "/encounters", label: "Encounters", short: "Encounters", icon: "list", perms: ["clinical:view"], owner: CLN },
  { id: "records", href: "/records", label: "Record requests", short: "Records", icon: "scroll", perms: ["clinical:view"], owner: "Platform & Trust", planned: { module: ["patient_app"] } },
  { id: "specialty", href: "/specialty", label: "Specialty workspace", short: "Specialty", icon: "stethoscope", perms: ["clinical:edit"], owner: CLN, planned: { module: ["emr_erx"] } },
  { id: "recalls", href: "/recalls", label: "Recalls", short: "Recalls", icon: "refresh", perms: ["queue:view"], owner: ACC, planned: { module: ["queue_eta"] } },
  { id: "follow-ups", href: "/follow-ups", label: "Follow-ups", short: "Follow-ups", icon: "refresh", perms: ["queue:view"], owner: ACC },
  { id: "tele", href: "/tele", label: "Teleconsult", short: "Tele", icon: "globe", perms: ["clinical:edit"], owner: CLN, planned: { module: ["teleconsult"] } },
  { id: "packages", href: "/packages", label: "Treatment Packages", short: "Packages", icon: "package", perms: ["packages:view"], owner: MON },
];

const NURSE: NavItem[] = [
  { id: "vitals", href: "/nursing", label: "Arrivals & Vitals", short: "Vitals", icon: "pulse", perms: [], owner: CLN, active: tabActive(undefined) },
  { id: "priority", href: "/nursing?tab=priority", label: "Priority Patients", short: "Priority", icon: "alert", perms: [], owner: CLN, badge: "nurse-priority", active: tabActive("priority") },
  { id: "administration", href: "/nursing?tab=administration", label: "Administration Orders", short: "Orders", icon: "syringe", perms: [], owner: CLN, badge: "nurse-admin", active: tabActive("administration") },
  { id: "procedures", href: "/nursing?tab=procedures", label: "Today's Procedures", short: "Procedures", icon: "list", perms: [], owner: CLN, active: tabActive("procedures") },
  { id: "nurse-packages", href: "/packages", label: "Package Sessions", short: "Packages", icon: "package", perms: ["packages:view"], owner: MON },
  { id: "activity", href: "/nursing?tab=activity", label: "Completed Activity", short: "Activity", icon: "clock", perms: [], owner: CLN, active: tabActive("activity") },
];

export const NAV: Record<RoleContext, NavItem[]> = { owner: OWNER, doctor: DOCTOR, nurse: NURSE, reception: RECEPTION };
export const findItem = (id: string): NavItem | undefined => Object.values(NAV).flat().find((i) => i.id === id);

/** Specialty profile (T-298) -> the Specialty workspace item's label and icon. Cosmetic only. */
const SPECIALTY_ICON: Record<string, IconName> = { dental: "sparkle", cardiology: "pulse", general: "stethoscope" };
const titleCase = (s: string) => s.charAt(0).toUpperCase() + s.slice(1).replace(/[_-]+/g, " ");

export function itemVisible(i: NavItem, perms: readonly string[], ent: Entitlements | null): boolean {
  if (!i.perms.every((p) => perms.includes(p))) return false;
  if (i.planned) return !!ent && i.planned.module.some((m) => ent.modules.some((x) => x.code === m && x.enabled));
  return navAllowed(i.href, ent);
}

/** Role contexts this session may use: an owner who also holds clinical grants can switch to the Doctor context. */
export function rolesFor(perms: readonly string[], owner: boolean): RoleContext[] {
  const doctor = perms.includes("clinical:edit") && perms.includes("queue:view");
  if (owner) return doctor ? ["owner", "doctor"] : ["owner"];
  if (perms.includes("nursing:view")) return ["nurse"];
  return perms.includes("clinical:edit") ? ["doctor"] : ["reception"];
}

export type NavInput = { perms: readonly string[]; owner: boolean; ent: Entitlements | null; override?: string | null; specialty?: string | null };
export function navFor({ perms, owner, ent, override, specialty }: NavInput): { role: RoleContext; roles: RoleContext[]; items: NavItem[] } {
  const roles = rolesFor(perms, owner);
  let role = roles.find((r) => r === override) ?? roles[0];
  let items = NAV[role].filter((i) => itemVisible(i, perms, ent));
  // A trimmed Owner role that can open none of the owner items falls back to the generic staff list.
  if (items.length === 0 && role === "owner") { role = "reception"; items = NAV.reception.filter((i) => itemVisible(i, perms, ent)); }
  if (specialty) items = items.map((i) => (i.id === "specialty" ? { ...i, label: `${titleCase(specialty)} workspace`, short: titleCase(specialty), icon: SPECIALTY_ICON[specialty] ?? i.icon } : i));
  return { role, roles, items };
}

/** The role home: the first screen of the role's nav. */
export const homeFor = (input: NavInput): string => navFor(input).items[0]?.href ?? "/account";

/** Ctrl+K: permitted screens of every role context this session can use, matched on label, id and keywords. */
export function jumpTargets(input: NavInput, query: string): NavItem[] {
  const q = query.trim().toLowerCase();
  const seen = new Set<string>();
  const out: NavItem[] = [];
  for (const r of rolesFor(input.perms, input.owner)) {
    for (const i of NAV[r].filter((x) => itemVisible(x, input.perms, input.ent))) {
      if (seen.has(i.href)) continue;
      seen.add(i.href);
      if (!q || [i.label, i.short, i.id, ...(i.keywords ?? [])].some((s) => s.toLowerCase().includes(q))) out.push(i);
    }
  }
  return out;
}

export const isActive = (i: NavItem, path: string | null, tab: string | null): boolean =>
  !!path && (i.active ? i.active(path, tab) : path === i.href.split("?")[0] || path.startsWith(`${i.href.split("?")[0]}/`));
