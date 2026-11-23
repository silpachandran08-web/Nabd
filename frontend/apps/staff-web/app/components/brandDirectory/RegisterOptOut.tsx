"use client";

// PLT-024: the registration form's 'do not show me at other branches' choice. Absent unless the brand shares demographics.
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { api } from "../../lib/api";
import styles from "./brandDirectory.module.css";

export default function RegisterOptOut({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  const t = useTranslations("shell.brandDirectory");
  const [enabled, setEnabled] = useState(false);
  useEffect(() => {
    api.get<{ enabled: boolean }>("/patients/brand-directory/status").then((r) => setEnabled(r.enabled)).catch(() => setEnabled(false));
  }, []);
  if (!enabled) return null;
  return (
    <label className={styles.check}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>{t("registerOptOut")}</span>
    </label>
  );
}
