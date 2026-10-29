// PLT-029/030: pure rules for the consult attachments panel. React-free so they are unit-tested.
export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
export const ALLOWED_TYPES = ["application/pdf", "image/jpeg", "image/png"] as const;
export type ScanStatus = "awaiting_upload" | "pending_scan" | "available" | "rejected_type" | "infected" | "deleted";

/** null when the file may be uploaded, else the line to show; the server repeats every check. */
export function validateUpload(f: { type: string; size: number }): string | null {
  if (!(ALLOWED_TYPES as readonly string[]).includes(f.type)) return "Only PDF, JPEG and PNG files can be attached.";
  if (f.size <= 0) return "This file is empty.";
  if (f.size > MAX_UPLOAD_BYTES) return "This file is larger than 20 MB.";
  return null;
}

/** What the uploader sees for each scan status; blocked files never show a reason beyond this. */
export function statusLabel(s: ScanStatus): string {
  if (s === "available") return "Ready";
  if (s === "infected" || s === "rejected_type") return "File blocked";
  if (s === "deleted") return "Removed";
  return "Scanning";
}

export const isTerminal = (s: ScanStatus): boolean => s === "available" || s === "infected" || s === "rejected_type" || s === "deleted";

/** The signed URLs live outside /v1; the api base ends in /v1, so step out of it. */
export const blobPath = (url: string): string => (url.startsWith("/") ? `/..${url}` : url);

export const KINDS = [{ id: "outside_report", label: "Outside report" }, { id: "photo", label: "Photo" }, { id: "xray", label: "X-ray" }] as const;
export type Kind = (typeof KINDS)[number]["id"];
export const defaultKind = (type: string): Kind => (type === "application/pdf" ? "outside_report" : "photo");
