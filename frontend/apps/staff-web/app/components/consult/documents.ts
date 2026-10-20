// DOC-030: clinical documents written from the consult. Shapes match ClinicalDocumentController
// (GET/POST /clinical/visits/{id}/documents, GET/PATCH /clinical/documents/{id}, POST .../issue).
// The labels below name the boxes the doctor fills in; no document wording lives in the app.

export type DocType = "visit_summary" | "referral" | "certificate";
export type DocBody = Record<string, string>;
export type ClinicalDocument = {
  id: string; patientId: string; queueEntryId: string; doctorId: string;
  type: DocType; status: "draft" | "issued";
  body: DocBody;
  // Snapshot taken at issue time (null while a draft). Read defensively: only the keys below are used.
  header: Record<string, unknown> | null;
  issuedBy: string | null; issuedAt: string | null; createdAt: string; updatedAt: string;
};
export type SummaryPrefill = { noteSigned: boolean; prescriptionSigned: boolean; body: DocBody };

export type FieldDef = { key: string; label: string; kind: "text" | "textarea" | "date"; required?: boolean };

export const DOC_LABEL: Record<DocType, string> = {
  visit_summary: "Visit summary",
  referral: "Referral letter",
  certificate: "Medical certificate",
};

export const DOC_ORDER: DocType[] = ["referral", "certificate", "visit_summary"];

export const DOC_FIELDS: Record<DocType, FieldDef[]> = {
  visit_summary: [
    { key: "diagnosis", label: "Diagnosis", kind: "text" },
    { key: "assessment", label: "Assessment", kind: "textarea" },
    { key: "plan", label: "Plan", kind: "textarea" },
    { key: "medicines", label: "Medicines", kind: "textarea" },
    { key: "patientInstructions", label: "Instructions for the patient", kind: "textarea" },
  ],
  referral: [
    { key: "referredTo", label: "Referred to (name or department)", kind: "text", required: true },
    { key: "department", label: "Department", kind: "text" },
    { key: "reason", label: "Reason for referral", kind: "textarea", required: true },
    { key: "findings", label: "Findings", kind: "textarea" },
    { key: "notes", label: "Notes", kind: "textarea" },
  ],
  certificate: [
    { key: "purpose", label: "Purpose", kind: "text", required: true },
    { key: "findings", label: "Findings", kind: "textarea" },
    { key: "restFrom", label: "Rest from", kind: "date" },
    { key: "restTo", label: "Rest until", kind: "date" },
    { key: "remarks", label: "Remarks", kind: "textarea" },
  ],
};

export const MAX_FIELD = 4000;

/** Client-side mirror of the server's issue checks, so the doctor sees the gap before the round trip. */
export function missingForIssue(type: DocType, body: DocBody): string[] {
  const filled = (k: string) => (body[k] ?? "").trim().length > 0;
  if (type === "visit_summary") return DOC_FIELDS[type].some((f) => filled(f.key)) ? [] : ["at least one field"];
  const missing = DOC_FIELDS[type].filter((f) => f.required && !filled(f.key)).map((f) => f.label);
  if (type === "certificate" && filled("restFrom") && filled("restTo") && body.restTo < body.restFrom) missing.push("a rest period that ends after it starts");
  return missing;
}

export function str(o: unknown, key: string): string {
  if (o && typeof o === "object") {
    const v = (o as Record<string, unknown>)[key];
    if (typeof v === "string") return v.trim();
  }
  return "";
}
export function obj(o: unknown, key: string): Record<string, unknown> | null {
  if (o && typeof o === "object") {
    const v = (o as Record<string, unknown>)[key];
    if (v && typeof v === "object") return v as Record<string, unknown>;
  }
  return null;
}
/** Registration number: shown only when the snapshot carries one (added by the doctor-profile work); never invented. */
export function registrationNumber(header: Record<string, unknown> | null): string {
  const d = obj(header, "doctor");
  return str(d, "registrationNumber") || str(d, "registrationNo");
}
