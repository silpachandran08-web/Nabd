import { Skeleton } from "../../../components/ui/StateViews";
import styles from "./audit.module.css";

// See fleet/loading.tsx for why this exists — same route-transition gap, every /platform/* page.
export default function Loading() {
  return (
    <main className={styles.page}>
      <Skeleton rows={6} label="Loading" />
    </main>
  );
}
