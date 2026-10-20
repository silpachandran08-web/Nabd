"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import styles from "./platform.module.css";
import Icon from "../components/ui/Icon";
import { Skeleton } from "../components/ui/StateViews";
import { groupBySection, OperatorProfile, ROLE_LABELS, visibleSurfaces } from "./surfaces";
import { hasPlatformSession, platformLogout, usePlatformFetch } from "./platformApi";

// Every /platform/* page used to be a dead end: only the Command Centre
// landing page (page.tsx) rendered the surface list, so navigating into any
// one surface (Fleet, Billing, ...) left no way back except the browser's
// back button. This bar is that missing shared nav, rendered once here
// instead of copy-pasted into all eight page.tsx files.
export function PlatformNav() {
  const pathname = usePathname();
  const router = useRouter();
  const fetchAuthed = usePlatformFetch();
  const [profile, setProfile] = useState<OperatorProfile | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0); // bumped by Retry

  useEffect(() => {
    if (pathname === "/platform/login") return;
    if (!hasPlatformSession()) return;
    let cancelled = false;
    (async () => {
      const res = await fetchAuthed("/platform/auth/me");
      if (cancelled || !res) return;
      if (res.ok) { setProfile(await res.json()); setFailed(false); }
      else setFailed(true);
    })().catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [pathname, fetchAuthed, attempt]);

  if (pathname === "/platform/login") return null;

  async function logout() {
    await platformLogout();
    router.replace("/platform/login");
  }

  // A failed /auth/me keeps the rail (and a way out) instead of silently dropping the whole nav.
  if (failed && !profile) {
    return (
      <nav className={styles.navBar} aria-label="Platform">
        <span className={styles.navBarBrand}><span className={styles.navBarBrandText}>Nabd Platform</span></span>
        <div className={styles.navBarError} role="alert">
          <Icon name="alert" size={16} />
          <span className={styles.navBarLabel}>Couldn&apos;t load your navigation.</span>
          <button type="button" className={styles.navBarLogout} onClick={() => { setFailed(false); setAttempt((n) => n + 1); }}>
            <Icon name="refresh" size={14} /><span className={styles.navBarLogoutText}>Retry</span>
          </button>
        </div>
        <div className={styles.navBarUser}>
          <button className={styles.navBarLogout} onClick={logout} title="Log out" aria-label="Log out">
            <Icon name="x" size={14} />
            <span className={styles.navBarLogoutText}>Log out</span>
          </button>
        </div>
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

  const groups = groupBySection(visibleSurfaces(profile.permissions));
  const roleLabel = ROLE_LABELS[profile.role] ?? profile.role;
  const initials = profile.name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase() ?? "").join("");

  return (
    <nav className={styles.navBar} aria-label="Platform">
      {/* The wireframe's install name ("Nabd India" / "Nabd Saudi Arabia"), region/env line and LIVE
          badge need an install descriptor the API does not expose yet, so none of it is guessed here. */}
      <Link href="/platform" className={styles.navBarBrand}>
        <span className={styles.navBarBrandText}>Nabd Platform</span>
        <span className={styles.navBarEnv}>Operator console</span>
      </Link>
      <div className={styles.navBarLinks}>
        {groups.map((g) => (
          <div key={g.section} className={styles.navBarGroup}>
            <div className={styles.navBarSection}>{g.section}</div>
            {g.items.map((s) => {
              const active = s.href === "/platform" ? pathname === s.href : pathname === s.href || pathname.startsWith(`${s.href}/`);
              return (
                <Link
                  key={s.href}
                  href={s.href}
                  title={s.label}
                  aria-current={active ? "page" : undefined}
                  className={`${styles.navBarLink} ${active ? styles.navBarLinkActive : ""}`}
                >
                  <Icon name={s.icon} size={16} />
                  <span className={styles.navBarLabel}>{s.label}</span>
                  <span className={styles.navBarShort} aria-hidden="true">{s.short}</span>
                </Link>
              );
            })}
          </div>
        ))}
      </div>
      <div className={styles.navBarUser}>
        <span className={styles.navBarAvatar} aria-hidden="true">{initials}</span>
        <div className={styles.navBarUserText}>
          <span className={styles.navBarUserName}>{profile.name}</span>
          <span className={styles.navBarUserRole}>{roleLabel}</span>
        </div>
        <button className={styles.navBarLogout} onClick={logout} title="Log out" aria-label="Log out">
          <Icon name="x" size={14} />
          <span className={styles.navBarLogoutText}>Log out</span>
        </button>
      </div>
    </nav>
  );
}
