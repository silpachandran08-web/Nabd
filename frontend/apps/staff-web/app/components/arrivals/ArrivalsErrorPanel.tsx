"use client";

import styles from "./arrivals.module.css";

// One recover panel for both error.tsx (whole route) and the dialog boundary (board stays usable).
export default function ArrivalsErrorPanel({ title, message, actionLabel, onRecover }: { title: string; message: string; actionLabel: string; onRecover: () => void }) {
  return (
    <div className={styles.formError} role="alert">
      <strong>{title}</strong> {message}{" "}
      <button type="button" className={styles.actionBtn} onClick={onRecover}>{actionLabel}</button>
    </div>
  );
}
