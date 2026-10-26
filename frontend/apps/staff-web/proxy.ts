// FEF-010: fresh nonce + CSP on every HTML response. Static headers (HSTS, nosniff, ...) are in next.config.ts so they cover assets too.
import { NextResponse, type NextRequest } from "next/server";
import { CLINIC_LOCALE_COOKIE, LOCALE_COOKIE, LOCALE_HEADER, resolveLocale } from "@nabd/i18n";
import { apiOrigin, buildCsp, cspHeaderName, newNonce } from "./app/security/csp";

export function proxy(request: NextRequest) {
  const nonce = newNonce();
  const csp = buildCsp(nonce, { dev: process.env.NODE_ENV === "development", connect: apiOrigin(process.env.NEXT_PUBLIC_API_BASE_URL) });
  const name = cspHeaderName(process.env.NABD_CSP_MODE);
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  // FEF-036: user preference (cookie mirrored by the BFF) -> clinic default -> Accept-Language -> en. Pseudo modes are test-only.
  const userPref = request.cookies.get(LOCALE_COOKIE)?.value;
  const pseudo = process.env.NABD_PSEUDO === "1" && (userPref === "pseudo" || userPref === "pseudo-rtl") ? userPref : null;
  requestHeaders.set(LOCALE_HEADER, pseudo ?? resolveLocale({ user: userPref, clinic: request.cookies.get(CLINIC_LOCALE_COOKIE)?.value, acceptLanguage: request.headers.get("accept-language") }));
  requestHeaders.set(name, csp); // Next reads the nonce from this request header and stamps its own scripts.
  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set(name, csp);
  return response;
}

export const config = {
  matcher: [
    {
      source: "/((?!api|_next/static|_next/image|favicon.ico).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
