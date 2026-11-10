"use client";
// FEF-034: the '?' overlay. Lists the shortcuts that exist on the current screen for this session (registry, scope + grants + role).
import Dialog from "../components/ui/Dialog";
import { useTranslations } from "next-intl";
import styles from "./shell.module.css";
import type { Shortcut } from "./shortcuts";

export default function ShortcutHelp({ rows, onClose }: { rows: Shortcut[]; onClose: () => void }) {
  const t = useTranslations("shell.shortcuts");
  return (
    <Dialog title={t("title")} onClose={onClose}>
      <p className={styles.empty}>{t("hint")}</p>
      <dl className={styles.keyList} aria-label={t("list")}>
        {rows.map((r) => (
          <div key={r.id} className={styles.keyRow}>
            <dt>{r.keys.map((k, i) => <span key={k}>{i > 0 && " + "}<kbd>{k}</kbd></span>)}</dt>
            <dd>{r.label}</dd>
          </div>
        ))}
      </dl>
    </Dialog>
  );
}
