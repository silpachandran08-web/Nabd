"use client";

import { queryOptions, useQuery } from "@tanstack/react-query";
import { ApiError, TimeoutError } from "@nabd/api-client";
import { platformMeKey, type OperatorProfile } from "./surfaces";
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

/** GET /platform/auth/me, shared by the shell (nav + route gate), Command Centre and useOperatorAccess: one request per session. */
export const platformMeQuery = () => queryOptions({
  queryKey: platformMeKey,
  queryFn: ({ signal }) => platformApi.get<OperatorProfile>("/platform/auth/me", { signal }),
  staleTime: Infinity,
});

export function useOperatorAccess(): OperatorAccess {
  const me = useQuery(platformMeQuery()); // a failed call leaves write controls absent
  const profile = me.data ?? null;
  return { profile, ready: me.isFetched, ...capabilitiesFrom(profile?.permissions) };
}
