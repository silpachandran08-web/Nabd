// FEF-061: per-request nonce CSP, no-store, and the page language. Locale order: ?lang= from the link, Accept-Language, English.
import { NextResponse, type NextRequest } from "next/server";
import { LOCALE_HEADER, isLocale, matchAcceptLanguage, DEFAULT_LOCALE } from "@nabd/i18n";
import { NO_STORE, apiOrigin, buildCsp, cspHeaderName, newNonce } from "./app/lib/security";

export const THEME_HEADER = "x-nabd-theme";

export function proxy(request: NextRequest) {
  const nonce = newNonce();
  const csp = buildCsp(nonce, { dev: process.env.NODE_ENV === "development", connect: apiOrigin(process.env.NEXT_PUBLIC_PATIENT_API_BASE_URL) });
  const name = cspHeaderName(process.env.NABD_CSP_MODE);
  const lang = request.nextUrl.searchParams.get("lang");
  const locale = isLocale(lang) ? lang : (matchAcceptLanguage(request.headers.get("accept-language")) ?? DEFAULT_LOCALE);
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set(LOCALE_HEADER, locale);
  requestHeaders.set(THEME_HEADER, request.nextUrl.searchParams.get("theme") === "dark" ? "dark" : "light"); // light by default, dark on request
  requestHeaders.set(name, csp); // Next reads the nonce from this request header and stamps its own scripts.
  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set(name, csp);
  response.headers.set("Cache-Control", NO_STORE);
  return response;
}

export const config = {
  matcher: [
    {
      source: "/((?!_next/static|_next/image|favicon.ico).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
