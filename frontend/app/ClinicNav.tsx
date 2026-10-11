"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import styles from "./clinicNav.module.css";
import Icon, { type IconName } from "./components/ui/Icon";
import { getIdentity, getPermissions, getSessionExpiresAt, isOwner, signOut, SHELL_SKIP_PREFIXES, type Identity } from "./lib/session";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
// Same "waiting" bucket as the Arrivals page's own tab counts (arrivals/page.tsx bucketOf).
const WAITING_STATUSES = ["checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done"];

// DESIGN.md §4/§8: 248px left sidebar, one item per accessible module. Every clinic page
// (arrivals, patients, ...) was built standalone with no shared shell — this is that missing
// nav, mounted once in layout.tsx (same pattern as /platform/PlatformNav.tsx), permission-gated
// off the JWT's own "permissions" claim so each role only sees what it can open.
// Reception's items follow DESIGN.md's Receptionist sidebar (Today / Arrivals, Queue &
// Appointments, Patients, Checkout & Billing, Treatment Packages); its Messages item is left out —
// there's no inbox page to open yet.
const NAV_ITEMS: { href: string; label: string; short: string; icon: IconName; permission: string | null }[] = [
  { href: "/arrivals", label: "Today / Arrivals", short: "Today", icon: "home", permission: "queue:view" },
  { href: "/schedule", label: "Queue & Appointments", short: "Queue", icon: "calendar", permission: "queue:view" },
  { href: "/patients", label: "Patients", short: "Patients", icon: "users", permission: "patients:view" },
  // clinical:edit, not clinical:view — reception can be granted clinical:view (e.g. for billing
  // lookups) without being a clinician; see lib/session.ts's LANDING_RULES for the full reasoning.
  { href: "/consult", label: "Consultation Workspace", short: "Consult", icon: "stethoscope", permission: "clinical:edit" },
  { href: "/billing", label: "Checkout & Billing", short: "Billing", icon: "receipt", permission: "billing:view" },
  { href: "/packages", label: "Treatment Packages", short: "Packages", icon: "package", permission: "packages:view" },
  { href: "/staff", label: "Staff & Access", short: "Staff", icon: "shield", permission: "staff:view" },
  { href: "/reports", label: "Owner Insights", short: "Insights", icon: "chart", permission: "reports:view" },
  { href: "/setup", label: "Setup", short: "Setup", icon: "settings", permission: "setup:view" },
  { href: "/account", label: "My account", short: "Account", icon: "user", permission: null },
];

// DESIGN.md's Nurse sidebar, exactly: Today, Waiting Patients, Vitals, Vaccines & Injections,
// Administration History (its own "More" is left out — nothing to overflow into here). The real
// nursing page is a single route with tabs/filters, so each deep-links via query params instead
// of being a separate page. Vaccines & Injections has no backend at all (filed separately) — it
// still links through, landing on the page's own honest "not available yet" state rather than
// being omitted, since the wireframe names it as a destination.
const NURSING_ITEMS: { label: string; short: string; icon: IconName; tab?: string; filter?: string }[] = [
  { label: "Today", short: "Today", icon: "home", filter: "due" },
  { label: "Waiting Patients", short: "Waiting", icon: "clock", filter: "all" },
  { label: "Vitals", short: "Vitals", icon: "pulse", filter: "recorded" },
  { label: "Vaccines & Injections", short: "Vaccines", icon: "syringe", tab: "vaccines" },
  { label: "Administration History", short: "History", icon: "list", tab: "administration" },
];

// DESIGN.md's Owner / Clinic Manager sidebar, in its order. The owner holds every grant (so the
// nursing check below would otherwise win); each item still needs its permission, so a trimmed
// Owner role never shows a link it can't open. Clinic Operations covers Schedule and its Live
// Queue tab (Arrivals); Compliance & Audit and Plan & Modules have no page of their own yet, so
// they open Setup's Licences and Subscription tabs.
type OwnerItem = { label: string; short: string; icon: IconName; href: string; permission: string; active: (path: string, tab: string | null) => boolean };
const OWNER_ITEMS: OwnerItem[] = [
  { label: "Overview", short: "Overview", icon: "home", href: "/overview", permission: "reports:view", active: (p) => p === "/overview" },
  { label: "Clinic Operations", short: "Clinic", icon: "calendar", href: "/schedule", permission: "queue:view",
    active: (p) => p === "/schedule" || p === "/arrivals" },
  { label: "Billing & Day Close", short: "Billing", icon: "receipt", href: "/billing", permission: "billing:view",
    active: (p) => p === "/billing" || p.startsWith("/checkout") },
  { label: "Treatment Packages", short: "Packages", icon: "package", href: "/packages", permission: "packages:view", active: (p) => p.startsWith("/packages") },
  { label: "Reports", short: "Reports", icon: "chart", href: "/reports", permission: "reports:view", active: (p) => p.startsWith("/reports") },
  { label: "Staff & Access", short: "Staff", icon: "shield", href: "/staff", permission: "staff:view", active: (p) => p.startsWith("/staff") },
  { label: "Clinic Setup", short: "Setup", icon: "settings", href: "/setup", permission: "setup:view",
    active: (p, tab) => p.startsWith("/setup") && tab !== "licences" && tab !== "subscription" },
  { label: "Compliance & Audit", short: "Audit", icon: "shield", href: "/setup?tab=licences", permission: "setup:view",
    active: (p, tab) => p === "/setup" && tab === "licences" },
  { label: "Plan & Modules", short: "Plan", icon: "list", href: "/setup?tab=subscription", permission: "setup:view",
    active: (p, tab) => p === "/setup" && tab === "subscription" },
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
  const [owner, setOwner] = useState(false);
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [showMenu, setShowMenu] = useState(false);
  const [sessionLabel, setSessionLabel] = useState("—");
  const [signingOut, setSigningOut] = useState(false);
  const [showMore, setShowMore] = useState(false);
  const [badges, setBadges] = useState<Record<string, number>>({});

  // Hydration-safe: render nothing until this mounts and can read localStorage, then reflect
  // it — never read it directly during render.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPermissions(skip ? [] : getPermissions());
    setIdentity(skip ? null : getIdentity());
    setOwner(skip ? false : isOwner());
  }, [pathname, skip]);

  // The Receptionist sidebar's count badges: today's waiting arrivals and visits sent to checkout,
  // both off today's queue (GET /queue). Refreshed on every navigation.
  // ponytail: no polling — a count can lag until the next page change; poll if reception asks.
  useEffect(() => {
    const perms = getPermissions();
    if (skip || isOwner() || perms.includes("nursing:view") || !perms.includes("queue:view")) return;
    const token = localStorage.getItem("nabd_access_token");
    if (!token) return;
    let cancelled = false;
    void fetch(`${API_BASE}/queue`, { headers: { Authorization: `Bearer ${token}` } })
      .then(async (res) => {
        if (cancelled || !res.ok) return;
        const rows: { status: string }[] = await res.json();
        if (cancelled) return;
        setBadges({
          "/arrivals": rows.filter((r) => WAITING_STATUSES.includes(r.status)).length,
          "/billing": rows.filter((r) => r.status === "checkout_pending").length,
        });
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [pathname, skip]);

  if (skip || !permissions || permissions.length === 0) return null;

  // A Nurse's sidebar is DESIGN.md's own dedicated worklist nav, never the generic module list —
  // a staff record that also carries queue:view/patients:view (this local test account does) must
  // not leak those in here, or the sidebar mixes two different roles' navigation into one.
  const ownerItems = owner ? OWNER_ITEMS.filter((item) => permissions.includes(item.permission)) : [];
  const showOwner = ownerItems.length > 0;
  const showNursing = !showOwner && permissions.includes("nursing:view");
  const items = showOwner || showNursing ? [] : NAV_ITEMS.filter((item) => !item.permission || permissions.includes(item.permission));
  if (items.length === 0 && !showNursing && !showOwner) return null;
  const currentTab = searchParams.get("tab");

  type Entry = { key: string; href: string; label: string; short: string; icon: IconName; active: boolean; badge?: number };
  const entries: Entry[] = [
    ...ownerItems.map((item) => ({
      key: item.label, href: item.href, label: item.label, short: item.short, icon: item.icon,
      active: !!pathname && item.active(pathname, currentTab),
    })),
    ...(showNursing ? NURSING_ITEMS.map((item) => ({
      key: item.label, href: nursingHref(item), label: item.label, short: item.short, icon: item.icon,
      active: isNursingItemActive(item, pathname, searchParams),
    })) : []),
    ...items.map((item) => ({
      key: item.href, href: item.href, label: item.label, short: item.short, icon: item.icon,
      active: !!pathname && (pathname.startsWith(item.href) || (item.href === "/billing" && pathname.startsWith("/checkout"))),
      badge: badges[item.href],
    })),
  ];
  // Mobile bottom nav: max 5 destinations (DESIGN.md §4) — 4 items + "More" when there are more.
  const primaryEntries = entries.length > 5 ? entries.slice(0, 4) : entries;
  const overflowEntries = entries.length > 5 ? entries.slice(4) : [];

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
    <>
    <nav className={`${styles.nav} ${collapsed ? styles.navRail : ""}`} aria-label="Main">
      <div className={styles.brand}>nabd</div>

      {identity && (
        <div className={styles.clinicInfo}>
          <div className={styles.clinicIcon}><Icon name="building" size={16} /></div>
          <div className={styles.clinicText}>
            <div className={styles.clinicName}>{identity.tenantName}</div>
            {identity.tenantRegion && <div className={styles.clinicRegion}>{identity.tenantRegion}</div>}
          </div>
        </div>
      )}

      <div className={styles.links}>
        {entries.map((e) => (
          <Link
            key={e.key}
            href={e.href}
            title={e.label}
            aria-current={e.active ? "page" : undefined}
            className={`${styles.link} ${e.active ? styles.linkActive : ""}`}
          >
            <Icon name={e.icon} />
            <span className={styles.linkLabel}>{e.label}</span>
            <span className={styles.linkShort} aria-hidden="true">{e.short}</span>
            {!!e.badge && <span className={styles.badge}>{e.badge}</span>}
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
    <nav className={styles.bottomNav} data-bottom-nav aria-label="Main">
      {primaryEntries.map((e) => (
        <Link key={e.key} href={e.href} aria-current={e.active ? "page" : undefined}
          className={`${styles.bottomLink} ${e.active ? styles.bottomLinkActive : ""}`}>
          <span className={styles.bottomIcon}>
            <Icon name={e.icon} size={20} />
            {!!e.badge && <span className={styles.bottomBadge}>{e.badge}</span>}
          </span>
          {e.short}
        </Link>
      ))}
      {overflowEntries.length > 0 && (
        <div className={styles.moreWrap}>
          {showMore && (
            <>
              <div className={styles.menuBackdrop} onClick={() => setShowMore(false)} />
              <div className={styles.moreMenu} role="menu">
                {overflowEntries.map((e) => (
                  <Link key={e.key} href={e.href} role="menuitem" onClick={() => setShowMore(false)}
                    className={`${styles.moreItem} ${e.active ? styles.linkActive : ""}`}>
                    <Icon name={e.icon} />
                    {e.label}
                  </Link>
                ))}
              </div>
            </>
          )}
          <button type="button" className={styles.bottomLink} aria-expanded={showMore} aria-haspopup="menu"
            onClick={() => setShowMore((v) => !v)}>
            <span className={styles.bottomIcon}><Icon name="more" size={20} /></span>
            More
          </button>
        </div>
      )}
    </nav>
    </>
  );
}
