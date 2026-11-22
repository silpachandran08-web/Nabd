// PHL-032: the pharmacy setup wizard's pure parts: the server's status, the five steps with their done / blocker state, and the
// licence-map edits. Unit-tested (tests/unit/s25-phl032-pharmacy-wizard.test.mjs).
export type PharmacySetup = {
  pharmacyEdition: boolean;
  region: string | null;
  requiredLicenceKeys: string[];
  missingLicenceKeys: string[];
  pharmacistInChargeSet: boolean;
  pharmacistRegistrationSet: boolean;
  printerTestedAt: string | null;
  stockedItems: number;
  invoicingBlocked: boolean;
};
export type ProfileFacts = { name: string; taxId: string | null };
export type StepKey = "business" | "licence" | "in_charge" | "printer" | "stock";
export type WizardStep = { key: StepKey; title: string; done: boolean; blocker: boolean };

/** Where the registration number of the pharmacist in charge is kept in the licence map (the server's PIC_REG_NO). */
export const PIC_REGISTRATION_KEY = "PIC_REG_NO";

export const LICENCE_LABEL: Record<string, string> = {
  IN_20B: "Drug licence, Form 20B (retail sale)",
  IN_21B: "Drug licence, Form 21B (retail sale, Schedule C and C1)",
  SFDA_ESTABLISHMENT: "SFDA establishment licence number",
};
export const licenceLabel = (key: string): string => LICENCE_LABEL[key] ?? key;

/** The five steps in order. Only a missing drug licence is a blocker: it is what stops invoices, so the badge sits on that step. */
export function wizardSteps(s: PharmacySetup, p: ProfileFacts | null): WizardStep[] {
  const licenceDone = s.missingLicenceKeys.length === 0;
  return [
    { key: "business", title: "Business details", done: !!p && p.name.trim().length > 0 && !!p.taxId?.trim(), blocker: false },
    { key: "licence", title: "Drug licence", done: licenceDone, blocker: !licenceDone },
    { key: "in_charge", title: "Pharmacist in charge", done: s.pharmacistInChargeSet && s.pharmacistRegistrationSet, blocker: false },
    { key: "printer", title: "Receipt printer", done: s.printerTestedAt != null, blocker: false },
    { key: "stock", title: "Opening stock", done: s.stockedItems > 0, blocker: false },
  ];
}

/** Keeps the keys the wizard does not edit: PATCH /pharmacy/settings replaces the whole licence map. */
export function mergeLicences(current: Record<string, string>, edits: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = { ...current };
  for (const [k, v] of Object.entries(edits)) {
    const t = v.trim();
    if (t) out[k] = t; else delete out[k];
  }
  return out;
}

export const blockerText = (s: PharmacySetup): string | null =>
  s.pharmacyEdition && s.invoicingBlocked
    ? `Invoices cannot be issued until ${s.missingLicenceKeys.map(licenceLabel).join(" and ")} ${s.missingLicenceKeys.length > 1 ? "are" : "is"} entered.`
    : null;

export const doneCount = (steps: WizardStep[]): number => steps.filter((x) => x.done).length;

/** The receipt-printer test page: a short 80 mm slip, printed by the browser. No patient data, no clinic secrets. */
export const printerTestHtml = (shop: string, now: Date): string =>
  `<!doctype html><meta charset="utf-8"><title>Printer test</title><style>@page{size:80mm auto;margin:4mm}body{font:12px monospace;width:72mm}</style>` +
  `<h3>${shop.replace(/[<>&]/g, "")}</h3><p>Receipt printer test</p><p>${now.toISOString().slice(0, 16).replace("T", " ")}</p><p>0123456789 ABCDEFGHIJ</p><p>If you can read this, the printer is ready.</p>`;
