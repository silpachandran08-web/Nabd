// PHL-056: the lab setup wizard's pure parts: the gate, the six steps, the PUT body, the letterhead preview, the price list
// template and the dry-run summary. Unit-tested (tests/unit/s27-phl056-lab-wizard.test.mjs).
import type { Entitlements } from "../../../lib/entitlementRules.ts";

export type LabSetup = {
  accreditationNo: string | null;
  pathologistStaffId: string | null;
  pathologistName: string | null;
  pathologistRegistrationNo: string | null;
  letterheadLines: string | null;
  printerTestedAt: string | null;
  catalogueTests: number;
  activeTests: number;
};
export type ProfileFacts = { name: string; taxId: string | null; taxIdType?: string | null };
export type StepKey = "business" | "accreditation" | "pathologist" | "letterhead" | "catalogue" | "printer";
export type WizardStep = { key: StepKey; title: string; done: boolean; optional: boolean };

/** The page mounts (and fetches) only on "open": the lab module must be in the first entitlements answer and the role must read the lab.
 * wait: no answer yet. unverified: the answer failed (fail-closed, manual retry). blocked: no lab module or no lab:view. */
export type WizardGate = "wait" | "unverified" | "blocked" | "open";
export function labWizardGate(ent: Entitlements | null, settled: boolean, canViewLab: boolean): WizardGate {
  if (!canViewLab) return "blocked";
  if (!ent) return settled ? "unverified" : "wait";
  return ent.modules.some((m) => m.code === "lab" && m.enabled) ? "open" : "blocked";
}

/** The trimmed text, or null when nothing was entered. */
const text = (v: string | null | undefined): string | null => v?.trim() || null;
const filled = (v: string | null | undefined): boolean => text(v) != null;

/** The six steps in order. Accreditation is optional: it never counts against "ready". */
export function labWizardSteps(s: LabSetup, p: ProfileFacts | null): WizardStep[] {
  return [
    { key: "business", title: "Business details", done: !!p && filled(p.name) && filled(p.taxId), optional: false },
    { key: "accreditation", title: "Accreditation", done: filled(s.accreditationNo), optional: true },
    { key: "pathologist", title: "Pathologist", done: !!s.pathologistStaffId && filled(s.pathologistRegistrationNo), optional: false },
    { key: "letterhead", title: "Letterhead", done: filled(s.letterheadLines), optional: false },
    { key: "catalogue", title: "Test catalogue and prices", done: s.activeTests > 0, optional: false },
    { key: "printer", title: "Label printer", done: s.printerTestedAt != null, optional: false },
  ];
}
export const requiredLeft = (steps: WizardStep[]): number => steps.filter((x) => !x.optional && !x.done).length;

/** PUT /lab/setup replaces all four fields, so a step sends what the server has plus its own edit. */
export type SetupBody = { accreditationNo: string | null; pathologistStaffId: string | null; pathologistRegistrationNo: string | null; letterheadLines: string | null };
export function setupBody(s: LabSetup, edits: Partial<SetupBody>): SetupBody {
  const b = { accreditationNo: s.accreditationNo, pathologistStaffId: s.pathologistStaffId, pathologistRegistrationNo: s.pathologistRegistrationNo, letterheadLines: s.letterheadLines, ...edits };
  return { accreditationNo: text(b.accreditationNo), pathologistStaffId: b.pathologistStaffId || null, pathologistRegistrationNo: text(b.pathologistRegistrationNo), letterheadLines: text(b.letterheadLines) };
}

export const LETTERHEAD_MAX_LINES = 6;
export const LETTERHEAD_MAX_CHARS = 400;
/** A sentence for the field, or null when the text fits (the server applies the same limits). */
export function letterheadError(text: string): string | null {
  if (text.length > LETTERHEAD_MAX_CHARS) return `Keep the letterhead to ${LETTERHEAD_MAX_CHARS} characters.`;
  if (text.trim().split("\n").length > LETTERHEAD_MAX_LINES) return `The letterhead takes up to ${LETTERHEAD_MAX_LINES} lines under the lab's name.`;
  return null;
}

/** What the letterhead shows, top to bottom. Only what was entered: an empty line is left out, never a placeholder. */
export type Letterhead = { name: string; lines: string[]; accreditation: string | null; tax: string | null; pathologist: string | null };
export function letterheadPreview(p: ProfileFacts, s: LabSetup, draftLines?: string): Letterhead {
  const lines = (draftLines ?? s.letterheadLines ?? "").split("\n").map((l) => l.trim()).filter(Boolean).slice(0, LETTERHEAD_MAX_LINES);
  const accreditation = text(s.accreditationNo), tax = text(p.taxId), who = text(s.pathologistName), reg = text(s.pathologistRegistrationNo);
  return {
    name: p.name.trim(),
    lines,
    accreditation: accreditation && `NABL accreditation no. ${accreditation}`,
    tax: tax && `${p.taxIdType === "VAT" ? "VAT no." : "GSTIN"} ${tax}`,
    pathologist: who && `${who}${reg ? `, Reg. no. ${reg}` : ""}`,
  };
}

/** The price list to fill in: one line per test, the price left empty. Opens in any spreadsheet. */
export const priceTemplateCsv = (tests: { code: string; name: string }[]): string =>
  ["code,price,name", ...tests.map((t) => `${t.code},,"${t.name.replace(/"/g, "'")}"`)].join("\r\n") + "\r\n";

export const PRICE_FILE_MAX_CHARS = 300_000;
export type PriceLine = { code: string; name: string; currentPrice: number; newPrice: number };
export type PriceImport = { dryRun: boolean; toPrice: PriceLine[]; unchanged: number; unknown: string[]; problems: { line: number; message: string }[]; updated: number };

const n = (count: number, one: string, many: string) => `${count} ${count === 1 ? one : many}`;
/** The dry run in sentences, most important first. */
export function priceImportSummary(r: PriceImport): string[] {
  const out = [r.toPrice.length > 0 ? `${n(r.toPrice.length, "test", "tests")} will get a price and become billable.` : "No test would change."];
  if (r.unchanged > 0) out.push(`${n(r.unchanged, "test already has", "tests already have")} that price.`);
  if (r.unknown.length > 0) out.push(`${n(r.unknown.length, "code is", "codes are")} not in your catalogue: ${r.unknown.slice(0, 10).join(", ")}${r.unknown.length > 10 ? " and more" : ""}.`);
  if (r.problems.length > 0) out.push(`${n(r.problems.length, "line", "lines")} could not be used.`);
  return out;
}

/** The label-printer test: one small label, printed by the browser. No patient data. Label stock 50 x 25 mm is a placeholder until the
 * sample label format is decided (PHL-045). */
export const labelTestHtml = (lab: string): string =>
  `<!doctype html><meta charset="utf-8"><title>Label printer test</title><style>@page{size:50mm 25mm;margin:2mm}body{font:10px monospace;margin:0}</style>` +
  `<b>${lab.replace(/[<>&]/g, "")}</b><p>Label printer test</p><p>0123456789 ABCDEFGHIJ</p>`;
