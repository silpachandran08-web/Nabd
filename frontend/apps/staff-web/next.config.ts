import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";
import { STATIC_HEADERS } from "./app/security/csp";

const nextConfig: NextConfig = {
  // FEF-029: build id on error-boundary reports (no secret; the commit sha is public in the repo).
  env: { NEXT_PUBLIC_RELEASE: (process.env.VERCEL_GIT_COMMIT_SHA ?? process.env.NABD_RELEASE ?? "dev").slice(0, 12) },
  // FEF-010: on every response, assets included; the nonce CSP itself is per request in proxy.ts.
  async headers() {
    return [
      { source: "/:path*", headers: Object.entries(STATIC_HEADERS).map(([key, value]) => ({ key, value })) },
      // FEF-048: the browser must always check for a new service worker (a stale one would keep an old shell cache).
      { source: "/sw.js", headers: [{ key: "Cache-Control", value: "no-cache, no-store, must-revalidate" }] },
    ];
  },
};

// FEF-036: next-intl without URL segments; request config in i18n/request.ts.
export default createNextIntlPlugin("./i18n/request.ts")(nextConfig);
