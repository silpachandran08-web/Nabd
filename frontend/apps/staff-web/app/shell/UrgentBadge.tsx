"use client";
// FEF-055: a warning badge never relies on colour: icon + count + the word "urgent" (the word is visually hidden where the nav is a rail).
import Icon from "../components/ui/Icon";
import { useTranslations } from "next-intl";
import styles from "../clinicNav.module.css";

export default function UrgentBadge({ n, className }: { n: number; className?: string }) {
  const t = useTranslations("shell.urgent");
  return (
    <span className={`${className ?? ""} ${styles.urgent}`} data-urgent-badge>
      <Icon name="alert" size={12} />{n}<span className={styles.urgentText}>{t("word")}</span>
    </span>
  );
}
