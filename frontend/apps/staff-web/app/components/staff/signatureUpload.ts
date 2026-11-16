import { api, reasonText } from "../../lib/api";
import { blobPath, isTerminal, statusLabel, type ScanStatus } from "../../lib/attachments";

// CLN-023: the prescriber's signature image printed on the Rx PDF. Same scanned upload pipeline as attachments (PLT-029,
// POST /files/uploads with purpose "signature", needs clinical:create); the file id then goes to PUT /staff/{id}/clinical-licence.
export const SIGNATURE_TYPES = ["image/png", "image/jpeg"] as const;
export const SIGNATURE_MAX_BYTES = 2 * 1024 * 1024;

export function signatureFileProblem(f: { type: string; size: number }): string | null {
  if (!(SIGNATURE_TYPES as readonly string[]).includes(f.type)) return "Use a PNG or JPEG image of the signature.";
  if (f.size <= 0 || f.size > SIGNATURE_MAX_BYTES) return "The image must be smaller than 2 MB.";
  return null;
}

/** Uploads and waits for the scan; resolves to the file id, or throws an Error with a message to show. */
export async function uploadSignature(file: File, wait: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))): Promise<string> {
  const bad = signatureFileProblem(file);
  if (bad) throw new Error(bad);
  let fileId: string;
  try {
    const slot = await api.post<{ uploadId: string; putUrl: string }>("/files/uploads", { purpose: "signature", contentType: file.type, sizeBytes: file.size });
    await api.put(blobPath(slot.putUrl), undefined, { rawBody: file, headers: { "Content-Type": "application/octet-stream" } });
    ({ fileId } = await api.post<{ fileId: string }>(`/files/uploads/${slot.uploadId}/complete`, {}));
  } catch (e) {
    throw new Error(reasonText(e, "The signature could not be uploaded."));
  }
  for (let i = 0; i < 60; i++) {
    const status = await api.get<{ status: ScanStatus }>(`/files/${fileId}`).then((r) => r.status, () => "pending_scan" as ScanStatus);
    if (isTerminal(status)) {
      if (status !== "available") throw new Error(statusLabel(status));
      return fileId;
    }
    await wait(1000);
  }
  throw new Error("The signature is still being checked. Try saving again in a minute.");
}
