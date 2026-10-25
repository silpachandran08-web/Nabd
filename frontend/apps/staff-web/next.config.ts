import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";
import { STATIC_HEADERS } from "./app/security/csp";

const nextConfig: NextConfig = {
  // FEF-010: on every response, assets included; the nonce CSP itself is per request in proxy.ts.
  async headers() {
    return [{ source: "/:path*", headers: Object.entries(STATIC_HEADERS).map(([key, value]) => ({ key, value })) }];
  },
};

// FEF-036: next-intl without URL segments; request config in i18n/request.ts.
export default createNextIntlPlugin("./i18n/request.ts")(nextConfig);
