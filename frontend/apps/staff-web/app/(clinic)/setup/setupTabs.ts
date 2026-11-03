// FEF-032: the Clinic Setup tabs and where each one lives. Pure, so tests/unit/s19-fef032-split.test.mjs pins the mapping
// of the old /setup?tab=<key> links onto the tab routes.
export const TABS = [
  { key: "checklist", label: "Checklist" },
  { key: "profile", label: "Profile" },
  { key: "departments", label: "Departments" },
  { key: "charges", label: "Charges" },
  { key: "policies", label: "Policies" },
  { key: "consent", label: "Consent" },
  { key: "privacy", label: "Privacy notice" },
  { key: "integrations", label: "Integrations" },
  { key: "whatsapp", label: "WhatsApp" },
  { key: "display", label: "Waiting-room display" },
  { key: "holidays", label: "Holidays" },
  { key: "shifts", label: "Shifts" },
  { key: "payroll", label: "Payroll" },
  { key: "subscription", label: "Subscription" },
  { key: "data", label: "Import / Export" },
  { key: "licences", label: "Licences" },
  { key: "pharmacy", label: "Pharmacy" },
];

export const isSetupTab = (t: string | null): t is string => TABS.some((x) => x.key === t);
/** Checklist and Departments stay on /setup (Departments is a card linking to /setup/departments, which is its own page); every other tab is its own route. */
export const tabHref = (key: string) => (key === "checklist" ? "/setup" : key === "departments" ? "/setup?tab=departments" : `/setup/${key}`);
/** A /setup/<segment> route that is one of the tabs (not the wizard, Departments or Booking rules pages). */
export const isTabRoute = (segment: string) => isSetupTab(segment) && segment !== "checklist" && segment !== "departments";
