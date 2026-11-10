"use client";
// FEF-025: System / Dark / Light. Applied at once (no reload, no network wait), then saved to /me/preferences, whose BFF
// answer refreshes the nabd_theme cookie. The cookie is also written here so a failed save still holds on this machine.
import { useLayoutEffect, useState } from "react";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { api } from "../lib/api";
import Icon from "../components/ui/Icon";
import styles from "./shell.module.css";
import { THEME_PREFS, resolveTheme, themeCookie, themePrefFromCookies, type ThemePref } from "./theme";

const LIGHT_MQ = "(prefers-color-scheme: light)";
const applyTheme = (pref: ThemePref) => { document.documentElement.dataset.theme = resolveTheme(pref, matchMedia(LIGHT_MQ).matches); };
const keepOnThisMachine = (pref: ThemePref) => { document.cookie = themeCookie(pref); applyTheme(pref); };

/** Mounted once in the root layout: re-applies the cookie after client navigations (sign-in lands without a reload) and follows live OS changes for System. */
export function ThemeBoot() {
  const pathname = usePathname();
  useLayoutEffect(() => {
    const mq = matchMedia(LIGHT_MQ);
    const sync = () => applyTheme(themePrefFromCookies(document.cookie));
    sync();
    mq.addEventListener("change", sync);
    return () => mq.removeEventListener("change", sync);
  }, [pathname]);
  return null;
}

export default function ThemeSwitch({ variant }: { variant: "menu" | "page" }) {
  const t = useTranslations("shell");
  const [pref, setPref] = useState<ThemePref>("system");
  const [failed, setFailed] = useState(false);
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useLayoutEffect(() => setPref(themePrefFromCookies(document.cookie)), []);

  async function choose(next: ThemePref) {
    setPref(next);
    setFailed(false);
    keepOnThisMachine(next);
    try {
      const prefs = await api.get<{ uiLocale?: string | null; digitStyle?: string | null }>("/me/preferences");
      await api.put("/me/preferences", { uiLocale: prefs.uiLocale ?? null, uiTheme: next, digitStyle: prefs.digitStyle ?? null });
    } catch {
      setFailed(true);
    }
  }

  const label = (p: ThemePref) => t(p === "system" ? "themeSystem" : p === "dark" ? "themeDark" : "themeLight");
  const error = failed && <span role="alert" className={styles.menuHead}>{t("themeSaveFailed")}</span>;
  if (variant === "menu") {
    return (
      <div role="group" aria-label={t("theme")}>
        <div className={styles.menuHead}>{t("theme")}</div>
        {THEME_PREFS.map((p) => (
          <button key={p} type="button" role="menuitemradio" aria-checked={p === pref} className={styles.menuItem} onClick={() => void choose(p)}>
            {label(p)}{p === pref && <Icon name="check" size={14} />}
          </button>
        ))}
        {error}
      </div>
    );
  }
  return (
    <fieldset className={styles.themeFieldset}>
      <legend>{t("theme")}</legend>
      {THEME_PREFS.map((p) => (
        <label key={p} className={styles.themeOption}>
          <input type="radio" name="nb-theme" value={p} checked={p === pref} onChange={() => void choose(p)} />
          {label(p)}
        </label>
      ))}
      {error}
    </fieldset>
  );
}
