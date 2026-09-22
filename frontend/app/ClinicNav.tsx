"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import styles from "./clinicNav.module.css";
import { getIdentity, getPermissions, getSessionExpiresAt, signOut, SHELL_SKIP_PREFIXES, type Identity } from "./lib/session";

// DESIGN.md §4/§8: 248px left sidebar, one item per accessible module. Every clinic page
// (arrivals, patients, ...) was built standalone with no shared shell — this is that missing
// nav, mounted once in layout.tsx (same pattern as /platform/PlatformNav.tsx), permission-gated
// off the JWT's own "permissions" claim so each role only sees what it can open.
const NAV_ITEMS: { href: string; label: string; permission: string | null }[] = [
  { href: "/arrivals", label: "Today · Arrivals", permission: "queue:view" },
  { href: "/patients", label: "Patients", permission: "patients:view" },
  { href: "/consult", label: "Consultation Workspace", permission: "clinical:view" },
  { href: "/packages", label: "Treatment Packages", permission: "packages:view" },
  { href: "/staff", label: "Staff & Access", permission: "staff:view" },
  { href: "/reports", label: "Owner Insights", permission: "reports:view" },
  { href: "/setup", label: "Setup", permission: "setup:view" },
  { href: "/account", label: "My account", permission: null },
];

// DESIGN.md's Nurse sidebar, exactly: Today, Waiting Patients, Vitals, Vaccines & Injections,
// Administration History (its own "More" is left out — nothing to overflow into here). The real
// nursing page is a single route with tabs/filters, so each deep-links via query params instead
// of being a separate page. Vaccines & Injections has no backend at all (filed separately) — it
// still links through, landing on the page's own honest "not available yet" state rather than
// being omitted, since the wireframe names it as a destination.
const NURSING_ITEMS: { label: string; tab?: string; filter?: string }[] = [
  { label: "Today", filter: "due" },
  { label: "Waiting Patients", filter: "all" },
  { label: "Vitals", filter: "recorded" },
  { label: "Vaccines & Injections", tab: "vaccines" },
  { label: "Administration History", tab: "administration" },
];

function nursingHref(item: { tab?: string; filter?: string }): string {
  const params = new URLSearchParams();
  if (item.tab) params.set("tab", item.tab);
  if (item.filter) params.set("filter", item.filter);
  const qs = params.toString();
  return qs ? `/nursing?${qs}` : "/nursing";
}

function isNursingItemActive(item: { tab?: string; filter?: string }, pathname: string | null, searchParams: URLSearchParams): boolean {
  if (pathname !== "/nursing") return false;
  const currentTab = searchParams.get("tab") ?? "vitals";
  if ((item.tab ?? "vitals") !== currentTab) return false;
  if (currentTab === "vitals" && (item.filter ?? "due") !== (searchParams.get("filter") ?? "due")) return false;
  return true;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts[1]?.[0] ?? "")).toUpperCase();
}

function formatExpiry(expiresAt: Date | null): string {
  if (!expiresAt) return "—";
  const ms = expiresAt.getTime() - Date.now();
  if (ms <= 0) return "expired";
  const totalMinutes = Math.floor(ms / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return hours > 0 ? `expires in ${hours}h ${minutes}m` : `expires in ${minutes}m`;
}

export default function ClinicNav({ collapsed }: { collapsed: boolean }) {
  const pathname = usePathname();
  const router = useRouter();
  const searchParams = useSearchParams();
  const skip = SHELL_SKIP_PREFIXES.some((p) => pathname?.startsWith(p));
  const [permissions, setPermissions] = useState<string[] | null>(null);
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [showMenu, setShowMenu] = useState(false);
  const [sessionLabel, setSessionLabel] = useState("—");
  const [signingOut, setSigningOut] = useState(false);

  // Hydration-safe: render nothing until this mounts and can read localStorage, then reflect
  // it — never read it directly during render.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPermissions(skip ? [] : getPermissions());
    setIdentity(skip ? null : getIdentity());
  }, [pathname, skip]);

  if (skip || !permissions || permissions.length === 0) return null;

  // A Nurse's sidebar is DESIGN.md's own dedicated worklist nav, never the generic module list —
  // a staff record that also carries queue:view/patients:view (this local test account does) must
  // not leak those in here, or the sidebar mixes two different roles' navigation into one.
  const showNursing = permissions.includes("nursing:view");
  const items = showNursing ? [] : NAV_ITEMS.filter((item) => !item.permission || permissions.includes(item.permission));
  if (items.length === 0 && !showNursing) return null;

  if (collapsed) return <nav className={styles.navCollapsed} aria-hidden="true" />;

  function openMenu() {
    setSessionLabel(formatExpiry(getSessionExpiresAt()));
    setShowMenu(true);
  }

  async function handleSignOut() {
    setSigningOut(true);
    await signOut();
    router.replace("/login");
  }

  return (
    <nav className={styles.nav}>
      <div className={styles.brand}>nabd</div>

      {identity && (
        <div className={styles.clinicInfo}>
          <div className={styles.clinicIcon}>🏥</div>
          <div className={styles.clinicText}>
            <div className={styles.clinicName}>{identity.tenantName}</div>
            {identity.tenantRegion && <div className={styles.clinicRegion}>{identity.tenantRegion}</div>}
          </div>
        </div>
      )}

      <div className={styles.links}>
        {showNursing && NURSING_ITEMS.map((item) => (
          <Link
            key={item.label}
            href={nursingHref(item)}
            className={`${styles.link} ${isNursingItemActive(item, pathname, searchParams) ? styles.linkActive : ""}`}
          >
            {item.label}
          </Link>
        ))}
        {items.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={`${styles.link} ${pathname?.startsWith(item.href) ? styles.linkActive : ""}`}
          >
            {item.label}
          </Link>
        ))}
      </div>

      {identity && (
        <div className={styles.identityWrap}>
          {showMenu && (
            <>
              <div className={styles.menuBackdrop} onClick={() => setShowMenu(false)} />
              <div className={styles.identityMenu}>
                <div className={styles.menuHeader}>Signed in</div>
                <div className={styles.menuIdentityRow}>
                  <div className={styles.identityAvatar}>{initials(identity.staffName)}</div>
                  <div>
                    <div className={styles.identityName}>{identity.staffName}</div>
                    <div className={styles.identityRole}>{identity.tenantName}</div>
                  </div>
                </div>
                <div className={styles.menuRow}>
                  <span>Active role</span>
                  <span className={styles.menuRowValue}>{identity.roleName}</span>
                </div>
                <div className={styles.menuRow}>
                  <span>Session</span>
                  <span className={styles.menuRowValue}>{sessionLabel}</span>
                </div>
                <button type="button" className={styles.menuSignOut} onClick={handleSignOut} disabled={signingOut}>
                  {signingOut ? "Signing out…" : "Sign out"}
                </button>
              </div>
            </>
          )}
          <button type="button" className={styles.identityFooter} onClick={openMenu}>
            <div className={styles.identityAvatar}>{initials(identity.staffName)}</div>
            <div className={styles.identityText}>
              <div className={styles.identityName}>{identity.staffName}</div>
              <div className={styles.identityRole}>{identity.roleName}</div>
            </div>
          </button>
        </div>
      )}
    </nav>
  );
}
