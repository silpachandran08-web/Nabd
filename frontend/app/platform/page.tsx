"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import styles from "./platform.module.css";
import { API_BASE, OperatorProfile, ROLE_LABELS, SURFACES } from "./surfaces";

export default function PlatformHomePage() {
  const router = useRouter();
  const [profile, setProfile] = useState<OperatorProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const clearSessionAndRedirect = useCallback(() => {
    localStorage.removeItem("nabd_platform_access_token");
    localStorage.removeItem("nabd_platform_refresh_token");
    router.replace("/platform/login");
  }, [router]);

  useEffect(() => {
    const token = localStorage.getItem("nabd_platform_access_token");
    if (!token) {
      router.replace("/platform/login");
      return;
    }
    (async () => {
      try {
        const res = await fetch(`${API_BASE}/platform/auth/me`, {
          headers: { Authorization: `Bearer ${token}` },
        });
        if (res.status === 401) {
          clearSessionAndRedirect();
          return;
        }
        if (!res.ok) {
          setError("Couldn't load your profile. Try again.");
          return;
        }
        setProfile(await res.json());
      } catch {
        setError("Couldn't reach the server. Check your connection and try again.");
      } finally {
        setLoading(false);
      }
    })();
  }, [router, clearSessionAndRedirect]);

  async function logout() {
    const token = localStorage.getItem("nabd_platform_access_token");
    if (token) {
      await fetch(`${API_BASE}/platform/auth/logout`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      }).catch(() => {});
    }
    clearSessionAndRedirect();
  }

  if (loading) {
    return (
      <main className={styles.page}>
        <div className={styles.state}>Loading…</div>
      </main>
    );
  }

  if (error || !profile) {
    return (
      <main className={styles.page}>
        <div className={styles.errorState}>{error ?? "Something went wrong."}</div>
      </main>
    );
  }

  const surfaces = SURFACES.filter((s) => profile.permissions.includes(s.permission));

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Command Centre</h1>
          <p className={styles.subtitle}>
            Signed in as {profile.name} · {ROLE_LABELS[profile.role] ?? profile.role}
          </p>
        </div>
        <button className={styles.logout} onClick={logout}>Log out</button>
      </div>

      <div className={styles.card}>
        {surfaces.length === 0 ? (
          <div className={styles.state}>Nothing else is available to your role yet.</div>
        ) : (
          <ul className={styles.navList}>
            {surfaces.map((s) => (
              <li key={s.href}>
                <Link className={styles.navLink} href={s.href}>{s.label}</Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </main>
  );
}
