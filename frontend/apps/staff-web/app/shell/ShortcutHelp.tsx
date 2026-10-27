"use client";
// FEF-034: the '?' overlay. Lists the shortcuts that exist on the current screen for this session (registry, scope + grants + role).
import Dialog from "../components/ui/Dialog";
import styles from "./shell.module.css";
import type { Shortcut } from "./shortcuts";

export default function ShortcutHelp({ rows, onClose }: { rows: Shortcut[]; onClose: () => void }) {
  return (
    <Dialog title="Keyboard shortcuts on this screen" onClose={onClose}>
      <p className={styles.empty}>Single-key shortcuts work when you are not typing in a field.</p>
      <dl className={styles.keyList} aria-label="Shortcuts">
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
