// FEF-016: the consult workspace's AuthedFetch (Response in, Response out) on top of the shared api client, so its
// ~20 components keep their status-code logic while token, refresh, timeout, retry and Idempotency-Key come from
// packages/api-client. A 401 returns null (the client has already sent the user to sign-in); other failures keep
// their Problem body and status.
import type { AuthedFetch } from "./apiClient";
import { api, ApiError } from "./api";

const json = (status: number, body: unknown) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

export const responseFetch: AuthedFetch = async (path, init) => {
  const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
  try {
    const data = await api.request<unknown>(init?.method ?? "GET", path, { body, signal: init?.signal ?? undefined });
    return json(data === undefined ? 204 : 200, data);
  } catch (e) {
    if (e instanceof ApiError) return e.status === 401 ? null : json(e.status, e.problem);
    throw e;
  }
};
