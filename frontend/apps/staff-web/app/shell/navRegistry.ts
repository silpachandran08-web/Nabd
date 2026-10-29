// FEF-052/053/054: the ONE nav registry. Role context -> items, each with its grants, plan module, badge source and the squad
// that owns the screen. Pure (unit-tested): ClinicNav, the Ctrl+K palette and the planned-screen pages all read this.
import type { IconName } from "../components/ui/Icon";
import { NAV_MODULES, navAllowed, type Entitlements } from "../lib/entitlementRules.ts";

export type RoleContext = "owner" | "doctor" | "nurse" | "reception" | "pharmacist" | "lab" | "billing";
export type BadgeSource = "waiting" | "checkout" | "myday" | "nurse-priority" | "nurse-admin" | "triage-urgent";
export type NavItem = {
  id: string; href: string; label: string; short: string; icon: IconName;
  perms: string[]; // every grant is required
  owner: string; // squad that fills the screen
  badge?: BadgeSource;
  urgent?: boolean; // the badge is a warning: shown with an icon and the word "urgent", not colour alone
  /** Planned screen: shown only when the plan explicitly enables one of these modules (half-built features stay invisible). */
  planned?: { module: string[] };
  /** Built screen whose plan modules are not keyed by its href (NAV_MODULES): any one enabled; unknown entitlements never hide it. */
  modules?: string[];
  section?: SectionId; // owner sidebar grouping
  keywords?: string[]; // Ctrl+K only
  active?: (path: string, tab: string | null) => boolean;
};

export const ROLE_LABEL: Record<RoleContext, string> = { owner: "Owner", doctor: "Doctor", nurse: "Nurse", reception: "Reception", pharmacist: "Pharmacist", lab: "Lab", billing: "Billing" };
export const MY_DAY_HREF = "/my-day";

const ACC = "Patient Access", CLN = "Clinical", MON = "Money & Pharmacy", OWN = "Owner & Admin";
const tabActive = (tab: string | undefined) => (p: string, t: string | null) => p === "/nursing" && (tab ?? "vitals") === (t ?? "vitals");

// FEF-058: the owner's sixteen admin areas in five sections. The order is the prototype id order (overview, ops, finance, workflows, privacy,
// staff, integrations, compliance, plan, setup, prices, reports, growth, bookingpage, payouts, hr) taken inside each section (NB.ROLES itself is
// not in the repo). Existing screens keep their route; areas whose screen another squad still builds are `planned` (visible only when the plan
// enables the module) and render through PlannedScreen. `section` is read by the sidebar only.
export type SectionId = "run" | "people" | "money" | "growth" | "compliance";
export const OWNER_SECTIONS: readonly SectionId[] = ["run", "people", "money", "growth", "compliance"];
export const SECTION_LABEL: Record<SectionId, string> = { run: "Run the clinic", people: "People & access", money: "Money", growth: "Growth", compliance: "Compliance" };
export const OWNER_PROTOTYPE_ORDER = ["overview", "ops", "finance", "workflows", "privacy", "staff", "integrations", "compliance", "plan", "setup", "prices", "reports", "growth", "bookingpage", "payouts", "hr"];
const DI = "Diagnostics & Interop", PT = "Platform & Trust", HOS = "Hospital";
const OWNER_ITEMS: NavItem[] = [
  { id: "overview", href: "/overview", label: "Overview", short: "Overview", icon: "home", perms: ["reports:view"], owner: "Data & Infra + Patient Access", section: "run", active: (p) => p === "/overview" },
  { id: "ops", href: "/schedule", label: "Clinic Operations", short: "Clinic", icon: "calendar", perms: ["queue:view"], owner: "Data & Infra + Patient Access", section: "run", badge: "waiting", active: (p) => p === "/schedule" || p === "/arrivals" },
  { id: "workflows", href: "/workflows", label: "Workflows", short: "Workflows", icon: "refresh", perms: ["setup:view"], owner: ACC, section: "run", planned: { module: ["whatsapp_booking", "queue_eta"] } },
  { id: "integrations", href: "/integrations", label: "Integrations", short: "Integrations", icon: "globe", perms: ["setup:view"], owner: DI, section: "run", planned: { module: ["lab", "insurance", "patient_app"] } },
  { id: "setup", href: "/setup", label: "Clinic Setup", short: "Setup", icon: "settings", perms: ["setup:view"], owner: PT, section: "run",
    active: (p, t) => p.startsWith("/setup") && t !== "licences" && t !== "subscription" },
  { id: "reports", href: "/reports", label: "Reports", short: "Reports", icon: "chart", perms: ["reports:view"], owner: "Data & Infra + Patient Access", section: "run" },
  { id: "staff", href: "/staff", label: "Staff & Access", short: "Staff", icon: "shield", perms: ["staff:view"], owner: PT, section: "people" },
  { id: "hr", href: "/hr", label: "HR & Payroll", short: "HR", icon: "users", perms: ["staff:view"], owner: HOS, section: "people", planned: { module: ["hr"] } },
  { id: "finance", href: "/billing", label: "Billing & Day Close", short: "Billing", icon: "receipt", perms: ["billing:view"], owner: MON, section: "money", badge: "checkout", active: (p) => p === "/billing" || p.startsWith("/checkout") },
  { id: "plan", href: "/plan", label: "Plan & Modules", short: "Plan", icon: "list", perms: ["setup:view"], owner: PT, section: "money" },
  { id: "prices", href: "/packages", label: "Prices & Packages", short: "Prices", icon: "package", perms: ["packages:view"], owner: MON, section: "money" },
  { id: "payouts", href: "/payouts", label: "Payouts", short: "Payouts", icon: "receipt", perms: ["billing:view"], owner: MON, section: "money", planned: { module: ["billing"] } },
  { id: "growth", href: "/growth", label: "Growth", short: "Growth", icon: "sparkle", perms: ["reports:view"], owner: ACC, section: "growth", planned: { module: ["whatsapp_booking"] } },
  { id: "bookingpage", href: "/booking-page", label: "Booking page", short: "Booking", icon: "globe", perms: ["setup:view"], owner: ACC, section: "growth", planned: { module: ["branded_site"] } },
  { id: "privacy", href: "/compliance", label: "Privacy & Support access", short: "Privacy", icon: "shield", perms: ["compliance:view"], owner: PT, section: "compliance" },
  { id: "compliance", href: "/setup?tab=licences", label: "Compliance & Audit", short: "Audit", icon: "shield", perms: ["setup:view"], owner: PT, section: "compliance", active: (p, t) => p === "/setup" && t === "licences" },
];
const OWNER: NavItem[] = OWNER_SECTIONS.flatMap((sec) => OWNER_ITEMS.filter((i) => i.section === sec));

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

// FEF-055: the nurse's role home is triage and vitals; orders, procedures, vaccines and the clinical inbox sit beside it (prototype
// nurse.triage / orders / procedures / inbox). The screens belong to Clinical (nursing module); the shell only gates and badges them.
const NURSING = ["nursing:view"];
const NURSE: NavItem[] = [
  { id: "triage", href: "/nursing", label: "Triage & Vitals", short: "Triage", icon: "pulse", perms: NURSING, owner: CLN, badge: "triage-urgent", urgent: true, active: tabActive(undefined), keywords: ["vitals", "arrivals"] },
  { id: "orders", href: "/nursing?tab=administration", label: "Nursing Orders", short: "Orders", icon: "syringe", perms: NURSING, owner: CLN, badge: "nurse-admin", active: tabActive("administration"), keywords: ["administration", "injection"] },
  { id: "procedures", href: "/nursing?tab=procedures", label: "Today's Procedures", short: "Procedures", icon: "list", perms: NURSING, owner: CLN, active: tabActive("procedures") },
  { id: "vaccines", href: "/nursing?tab=vaccines", label: "Vaccines & Injections", short: "Vaccines", icon: "syringe", perms: NURSING, owner: CLN, active: tabActive("vaccines"), keywords: ["immunisation", "vaccination"] },
  { id: "nurse-inbox", href: "/inbox", label: "Clinical Inbox", short: "Inbox", icon: "inbox", perms: ["clinical:view"], owner: CLN, planned: { module: ["lab", "emr_erx"] }, keywords: ["results", "messages"] },
  { id: "priority", href: "/nursing?tab=priority", label: "Priority Patients", short: "Priority", icon: "alert", perms: NURSING, owner: CLN, badge: "nurse-priority", active: tabActive("priority") },
  { id: "nurse-packages", href: "/packages", label: "Package Sessions", short: "Packages", icon: "package", perms: ["packages:view"], owner: MON },
  { id: "activity", href: "/nursing?tab=activity", label: "Completed Activity", short: "Activity", icon: "clock", perms: NURSING, owner: CLN, active: tabActive("activity") },
];

// FEF-057: the counter shells (billing desk, pharmacy counter, lab bench): a live queue home, the transaction screen, then reports. Order: the
// spec's prototype ids (S18_SPECS FEF-057 "prototype", source prototype/js/core.js NB.ROLES, not in the repo). Built screens keep their
// route; the rest are `planned` route skeletons (PlannedScreen) the owning squad fills. The same shells serve a standalone pharmacy-only or
// lab-only tenant: they hold no clinic screens.
export const COUNTER_PROTOTYPE_ORDER = {
  billing: ["checkout", "invoices", "preauth", "claims", "packages", "dayclose"],
  pharmacist: ["dispense", "pos", "stock", "purchase", "registers", "insights"],
  lab: ["worklist", "collect", "results", "validate", "catalog"],
} as const;
const INS = "Insurance (GCC/KSA squads)", PHARMACY_MODULES = ["pharmacy_inhouse", "pharmacy_external"];
const BILLING: NavItem[] = [
  { id: "billing-checkout", href: "/arrivals?tab=checkout_pending", label: "Checkout queue", short: "Checkout", icon: "receipt", perms: ["billing:view", "queue:view"], modules: ["billing"], owner: MON, badge: "checkout",
    active: (p, t) => (p === "/arrivals" && t === "checkout_pending") || (p.startsWith("/checkout/") && p !== "/checkout/otc"), keywords: ["payment", "bill"] },
  { id: "billing-invoices", href: "/billing/invoices", label: "Invoices", short: "Invoices", icon: "scroll", perms: ["billing:view"], owner: MON, planned: { module: ["billing"] }, active: (p) => p === "/billing/invoices" }, // /billing/invoices/[id] is the built invoice view
  { id: "billing-preauth", href: "/billing/preauth", label: "Pre-authorisations", short: "Pre-auth", icon: "shield", perms: ["billing:view"], owner: INS, planned: { module: ["insurance"] } },
  { id: "billing-claims", href: "/billing/claims", label: "Insurance claims", short: "Claims", icon: "list", perms: ["billing:view"], owner: INS, planned: { module: ["insurance"] } },
  { id: "billing-packages", href: "/packages", label: "Treatment Packages", short: "Packages", icon: "package", perms: ["packages:view"], owner: MON },
  { id: "billing-dayclose", href: "/billing", label: "Day Close", short: "Day close", icon: "banknote", perms: ["billing:view"], owner: MON, active: (p) => p === "/billing" },
];
const PHARMACIST: NavItem[] = [
  { id: "ph-dispense", href: "/pharmacy", label: "e-Rx queue & Dispense", short: "Dispense", icon: "syringe", perms: ["pharmacy:view"], owner: MON, active: (p) => p === "/pharmacy" || p.startsWith("/pharmacy/receipt"), keywords: ["e-rx", "prescriptions", "dispensing"] },
  { id: "ph-pos", href: "/checkout/otc", label: "Counter sale (POS)", short: "POS", icon: "card", perms: ["billing:view", "billing:create"], modules: PHARMACY_MODULES, owner: MON, keywords: ["otc", "sale"] },
  { id: "ph-stock", href: "/pharmacy/stock", label: "Stock & expiry", short: "Stock", icon: "package", perms: ["pharmacy:view"], owner: MON, planned: { module: PHARMACY_MODULES }, keywords: ["inventory", "stock take", "expiry"] },
  { id: "ph-purchase", href: "/pharmacy/purchase", label: "Purchase & GRN", short: "Purchase", icon: "plusSquare", perms: ["pharmacy:view"], owner: MON, planned: { module: PHARMACY_MODULES }, keywords: ["goods receipt", "supplier"] },
  { id: "ph-registers", href: "/pharmacy/registers", label: "Controlled registers", short: "Registers", icon: "scroll", perms: ["pharmacy:view"], owner: MON, planned: { module: PHARMACY_MODULES } },
  { id: "ph-insights", href: "/pharmacy/insights", label: "Pharmacy insights", short: "Insights", icon: "chart", perms: ["pharmacy:view"], owner: MON, planned: { module: PHARMACY_MODULES } },
];
// No lab permission exists yet: "lab:view" is the placeholder grant the Diagnostics squad defines with the lab screens (absent until then).
export const LAB_GRANT = "lab:view";
const LAB: NavItem[] = [
  { id: "lab-worklist", href: "/lab", label: "Worklist", short: "Worklist", icon: "list", perms: [LAB_GRANT], owner: DI, planned: { module: ["lab"] }, active: (p) => p === "/lab" },
  { id: "lab-collect", href: "/lab/collect", label: "Sample collection", short: "Collect", icon: "syringe", perms: [LAB_GRANT], owner: DI, planned: { module: ["lab"] } },
  { id: "lab-results", href: "/lab/results", label: "Result entry", short: "Results", icon: "pulse", perms: [LAB_GRANT], owner: DI, planned: { module: ["lab"] } },
  { id: "lab-validate", href: "/lab/validate", label: "Validation", short: "Validate", icon: "check", perms: [LAB_GRANT], owner: DI, planned: { module: ["lab"] } },
  { id: "lab-catalog", href: "/lab/catalog", label: "Test catalogue", short: "Catalogue", icon: "settings", perms: [LAB_GRANT], owner: DI, planned: { module: ["lab"] } },
];
/** Counter role -> the grant that marks it, for a role that does not book patients (queue:create is the front desk's). */
const COUNTER_SIGNAL: [RoleContext, string][] = [["pharmacist", "pharmacy:edit"], ["lab", LAB_GRANT], ["billing", "billing:create"]];
export const COUNTER_ROLES: readonly RoleContext[] = COUNTER_SIGNAL.map(([r]) => r);

export const NAV: Record<RoleContext, NavItem[]> = { owner: OWNER, doctor: DOCTOR, nurse: NURSE, reception: RECEPTION, pharmacist: PHARMACIST, lab: LAB, billing: BILLING };
export const findItem = (id: string): NavItem | undefined => Object.values(NAV).flat().find((i) => i.id === id);

/** Specialty profile (T-298) -> the Specialty workspace item's label and icon. Cosmetic only. */
const SPECIALTY_ICON: Record<string, IconName> = { dental: "sparkle", cardiology: "pulse", general: "stethoscope" };
const titleCase = (s: string) => s.charAt(0).toUpperCase() + s.slice(1).replace(/[_-]+/g, " ");

export function itemVisible(i: NavItem, perms: readonly string[], ent: Entitlements | null): boolean {
  if (!i.perms.every((p) => perms.includes(p))) return false;
  const on = (m: string) => !!ent && ent.modules.some((x) => x.code === m && x.enabled);
  if (i.planned) return i.planned.module.some(on);
  return i.modules ? !ent || i.modules.some(on) : navAllowed(i.href, ent);
}

/** Role contexts this session may use: an owner who also holds clinical grants can switch to the Doctor context. */
export function rolesFor(perms: readonly string[], owner: boolean): RoleContext[] {
  const doctor = perms.includes("clinical:edit") && perms.includes("queue:view");
  if (owner) return doctor ? ["owner", "doctor"] : ["owner"];
  if (perms.includes("nursing:view")) return ["nurse"];
  if (perms.includes("clinical:edit")) return ["doctor"];
  const counter = perms.includes("queue:create") ? [] : COUNTER_SIGNAL.filter(([, g]) => perms.includes(g)).map(([r]) => r);
  return counter.length ? counter : ["reception"];
}

export type NavInput = { perms: readonly string[]; owner: boolean; ent: Entitlements | null; override?: string | null; specialty?: string | null };
export function navFor({ perms, owner, ent, override, specialty }: NavInput): { role: RoleContext; roles: RoleContext[]; items: NavItem[] } {
  const roles = rolesFor(perms, owner);
  let role = roles.find((r) => r === override) ?? roles[0];
  let items = NAV[role].filter((i) => itemVisible(i, perms, ent));
  // A trimmed Owner role (or a counter role) that can open none of its items falls back to the generic staff list.
  if (items.length === 0 && (role === "owner" || COUNTER_ROLES.includes(role))) { role = "reception"; items = NAV.reception.filter((i) => itemVisible(i, perms, ent)); }
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

/** FEF-057: direct-URL gate of the counter shells: the item of the role's nav this path belongs to, when the session may not see it. */
export function blockedRoute(role: RoleContext, input: NavInput, path: string | null, tab: string | null = null): NavItem | undefined {
  if (!COUNTER_ROLES.includes(role)) return undefined;
  return NAV[role].find((i) => isActive(i, path, tab) && !itemVisible(i, input.perms, input.ent));
}

/** Phone bottom nav: at most 5 destinations (DESIGN.md §4), so more than 5 items become the top 4 + More. */
export const bottomNavSplit = <T,>(items: T[]): { primary: T[]; more: T[] } =>
  items.length > 5 ? { primary: items.slice(0, 4), more: items.slice(4) } : { primary: items, more: [] };

/** FEF-058: owner areas the user may open but the plan does not include (module off). The Plan screen lists them as upgrade options. */
export function ownerUpgradeOptions(perms: readonly string[], ent: Entitlements | null): { item: NavItem; modules: string[] }[] {
  if (!ent) return [];
  const on = (m: string) => ent.modules.some((x) => x.code === m && x.enabled);
  return NAV.owner.flatMap((item) => {
    const modules = item.planned?.module ?? NAV_MODULES[item.href.split("?")[0]];
    return modules && item.perms.every((p) => perms.includes(p)) && !modules.some(on) ? [{ item, modules }] : [];
  });
}
