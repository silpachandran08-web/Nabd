// FEF-061: the calls patient-web makes. Contract with patient-edge (patient-auth, aud=patient) and the IDC-015 secure-link service,
// written against the story spec; the services are not in this tree yet, so the shapes below are the agreed interface (see the FEF-061 report).
//
//   GET  {base}/patient-links/{linkId}                 -> LinkInfo           (no token; says nothing about the document type)
//   POST {base}/patient-links/{linkId}/otp             -> 202 {resendAfterSeconds}
//   POST {base}/patient-links/{linkId}/otp/verify      -> 200 {accessToken}  (patient session aud=patient, bound to this link)
//   GET  {base}/patient-links/{linkId}/content         -> Resolved           (Bearer)
//   GET  {base}/patient-links/{linkId}/document        -> file bytes         (Bearer)
//   POST {base}/exchange/consents/{id}/patient-decision -> 200 {status}      (Bearer, Idempotency-Key; 409 ARTEFACT_CHANGED, 410 EXPIRED)
//
// The access token lives in component state only: never in a URL, storage or cookie.

export const API_BASE = (process.env.NEXT_PUBLIC_PATIENT_API_BASE_URL ?? "http://localhost:8080/v1").replace(/\/$/, "");
const TIMEOUT_MS = 15000;

export type LinkInfo = { clinicName: string };
export type ConsentSummary = {
  consentId: string; requester: string; categories: string[]; from: string; to: string;
  purpose: string; validUntil: string; artefactHash: string; decided?: "approve" | "decline" | null;
};
export type Booking = { doctorName: string; clinicName: string; address?: string | null; startsAt: string; timeZone: string; status: "confirmed" | "held" | "cancelled" };
export type Resolved =
  | { kind: "document"; contentType: string; fileName?: string | null }
  | { kind: "consent"; consent: ConsentSummary }
  | { kind: "booking"; booking: Booking };

/** Why a call failed, in the only terms the UI cares about. */
export type Failure = "expired" | "wrong_code" | "too_many" | "changed" | "network";
export class PatientApiError extends Error {
  failure: Failure;
  status: number;
  constructor(failure: Failure, status = 0) { super(failure); this.failure = failure; this.status = status; }
}

type Fetch = typeof fetch;

/** Maps a non-2xx answer to a Failure. 404 counts as expired: an unknown link must look like an old one. */
export function failureOf(status: number, code: string | null): Failure {
  if (status === 410 || status === 404) return "expired";
  if (status === 409 && code === "ARTEFACT_CHANGED") return "changed";
  if (status === 429) return "too_many";
  if (status === 401 || status === 403) return code === "EXPIRED" ? "expired" : "wrong_code";
  return "network";
}

async function codeOf(res: Response): Promise<string | null> {
  try {
    const body = (await res.clone().json()) as { code?: unknown; title?: unknown };
    return typeof body.code === "string" ? body.code : typeof body.title === "string" ? body.title : null;
  } catch { return null; }
}

async function call(f: Fetch, path: string, init: RequestInit & { token?: string; idempotencyKey?: string } = {}): Promise<Response> {
  const { token, idempotencyKey, ...rest } = init;
  const headers = new Headers(rest.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (idempotencyKey) headers.set("Idempotency-Key", idempotencyKey);
  if (rest.body) headers.set("Content-Type", "application/json");
  let res: Response;
  try {
    res = await f(`${API_BASE}${path}`, { ...rest, headers, cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer", signal: AbortSignal.timeout(TIMEOUT_MS) });
  } catch { throw new PatientApiError("network"); }
  if (!res.ok) throw new PatientApiError(failureOf(res.status, await codeOf(res)), res.status);
  return res;
}

const enc = encodeURIComponent;

export const patientApi = (f: Fetch = (...a) => fetch(...a)) => ({
  linkInfo: async (linkId: string): Promise<LinkInfo> => (await call(f, `/patient-links/${enc(linkId)}`)).json(),
  requestOtp: async (linkId: string): Promise<{ resendAfterSeconds: number }> => (await call(f, `/patient-links/${enc(linkId)}/otp`, { method: "POST" })).json(),
  verifyOtp: async (linkId: string, code: string): Promise<{ accessToken: string }> =>
    (await call(f, `/patient-links/${enc(linkId)}/otp/verify`, { method: "POST", body: JSON.stringify({ code }) })).json(),
  content: async (linkId: string, token: string): Promise<Resolved> => (await call(f, `/patient-links/${enc(linkId)}/content`, { token })).json(),
  document: async (linkId: string, token: string): Promise<Blob> => (await call(f, `/patient-links/${enc(linkId)}/document`, { token })).blob(),
  decide: async (consentId: string, token: string, decision: "approve" | "decline", artefactHash: string, key: string): Promise<{ status: string }> =>
    (await call(f, `/exchange/consents/${enc(consentId)}/patient-decision`, { method: "POST", token, idempotencyKey: key, body: JSON.stringify({ decision, artefactHash }) })).json(),
});

/** "Plain language" labels: known codes come from the catalogue (cat_*, purpose_*), anything else is shown as the clinic wrote it. */
export function labelOf(prefix: "cat" | "purpose", code: string, messages: Record<string, string>): string {
  return messages[`${prefix}_${code}`] ?? code.replace(/_/g, " ");
}

export const DOC_INLINE = (contentType: string) => (contentType.startsWith("image/") ? "image" : contentType === "application/pdf" ? "pdf" : "other");
