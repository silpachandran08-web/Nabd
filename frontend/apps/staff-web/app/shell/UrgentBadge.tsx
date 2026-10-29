"use client";
// FEF-055: a warning badge never relies on colour: icon + count + the word "urgent" (the word is visually hidden where the nav is a rail).
import Icon from "../components/ui/Icon";
import styles from "../clinicNav.module.css";

export default function UrgentBadge({ n, className }: { n: number; className?: string }) {
  return (
    <span className={`${className ?? ""} ${styles.urgent}`} data-urgent-badge>
      <Icon name="alert" size={12} />{n}<span className={styles.urgentText}> urgent</span>
    </span>
  );
}
