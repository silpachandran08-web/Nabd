import Link from "next/link";
import { EmptyState } from "./StateViews";
import styles from "./ui.module.css";

// FEF-029: not-found.tsx of every route group; it renders inside that group's layout, so the right shell stays around it.
export default function RouteNotFound({ home = "/", homeLabel = "Go home" }: { home?: string; homeLabel?: string }) {
  return (
    <main className={styles.routeError}>
      <EmptyState icon="alert" title="Page not found" hint="This address does not exist, or it has moved." action={<Link href={home}>{homeLabel}</Link>} />
    </main>
  );
}
