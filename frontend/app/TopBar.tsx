"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import styles from "./topBar.module.css";
import { SHELL_SKIP_PREFIXES } from "./lib/session";

const SEARCH_INPUT_ID = "nb-global-search";

// DESIGN.md's top bar, present on every clinic page: sidebar collapse toggle, global patient
// search, and a sync/connectivity indicator. Left out: the wireframe's language switcher and
// notification bell (no i18n or notifications system exists anywhere in this app — a toggle or
// bell that does nothing isn't a real feature) and its per-page primary action button (Register
// walk-in / Capture vitals / ...) — each page already has its own, in its own header; putting it
// in both places would just be a second, redundant button. "Synced"/"Offline" uses the browser's
// real online/offline events — the app doesn't have — or need — any actual sync queue, so this
// is a connectivity indicator, not a claim about queued writes.
export default function TopBar({ collapsed, onToggleCollapse }: { collapsed: boolean; onToggleCollapse: () => void }) {
  const pathname = usePathname();
  const router = useRouter();
  const skip = SHELL_SKIP_PREFIXES.some((p) => pathname?.startsWith(p));
  const [hasSession, setHasSession] = useState(false);
  const [query, setQuery] = useState("");
  const [online, setOnline] = useState(true);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setHasSession(!skip && !!localStorage.getItem("nabd_access_token"));
  }, [pathname, skip]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setOnline(navigator.onLine);
    const goOnline = () => setOnline(true);
    const goOffline = () => setOnline(false);
    window.addEventListener("online", goOnline);
    window.addEventListener("offline", goOffline);
    return () => {
      window.removeEventListener("online", goOnline);
      window.removeEventListener("offline", goOffline);
    };
  }, []);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      const target = e.target as HTMLElement | null;
      const typing = target?.tagName === "INPUT" || target?.tagName === "TEXTAREA" || target?.isContentEditable;
      const isShortcut = (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k";
      const isSlash = e.key === "/" && !typing;
      if (isShortcut || isSlash) {
        e.preventDefault();
        document.getElementById(SEARCH_INPUT_ID)?.focus();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  if (!hasSession) return null;

  function submitSearch(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = query.trim();
    if (trimmed) router.push(`/patients?q=${encodeURIComponent(trimmed)}`);
  }

  return (
    <div className={styles.bar}>
      <button type="button" className={styles.iconBtn} onClick={onToggleCollapse}
        aria-label={collapsed ? "Show sidebar" : "Hide sidebar"} title={collapsed ? "Show sidebar" : "Hide sidebar"}>
        ☰
      </button>
      <form className={styles.search} onSubmit={submitSearch}>
        <span className={styles.searchIcon} aria-hidden="true">⌕</span>
        <input
          id={SEARCH_INPUT_ID}
          className={styles.searchInput}
          placeholder="Search patient by name or phone"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <span className={styles.kbd}>/</span>
      </form>
      <span className={styles.kbd}>⌘K</span>
      <div className={styles.spacer} />
      <div className={`${styles.syncPill} ${online ? styles.syncOnline : styles.syncOffline}`}>
        <span className={styles.syncDot} />
        {online ? "Synced" : "Offline"}
      </div>
    </div>
  );
}
