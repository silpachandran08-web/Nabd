"use client";

import { Fragment, useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { useTranslations } from "next-intl";
import styles from "./clinicNav.module.css";
import Icon from "./components/ui/Icon";
import { initials } from "@nabd/domain";
import { getIdentity, getSessionExpiresAt, ownerPendingTokenValid, regionLabel, signOut, type Identity } from "./lib/session";
import { isActive, ROLE_LABEL } from "./shell/navRegistry";
import { useShellNav } from "./shell/useShellNav";
import { usePharmacyCapabilities } from "./lib/pharmacyCapabilities";
import { pharmacyNavAllowed } from "./lib/pharmacyRules";
import { useNavBadges } from "./shell/useNavBadges";
import FacilitySwitcher from "./shell/FacilitySwitcher";

// FEF-052: the sidebar renders whatever shell/navRegistry resolves for the role context (one registry, one badge resolver).
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

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
  const t = useTranslations("shell");
  const { input, role, roles, items, owner, tenantId } = useShellNav();
  const { badges } = useNavBadges(role, input.perms, pathname);
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [showMenu, setShowMenu] = useState(false);
  const [sessionLabel, setSessionLabel] = useState("—");
  const [signingOut, setSigningOut] = useState(false);
  const [showMore, setShowMore] = useState(false);
  const moreBtnRef = useRef<HTMLButtonElement>(null);
  const [plan, setPlan] = useState<{ plan: string | null; status: string; branchesUsed: number; branchesLimit: number } | null>(null);
  const [canReturnToWorkspaces, setCanReturnToWorkspaces] = useState(false);

  // Escape closes the mobile More menu and hands focus back to its button.
  useEffect(() => {
    if (!showMore) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") { setShowMore(false); moreBtnRef.current?.focus(); }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [showMore]);

  // Hydration-safe: read the identity after mount, never during render.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setIdentity(getIdentity());
  }, [pathname, input.perms]);

  // Owner's plan chip (wireframe showPlanChip): GET /setup/subscription (setup:view).
  useEffect(() => {
    if (!owner || !input.perms.includes("setup:view")) return;
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
  }, [owner, input.perms]);

  const pharmacyCaps = usePharmacyCapabilities(input.perms.includes("pharmacy:view")); // PHL-004: hide Pharmacy while the clinic is External
  if (input.perms.length === 0 || items.length === 0) return null;
  const currentTab = searchParams.get("tab");
  const entries = items.filter((i) => pharmacyNavAllowed(i.href, pharmacyCaps)).map((i0) => (i0.section && role === "owner" ? { ...i0, label: t(`ownerNav.${i0.id}.label`), short: t(`ownerNav.${i0.id}.short`) } : i0)).map((i) => ({ ...i, key: i.id, active: isActive(i, pathname, currentTab), badgeCount: i.badge ? badges[i.badge] : undefined }));
  // Mobile bottom nav: max 5 destinations (DESIGN.md §4): 4 items + "More" when there are more.
  const primaryEntries = entries.length > 5 ? entries.slice(0, 4) : entries;
  const overflowEntries = entries.length > 5 ? entries.slice(4) : [];
  const activeRoleLabel = roles.length > 1 ? ROLE_LABEL[role] : identity?.roleName ?? ROLE_LABEL[role];
  const branchLocked = !!plan && plan.branchesLimit > 0 && plan.branchesUsed >= plan.branchesLimit;

  function openMenu() {
    setSessionLabel(formatExpiry(getSessionExpiresAt()));
    setShowMenu(true);
  }

  async function handleSignOut() {
    setSigningOut(true);
    await signOut();
    router.replace(owner ? "/owner/login" : "/login");
  }

  return (
    <>
    <nav className={`${styles.nav} ${collapsed ? styles.navRail : ""}`} aria-label="Main">
      <div className={styles.brand}>nabd</div>

      {identity && (owner ? (
        <FacilitySwitcher name={identity.tenantName} region={identity.tenantRegion} tenantId={tenantId} branchLocked={branchLocked}
          onSignInAgain={() => void handleSignOut()} signingOut={signingOut} />
      ) : (
        <div className={styles.clinicInfo}>
          <div className={styles.clinicIcon}><Icon name="building" size={16} /></div>
          <div className={styles.clinicText}>
            <div className={styles.clinicName}><bdi>{identity.tenantName}</bdi></div>
          </div>
        </div>
      ))}

      <div className={styles.links}>
        {entries.map((e, n) => (
          <Fragment key={e.key}>
          {e.section && e.section !== entries[n - 1]?.section && <p className={styles.sectionLabel}>{t(`ownerSection.${e.section}`)}</p>}
          <Link
            href={e.href}
            title={e.label}
            aria-current={e.active ? "page" : undefined}
            className={`${styles.link} ${e.active ? styles.linkActive : ""}`}
          >
            <Icon name={e.icon} />
            <span className={styles.linkLabel}>{e.label}</span>
            <span className={styles.linkShort} aria-hidden="true">{e.short}</span>
            {!!e.badgeCount && <span className={styles.badge}>{e.badgeCount}</span>}
          </Link>
          </Fragment>
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
                  <span className={styles.menuRowValue}>{activeRoleLabel}</span>
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
              <div className={styles.identityRole}>{activeRoleLabel}</div>
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
            {!!e.badgeCount && <span className={styles.bottomBadge}>{e.badgeCount}</span>}
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
