// PHL-041: the lab test and panel catalogue's shapes and rules (pure: unit-tested in tests/unit/s25-phl041-lab-catalogue.test.mjs).

export type LabTest = {
  id: string; code: string; name: string; loinc?: string | null; sampleType: string; container: string; volumeMl?: number | null;
  method?: string | null; unit?: string | null; tatMinutes: number; price: number; taxCode?: string | null; chargeItemId: string;
  chargeCode: string; active: boolean; updatedAt: string;
};
export type LabTestPage = { items: LabTest[]; nextCursor?: string | null };
export type LabPanel = {
  id: string; code: string; name: string; price: number; testsTotal: number; discount: number; chargeCode: string; active: boolean;
  tests: { id: string; code: string; name: string; price: number }[];
};
export type ImportResult = { pack: string; dryRun: boolean; reviewed: boolean; reviewNote?: string | null; toCreate: string[]; skipped: string[]; created: number };

export type CatalogueTab = "tests" | "panels";
export const TABS: CatalogueTab[] = ["tests", "panels"];
/** The starter pack offered on this screen. A GCC pack is not shipped yet. */
export const STARTER_PACK = "IN_STARTER";

// Values the starter pack uses; the server stores free text, so an unknown value is shown as it is.
export const SAMPLE_TYPES = ["whole_blood", "serum", "plasma", "urine", "urine_24h", "arterial_blood"] as const;
export const CONTAINERS = ["edta_lavender", "sst_gold", "plain_red", "citrate_blue", "fluoride_grey", "heparin_green", "heparin_syringe", "urine_container", "urine_24h_container"] as const;

/** "edta_lavender" -> "Edta lavender": the fallback label for a value the message catalogue has no entry for. */
export const plain = (v: string): string => (v ? v.charAt(0).toUpperCase() + v.slice(1).replace(/_/g, " ") : "");

/** Turnaround as the two numbers the label needs: whole days and hours, or minutes under an hour. */
export function tatParts(minutes: number): { unit: "minutes" | "hours" | "days"; value: number } {
  if (minutes < 60) return { unit: "minutes", value: minutes };
  if (minutes < 1440 || minutes % 1440 !== 0) return { unit: "hours", value: Math.round((minutes / 60) * 10) / 10 };
  return { unit: "days", value: minutes / 1440 };
}

/** An imported test has no price yet: it stays inactive until the lab sets one. */
export const needsPrice = (t: Pick<LabTest, "price" | "active">): boolean => !t.active && Number(t.price) === 0;

export type TestForm = { code: string; name: string; loinc: string; sampleType: string; container: string; volumeMl: string; method: string; unit: string; tatMinutes: string; price: string; taxCode: string; active: boolean };
export const emptyForm = (): TestForm => ({ code: "", name: "", loinc: "", sampleType: "serum", container: "sst_gold", volumeMl: "", method: "", unit: "", tatMinutes: "240", price: "", taxCode: "", active: true });
export const formOf = (t: LabTest): TestForm => ({
  code: t.code, name: t.name, loinc: t.loinc ?? "", sampleType: t.sampleType, container: t.container, volumeMl: t.volumeMl == null ? "" : String(t.volumeMl),
  method: t.method ?? "", unit: t.unit ?? "", tatMinutes: String(t.tatMinutes), price: String(t.price), taxCode: t.taxCode ?? "", active: t.active,
});

const MONEY = /^\d{1,10}(\.\d{1,2})?$/;
export type FormError = "code" | "name" | "loinc" | "volume" | "unit" | "tat" | "price";
/** The first problem with the form, or null. `creating` also needs the code, volume and unit the create call requires. */
export function formError(f: TestForm, creating: boolean): FormError | null {
  if (creating && !/^[A-Za-z0-9][A-Za-z0-9_-]{0,23}$/.test(f.code.trim())) return "code";
  if (!f.name.trim() || f.name.trim().length > 120) return "name";
  if (f.loinc.trim() && !/^\d{1,7}-\d$/.test(f.loinc.trim())) return "loinc";
  if (f.volumeMl.trim() ? !(Number(f.volumeMl) > 0 && Number(f.volumeMl) < 10000) : creating) return "volume";
  if (creating && !f.unit.trim()) return "unit";
  const tat = Number(f.tatMinutes);
  if (!Number.isInteger(tat) || tat < 1 || tat > 43200) return "tat";
  if (!MONEY.test(f.price.trim())) return "price";
  return null;
}

/** The create body, or the PATCH body holding only what changed against `before`. */
export function testBody(f: TestForm, before?: LabTest): Record<string, unknown> {
  const all: Record<string, unknown> = {
    name: f.name.trim(), loinc: f.loinc.trim() || undefined, sampleType: f.sampleType, container: f.container,
    volumeMl: f.volumeMl.trim() ? Number(f.volumeMl) : undefined, method: f.method.trim(), unit: f.unit.trim() || undefined,
    tatMinutes: Number(f.tatMinutes), price: Number(f.price), taxCode: f.taxCode.trim(), active: f.active,
  };
  if (!before) {
    const create: Record<string, unknown> = { code: f.code.trim().toUpperCase(), ...all, method: all.method || undefined, taxCode: all.taxCode || undefined };
    delete create.active; // a new test starts active
    return create;
  }
  const was = formOf(before);
  const same: Record<string, boolean> = {
    name: was.name === f.name.trim(), loinc: was.loinc === f.loinc.trim(), sampleType: was.sampleType === f.sampleType, container: was.container === f.container,
    volumeMl: Number(was.volumeMl || 0) === Number(f.volumeMl || 0), method: was.method === f.method.trim(), unit: was.unit === f.unit.trim(),
    tatMinutes: before.tatMinutes === Number(f.tatMinutes), price: Number(before.price) === Number(f.price), taxCode: was.taxCode === f.taxCode.trim(), active: before.active === f.active,
  };
  return Object.fromEntries(Object.entries(all).filter(([k, v]) => !same[k] && v !== undefined));
}

/** Panel maths for the builder: the sum of the picked tests and what the panel price saves against it (never below zero). */
export function panelMaths(tests: readonly Pick<LabTest, "id" | "price">[], picked: readonly string[], price: string): { total: number; saving: number } {
  const total = tests.filter((t) => picked.includes(t.id)).reduce((s, t) => s + Number(t.price), 0);
  const p = MONEY.test(price.trim()) ? Number(price) : NaN;
  return { total, saving: Number.isNaN(p) ? 0 : Math.max(0, Math.round((total - p) * 100) / 100) };
}

export type PanelError = "code" | "name" | "tests" | "price";
export function panelError(code: string, name: string, picked: readonly string[], price: string): PanelError | null {
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,23}$/.test(code.trim())) return "code";
  if (!name.trim() || name.trim().length > 120) return "name";
  if (picked.length === 0 || picked.length > 60) return "tests";
  return MONEY.test(price.trim()) ? null : "price";
}
