"use client";

import { Fragment, useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { useTranslations } from "next-intl";
import styles from "./clinicNav.module.css";
import Icon from "./components/ui/Icon";
import { initials } from "@nabd/domain";
import { getIdentity, getSessionExpiresAt, ownerPendingTokenValid, regionLabel, signOut, type Identity } from "./lib/session";
import { bottomNavSplit, isActive } from "./shell/navRegistry";
import { useShellNav } from "./shell/useShellNav";
import { usePharmacyCapabilities } from "./lib/pharmacyCapabilities";
import { pharmacyNavAllowed } from "./lib/pharmacyRules";
import { useNavBadges } from "./shell/useNavBadges";
import FacilitySwitcher from "./shell/FacilitySwitcher";
import { BottomSheet } from "@nabd/ui";
import UrgentBadge from "./shell/UrgentBadge";
import BarcodeWedge from "./shell/BarcodeWedge";
import { navText } from "./shell/navText";

// FEF-052: the sidebar renders whatever shell/navRegistry resolves for the role context (one registry, one badge resolver).
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

function formatExpiry(expiresAt: Date | null, t: (key: string, values?: Record<string, number>) => string): string {
  if (!expiresAt) return "—";
  const ms = expiresAt.getTime() - Date.now();
  if (ms <= 0) return t("nav.expired");
  const totalMinutes = Math.floor(ms / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return hours > 0 ? t("nav.expiresInHm", { h: hours, m: minutes }) : t("nav.expiresInM", { m: minutes });
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
  const entries = items.filter((i) => pharmacyNavAllowed(i.href, pharmacyCaps)).map((i0) => ({ ...i0, label: navText(t, i0, role), short: navText(t, i0, role, "short") })).map((i) => ({ ...i, key: i.id, active: isActive(i, pathname, currentTab), badgeCount: i.badge ? badges[i.badge] : undefined, urgent: !!i.urgent }));
  // Mobile bottom nav: max 5 destinations (DESIGN.md §4): 4 items + "More" when there are more.
  const { primary: primaryEntries, more: overflowEntries } = bottomNavSplit(entries);
  const activeRoleLabel = roles.length > 1 ? t(`role.${role}`) : identity?.roleName ?? t(`role.${role}`);
  const branchLocked = !!plan && plan.branchesLimit > 0 && plan.branchesUsed >= plan.branchesLimit;

  function openMenu() {
    setSessionLabel(formatExpiry(getSessionExpiresAt(), t));
    setShowMenu(true);
  }

  async function handleSignOut() {
    setSigningOut(true);
    await signOut();
    router.replace(owner ? "/owner/login" : "/login");
  }

  return (
    <>
    {role === "pharmacist" && <BarcodeWedge />}
    <nav className={`${styles.nav} ${collapsed ? styles.navRail : ""}`} aria-label={t("nav.mainNav")}>
      <div className={styles.brand}>{t("nav.brand")}</div>

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
            {!!e.badgeCount && (e.urgent ? <UrgentBadge n={e.badgeCount} className={styles.badge} /> : <span className={styles.badge}>{e.badgeCount}</span>)}
          </Link>
          </Fragment>
        ))}
      </div>

      {owner && plan && (
        <Link href="/setup?tab=subscription" className={styles.planChip}>
          <span className={styles.planName}>{plan.plan ? t("nav.planName", { plan: plan.plan }) : t("nav.noPlan")}</span>
          <span className={styles.planSub}>{t("nav.planSub", { status: plan.status.replace(/_/g, " ") })}</span>
        </Link>
      )}

      {identity && (
        <div className={styles.identityWrap}>
          {showMenu && (
            <>
              <div className={styles.menuBackdrop} onClick={() => setShowMenu(false)} />
              <div className={styles.identityMenu}>
                <div className={styles.menuHeader}>{t("nav.signedIn")}</div>
                <div className={styles.menuIdentityRow}>
                  <div className={styles.identityAvatar}>{initials(identity.staffName)}</div>
                  <div>
                    <div className={styles.identityName}><bdi>{identity.staffName}</bdi></div>
                    <div className={styles.identityRole}><bdi>{identity.tenantName}</bdi></div>
                  </div>
                </div>
                <div className={styles.menuRow}>
                  <span>{t("nav.activeRole")}</span>
                  <span className={styles.menuRowValue}>{activeRoleLabel}</span>
                </div>
                {owner && identity.tenantRegion && (
                  <div className={styles.menuRow}>
                    <span>{t("nav.region")}</span>
                    <span className={styles.menuRowValue} title={t("nav.fixedAtProvisioning")}>{regionLabel(identity.tenantRegion)}</span>
                  </div>
                )}
                <div className={styles.menuRow}>
                  <span>{t("nav.session")}</span>
                  <span className={styles.menuRowValue}>{sessionLabel}</span>
                </div>
                {owner && (
                  <Link href="/account" className={styles.menuLink} onClick={() => setShowMenu(false)}>
                    {t("nav.accountSecurity")}
                  </Link>
                )}
                <button type="button" className={styles.menuSignOut} onClick={handleSignOut} disabled={signingOut}>
                  {signingOut ? t("nav.signingOut") : t("nav.signOut")}
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
    <nav className={styles.bottomNav} data-bottom-nav aria-label={t("nav.mainNav")}>
      {primaryEntries.map((e) => (
        <Link key={e.key} href={e.href} aria-current={e.active ? "page" : undefined}
          className={`${styles.bottomLink} ${e.active ? styles.bottomLinkActive : ""}`}>
          <span className={styles.bottomIcon}>
            <Icon name={e.icon} size={20} />
            {!!e.badgeCount && (e.urgent ? <UrgentBadge n={e.badgeCount} className={styles.bottomBadge} /> : <span className={styles.bottomBadge}>{e.badgeCount}</span>)}
          </span>
          {e.short}
        </Link>
      ))}
      {overflowEntries.length > 0 && (
        <div className={styles.moreWrap}>
          {showMore && (
            <BottomSheet title={t("nav.more")} onClose={() => setShowMore(false)}>
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
                    {t("nav.accountSecurity")}
                  </Link>
                )}
                {owner && (canReturnToWorkspaces ? (
                  <Link href="/owner/workspaces" role="menuitem" className={styles.moreItem} onClick={() => setShowMore(false)}>
                    <Icon name="building" />
                    {t("nav.switchClinic")}
                  </Link>
                ) : (
                  <button type="button" role="menuitem" className={`${styles.moreItem} ${styles.moreButton}`} disabled={signingOut}
                    onClick={() => { setShowMore(false); void handleSignOut(); }}>
                    <Icon name="building" />
                    {signingOut ? t("nav.signingOut") : t("nav.switchClinicAgain")}
                  </button>
                ))}
              </div>
            </BottomSheet>
          )}
          <button type="button" ref={moreBtnRef} className={styles.bottomLink} aria-expanded={showMore} aria-haspopup="menu"
            onClick={() => { setCanReturnToWorkspaces(ownerPendingTokenValid()); setShowMore((v) => !v); }}>
            <span className={styles.bottomIcon}><Icon name="more" size={20} /></span>
            {t("nav.more")}
          </button>
        </div>
      )}
    </nav>
    </>
  );
}
