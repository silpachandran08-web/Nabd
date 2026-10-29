"use client";
// FEF-036: language picker for the top bar. Each language is labelled in its own script. Saving goes through PUT
// /me/preferences; the BFF mirrors the choice into the nabd_locale cookie, then a refresh re-renders <html lang dir>.
import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { LOCALES, LOCALE_LABELS, isLocale } from "@nabd/i18n";
import { api } from "./lib/api";
import styles from "./topBar.module.css";

export default function LanguagePicker() {
  const t = useTranslations("shell");
  const router = useRouter();
  const current = useLocale();
  const [failed, setFailed] = useState(false);

  async function choose(next: string) {
    if (!isLocale(next)) return;
    setFailed(false);
    try {
      const prefs = await api.get<{ uiTheme?: string; digitStyle?: string | null }>("/me/preferences");
      await api.put("/me/preferences", { uiLocale: next, uiTheme: prefs.uiTheme ?? "system", digitStyle: prefs.digitStyle ?? null });
      router.refresh();
    } catch {
      setFailed(true);
    }
  }

  return (
    <label style={{ display: "inline-flex", alignItems: "center", gap: 6, minBlockSize: 44 }}>
      <span className={styles.langLabel} style={{ fontSize: 12 }}>{t("language")}</span>
      <select value={current} onChange={(e) => void choose(e.target.value)} aria-label={t("language")} className={styles.langSelect} style={{ minBlockSize: 44 }}>
        {LOCALES.map((l) => <option key={l} value={l} lang={l}>{LOCALE_LABELS[l]}</option>)}
      </select>
      {failed && <span role="alert" style={{ fontSize: 12 }}>{t("languageSaveFailed")}</span>}
    </label>
  );
}
