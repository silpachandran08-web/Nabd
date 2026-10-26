import type { ReactNode } from "react";
import Icon, { type IconName } from "./Icon";
import styles from "./ui.module.css";

// The three non-happy states every list/detail needs (DESIGN.md §8 loading / empty / error).
export function Skeleton({ rows = 4, label = "Loading" }: { rows?: number; label?: string }) {
  return (
    <div className={styles.skeletonWrap} role="status" aria-busy="true" aria-label={label}>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className={styles.skeletonRow} />
      ))}
    </div>
  );
}

export function EmptyState({ title, hint, icon = "inbox", action }: { title: string; hint?: string; icon?: IconName; action?: ReactNode }) {
  return (
    <div className={styles.state}>
      <Icon name={icon} size={28} />
      <div className={styles.stateTitle}>{title}</div>
      {hint && <div className={styles.stateHint}>{hint}</div>}
      {action}
    </div>
  );
}

export function ErrorState({ message, onRetry, retryLabel = "Retry" }: { message: string; onRetry?: () => void; retryLabel?: string }) {
  return (
    <div className={`${styles.state} ${styles.stateError}`} role="alert">
      <Icon name="alert" size={28} />
      <div className={styles.stateTitle}>{message}</div>
      {onRetry && (
        <button type="button" className={styles.stateBtn} onClick={onRetry}>
          <Icon name="refresh" size={14} /> {retryLabel}
        </button>
      )}
    </div>
  );
}
