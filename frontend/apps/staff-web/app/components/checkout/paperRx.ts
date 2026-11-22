// PHL-020: pure rules for the "Paper Rx" dialog (outside paper / WhatsApp-photo prescription). React-free and without the
// app's api singleton (the client is passed in), so they are unit-tested. Every result is a key of checkout.paperRx.
import type { RequestOptions } from "@nabd/api-client";
import { ALLOWED_TYPES, MAX_UPLOAD_BYTES, blobPath, isTerminal, type ScanStatus } from "../../lib/attachments.ts";

export type PaperRxForm = {
  prescriberName: string;
  prescriberRegNo: string;
  rxDate: string; // YYYY-MM-DD
  qty: number; // units the paper allows in all
  hasPhoto: boolean;
  duplicateCopyRetained: boolean;
};

/** The first thing wrong with the form, or null. needsCopy: the item's schedule class keeps a duplicate copy (the server said so). */
export function paperRxProblem(f: PaperRxForm, today: string, cartQty: number, needsCopy: boolean): string | null {
  if (!f.prescriberName.trim()) return "needName";
  if (!f.prescriberRegNo.trim()) return "needRegNo";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(f.rxDate)) return "needDate";
  if (f.rxDate > today) return "futureDate";
  if (!Number.isInteger(f.qty) || f.qty < 1) return "needQty";
  if (f.qty < cartQty) return "qtyBelowCart";
  if (needsCopy && (!f.duplicateCopyRetained || !f.hasPhoto)) return "copyNeeded";
  return null;
}

export function photoProblem(file: { type: string; size: number }): string | null {
  if (!(ALLOWED_TYPES as readonly string[]).includes(file.type)) return "photoType";
  if (file.size <= 0 || file.size > MAX_UPLOAD_BYTES) return "photoSize";
  return null;
}

/** The message key for a refused record or sale, by problem type (scanStatus says why a photo is not usable); null = not one of ours. */
export function paperRxErrorKey(p: { type?: string; scanStatus?: unknown } | null | undefined): string | null {
  const slug = p?.type?.split("/").pop() ?? "";
  if (slug === "rx-copy-required") return "copyNeeded";
  if (slug === "document-already-used") return "photoUsed";
  if (slug === "rx-expired") return "expired";
  if (slug === "rx-item-mismatch") return "itemMismatch";
  if (slug !== "document-not-clean") return null;
  if (p?.scanStatus === "infected") return "photoInfected";
  if (p?.scanStatus === "rejected_type") return "photoRejected";
  if (p?.scanStatus === "pending_scan" || p?.scanStatus === "awaiting_upload") return "photoStillScanning";
  return "photoUnavailable";
}

type Client = {
  get<T>(path: string): Promise<T>;
  post<T>(path: string, body?: unknown): Promise<T>;
  put<T>(path: string, body?: unknown, opts?: Omit<RequestOptions, "body">): Promise<T>;
};

const BASE = "/pharmacy/paper-prescriptions";

/** Uploads through the pharmacy endpoints (pharmacy:dispense) and waits for the scan. Resolves to the file id; throws an Error
 * whose message is a checkout.paperRx key (a blocked photo never becomes a document id, so the sale cannot go on with it). */
export async function uploadPaperRxPhoto(client: Client, file: Blob,
  wait: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))): Promise<string> {
  const bad = photoProblem(file);
  if (bad) throw new Error(bad);
  let fileId: string;
  try {
    const slot = await client.post<{ uploadId: string; putUrl: string }>(`${BASE}/uploads`, { contentType: file.type, sizeBytes: file.size });
    await client.put(blobPath(slot.putUrl), undefined, { rawBody: file, headers: { "Content-Type": "application/octet-stream" } });
    ({ fileId } = await client.post<{ fileId: string }>(`${BASE}/uploads/${slot.uploadId}/complete`, {}));
  } catch {
    throw new Error("photoUploadFailed");
  }
  for (let i = 0; i < 40; i++) {
    const status = await client.get<{ status: ScanStatus }>(`${BASE}/uploads/${fileId}`).then((r) => r.status, () => "pending_scan" as ScanStatus);
    if (isTerminal(status)) {
      if (status === "available") return fileId;
      throw new Error(status === "infected" ? "photoInfected" : status === "rejected_type" ? "photoRejected" : "photoUnavailable");
    }
    await wait(500);
  }
  throw new Error("photoStillScanning");
}
