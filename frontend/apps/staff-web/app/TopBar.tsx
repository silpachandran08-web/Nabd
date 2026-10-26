"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import styles from "./topBar.module.css";
import { getPermissions, isOwner, SHELL_SKIP_PREFIXES } from "./lib/session";
import { can } from "./lib/permissions";
import type { AppPermission } from "@nabd/api-client/types";
import { useLiveStatus } from "./lib/live";
import { useTranslations } from "next-intl";
import LanguagePicker from "./LanguagePicker";
import { formatClinicTime } from "./lib/clinicTime";
import { API_BASE, FAIL_LIMIT, PROBE_MS, nextConn, watchApiFetch } from "./lib/connection";

const SEARCH_INPUT_ID = "nb-global-search";

// Pathname -> the top bar's primary action button, DESIGN.md's Vitals Worklist ("Capture
// vitals" next to the connection pill). Navigation-based (a query param the target page reacts to) rather
// than a cross-component callback registry — the only consumer so far is nursing's own capture-
// vitals picker, and a URL round trip is simpler and less error-prone than wiring a context
// just for this one case. Add more pathnames here if other pages want the same treatment.
// The Receptionist's Queue & Appointments board carries "Register walk-in", which opens the
// Arrivals page's own walk-in dialog (?action=walkin) — only for those who can check in.
// permission: every listed grant is required; "a|b" means either. Capture vitals records first vitals
// (clinical:create or clinical:edit, REC-060) for patients picked from the queue (queue:view); without the
// queue the picker could only claim nobody is waiting. The walk-in dialog starts with a patient search
// (GET /patients, patients:view) before check-in (queue:create), so both are needed (LIVE-04).
type Grant = AppPermission | `${AppPermission}|${AppPermission}`;
const PRIMARY_ACTIONS: Record<string, { label: string; href: string; permission?: Grant | Grant[] }> = {
  "/nursing": { label: "captureVitals", href: "/nursing?action=capture", permission: ["clinical:create|clinical:edit", "queue:view"] },
  "/schedule": { label: "registerWalkIn", href: "/arrivals?action=walkin", permission: ["queue:create", "patients:view"] },
};

// DESIGN.md's top bar, present on every clinic page: sidebar collapse toggle, global patient
// search, the current page's primary action, and a connection indicator. Left out: the
// wireframe's language switcher and notification bell — no i18n or notifications system exists
// anywhere in this app, and a control that does nothing isn't a real feature. The pill reports
// what was observed (browser online/offline events, real API failures, slow answers, last good
// answer — WB-FEF-021). There is no offline write queue, so it never claims queued or synced saves.
export default function TopBar({ collapsed, onToggleCollapse }: { collapsed: boolean; onToggleCollapse: () => void }) {
  const t = useTranslations("shell");
  const pathname = usePathname();
  const router = useRouter();
  const skip = SHELL_SKIP_PREFIXES.some((p) => pathname?.startsWith(p));
  const [hasSession, setHasSession] = useState(false);
  const [query, setQuery] = useState("");
  const [online, setOnline] = useState(true);
  const [conn, setConn] = useState<{ fails: number; slow: boolean; lastOkAt: number | null }>({ fails: 0, slow: false, lastOkAt: null });

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setHasSession(!skip && !!localStorage.getItem("nabd_access_token"));
  }, [pathname, skip]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setOnline(navigator.onLine);
    const goOnline = () => { setOnline(true); fetch(`${API_BASE}/health`).catch(() => {}); };
    const goOffline = () => setOnline(false);
    window.addEventListener("online", goOnline);
    window.addEventListener("offline", goOffline);
    return () => {
      window.removeEventListener("online", goOnline);
      window.removeEventListener("offline", goOffline);
    };
  }, []);

  useEffect(() => watchApiFetch((e) => setConn((s) => nextConn(s, e))), []);

  const live = useLiveStatus();
  const degraded = conn.fails >= FAIL_LIMIT;
  useEffect(() => {
    // While degraded nothing else may call the API, so probe; any non-5xx answer (even 404) proves it is reachable.
    // shortcut: no health endpoint exists today, so the probe GETs /health; switch to the real one when added.
    if (!degraded || !online) return;
    const probe = () => { fetch(`${API_BASE}/health`).catch(() => {}); };
    const t = setInterval(probe, PROBE_MS);
    return () => clearInterval(t);
  }, [degraded, online]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      const target = e.target as HTMLElement | null;
      const typing = target?.tagName === "INPUT" || target?.tagName === "TEXTAREA" || target?.isContentEditable;
      const isShortcut = (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k";
      const isSlash = e.key === "/" && !typing;
      if ((isShortcut || isSlash) && can("patients:view")) {
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

  // The owner's primary action on every owner page is "Start day close" (wireframe PRIMARY.owner);
  // /billing already carries its own button, and closing the day needs billing:approve.
  const ownerAction: { label: string; href: string; permission?: Grant } | undefined = isOwner() && pathname !== "/billing" && getPermissions().includes("billing:approve")
    ? { label: "startDayClose", href: "/billing?action=close" }
    : undefined;
  // The owner's day-close action wins over a page-level shortcut (wireframe PRIMARY.owner).
  const action = ownerAction ?? (pathname ? PRIMARY_ACTIONS[pathname] : undefined);
  const primaryAction = action && [action.permission ?? []].flat().every((p) => p.split("|").some((g) => can(g as AppPermission))) ? action : undefined; // Grant typed each part

  // Patient search lands on /patients, so a role without patients:view gets no search box at all.
  const canSearch = can("patients:view");
  const lastSync = conn.lastOkAt ? formatClinicTime(conn.lastOkAt) : null;
  const lastSyncText = lastSync ? t("lastSync", { time: lastSync }) : t("noServerContact");
  const [pillLabel, pillWarn, pillHint] = !online
    ? [t("offline"), true, t("offlineHint")]
    : degraded
    ? [t("reconnecting"), true, t("degradedHint")]
    : live === "reconnecting"
    ? [t("reconnecting"), true, t("liveReconnectingHint")]
    : conn.slow
    ? [t("slow"), true, t("slowHint")]
    : live === "live"
    ? [t("live"), false, t("liveHint")]
    : [t("online"), false, ""];

  return (
    <div className={styles.bar}>
      <button type="button" className={styles.iconBtn} onClick={onToggleCollapse}
        aria-label={collapsed ? t("showSidebar") : t("hideSidebar")} title={collapsed ? t("showSidebar") : t("hideSidebar")}>
        ☰
      </button>
      {canSearch && <>
        <form className={styles.search} onSubmit={submitSearch}>
          <span className={styles.searchIcon} aria-hidden="true">⌕</span>
          <input
            id={SEARCH_INPUT_ID}
            className={styles.searchInput}
            placeholder={t("searchPatient")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <span className={styles.kbd}>/</span>
        </form>
        <span className={styles.kbd}>⌘K</span>
      </>}
      <div className={styles.spacer} />
      <LanguagePicker />
      {primaryAction && (
        <button type="button" className={styles.primaryAction} onClick={() => router.push(primaryAction.href)}>
          {t(primaryAction.label)}
        </button>
      )}
      <div role="status" className={`${styles.syncPill} ${pillWarn ? styles.syncOffline : styles.syncOnline}`}
        title={[pillHint, lastSyncText].filter(Boolean).join(" ")}>
        <span className={styles.syncDot} />
        {pillLabel}
        <span className={styles.syncTime}>{!pillWarn && lastSync ? `· ${lastSync}` : ""}</span>
        <span className={styles.srOnly}>{[pillHint, lastSyncText].filter(Boolean).join(" ")}</span>
      </div>
    </div>
  );
}
