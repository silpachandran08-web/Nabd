"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname, useRouter } from "next/navigation";

// NB-044: shared clinic workstations auto-lock after inactivity. This is an overlay on top of
// whatever page is mounted, not a navigation — nothing in the page underneath unmounts, so a
// doctor's in-progress consultation note (or any other draft state) is exactly as it was when
// the lock clears. Unlocking re-checks the PIN only; the existing session/access token is
// untouched, so there's no new login and nothing else to restore.
const IDLE_TIMEOUT_MS = 5 * 60 * 1000;
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const SKIP_PREFIXES = ["/login", "/platform", "/owner"];

export default function IdleLockGuard() {
  const pathname = usePathname();
  const router = useRouter();
  const [locked, setLocked] = useState(false);
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const skip = SKIP_PREFIXES.some((p) => pathname?.startsWith(p));

  useEffect(() => {
    if (skip) return;

    function resetTimer() {
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => {
        if (localStorage.getItem("nabd_access_token")) setLocked(true);
      }, IDLE_TIMEOUT_MS);
    }

    const events = ["mousedown", "keydown", "touchstart", "scroll"];
    events.forEach((e) => window.addEventListener(e, resetTimer));
    resetTimer();
    return () => {
      events.forEach((e) => window.removeEventListener(e, resetTimer));
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [skip]);

  async function unlock(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setChecking(true);
    try {
      const token = localStorage.getItem("nabd_access_token");
      const res = await fetch(`${API_BASE}/auth/unlock`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ pin }),
      });
      if (!res.ok) {
        setError("Incorrect PIN.");
        return;
      }
      setLocked(false);
      setPin("");
    } catch {
      setError("Couldn't reach the server.");
    } finally {
      setChecking(false);
    }
  }

  // The lock overlay covers the whole viewport (z-index 9999) above LogoutButton (z-index 100),
  // so without this, a locked session with a forgotten PIN has no way out at all.
  async function signOut() {
    setSigningOut(true);
    const token = localStorage.getItem("nabd_access_token");
    if (token) {
      await fetch(`${API_BASE}/auth/logout`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      }).catch(() => {});
    }
    localStorage.removeItem("nabd_access_token");
    localStorage.removeItem("nabd_refresh_token");
    setLocked(false);
    setPin("");
    router.replace("/login");
  }

  if (skip || !locked) return null;

  return (
    <div style={{
      position: "fixed", inset: 0, background: "rgba(10,12,14,0.97)", zIndex: 9999,
      display: "flex", alignItems: "center", justifyContent: "center",
    }}>
      <form onSubmit={unlock} style={{
        width: 320, background: "var(--nb-surface-1)", border: "1px solid var(--nb-border-default)",
        borderRadius: "var(--nb-radius-lg)", padding: 24,
      }}>
        <h2 style={{ fontSize: 16, fontWeight: 600, margin: "0 0 8px", color: "var(--nb-text-primary)" }}>Session locked</h2>
        <p style={{ fontSize: 13, color: "var(--nb-text-secondary)", margin: "0 0 16px" }}>Enter your PIN to continue where you left off.</p>
        {error && <div style={{ background: "var(--nb-danger-tint)", color: "var(--nb-danger-500)", borderRadius: 8, padding: "8px 12px", fontSize: 13, marginBottom: 12 }}>{error}</div>}
        <input
          type="password" inputMode="numeric" pattern="[0-9]{4,6}" maxLength={6} autoFocus required
          value={pin} onChange={(ev) => setPin(ev.target.value.replace(/\D/g, ""))}
          style={{
            width: "100%", height: 40, background: "var(--nb-surface-3)", border: "1px solid var(--nb-border-default)",
            borderRadius: 8, padding: "0 12px", color: "var(--nb-text-primary)", fontSize: 14, marginBottom: 12, boxSizing: "border-box",
          }}
        />
        <button type="submit" disabled={checking || pin.length < 4} style={{
          width: "100%", height: 40, border: "none", borderRadius: 8, background: "var(--nb-accent-gradient)",
          color: "var(--nb-on-accent)", fontWeight: 600, fontSize: 13, cursor: "pointer",
        }}>
          {checking ? "Checking…" : "Unlock"}
        </button>
        <button type="button" onClick={signOut} disabled={signingOut} style={{
          width: "100%", height: 36, marginTop: 8, border: "none", background: "transparent",
          color: "var(--nb-text-secondary)", fontSize: 13, cursor: "pointer", textDecoration: "underline",
        }}>
          {signingOut ? "Signing out…" : "Not you? Sign out"}
        </button>
      </form>
    </div>
  );
}
