import styles from "./audit.module.css";

// See fleet/loading.tsx for why this exists — same route-transition gap, every /platform/* page.
export default function Loading() {
  return (
    <main className={styles.page}>
      <div className={styles.state}>Loading…</div>
    </main>
  );
}
