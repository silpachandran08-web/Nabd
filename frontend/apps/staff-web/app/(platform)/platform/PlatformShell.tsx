"use client";

import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import styles from "./platform.module.css";
import { Skeleton } from "../../components/ui/StateViews";
import RoleDenied from "../../components/platform/RoleDenied";
import { useHasMounted } from "../../lib/useHasMounted";
import { PlatformNav } from "./PlatformNav";
import { hasPlatformSession, platformMeQuery } from "./platformApi";
import { platformItemFor, platformRouteAllowed } from "./surfaces";

// FEF-059: the console shell. One /platform/auth/me (shared query) drives the nav and the direct-URL gate: a nav item's route the
// operator may not open renders the no-access state instead of the page, so its screen never mounts or fetches.
export default function PlatformShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const mounted = useHasMounted();
  const session = mounted && hasPlatformSession(); // localStorage: read after hydration only
  const me = useQuery({ ...platformMeQuery(), enabled: session });

  if (pathname === "/platform/login") return <>{children}</>;

  let content: React.ReactNode = children; // no session: the page's own call sends the visitor to login
  if (!mounted || (session && me.isPending && !me.isError)) content = <main className={styles.page}><Skeleton rows={5} label="Loading" /></main>;
  else if (me.data && !platformRouteAllowed(pathname, me.data.permissions)) {
    content = <main className={styles.page}><RoleDenied surface={platformItemFor(pathname)?.label ?? "This screen"} /></main>;
  }
  // A failed /auth/me keeps the page: the server still answers its calls with 403 and the page shows RoleDenied.

  return (
    <div className={styles.shell}>
      <PlatformNav profile={me.data ?? null} failed={me.isError} onRetry={() => void me.refetch()} />
      <div className={styles.shellContent}>{content}</div>
    </div>
  );
}
