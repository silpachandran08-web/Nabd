// FEF-048: pure helpers for the service worker and Web Push (no React, no api import: session.ts uses them on sign-out).
import { base64UrlBytes } from "./jwt.ts";

/** localStorage key of this browser's push subscription id at Core (POST /v1/me/push-subscriptions). */
export const PUSH_SUB_KEY = "nabd_push_sub_id";

/** The service worker URL: v names its cache (one per release), ready is the localised notification text with a {token} slot. */
export function swUrl(release: string, ready: string): string {
  return `/sw.js?${new URLSearchParams({ v: release, ready })}`;
}

/** VAPID public key (base64url) -> the bytes PushManager.subscribe wants. */
export const vapidKeyBytes = (b64url: string): Uint8Array<ArrayBuffer> => base64UrlBytes(b64url);

export type PushSupport = "supported" | "unsupported" | "not-configured";
/** Whether this browser can take Web Push (iOS: only the installed Home Screen app, 16.4+) and the deployment has a VAPID key. */
export function pushSupport(env: { serviceWorker: boolean; pushManager: boolean; notification: boolean; vapidKey: string | undefined }): PushSupport {
  if (!env.serviceWorker || !env.pushManager || !env.notification) return "unsupported";
  return env.vapidKey ? "supported" : "not-configured";
}

/** Sign-out: removes this device's subscription at Core (best effort, the session is ending anyway) and in the browser. */
export async function dropPushSubscription(remove: (id: string) => Promise<unknown>): Promise<void> {
  let id: string | null = null;
  try { id = localStorage.getItem(PUSH_SUB_KEY); } catch { /* blocked storage: nothing was stored */ }
  if (id) await remove(id).catch(() => {});
  try { localStorage.removeItem(PUSH_SUB_KEY); } catch { /* ignore */ }
  try {
    const reg = typeof navigator !== "undefined" && "serviceWorker" in navigator ? await navigator.serviceWorker.getRegistration() : undefined;
    await (await reg?.pushManager?.getSubscription())?.unsubscribe();
  } catch { /* no worker or no push: nothing to unsubscribe */ }
}
