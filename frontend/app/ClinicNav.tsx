"use client";

import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import Link from "next/link";
import styles from "./clinicNav.module.css";
import { getPermissions, SHELL_SKIP_PREFIXES } from "./lib/session";

// DESIGN.md §4/§8: 248px left sidebar, one item per accessible module. Every clinic page
// (arrivals, patients, ...) was built standalone with no shared shell — this is that missing
// nav, mounted once in layout.tsx (same pattern as /platform/PlatformNav.tsx), permission-gated
// off the JWT's own "permissions" claim so each role only sees what it can open.
const NAV_ITEMS: { href: string; label: string; permission: string | null }[] = [
  { href: "/arrivals", label: "Today · Arrivals", permission: "queue:view" },
  { href: "/nursing", label: "Nursing Worklist", permission: "nursing:view" },
  { href: "/patients", label: "Patients", permission: "patients:view" },
  { href: "/consult", label: "Consultation Workspace", permission: "clinical:view" },
  { href: "/packages", label: "Treatment Packages", permission: "packages:view" },
  { href: "/staff", label: "Staff & Access", permission: "staff:view" },
  { href: "/reports", label: "Owner Insights", permission: "reports:view" },
  { href: "/setup", label: "Setup", permission: "setup:view" },
  { href: "/account", label: "My account", permission: null },
];

export default function ClinicNav() {
  const pathname = usePathname();
  const skip = SHELL_SKIP_PREFIXES.some((p) => pathname?.startsWith(p));
  const [permissions, setPermissions] = useState<string[] | null>(null);

  // Hydration-safe, same reasoning as LogoutButton: render nothing until this mounts and can
  // read localStorage, then reflect it — never read it directly during render.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPermissions(skip ? [] : getPermissions());
  }, [pathname, skip]);

  if (skip || !permissions || permissions.length === 0) return null;

  const items = NAV_ITEMS.filter((item) => !item.permission || permissions.includes(item.permission));
  if (items.length === 0) return null;

  return (
    <nav className={styles.nav}>
      <div className={styles.brand}>nabd</div>
      <div className={styles.links}>
        {items.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={`${styles.link} ${pathname?.startsWith(item.href) ? styles.linkActive : ""}`}
          >
            {item.label}
          </Link>
        ))}
      </div>
    </nav>
  );
}
