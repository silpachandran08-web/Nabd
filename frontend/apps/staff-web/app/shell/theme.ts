// FEF-025: the staff member's theme preference (System / Dark / Light). Saved in staff_preferences.ui_theme through
// /v1/me/preferences; the BFF mirrors it into the readable nabd_theme cookie, so the server renders data-theme on <html>
// and, for System, the inline pre-paint script below resolves prefers-color-scheme before the first frame.
// Pure (no React, no Next) so tests/unit loads it directly.

export const THEME_COOKIE = "nabd_theme";
export const THEME_PREFS = ["system", "dark", "light"] as const;
export type ThemePref = (typeof THEME_PREFS)[number];
export type Theme = "dark" | "light";

export const isThemePref = (v: unknown): v is ThemePref => typeof v === "string" && (THEME_PREFS as readonly string[]).includes(v);
/** Missing or unknown cookie = System (the default preference of a new user). */
export const themePrefOf = (v: string | null | undefined): ThemePref => (isThemePref(v) ? v : "system");
export const resolveTheme = (pref: ThemePref, osPrefersLight: boolean): Theme => (pref === "system" ? (osPrefersLight ? "light" : "dark") : pref);
export const themePrefFromCookies = (cookie: string): ThemePref => themePrefOf(new RegExp(`(?:^|; )${THEME_COOKIE}=([a-z]+)`).exec(cookie)?.[1]);

/** Runs synchronously in <head> (nonce'd, CSP FEF-010) before the body paints. Kept in step with resolveTheme. */
export const THEME_BOOT_SCRIPT =
  `(function(){try{var m=/(?:^|; )${THEME_COOKIE}=(dark|light)(?:;|$)/.exec(document.cookie);` +
  `document.documentElement.dataset.theme=m?m[1]:(matchMedia("(prefers-color-scheme: light)").matches?"light":"dark")}catch(e){}})()`;

/** Same attributes the BFF uses (readable, Lax, a year), so a choice survives a failed save until the next sign-in. */
export const themeCookie = (pref: ThemePref) => `${THEME_COOKIE}=${pref}; Path=/; Max-Age=${365 * 24 * 3600}; Secure; SameSite=Lax`;
