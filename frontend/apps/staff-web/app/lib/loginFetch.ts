// LIVE-01: the hosted backend can cold-start, so sign-in requests get a hard timeout and a
// "still connecting" hint instead of an endless spinner. Nothing here logs the request body.
export const LOGIN_TIMEOUT_MS = 20_000;
export const LOGIN_SLOW_MS = 5_000;
export const LOGIN_SLOW_MESSAGE = "Still connecting - the server may be waking up…";
export const LOGIN_TIMEOUT_MESSAGE = "The server took too long to answer. Try again.";

export class LoginTimeoutError extends Error {
  constructor() {
    super("Login request timed out");
    this.name = "LoginTimeoutError";
  }
}

/** fetch that aborts after timeoutMs (throwing LoginTimeoutError) and calls onSlow once after slowMs. */
export async function loginFetch(url: string, init: RequestInit, onSlow: () => void,
  timeoutMs = LOGIN_TIMEOUT_MS, slowMs = LOGIN_SLOW_MS): Promise<Response> {
  const ctrl = new AbortController();
  const slow = setTimeout(onSlow, slowMs);
  const hard = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: ctrl.signal });
  } catch (e) {
    throw ctrl.signal.aborted ? new LoginTimeoutError() : e;
  } finally {
    clearTimeout(slow);
    clearTimeout(hard);
  }
}
