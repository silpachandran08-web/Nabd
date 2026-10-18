import { apiFetch, goLogin } from "../../lib/apiClient";
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export type PageMeta = { nextCursor: string | null; limit: number };

/** GET with the session token. Throws "auth" after redirecting on 401, "http" on any other failure. */
export async function getJson<T>(path: string): Promise<T> {
  const token = localStorage.getItem("nabd_access_token");
  if (!token) { goLogin(); throw new Error("auth"); }
  const res = await apiFetch(path, { headers: { Authorization: `Bearer ${token}` } });
  if (res.status === 401) { localStorage.removeItem("nabd_access_token"); goLogin(); throw new Error("auth"); }
  if (!res.ok) throw new Error("http");
  return (await res.json()) as T;
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
