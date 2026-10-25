// FEF-018: sign-in calls (owner portal, operator console) through the shared client with the LIVE-01 behaviour of
// loginFetch: a "still connecting" hint after LOGIN_SLOW_MS and a LoginTimeoutError after the client's 20 s timeout.
// Resolves to the parsed body; a non-2xx answer throws ApiError (status + Problem); the session is never touched.
import { TimeoutError } from "@nabd/api-client";
import { publicApi } from "./api";
import { LOGIN_SLOW_MS, LoginTimeoutError } from "./loginFetch";

export async function loginCall<T>(path: string, body: unknown, onSlow: () => void): Promise<T> {
  const slow = setTimeout(onSlow, LOGIN_SLOW_MS);
  try {
    return await publicApi.post<T>(path, body, { keepSession: true });
  } catch (e) {
    throw e instanceof TimeoutError ? new LoginTimeoutError() : e;
  } finally {
    clearTimeout(slow);
  }
}
