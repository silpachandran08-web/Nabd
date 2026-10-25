"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { type OperatorProfile } from "./surfaces";
import { platformFetch, platformLoginPath } from "./platformSession";
import { capabilitiesFrom, type OperatorCapabilities } from "./capabilities";

// Authed fetch + session helpers live in platformSession.ts (no React there, so it is unit-tested).
export {
  clearPlatformSession, hasPlatformSession, platformLogout, SESSION_EXPIRED_MESSAGE, storePlatformSession,
} from "./platformSession";

import type { Problem } from "@nabd/api-client/types";
export type { Problem };

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

// Write authority is derived from the permissions returned by GET /platform/auth/me (capabilities.ts),
// mirroring the wireframe's pxCanWrite / pxCanBill / pxIsSuper flags (SSA-02). The server stays the authority —
// this only keeps actions a role cannot perform out of the DOM ("absent, not disabled").
export type OperatorAccess = OperatorCapabilities & {
  profile: OperatorProfile | null;
  /** false until /auth/me has answered; while false, write controls stay absent (safe default). */
  ready: boolean;
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

  return { profile, ready, ...capabilitiesFrom(profile?.permissions) };
}
