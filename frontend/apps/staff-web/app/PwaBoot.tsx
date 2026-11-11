"use client";
// FEF-048: registers the shell-only service worker (public/sw.js) once per page load. Not in `next dev`: unhashed dev chunks
// would be served stale from the cache. The worker gets the localised "Token {token} ready" text in its URL.
import { useEffect } from "react";
import { useTranslations } from "next-intl";
import { swUrl } from "./lib/push";

export default function PwaBoot() {
  const t = useTranslations("shell");
  const ready = t("pwa.ready", { token: "{token}" });
  useEffect(() => {
    if (process.env.NODE_ENV === "development" || !("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register(swUrl(process.env.NEXT_PUBLIC_RELEASE ?? "dev", ready), { scope: "/" }).catch(() => {});
  }, [ready]);
  return null;
}
