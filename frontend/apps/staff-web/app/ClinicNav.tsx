"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { adminOrdersQuery, prefix, queueQuery } from "./lib/query";
import styles from "./clinicNav.module.css";
import Icon, { type IconName } from "./components/ui/Icon";
import { initials } from "@nabd/domain";
import { decodeAccessToken, getIdentity, getPermissions, hasNursingGrant, getSessionExpiresAt, isOwner, ownerPendingTokenValid, regionLabel, signOut, SHELL_SKIP_PREFIXES, type Identity } from "./lib/session";

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
  // lookups) without being a clinician; see lib/session.ts (landingPathFor) for the full reasoning.
  { href: "/consult", label: "Consultation Workspace", short: "Consult", icon: "stethoscope", permission: "clinical:edit" },
  { href: "/pharmacy", label: "Pharmacy", short: "Pharmacy", icon: "syringe", permission: "pharmacy:view" },
  { href: "/billing", label: "Checkout & Billing", short: "Billing", icon: "receipt", permission: "billing:view" },
  { href: "/packages", label: "Treatment Packages", short: "Packages", icon: "package", permission: "packages:view" },
  { href: "/staff", label: "Staff & Access", short: "Staff", icon: "shield", permission: "staff:view" },
  { href: "/reports", label: "Owner Insights", short: "Insights", icon: "chart", permission: "reports:view" },
  { href: "/setup", label: "Setup", short: "Setup", icon: "settings", permission: "setup:view" },
  { href: "/account", label: "My account", short: "Account", icon: "user", permission: null },
];

// The Nurse sidebar (wireframe "Nurse / Clinical Assistant"): the nursing worklist's own sections,
// each deep-linking into the single /nursing route via ?tab=. Package Sessions opens /packages
// (packages:view). The wireframe's Clinical Inbox and More have no page or data source yet, so
// they are left out rather than linked to nothing.
type NursingItem = { label: string; short: string; icon: IconName; tab?: string; href?: string; permission?: string; badgeKey?: "priority" | "admin" };
const NURSING_ITEMS: NursingItem[] = [
  { label: "Arrivals & Vitals", short: "Vitals", icon: "pulse" },
  { label: "Priority Patients", short: "Priority", icon: "alert", tab: "priority", badgeKey: "priority" },
  { label: "Administration Orders", short: "Orders", icon: "syringe", tab: "administration", badgeKey: "admin" },
  { label: "Today's Procedures", short: "Procedures", icon: "list", tab: "procedures" },
  { label: "Package Sessions", short: "Packages", icon: "package", href: "/packages", permission: "packages:view" },
  { label: "Completed Activity", short: "Activity", icon: "clock", tab: "activity" },
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
  { label: "Support access", short: "Support", icon: "shield", href: "/compliance", permission: "compliance:view", active: (p) => p.startsWith("/compliance") },
  { label: "Plan & Modules", short: "Plan", icon: "list", href: "/setup?tab=subscription", permission: "setup:view",
    active: (p, tab) => p === "/setup" && tab === "subscription" },
];

// DESIGN.md / wireframe Doctor sidebar (NabdShell.html doctor list), in its order. A doctor is a
// non-owner, non-nursing role with clinical:edit; each item still needs its own permission. Billing
// and Arrivals are deliberately not here (the doctor works from My Day). /my-day is built by DOC-002.
export const MY_DAY_HREF = "/my-day";
type DoctorItem = { label: string; short: string; icon: IconName; href: string; permission: string; alsoPermission?: string; badgeKey?: string };
const DOCTOR_ITEMS: DoctorItem[] = [
  { label: "My Day", short: "My Day", icon: "sun", href: MY_DAY_HREF, permission: "clinical:edit", alsoPermission: "queue:view", badgeKey: MY_DAY_HREF },
  { label: "Patients", short: "Patients", icon: "users", href: "/patients", permission: "patients:view" },
  // The workspace reads the queue: without queue:view it is a "no access" page (LIVE-04).
  { label: "Consultation Workspace", short: "Consult", icon: "stethoscope", href: "/consult", permission: "clinical:edit", alsoPermission: "queue:view" },
  // DOC-003: sets are read with clinical:view; the doctor sidebar itself already needs clinical:edit.
  { label: "Prescriptions & Templates", short: "Rx sets", icon: "syringe", href: "/prescriptions", permission: "clinical:view", alsoPermission: "clinical:edit" },
  { label: "Treatment Packages", short: "Packages", icon: "package", href: "/packages", permission: "packages:view" },
  { label: "Schedule", short: "Schedule", icon: "calendar", href: "/schedule", permission: "queue:view" },
  // DOC-004: the doctor's own hours and leave (reads need queue:view; writes inside need queue:edit).
  { label: "My Schedule", short: "Hours", icon: "clock", href: "/my-schedule", permission: "clinical:edit", alsoPermission: "queue:view" },
  // DOC-005 / DOC-006: wireframe order is Schedule, Encounters, Follow-ups.
  { label: "Encounters", short: "Encounters", icon: "list", href: "/encounters", permission: "clinical:view" },
  { label: "Follow-ups", short: "Follow-ups", icon: "refresh", href: "/follow-ups", permission: "queue:view" },
];
// Wireframe doctor items whose page does not exist yet. Not linked (no dead links) and not shown
// disabled (restricted/unavailable UI is absent): when a page ships, move its entry into DOCTOR_ITEMS
// at the wireframe position (after Schedule:
// External Reports, Clinical Documents, Specialty Workspace, Clinical Settings, More).
export const DOCTOR_PLANNED = [
  "External Reports",
  "Clinical Documents", "Specialty Workspace", "Clinical Settings", "More",
] as const;
function isDoctorNav(perms: string[]): boolean {
  return !isOwner() && !hasNursingGrant(perms) && perms.includes("clinical:edit");
}

function nursingHref(item: NursingItem): string {
  if (item.href) return item.href;
  return item.tab ? `/nursing?tab=${item.tab}` : "/nursing";
}

function isNursingItemActive(item: NursingItem, pathname: string | null, searchParams: URLSearchParams): boolean {
  if (item.href) return !!pathname && pathname.startsWith(item.href);
  if (pathname !== "/nursing") return false;
  return (item.tab ?? "vitals") === (searchParams.get("tab") ?? "vitals");
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
  const moreBtnRef = useRef<HTMLButtonElement>(null);
  const [showClinic, setShowClinic] = useState(false);
  const [plan, setPlan] = useState<{ plan: string | null; status: string; branchesUsed: number; branchesLimit: number } | null>(null);
  const [canReturnToWorkspaces, setCanReturnToWorkspaces] = useState(false);
  const [myDayBadge, setMyDayBadge] = useState<Record<string, number>>({});

  // Escape closes the mobile More menu and hands focus back to its button.
  useEffect(() => {
    if (!showMore) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") { setShowMore(false); moreBtnRef.current?.focus(); }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [showMore]);

  // Escape closes the clinic & branch popover.
  useEffect(() => {
    if (!showClinic) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setShowClinic(false);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [showClinic]);

  // Hydration-safe: render nothing until this mounts and can read localStorage, then reflect
  // it — never read it directly during render.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPermissions(skip ? [] : getPermissions());
    setIdentity(skip ? null : getIdentity());
    setOwner(skip ? false : isOwner());
  }, [pathname, skip]);

  // The Receptionist sidebar's count badges: today's waiting arrivals and visits sent to checkout, off
  // today's queue. FEF-015: the same query (key and request) Arrivals reads, so the two share one GET /queue.
  const perms = permissions ?? [];
  const nurseNav = !skip && !owner && hasNursingGrant(perms);
  const queueRows = useQuery({ ...queueQuery(), enabled: !skip && !owner && !nurseNav && !isDoctorNav(perms) && perms.includes("queue:view") }).data;
  // The Nurse sidebar's count badges, off the same queries the nursing page itself reads: priority-flagged
  // queue entries (queue:view) and today's administration orders not yet given (nursing:view).
  const priorityRows = useQuery({ ...queueQuery(true), enabled: nurseNav && perms.includes("queue:view") }).data;
  const adminRows = useQuery({ ...adminOrdersQuery(), enabled: nurseNav && perms.includes("nursing:view") }).data;
  const count = <T,>(rows: T[] | undefined, keep: (r: T) => boolean) => (Array.isArray(rows) ? rows.filter(keep).length : undefined);
  const badges: Record<string, number | undefined> = {
    ...myDayBadge,
    "/arrivals": count(queueRows, (r) => WAITING_STATUSES.includes(r.status ?? "")),
    "/billing": count(queueRows, (r) => r.status === "checkout_pending"),
    "nursing:priority": count(priorityRows, () => true),
    "nursing:admin": count(adminRows, (o) => o.status === "not_started"),
  };
  // Badges refresh on every navigation, as before; a newer refresh cancels a slower one still in flight (FEF-004).
  const qc = useQueryClient();
  useEffect(() => {
    void qc.refetchQueries({ queryKey: prefix("queue"), type: "active" });
    void qc.refetchQueries({ queryKey: adminOrdersQuery().queryKey, type: "active" });
  }, [pathname, qc]);

  // My Day badge: this doctor's waiting patients today (GET /queue?doctorId=<own staff id>, queue:view).
  // The JWT subject is the staff id (AuthService.mintTokenPair); no queue:view = no badge.
  useEffect(() => {
    const perms = getPermissions();
    if (skip || !isDoctorNav(perms) || !perms.includes("queue:view")) return;
    const token = localStorage.getItem("nabd_access_token");
    const me = token ? decodeAccessToken(token)?.sub : undefined;
    if (!token || !me) return;
    const ctrl = new AbortController();
    let cancelled = false;
    void fetch(`${API_BASE}/queue?doctorId=${encodeURIComponent(me)}`, { headers: { Authorization: `Bearer ${token}` }, signal: ctrl.signal })
      .then(async (res) => {
        if (cancelled || !res.ok) return;
        const rows: { status: string; doctorId?: string }[] = await res.json();
        if (cancelled || !Array.isArray(rows)) return;
        setMyDayBadge({ [MY_DAY_HREF]: rows.filter((r) => (!r.doctorId || r.doctorId === me) && WAITING_STATUSES.includes(r.status)).length });
      })
      .catch(() => {});
    return () => { cancelled = true; ctrl.abort(); };
  }, [pathname, skip]);

  // Owner's plan chip (wireframe showPlanChip): GET /setup/subscription (setup:view).
  useEffect(() => {
    if (skip || !isOwner() || !getPermissions().includes("setup:view")) return;
    const token = localStorage.getItem("nabd_access_token");
    if (!token) return;
    const ctrl = new AbortController();
    let cancelled = false;
    void fetch(`${API_BASE}/setup/subscription`, { headers: { Authorization: `Bearer ${token}` }, signal: ctrl.signal })
      .then(async (res) => {
        if (cancelled || !res.ok) return;
        const body: { plan: string | null; status: string; branchesUsed: number; branchesLimit: number } = await res.json();
        if (!cancelled) setPlan({ plan: body.plan, status: body.status, branchesUsed: Number(body.branchesUsed), branchesLimit: Number(body.branchesLimit) });
      })
      .catch(() => {});
    return () => { cancelled = true; ctrl.abort(); };
  }, [skip]);

  if (skip || !permissions || permissions.length === 0) return null;

  // A Nurse's sidebar is DESIGN.md's own dedicated worklist nav, never the generic module list —
  // a staff record that also carries queue:view/patients:view (this local test account does) must
  // not leak those in here, or the sidebar mixes two different roles' navigation into one.
  const ownerItems = owner ? OWNER_ITEMS.filter((item) => permissions.includes(item.permission)) : [];
  const showOwner = ownerItems.length > 0;
  const showNursing = !showOwner && hasNursingGrant(permissions);
  const showDoctor = !showOwner && !showNursing && !owner && permissions.includes("clinical:edit");
  const doctorItems = showDoctor ? DOCTOR_ITEMS.filter((item) => permissions.includes(item.permission) && (!item.alsoPermission || permissions.includes(item.alsoPermission))) : [];
  const items = showOwner || showNursing || showDoctor ? [] : NAV_ITEMS.filter((item) => !item.permission || permissions.includes(item.permission));
  if (items.length === 0 && !showNursing && !showOwner && doctorItems.length === 0) return null;
  const currentTab = searchParams.get("tab");

  type Entry = { key: string; href: string; label: string; short: string; icon: IconName; active: boolean; badge?: number };
  const entries: Entry[] = [
    ...ownerItems.map((item) => ({
      key: item.label, href: item.href, label: item.label, short: item.short, icon: item.icon,
      active: !!pathname && item.active(pathname, currentTab),
    })),
    ...(showNursing ? NURSING_ITEMS.filter((item) => !item.permission || permissions.includes(item.permission)).map((item) => ({
      key: item.label, href: nursingHref(item), label: item.label, short: item.short, icon: item.icon,
      active: isNursingItemActive(item, pathname, searchParams),
      badge: item.badgeKey ? badges[`nursing:${item.badgeKey}`] : undefined,
    })) : []),
    ...doctorItems.map((item) => ({
      key: item.label, href: item.href, label: item.label, short: item.short, icon: item.icon,
      active: !!pathname && pathname.startsWith(item.href),
      badge: item.badgeKey ? badges[item.badgeKey] : undefined,
    })),
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
    router.replace(owner ? "/owner/login" : "/login");
  }

  function toggleClinicMenu() {
    // Re-checked on every open: the owner's pending-workspace token only lives ~5 minutes.
    setCanReturnToWorkspaces(ownerPendingTokenValid());
    setShowClinic((v) => !v);
  }

  // OWN-003: with an expired pending token the workspace list needs the owner PIN again, so the
  // clinic session is ended first (no orphaned session left behind) and the owner signs back in.
  async function switchClinicViaLogin() {
    setShowClinic(false);
    await handleSignOut();
  }

  return (
    <>
    <nav className={`${styles.nav} ${collapsed ? styles.navRail : ""}`} aria-label="Main">
      <div className={styles.brand}>nabd</div>

      {identity && (owner ? (
        <div className={styles.clinicWrap}>
          <button type="button" className={`${styles.clinicInfo} ${styles.clinicButton}`} aria-haspopup="menu"
            aria-expanded={showClinic} aria-label={`Clinic: ${identity.tenantName}`} onClick={toggleClinicMenu}>
            <div className={styles.clinicIcon}><Icon name="building" size={16} /></div>
            <div className={styles.clinicText}>
              <div className={styles.clinicName}><bdi>{identity.tenantName}</bdi></div>
            </div>
            <span className={styles.clinicChevron}><Icon name="chevron" size={14} /></span>
          </button>
          {showClinic && (
            <>
              <div className={styles.menuBackdrop} onClick={() => setShowClinic(false)} />
              <div className={styles.clinicMenu} role="menu" aria-label="Clinic & branch">
                <div className={styles.menuHeader}>Clinic &amp; branch</div>
                <div className={styles.clinicCurrent} role="menuitem" aria-current="true">
                  <Icon name="building" size={14} />
                  <span className={styles.clinicRowText}>
                    <span><bdi>{identity.tenantName}</bdi></span>
                    {identity.tenantRegion && <span className={styles.clinicRowSub}>{regionLabel(identity.tenantRegion)}</span>}
                  </span>
                  <span className={styles.clinicRowTag}>Current</span>
                </div>
                {/* Branch names have no API yet; the subscription summary does say whether this
                    brand is at its branch limit, which is the wireframe's locked Multi-branch row. */}
                {plan && plan.branchesLimit > 0 && plan.branchesUsed >= plan.branchesLimit && (
                  <Link href="/setup?tab=subscription" role="menuitem" className={styles.clinicLocked} onClick={() => setShowClinic(false)}>
                    <Icon name="lock" size={14} />
                    <span className={styles.clinicRowText}>
                      <span>More branches</span>
                      <span className={styles.clinicRowSub}>Locked: needs Multi-branch module</span>
                    </span>
                  </Link>
                )}
                {canReturnToWorkspaces ? (
                  <Link href="/owner/workspaces" role="menuitem" className={styles.clinicSwitch} onClick={() => setShowClinic(false)}>
                    Switch clinic
                  </Link>
                ) : (
                  <button type="button" role="menuitem" className={styles.clinicSwitch} onClick={switchClinicViaLogin} disabled={signingOut}>
                    {signingOut ? "Signing out…" : "Switch clinic"}
                    <span className={styles.clinicRowSub}>Signs you out here; sign in again to pick a clinic.</span>
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      ) : (
        <div className={styles.clinicInfo}>
          <div className={styles.clinicIcon}><Icon name="building" size={16} /></div>
          <div className={styles.clinicText}>
            <div className={styles.clinicName}><bdi>{identity.tenantName}</bdi></div>
          </div>
        </div>
      ))}

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

      {owner && plan && (
        <Link href="/setup?tab=subscription" className={styles.planChip}>
          <span className={styles.planName}>{plan.plan ? `Plan: ${plan.plan}` : "No plan assigned"}</span>
          <span className={styles.planSub}>{plan.status.replace(/_/g, " ")} · view catalog</span>
        </Link>
      )}

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
                    <div className={styles.identityName}><bdi>{identity.staffName}</bdi></div>
                    <div className={styles.identityRole}><bdi>{identity.tenantName}</bdi></div>
                  </div>
                </div>
                <div className={styles.menuRow}>
                  <span>Active role</span>
                  <span className={styles.menuRowValue}>{identity.roleName}</span>
                </div>
                {owner && identity.tenantRegion && (
                  <div className={styles.menuRow}>
                    <span>Region</span>
                    <span className={styles.menuRowValue} title="Fixed at provisioning">{regionLabel(identity.tenantRegion)}</span>
                  </div>
                )}
                <div className={styles.menuRow}>
                  <span>Session</span>
                  <span className={styles.menuRowValue}>{sessionLabel}</span>
                </div>
                {owner && (
                  <Link href="/account" className={styles.menuLink} onClick={() => setShowMenu(false)}>
                    Account &amp; Security
                  </Link>
                )}
                <button type="button" className={styles.menuSignOut} onClick={handleSignOut} disabled={signingOut}>
                  {signingOut ? "Signing out…" : "Sign out"}
                </button>
              </div>
            </>
          )}
          <button type="button" className={styles.identityFooter} onClick={openMenu}>
            <div className={styles.identityAvatar}>{initials(identity.staffName)}</div>
            <div className={styles.identityText}>
              <div className={styles.identityName}><bdi>{identity.staffName}</bdi></div>
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
                {owner && (
                  <Link href="/account" role="menuitem" className={`${styles.moreItem} ${pathname === "/account" ? styles.linkActive : ""}`} onClick={() => setShowMore(false)}>
                    <Icon name="user" />
                    Account &amp; Security
                  </Link>
                )}
                {owner && (canReturnToWorkspaces ? (
                  <Link href="/owner/workspaces" role="menuitem" className={styles.moreItem} onClick={() => setShowMore(false)}>
                    <Icon name="building" />
                    Switch clinic
                  </Link>
                ) : (
                  <button type="button" role="menuitem" className={`${styles.moreItem} ${styles.moreButton}`} disabled={signingOut}
                    onClick={() => { setShowMore(false); void handleSignOut(); }}>
                    <Icon name="building" />
                    {signingOut ? "Signing out…" : "Switch clinic (sign in again)"}
                  </button>
                ))}
              </div>
            </>
          )}
          <button type="button" ref={moreBtnRef} className={styles.bottomLink} aria-expanded={showMore} aria-haspopup="menu"
            onClick={() => { setCanReturnToWorkspaces(ownerPendingTokenValid()); setShowMore((v) => !v); }}>
            <span className={styles.bottomIcon}><Icon name="more" size={20} /></span>
            More
          </button>
        </div>
      )}
    </nav>
    </>
  );
}
