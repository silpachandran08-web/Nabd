"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import styles from "./platform.module.css";

// Matches GET /v1/platform/auth/me (PlatformAuthController) — permissions
// already come back as "<surface>:view" strings from PlatformPermissions.
type OperatorProfile = {
  id: string;
  name: string;
  email: string;
  role: string;
  permissions: string[];
};

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

// Only surfaces with a built page get a nav entry here — SSA-02's "no access,
// no button" rule extends to "no page yet, no link yet" for the rest.
const SURFACES: { permission: string; label: string; href: string }[] = [
  { permission: "clinics_fleet:view", label: "Clinic fleet", href: "/platform/fleet" },
  { permission: "billing_revenue:view", label: "Billing & Revenue", href: "/platform/billing" },
  { permission: "pricing_packaging:view", label: "Pricing & Packaging", href: "/platform/plans" },
  { permission: "territories:view", label: "Territories", href: "/platform/territories" },
  { permission: "support_tickets:view", label: "Support tickets", href: "/platform/support" },
  { permission: "support_access:view", label: "Support access", href: "/platform/access" },
  { permission: "audit_compliance:view", label: "Audit log", href: "/platform/audit" },
];

const ROLE_LABELS: Record<string, string> = {
  super_admin: "Super Admin",
  implementation: "Implementation",
  support_engineer: "Support Engineer",
  billing: "Billing",
  sre: "SRE",
  commercial: "Commercial",
  compliance_dpo: "Compliance / DPO",
};

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
