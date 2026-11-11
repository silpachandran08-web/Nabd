"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { send } from "../../TelemetryReporter";
import { boundaryReport } from "../../lib/telemetryScrub";
import { ErrorState } from "./StateViews";
import styles from "./ui.module.css";

// FEF-029: what every route-group error.tsx renders. The layout (shell, nav) stays; only this panel failed.
// The report carries the error class, the route shape and the build id: never the message, a name or an id.
export default function RouteError({ error, reset, group }: { error: Error & { digest?: string }; reset: () => void; group: string }) {
  const pathname = usePathname();
  const t = useTranslations("shell");
  useEffect(() => { send(boundaryReport(error, pathname ?? "/", group, process.env.NEXT_PUBLIC_RELEASE)); }, [error, pathname, group]);
  return (
    <main className={styles.routeError} data-testid={`route-error-${group}`}>
      <ErrorState message={t("routeError.title")} onRetry={reset} />
      <p className={styles.stateHint}>{t("globalError.body")} <Link href="/">{t("routeError.home")}</Link></p>
    </main>
  );
}
