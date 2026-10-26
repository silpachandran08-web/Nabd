"use client";

import { useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { send } from "../../TelemetryReporter";
import { boundaryReport } from "../../lib/telemetryScrub";
import { ErrorState } from "./StateViews";
import styles from "./ui.module.css";

// FEF-029: what every route-group error.tsx renders. The layout (shell, nav) stays; only this panel failed.
// The report carries the error class, the route shape and the build id: never the message, a name or an id.
export default function RouteError({ error, reset, group }: { error: Error & { digest?: string }; reset: () => void; group: string }) {
  const pathname = usePathname();
  useEffect(() => { send(boundaryReport(error, pathname ?? "/", group, process.env.NEXT_PUBLIC_RELEASE)); }, [error, pathname, group]);
  return (
    <main className={styles.routeError} data-testid={`route-error-${group}`}>
      <ErrorState message="This panel failed" onRetry={reset} />
      <p className={styles.stateHint}>Nothing you typed was lost: drafts stay on this device. <Link href="/">Go home</Link></p>
    </main>
  );
}
