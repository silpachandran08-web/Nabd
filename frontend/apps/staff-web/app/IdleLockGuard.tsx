"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { lockedMessage } from "./lib/pinPolicy";
import { refreshSession, unlockMessage } from "./lib/apiClient";
import { signOut as endSession } from "./lib/session";
import { clampIdleMinutes, idlePhase, IDLE_POLICY_KEY, onLockMessage, persistLock, persistUnlock, readLockedAt } from "./lib/idleLock";

// NB-044 / FEF-005: shared clinic workstations auto-lock after inactivity (clinic setting
// session.idle_lock_minutes, default 5). The lock is persisted (reload and new tabs start locked) and
// broadcast to other tabs. The page tree is never unmounted by a lock, so an in-progress note survives,
// but it is `inert` while locked; a tab that OPENS locked does not render the page at all.
// Unlocking re-checks the PIN only; the existing session/access token is untouched.
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function IdleLockGuard({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [booted, setBooted] = useState(false);
  const [released, setReleased] = useState(false); // false = opened locked: render no page until unlocked
  const [locked, setLocked] = useState(false);
  const [warn, setWarn] = useState<number | null>(null);
  const [timeoutMs, setTimeoutMs] = useState(clampIdleMinutes(null) * 60_000);
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const lastRef = useRef(0);
  const lockedRef = useRef(false);
  const dialogRef = useRef<HTMLFormElement>(null);

  const hasToken = () => !!localStorage.getItem("nabd_access_token");

  function applyLock(on: boolean) {
    lockedRef.current = on;
    setLocked(on);
    if (!on) { setReleased(true); setWarn(null); lastRef.current = Date.now(); }
  }

  useEffect(() => {
    // A lock left behind by a session that no longer has a token must not trap the next login.
    const on = hasToken() && readLockedAt() !== null;
    if (!on && readLockedAt() !== null) persistUnlock();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    applyLock(on); setBooted(true);
    return onLockMessage((l) => { if (!l || hasToken()) applyLock(l); });
  }, []);

  // Clinic idle timeout (setup:view only; anyone who cannot read it keeps the 5 min default).
  useEffect(() => {
    if (!hasToken()) return;
    fetch(`${API_BASE}/setup/policies`, { headers: { Authorization: `Bearer ${localStorage.getItem("nabd_access_token")}` } })
      .then((r) => (r.ok ? r.json() : []))
      .then((rows: { policyKey: string; value: string }[]) => {
        const row = Array.isArray(rows) ? rows.find((x) => x.policyKey === IDLE_POLICY_KEY) : undefined;
        setTimeoutMs(clampIdleMinutes(row?.value) * 60_000);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    lastRef.current = Date.now();
    const bump = () => { if (!lockedRef.current) { lastRef.current = Date.now(); setWarn(null); } };
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "l" && hasToken()) {
        e.preventDefault();
        if (!lockedRef.current) { persistLock(); applyLock(true); }
        return;
      }
      bump();
    };
    const events = ["mousedown", "touchstart", "scroll"];
    events.forEach((e) => window.addEventListener(e, bump));
    window.addEventListener("keydown", onKey);
    const tick = setInterval(() => {
      if (lockedRef.current) return;
      const { phase, secondsLeft } = idlePhase(Date.now() - lastRef.current, timeoutMs);
      if (phase === "lock") {
        if (hasToken()) { persistLock(); applyLock(true); }
        else lastRef.current = Date.now();
      } else setWarn(phase === "warn" ? secondsLeft : null);
    }, 1000);
    return () => {
      events.forEach((e) => window.removeEventListener(e, bump));
      window.removeEventListener("keydown", onKey);
      clearInterval(tick);
    };
  }, [timeoutMs]);

  async function unlock(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setChecking(true);
    try {
      // FEF-002: the token may have expired during the lock. Refresh first, so a 401 from unlock then
      // means the PIN is wrong (the backend answers both with 401), not "session expired".
      if (!(await refreshSession(localStorage.getItem("nabd_access_token")))) {
        setError(unlockMessage(401, null, true));
        return;
      }
      const token = localStorage.getItem("nabd_access_token");
      const res = await fetch(`${API_BASE}/auth/unlock`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ pin }),
      });
      if (res.status === 423) { // locked out: say so, distinct from a wrong PIN
        const problem = await res.json().catch(() => ({}));
        setError(lockedMessage(problem?.retryAfterSeconds));
        return;
      }
      if (!res.ok) {
        setError(unlockMessage(res.status, res.headers.get("Retry-After"), false));
        return;
      }
      persistUnlock();
      applyLock(false);
      setPin("");
    } catch {
      setError("Couldn't reach the server.");
    } finally {
      setChecking(false);
    }
  }

  // The lock overlay covers the whole viewport (z-index 9999), above every page's own modals,
  // so without this, a locked session with a forgotten PIN has no way out at all.
  async function signOut() {
    setSigningOut(true);
    await endSession(); // logout call, tokens, lock flag and the drafts database
    persistUnlock();
    applyLock(false);
    setPin("");
    router.replace("/login");
  }

  // Tab traps inside the dialog; the page behind is inert as well.
  function trapTab(e: React.KeyboardEvent) {
    if (e.key !== "Tab") return;
    const els = dialogRef.current?.querySelectorAll<HTMLElement>("input, button:not([disabled])");
    if (!els?.length) return;
    const first = els[0], last = els[els.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }

  return (
    <>
      <div id="app-root" style={{ display: "contents" }} inert={locked}>{booted && (released || !locked) ? children : null}</div>
      {warn !== null && !locked && (
        <div role="alert" style={{
          position: "fixed", insetBlockEnd: 16, insetInlineStart: 16, zIndex: 9998, maxInlineSize: 360,
          background: "var(--nb-surface-1)", color: "var(--nb-text-primary)", border: "1px solid var(--nb-border-default)",
          borderRadius: "var(--nb-radius-lg)", padding: "12px 16px", fontSize: 13,
        }}>
          Screen locks in {warn} s. Press any key to stay signed in.
        </div>
      )}
      {locked && (
        <div style={{
          position: "fixed", inset: 0, background: "rgba(10,12,14,0.97)", zIndex: 9999,
          display: "flex", alignItems: "center", justifyContent: "center",
        }}>
          <form ref={dialogRef} onSubmit={unlock} onKeyDown={trapTab} role="dialog" aria-modal="true" aria-labelledby="lock-title" style={{
            inlineSize: 320, maxInlineSize: "calc(100vw - 32px)", background: "var(--nb-surface-1)", border: "1px solid var(--nb-border-default)",
            borderRadius: "var(--nb-radius-lg)", padding: 24,
          }}>
            <h2 id="lock-title" style={{ fontSize: 16, fontWeight: 600, margin: "0 0 8px", color: "var(--nb-text-primary)" }}>Screen locked</h2>
            <p style={{ fontSize: 13, color: "var(--nb-text-secondary)", margin: "0 0 16px" }}>Enter your PIN to continue where you left off.</p>
            {error && <div role="alert" aria-live="assertive" style={{ background: "var(--nb-danger-tint)", color: "var(--nb-danger-500)", borderRadius: 8, padding: "8px 12px", fontSize: 13, marginBlockEnd: 12 }}>{error}</div>}
            <label htmlFor="lock-pin" style={{ display: "block", fontSize: 13, color: "var(--nb-text-secondary)", marginBlockEnd: 4 }}>PIN</label>
            <input
              id="lock-pin" type="password" inputMode="numeric" pattern="[0-9]{4,8}" maxLength={8} autoFocus required autoComplete="off"
              value={pin} onChange={(ev) => setPin(ev.target.value.replace(/\D/g, ""))}
              style={{
                inlineSize: "100%", blockSize: 44, background: "var(--nb-surface-3)", border: "1px solid var(--nb-border-default)",
                borderRadius: 8, paddingInline: 12, color: "var(--nb-text-primary)", fontSize: 14, marginBlockEnd: 12, boxSizing: "border-box",
              }}
            />
            <button type="submit" disabled={checking || pin.length < 4} style={{
              inlineSize: "100%", blockSize: 44, border: "none", borderRadius: 8, background: "var(--nb-accent-gradient)",
              color: "var(--nb-on-accent)", fontWeight: 600, fontSize: 13, cursor: "pointer",
            }}>
              {checking ? "Checking…" : "Unlock"}
            </button>
            <button type="button" onClick={signOut} disabled={signingOut} style={{
              inlineSize: "100%", blockSize: 44, marginBlockStart: 8, border: "none", background: "transparent",
              color: "var(--nb-text-secondary)", fontSize: 13, cursor: "pointer", textDecoration: "underline",
            }}>
              {signingOut ? "Signing out…" : "Not you? Sign out"}
            </button>
          </form>
        </div>
      )}
    </>
  );
}
