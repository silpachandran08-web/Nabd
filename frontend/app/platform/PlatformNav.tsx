"use client";

import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import styles from "./platform.module.css";
import { API_BASE, OperatorProfile, SURFACES } from "./surfaces";

// Every /platform/* page used to be a dead end: only the Command Centre
// landing page (page.tsx) rendered the surface list, so navigating into any
// one surface (Fleet, Billing, ...) left no way back except the browser's
// back button. This bar is that missing shared nav, rendered once here
// instead of copy-pasted into all eight page.tsx files.
export function PlatformNav() {
  const pathname = usePathname();
  const router = useRouter();
  const [profile, setProfile] = useState<OperatorProfile | null>(null);

  useEffect(() => {
    if (pathname === "/platform/login") return;
    const token = localStorage.getItem("nabd_platform_access_token");
    if (!token) return;
    (async () => {
      const res = await fetch(`${API_BASE}/platform/auth/me`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (res.ok) setProfile(await res.json());
    })().catch(() => {});
  }, [pathname]);

  if (pathname === "/platform/login" || !profile) return null;

  async function logout() {
    const token = localStorage.getItem("nabd_platform_access_token");
    if (token) {
      await fetch(`${API_BASE}/platform/auth/logout`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      }).catch(() => {});
    }
    localStorage.removeItem("nabd_platform_access_token");
    localStorage.removeItem("nabd_platform_refresh_token");
    router.replace("/platform/login");
  }

  const surfaces = SURFACES.filter((s) => profile.permissions.includes(s.permission));

  return (
    <nav className={styles.navBar}>
      <Link href="/platform" className={styles.navBarBrand}>Nabd Platform</Link>
      <div className={styles.navBarLinks}>
        {surfaces.map((s) => (
          <Link
            key={s.href}
            href={s.href}
            className={`${styles.navBarLink} ${pathname === s.href ? styles.navBarLinkActive : ""}`}
          >
            {s.label}
          </Link>
        ))}
      </div>
      <button className={styles.navBarLogout} onClick={logout}>Log out</button>
    </nav>
  );
}
