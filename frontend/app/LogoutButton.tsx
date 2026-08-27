"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";

// Wireframe (Nabd Shell) puts "Sign out" as the last item in the header's user popover, present on
// every screen — this app has no shared shell/header wrapping every clinic page yet, so rather than
// hand-adding a button to each page's own bespoke header (and risk missing one), this mounts once
// globally in layout.tsx, same pattern as IdleLockGuard, and floats itself into a fixed corner.
const SKIP_PREFIXES = ["/login", "/platform", "/accept-invite"];
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function LogoutButton() {
  const pathname = usePathname();
  const router = useRouter();
  const [loggingOut, setLoggingOut] = useState(false);
  const [hasSession, setHasSession] = useState(false);
  const skip = SKIP_PREFIXES.some((p) => pathname?.startsWith(p));

  // Re-read on every navigation, not just mount — login/logout/idle-lock elsewhere in the app
  // always pair a token change with a route change (never a same-page silent clear), so this
  // stays in sync without needing a storage-event listener. Rendering null on the server (no
  // window) then flipping post-mount is the standard hydration-safe way to reflect browser-only
  // state — the alternative (reading localStorage directly during render) would mismatch SSR output.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setHasSession(!!localStorage.getItem("nabd_access_token"));
  }, [pathname]);

  if (skip || !hasSession) return null;

  async function logout() {
    setLoggingOut(true);
    const token = localStorage.getItem("nabd_access_token");
    if (token) {
      await fetch(`${API_BASE}/auth/logout`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      }).catch(() => {});
    }
    localStorage.removeItem("nabd_access_token");
    localStorage.removeItem("nabd_refresh_token");
    router.replace("/login");
  }

  return (
    <button
      type="button"
      onClick={logout}
      disabled={loggingOut}
      style={{
        // Bottom-right, not top-right: several pages (Arrivals, Staff & Access, ...) already put
        // their own action buttons in the top-right of their in-flow header, and a fixed element
        // there would sit visually on top of them. Nothing in this app currently docks anything
        // in the bottom-right corner.
        position: "fixed", bottom: 16, right: 16, zIndex: 100,
        height: 36, padding: "0 16px", border: "1px solid var(--nb-border-default)",
        borderRadius: "var(--nb-radius-md)", background: "var(--nb-surface-2)", color: "var(--nb-text-primary)",
        boxShadow: "0 2px 8px rgba(0,0,0,0.24)",
        fontFamily: "inherit", fontSize: 13, fontWeight: 500, cursor: "pointer",
      }}
    >
      {loggingOut ? "Signing out…" : "Sign out"}
    </button>
  );
}
