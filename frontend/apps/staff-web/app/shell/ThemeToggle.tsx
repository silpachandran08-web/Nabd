"use client";
// FEF-052: dark/light switch (tokens already define :root[data-theme="light"]); a per-viewer preference in localStorage.
import { useEffect, useState } from "react";
import styles from "./shell.module.css";
import Icon from "../components/ui/Icon";

const KEY = "nabd_theme";
export default function ThemeToggle() {
  const [light, setLight] = useState(false);
  useEffect(() => {
    let v = false;
    try { v = localStorage.getItem(KEY) === "light"; } catch { /* default dark */ }
    document.documentElement.dataset.theme = v ? "light" : "dark";
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLight(v);
  }, []);
  function toggle() {
    const next = !light;
    setLight(next);
    document.documentElement.dataset.theme = next ? "light" : "dark";
    try { localStorage.setItem(KEY, next ? "light" : "dark"); } catch { /* not persisted */ }
  }
  return (
    <button type="button" className={`${styles.iconBtn} ${styles.themeBtn}`} onClick={toggle} aria-pressed={light} aria-label="Light theme" title="Light theme">
      <Icon name={light ? "sun" : "sparkle"} size={16} />
    </button>
  );
}
