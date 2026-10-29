import styles from "../platform.module.css";
import { EmptyState } from "../../../components/ui/StateViews";

// FEF-059 route skeleton for platform.health (owned by Data & Infra). The shell's gate keeps it unreachable until the item is
// entitled and permitted; the owning squad replaces this placeholder with the screen.
export default function PlatformHealthPage() {
  return <main className={styles.page}><EmptyState icon="info" title="Platform health is not available yet." hint="This screen is being built by Data & Infra." /></main>;
}
