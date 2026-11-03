"use client";

// FEF-032: the Clinic Setup frame (title, wizard buttons, tab bar). Each tab is its own route (/setup/charges,
// /setup/licences …), so a tab's code loads only when that tab is opened. The wizard, Departments and Booking rules pages
// are not tabs: they render without this frame and keep their own gates, as before.
import { Suspense, useEffect, useState, type ReactNode } from "react";
import { usePathname, useRouter, useSearchParams, useSelectedLayoutSegment } from "next/navigation";
import styles from "./setup.module.css";
import AccessGate from "../../components/ui/AccessGate";
import { can } from "../../lib/permissions";
import { TABS, isSetupTab, isTabRoute, tabHref } from "./setupTabs";

export default function SetupLayout({ children }: { children: ReactNode }) {
  const segment = useSelectedLayoutSegment();
  if (segment !== null && !isTabRoute(segment)) return <>{children}</>;
  return (
    <AccessGate permission="setup:view" surface="Clinic Setup">
      <main className={styles.page}>
        <div className={styles.header}>
          <div>
            <h1 className={styles.title}>Clinic Setup</h1>
            <p className={styles.subtitle}>Configure the clinic before going live.</p>
          </div>
          <HeaderActions />
        </div>
        {/* ?tab= deep links read useSearchParams, which needs a Suspense boundary for static prerender. */}
        <Suspense fallback={null}>
          <SetupTabs />
        </Suspense>
        {children}
      </main>
    </AccessGate>
  );
}

function HeaderActions() {
  const router = useRouter();
  return (
    <div className={styles.headerActions}>
      {can("queue:view") && <button className={`${styles.wizardBtn} ${styles.headerBtn}`} onClick={() => router.push("/setup/workflows")}>Booking rules</button>}
      <button className={`${styles.wizardBtn} ${styles.headerBtn}`} onClick={() => router.push("/setup/wizard")}>Open setup wizard</button>
    </div>
  );
}

/** The URL is the tab's only state: sidebar links and tab clicks both just change the route, so the sidebar's active item always matches what's on screen. */
function SetupTabs() {
  const router = useRouter();
  const pathname = usePathname();
  const tabParam = useSearchParams().get("tab");
  const segment = pathname.split("/")[2] ?? null;
  const tab = pathname === "/setup" ? (isSetupTab(tabParam) ? tabParam : "checklist") : segment;
  const [canPharmacy, setCanPharmacy] = useState(false);
  useEffect(() => {
    void Promise.resolve().then(() => setCanPharmacy(can("pharmacy:view")));
  }, []);
  return (
    <div className={styles.tabs}>
      {TABS.filter((t) => t.key !== "pharmacy" || canPharmacy).map((t) => (
        <button key={t.key} className={tab === t.key ? styles.tabActive : styles.tab} onClick={() => router.replace(tabHref(t.key), { scroll: false })}>
          {t.label}
        </button>
      ))}
    </div>
  );
}
