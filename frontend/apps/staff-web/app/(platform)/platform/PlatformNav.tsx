"use client";

import { useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useQueryClient } from "@tanstack/react-query";
import { BottomSheet } from "@nabd/ui";
import styles from "./platform.module.css";
import Icon from "../../components/ui/Icon";
import { Skeleton } from "../../components/ui/StateViews";
import { initials as initialsOf } from "@nabd/domain";
import { isActive } from "../../shell/navRegistry";
import { OperatorProfile, PLATFORM_QK, platformNavFor, ROLE_LABELS, splitBottomNav } from "./surfaces";
import { platformLogout } from "./platformApi";

// FEF-059: the console's nav, from the typed role config (surfaces.ts). Desktop sidebar, tablet icon rail (--bp-tablet) and
// phone bottom nav with the top four + More (--bp-phone), like ClinicNav. Items the operator may not open are absent.
// Badge wiring point: no operator SSE topic or counts endpoint exists yet, so no item declares a badge (FEF-059 report, needs backend).
const BADGES: Partial<Record<string, number>> = {};

export function PlatformNav({ profile, failed, onRetry }: { profile: OperatorProfile | null; failed: boolean; onRetry: () => void }) {
  const pathname = usePathname();
  const router = useRouter();
  const qc = useQueryClient();
  const t = useTranslations("shell.platformNav");
  const [showMore, setShowMore] = useState(false);

  async function logout() {
    await platformLogout();
    qc.removeQueries({ queryKey: PLATFORM_QK }); // the next operator in this tab starts with an empty console cache
    router.replace("/platform/login");
  }

  const logoutButton = (
    <button className={styles.navBarLogout} onClick={logout} title="Log out" aria-label="Log out">
      <Icon name="x" size={14} />
      <span className={styles.navBarLogoutText}>Log out</span>
    </button>
  );

  // A failed /auth/me keeps the rail (and a way out) instead of silently dropping the whole nav.
  if (failed && !profile) {
    return (
      <nav className={`${styles.navBar} ${styles.navBarAlways}`} aria-label="Platform">
        <span className={styles.navBarBrand}><span className={styles.navBarBrandText}>Nabd Platform</span></span>
        <div className={styles.navBarError} role="alert">
          <Icon name="alert" size={16} />
          <span className={styles.navBarLabel}>Couldn&apos;t load your navigation.</span>
          <button type="button" className={styles.navBarLogout} onClick={onRetry}>
            <Icon name="refresh" size={14} /><span className={styles.navBarLogoutText}>Retry</span>
          </button>
        </div>
        <div className={styles.navBarUser}>{logoutButton}</div>
      </nav>
    );
  }

  if (!profile) {
    return (
      <nav className={styles.navBar} aria-label="Platform" aria-busy="true">
        <span className={styles.navBarBrand}><span className={styles.navBarBrandText}>Nabd Platform</span></span>
        <Skeleton rows={6} label="Loading navigation" />
      </nav>
    );
  }

  const entries = platformNavFor(profile.permissions).map((i) => ({
    ...i, label: t(`${i.id}.label`), short: t(`${i.id}.short`),
    active: i.href === "/platform" ? pathname === "/platform" : isActive(i, pathname, null),
    badgeCount: i.badge ? BADGES[i.badge] : undefined,
  }));
  const { primary, more } = splitBottomNav(entries);
  const roleLabel = ROLE_LABELS[profile.role] ?? profile.role;
  const initials = initialsOf(profile.name);

  return (
    <>
      <nav className={styles.navBar} aria-label="Platform">
        {/* The wireframe's install name ("Nabd India" / "Nabd Saudi Arabia"), region/env line and LIVE
            badge need an install descriptor the API does not expose yet, so none of it is guessed here. */}
        <span className={styles.navBarBrand}>
          <span className={styles.navBarBrandText}>Nabd Platform</span>
          <span className={styles.navBarEnv}>Operator console</span>
        </span>
        <div className={styles.navBarLinks}>
          {entries.map((e) => (
            <Link
              key={e.id}
              href={e.href}
              title={e.label}
              aria-current={e.active ? "page" : undefined}
              className={`${styles.navBarLink} ${e.active ? styles.navBarLinkActive : ""}`}
            >
              <Icon name={e.icon} size={16} />
              <span className={styles.navBarLabel}>{e.label}</span>
              <span className={styles.navBarShort} aria-hidden="true">{e.short}</span>
              {!!e.badgeCount && <span className={styles.navBadge}>{e.badgeCount}</span>}
            </Link>
          ))}
        </div>
        <div className={styles.navBarUser}>
          <span className={styles.navBarAvatar} aria-hidden="true">{initials}</span>
          <div className={styles.navBarUserText}>
            <span className={styles.navBarUserName}>{profile.name}</span>
            <span className={styles.navBarUserRole}>{roleLabel}</span>
          </div>
          {logoutButton}
        </div>
      </nav>
      <nav className={styles.bottomNav} data-bottom-nav aria-label="Platform">
        {primary.map((e) => (
          <Link key={e.id} href={e.href} aria-current={e.active ? "page" : undefined}
            className={`${styles.bottomLink} ${e.active ? styles.bottomLinkActive : ""}`}>
            <span className={styles.bottomIcon}>
              <Icon name={e.icon} size={20} />
              {!!e.badgeCount && <span className={styles.navBadge}>{e.badgeCount}</span>}
            </span>
            {e.short}
          </Link>
        ))}
        {showMore && (
          <BottomSheet title="More" onClose={() => setShowMore(false)}>
            <div className={styles.moreMenu} role="menu">
              {more.map((e) => (
                <Link key={e.id} href={e.href} role="menuitem" onClick={() => setShowMore(false)}
                  aria-current={e.active ? "page" : undefined} className={`${styles.moreItem} ${e.active ? styles.navBarLinkActive : ""}`}>
                  <Icon name={e.icon} />
                  {e.label}
                  {!!e.badgeCount && <span className={styles.navBadge}>{e.badgeCount}</span>}
                </Link>
              ))}
              <button type="button" role="menuitem" className={styles.moreItem} onClick={() => { setShowMore(false); void logout(); }}>
                <Icon name="x" />
                Log out ({profile.name} · {roleLabel})
              </button>
            </div>
          </BottomSheet>
        )}
        <button type="button" className={styles.bottomLink} aria-expanded={showMore} aria-haspopup="menu" onClick={() => setShowMore((v) => !v)}>
          <span className={styles.bottomIcon}><Icon name="more" size={20} /></span>
          More
        </button>
      </nav>
    </>
  );
}
