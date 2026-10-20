"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { type OperatorProfile } from "./surfaces";
import { platformFetch, platformLoginPath } from "./platformSession";

// Authed fetch + session helpers live in platformSession.ts (no React there, so it is unit-tested).
export {
  clearPlatformSession, hasPlatformSession, platformLogout, SESSION_EXPIRED_MESSAGE, storePlatformSession,
} from "./platformSession";

export type Problem = { title?: string; detail?: string };

/** Returns an authed fetch. Resolves to null when the session is gone (the operator is already being
 * redirected to login); callers treat null as "stop quietly", same contract the old copies had. */
export function usePlatformFetch() {
  const router = useRouter();
  return useCallback(
    async (path: string, init?: RequestInit): Promise<Response | null> => {
      const res = await platformFetch(path, init);
      if (!res) router.replace(platformLoginPath());
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
