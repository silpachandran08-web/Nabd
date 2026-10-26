import type { NextConfig } from "next";
import { NO_STORE, STATIC_HEADERS } from "./app/lib/security";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  async headers() {
    return [
      { source: "/:path*", headers: Object.entries(STATIC_HEADERS).map(([key, value]) => ({ key, value })) },
      // Every page and API response is no-store. Hashed build assets (/_next/static, /_next/image) are not PHI and keep Next's immutable caching: on 3G a repeat visit must not re-download the JS.
      { source: "/((?!_next/static|_next/image).*)", headers: [{ key: "Cache-Control", value: NO_STORE }] },
    ];
  },
};

export default nextConfig;
