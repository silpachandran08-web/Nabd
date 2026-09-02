import styles from "./departments.module.css";

// Same route-transition gap fixed for every /platform/* page — see fleet/loading.tsx.
export default function Loading() {
  return (
    <main className={styles.page}>
      <div className={styles.state}>Loading…</div>
    </main>
  );
}
