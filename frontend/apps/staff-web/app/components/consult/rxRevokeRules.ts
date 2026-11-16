// CLN-023: the revoke reason rule, mirrored from the server (RxRevokeRequest: 5 to 500 characters). Pure, so node:test covers it.
export const REVOKE_REASON_MIN = 5;
export const REVOKE_REASON_MAX = 500;

export function revokeReasonOk(reason: string): boolean {
  const n = reason.trim().length;
  return n >= REVOKE_REASON_MIN && n <= REVOKE_REASON_MAX;
}
