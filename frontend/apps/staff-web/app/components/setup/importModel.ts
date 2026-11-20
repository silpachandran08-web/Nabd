// ADM-013: what the staged import can bring in, from which old software, and the fields of each kind of list.
// Field keys are the server's (ImportMapper targets); the unit test keeps the two lists equal.
export type ImportType = "patients" | "visit_history" | "drugs" | "stock";
export type Field = { key: string; label: string; required: boolean };

export const PATIENT_FIELDS: Field[] = [
  { key: "name", label: "Name", required: true },
  { key: "mobile", label: "Mobile", required: true },
  { key: "dob", label: "Date of birth", required: true },
  { key: "sex", label: "Sex", required: true },
  { key: "oldRecordNo", label: "Old record number", required: false },
  { key: "allergies", label: "Allergies (a doctor confirms them later)", required: false },
];

const ITEM_EXTRAS: Field[] = [
  { key: "genericName", label: "Generic name", required: false },
  { key: "taxRate", label: "Tax rate %", required: false },
  { key: "hsnCode", label: "HSN code", required: false },
  { key: "packSize", label: "Pack size", required: false },
  { key: "unit", label: "Unit", required: false },
  { key: "rx", label: "Prescription only (yes / no)", required: false },
];

export const TYPE_FIELDS: Record<ImportType, Field[]> = {
  patients: PATIENT_FIELDS,
  visit_history: [
    { key: "oldRecordNo", label: "Old record number", required: false },
    { key: "mobile", label: "Mobile", required: false },
    { key: "dob", label: "Date of birth", required: false },
    { key: "visitDate", label: "Visit date", required: true },
    { key: "doctorName", label: "Doctor name", required: true },
    { key: "complaint", label: "Complaint", required: false },
    { key: "diagnosis", label: "Diagnosis", required: false },
    { key: "notes", label: "Notes", required: false },
    { key: "medications", label: "Medicines", required: false },
    { key: "billNo", label: "Bill number", required: false },
    { key: "billAmount", label: "Bill amount", required: false },
  ],
  drugs: [
    { key: "name", label: "Item name", required: true },
    { key: "price", label: "Selling price", required: true },
    { key: "mrp", label: "MRP", required: false },
    ...ITEM_EXTRAS,
  ],
  stock: [
    { key: "name", label: "Item name", required: true },
    { key: "batchNo", label: "Batch number", required: true },
    { key: "expiry", label: "Expiry date", required: true },
    { key: "qty", label: "Quantity", required: true },
    { key: "mrp", label: "MRP", required: false },
    { key: "purchaseRate", label: "Purchase rate", required: false },
    { key: "price", label: "Selling price", required: false },
    ...ITEM_EXTRAS,
  ],
};

/** option = the picker text; noun = "Import 12 <noun>". */
export const TYPES: { key: ImportType; option: string; noun: string }[] = [
  { key: "patients", option: "Patients", noun: "patients" },
  { key: "visit_history", option: "Past visits and bills (read-only history)", noun: "past visits" },
  { key: "drugs", option: "Drug list", noun: "drugs" },
  { key: "stock", option: "Opening stock (batch, expiry, quantity)", noun: "stock lines" },
];

/** csv = any other software: the owner matches the columns. The named ones are matched by their standard export headings. */
export const SOURCES: { key: string; label: string }[] = [
  { key: "clinicea", label: "Clinicea" },
  { key: "practo_ray", label: "Practo Ray" },
  { key: "lybrate", label: "Lybrate Pro" },
  { key: "medas", label: "Medas" },
  { key: "csv", label: "Other software (CSV or Excel)" },
];

export const SUPPORTED_NOTE = "India: Practo Ray, Clinicea, Lybrate Pro. Gulf (Saudi Arabia, UAE, Qatar): Medas, Clinicea. Upload the export as it came out of the old software (zip, Excel or CSV); its columns are matched for you. Import patients first, then past visits. For any other software, or if the headings of your export are not recognised, choose Other software and match the columns yourself.";

export function typeOf(importType: string | null | undefined): ImportType {
  return TYPES.some((t) => t.key === importType) ? (importType as ImportType) : "patients";
}

/** Labels of what still has to be mapped. A past visit finds its patient by the old record number, or by mobile with date of birth. */
export function missingFields(type: ImportType, columns: Record<string, string>): string[] {
  const out = TYPE_FIELDS[type].filter((f) => f.required && !columns[f.key]).map((f) => f.label);
  if (type === "visit_history" && !columns.oldRecordNo && !(columns.mobile && columns.dob)) out.push("Old record number (or Mobile and Date of birth)");
  return out;
}

/** The upload format from the file name; an export zip only from the old-software tab. */
export function formatOf(fileName: string, allowZip: boolean): "xlsx" | "csv" | "zip" | null {
  if (/\.xlsx$/i.test(fileName)) return "xlsx";
  if (/\.csv$/i.test(fileName)) return "csv";
  return allowZip && /\.zip$/i.test(fileName) ? "zip" : null;
}
