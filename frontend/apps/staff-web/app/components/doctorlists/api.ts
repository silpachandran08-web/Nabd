// FEF-016: doctor lists read through the shared api client (token, refresh, timeout from packages/api-client).
import type { PageMeta as GPageMeta } from "@nabd/api-client/generated";
import { api, ApiError } from "../../lib/api";

export type PageMeta = Omit<GPageMeta, "nextCursor" | "limit"> & { nextCursor: string | null; limit: number };

/** GET through the api client. Throws "auth" on 401 (the client has already sent the user to sign-in), "http" on any other failure. */
export async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  try {
    return await api.get<T>(path, { signal });
  } catch (e) {
    throw new Error(e instanceof ApiError && e.status === 401 ? "auth" : "http");
  }
}

export function isoDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export function addDays(d: Date, n: number): Date {
  const c = new Date(d);
  c.setDate(c.getDate() + n);
  return c;
}

export function fmtDate(iso: string): string {
  const d = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? new Date(`${iso}T00:00:00`) : new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

export function fmtTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
}
