import styles from "../../../login/login.module.css";

// Same route-transition gap fixed for every /platform/* page — see fleet/loading.tsx.
export default function Loading() {
  return (
    <main className={styles.page}>
      <div className={styles.card}>Loading…</div>
    </main>
  );
}
