"use client";
// FEF-009: cookie-mode fetch routing must be installed before any screen (login included) calls Core. The side-effect import used to ride on AppShell; the route groups made the shell clinic-only.
import "./lib/bffShim";

export default function BffBoot() {
  return null;
}
