// FEF-027: document.title per route, '<Page> · <Clinic> · Nabd'. Pure so it is unit-tested.
const PAGES: [prefix: string, name: string][] = [
  ["/arrivals", "Today"], ["/schedule", "Queue & Appointments"], ["/patients", "Patients"], ["/consult", "Consultation"],
  ["/pharmacy", "Pharmacy"], ["/billing", "Billing"], ["/checkout", "Checkout"], ["/packages", "Treatment Packages"],
  ["/staff", "Staff & Access"], ["/reports", "Reports"], ["/setup", "Setup"], ["/account", "My account"],
  ["/my-day", "My Day"], ["/solo", "Today"], ["/my-schedule", "My Schedule"], ["/prescriptions", "Prescriptions"], ["/encounters", "Encounters"],
  ["/follow-ups", "Follow-ups"], ["/nursing", "Nursing"], ["/overview", "Overview"], ["/compliance", "Support access"],
  ["/documents", "Documents"], ["/security", "Security"], ["/login", "Sign in"], ["/accept-invite", "Accept invitation"],
  ["/requests", "Booking requests"], ["/checkin", "Self check-in"], ["/display", "Display"], ["/messages", "WhatsApp inbox"], ["/results", "Results"],
  ["/inpatients", "Ward rounds"], ["/records", "Record requests"], ["/specialty", "Specialty workspace"], ["/recalls", "Recalls"], ["/tele", "Teleconsult"], ["/inbox", "Clinical inbox"],
  ["/owner", "Workspaces"], ["/platform", "Platform console"],
];

export function pageName(pathname: string | null): string {
  const p = pathname ?? "/";
  const hit = PAGES.find(([prefix]) => p === prefix || p.startsWith(`${prefix}/`));
  return hit ? hit[1] : "Nabd";
}

export function routeTitle(pathname: string | null, clinic: string | null | undefined): string {
  const page = pageName(pathname);
  return [page === "Nabd" ? null : page, clinic || null, "Nabd"].filter(Boolean).join(" · ");
}
