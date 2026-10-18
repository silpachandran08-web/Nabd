// PLT-003: PIN policy v2 (6-8 digits) and the lockout wording shared by the lock screen and sign-in.
export const NEW_PIN_PATTERN = /^[0-9]{6,8}$/;

/** "Too many attempts, try again in N min" - distinct from "Incorrect PIN". Missing/invalid seconds fall back to "later". */
export function lockedMessage(retryAfterSeconds: unknown): string {
  const s = typeof retryAfterSeconds === "number" && Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0 ? retryAfterSeconds : 0;
  if (!s) return "Too many attempts, try again later.";
  const min = Math.ceil(s / 60);
  return `Too many attempts, try again in ${min} min.`;
}

/** The token claim minted while the account still has a pre-v2 PIN. */
export function needsPinChange(claims: { pinChange?: boolean } | null | undefined): boolean {
  return claims?.pinChange === true;
}
