"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { API_BASE, type OperatorProfile } from "./surfaces";

// One place for the operator-console's authed fetch + 401 handling (PLT-037). It used to be
// copy-pasted into billing, plans, provisioning, access, support and audit, and only some copies
// cleared the session on 401. A 401 now tries POST /platform/auth/refresh once (single-flight, so
// parallel requests share one refresh) and replays the request; only if that fails is the session
// cleared and the operator sent to login.

const ACCESS_KEY = "nabd_platform_access_token";
const REFRESH_KEY = "nabd_platform_refresh_token";

export type Problem = { title?: string; detail?: string };

let refreshing: Promise<boolean> | null = null;

function refreshSession(): Promise<boolean> {
  if (refreshing) return refreshing;
  refreshing = (async () => {
    const refreshToken = localStorage.getItem(REFRESH_KEY);
    if (!refreshToken) return false;
    try {
      const res = await fetch(`${API_BASE}/platform/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });
      if (!res.ok) return false;
      const pair: { accessToken?: string; refreshToken?: string } = await res.json();
      if (!pair.accessToken) return false;
      localStorage.setItem(ACCESS_KEY, pair.accessToken);
      if (pair.refreshToken) localStorage.setItem(REFRESH_KEY, pair.refreshToken);
      return true;
    } catch {
      return false;
    } finally {
      // Let the next 401 (minutes later) trigger a fresh refresh.
      setTimeout(() => { refreshing = null; }, 0);
    }
  })();
  return refreshing;
}

export function clearPlatformSession() {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
}

function send(path: string, init: RequestInit | undefined, token: string) {
  const headers = new Headers(init?.headers);
  headers.set("Authorization", `Bearer ${token}`);
  if (typeof init?.body === "string" && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return fetch(`${API_BASE}${path}`, { ...init, headers });
}

/** Returns an authed fetch. Resolves to null when the session is gone (the operator is already being
 * redirected to login); callers treat null as "stop quietly", same contract the old copies had. */
export function usePlatformFetch() {
  const router = useRouter();
  return useCallback(
    async (path: string, init?: RequestInit): Promise<Response | null> => {
      const token = localStorage.getItem(ACCESS_KEY);
      if (!token) {
        router.replace("/platform/login");
        return null;
      }
      let res = await send(path, init, token);
      if (res.status === 401) {
        if (await refreshSession()) {
          res = await send(path, init, localStorage.getItem(ACCESS_KEY) ?? "");
        }
        if (res.status === 401) {
          clearPlatformSession();
          router.replace("/platform/login");
          return null;
        }
      }
      return res;
    },
    [router]
  );
}

export async function problemDetail(res: Response, fallback: string): Promise<string> {
  const p: Problem | null = await res.json().catch(() => null);
  return p?.detail || fallback;
}

// Write authority is derived from the role code returned by GET /platform/auth/me, mirroring the
// wireframe's pxCanWrite / pxCanBill / pxIsSuper flags (SSA-02). The server stays the authority —
// this only keeps actions a role cannot perform out of the DOM ("absent, not disabled").
export type OperatorAccess = {
  profile: OperatorProfile | null;
  /** false until /auth/me has answered; while false, write controls stay absent (safe default). */
  ready: boolean;
  canWrite: boolean; // super_admin, implementation — tenants, provisioning
  canBill: boolean; // super_admin, billing — plans, subscriptions, discount requests (server: billing_revenue:edit)
  isSuper: boolean; // super_admin — discount approvals above the cap (server: billing_revenue:approve, V2-005)
  readOnly: boolean; // commercial, compliance_dpo — wireframe's read-only note
};

export function useOperatorAccess(): OperatorAccess {
  const fetchAuthed = usePlatformFetch();
  const [profile, setProfile] = useState<OperatorProfile | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const res = await fetchAuthed("/platform/auth/me");
        if (!cancelled && res?.ok) setProfile(await res.json());
      } catch {
        /* leave write controls absent */
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => { cancelled = true; };
  }, [fetchAuthed]);

  const role = profile?.role ?? "";
  return {
    profile,
    ready,
    canWrite: role === "super_admin" || role === "implementation",
    canBill: role === "super_admin" || role === "billing",
    isSuper: role === "super_admin",
    readOnly: role === "commercial" || role === "compliance_dpo",
  };
}
