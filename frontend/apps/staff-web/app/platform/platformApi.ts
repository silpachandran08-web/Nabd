"use client";

import { useEffect, useState } from "react";
import { ApiError, TimeoutError } from "@nabd/api-client";
import { type OperatorProfile } from "./surfaces";
import { platformApi } from "./platformSession";
import { capabilitiesFrom, type OperatorCapabilities } from "./capabilities";

// The operator client + session helpers live in platformSession.ts (no React there, so it is unit-tested).
export {
  clearPlatformSession, hasPlatformSession, platformApi, platformLogout, SESSION_EXPIRED_MESSAGE, storePlatformSession,
} from "./platformSession";

import type { Problem } from "@nabd/api-client/types";
export type { Problem };

export const isForbidden = (e: unknown) => e instanceof ApiError && e.status === 403;

/** The server's reason for a refused call, else `fallback`; a timeout or dropped connection says so. */
export function platformError(e: unknown, fallback: string): string {
  if (e instanceof ApiError) return e.detail || fallback;
  if (e instanceof TimeoutError) return "The server is taking too long. Check status before trying again.";
  return "Couldn't reach the server. Check your connection and try again.";
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
  const [profile, setProfile] = useState<OperatorProfile | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const me = await platformApi.get<OperatorProfile>("/platform/auth/me");
        if (!cancelled) setProfile(me);
      } catch {
        /* leave write controls absent */
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  return { profile, ready, ...capabilitiesFrom(profile?.permissions) };
}
