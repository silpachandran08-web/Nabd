import Icon, { type IconName } from "./Icon";
import styles from "./ui.module.css";

// DESIGN.md §8: a status is never colour alone — every pill carries an icon (shape cue) and text.
export type PillTone = "ok" | "warn" | "danger" | "info" | "sync" | "urgent" | "neutral";

const DEFAULT_ICON: Record<PillTone, IconName> = {
  ok: "check",
  warn: "clock",
  danger: "alert",
  info: "info",
  sync: "sync",
  urgent: "alert",
  neutral: "list",
};

export default function StatusPill({ tone = "neutral", label, icon }: { tone?: PillTone; label: string; icon?: IconName }) {
  return (
    <span className={`${styles.pill} ${styles[`pill_${tone}`]}`}>
      <Icon name={icon ?? DEFAULT_ICON[tone]} size={12} />
      {label}
    </span>
  );
}
