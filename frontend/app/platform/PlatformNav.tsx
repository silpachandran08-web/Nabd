"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import styles from "./platform.module.css";
import Icon from "../components/ui/Icon";
import { Skeleton } from "../components/ui/StateViews";
import { API_BASE, groupBySection, OperatorProfile, ROLE_LABELS, visibleSurfaces } from "./surfaces";
import { clearPlatformSession, usePlatformFetch } from "./platformApi";

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

  useEffect(() => {
    if (pathname === "/platform/login") return;
    if (!localStorage.getItem("nabd_platform_access_token")) return;
    let cancelled = false;
    (async () => {
      const res = await fetchAuthed("/platform/auth/me");
      if (cancelled || !res) return;
      if (res.ok) setProfile(await res.json());
      else setFailed(true);
    })().catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [pathname, fetchAuthed]);

  if (pathname === "/platform/login" || failed) return null;

  async function logout() {
    const token = localStorage.getItem("nabd_platform_access_token");
    if (token) {
      await fetch(`${API_BASE}/platform/auth/logout`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      }).catch(() => {});
    }
    clearPlatformSession();
    router.replace("/platform/login");
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

  return (
    <nav className={styles.navBar} aria-label="Platform">
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
