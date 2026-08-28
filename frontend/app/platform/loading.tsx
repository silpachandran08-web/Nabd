import styles from "./platform.module.css";

// See fleet/loading.tsx for why this exists — covers the same gap right after a successful
// PIN login, before Command Centre's own JS chunk finishes loading.
export default function Loading() {
  return (
    <main className={styles.page}>
      <div className={styles.state}>Loading…</div>
    </main>
  );
}
