// ADM-015: the owner's full export drawer. Contract: POST /v1/setup/export-jobs (setup:export + X-Step-Up-Token with target
// "data-export") -> 202 { id, status, estimateMinutes }; GET /v1/setup/export-jobs/{id} -> { status, datasets[{ name, rows, status }],
// linkSent, linkExpiresAt, bytes, archiveSha256 }.

export const STEP_UP_TARGET = "data-export";

export const DATASETS: { key: string; label: string }[] = [
  { key: "patients", label: "Patients and family links" },
  { key: "appointments", label: "Appointments" },
  { key: "visits", label: "Visits, queue history and notes" },
  { key: "prescriptions", label: "Prescriptions" },
  { key: "results", label: "Results (vital signs)" },
  { key: "invoices", label: "Invoices and payments" },
  { key: "consents", label: "Consents" },
  { key: "audit", label: "Audit log" },
  { key: "files", label: "Files" },
];

export type ExportFormat = "xlsx" | "csv" | "fhir_ndjson" | "pdf_per_patient";
export const FORMATS: { value: ExportFormat; label: string }[] = [
  { value: "xlsx", label: "Excel" },
  { value: "csv", label: "CSV" },
  { value: "fhir_ndjson", label: "FHIR R4" },
  { value: "pdf_per_patient", label: "PDF per patient" },
];

export type DatasetProgress = { name: string; rows: number | null; status: string };
export type ExportJob = { id: string; status: string; format: string; datasets: DatasetProgress[]; linkSent: boolean; linkExpiresAt: string | null;
  bytes: number | null; archiveSha256: string | null };

export const startBody = (datasets: string[], format: ExportFormat) => ({ scope: "full_tenant", datasets, format });

export const finished = (status: string | undefined) => status === "done" || status === "failed";

/** Human size for the ready view. */
export function size(bytes: number | null | undefined): string {
  if (bytes == null) return "";
  const units = ["bytes", "KB", "MB", "GB"];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${i === 0 ? v : v.toFixed(1)} ${units[i]}`;
}

/** What the owner should do after a failed start; anything that is not an HTTP problem is a network failure. */
export function startError(e: unknown): string {
  const p = e && typeof e === "object" && "status" in e ? (e as { status: number; code?: string }) : null;
  if (!p) return "Couldn't reach the server. No export was started.";
  if (p.code === "EXPORT_RUNNING") return "An export is already running for this clinic. Wait for it to finish.";
  if (p.code === "STEP_UP_REQUIRED" || p.status === 401) return "The authenticator code was not accepted. Enter a fresh code.";
  if (p.status === 403) return "Only the clinic owner can export all data.";
  return "Couldn't start the export. Try again.";
}
