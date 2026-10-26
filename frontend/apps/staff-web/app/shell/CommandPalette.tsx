"use client";
// FEF-052: Ctrl/Cmd+K jumps to any screen the user may open (registry-driven, so a screen the role lacks is never listed).
import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./shell.module.css";
import Icon from "../components/ui/Icon";
import Dialog from "../components/ui/Dialog";
import { jumpTargets } from "./navRegistry";
import { useShellNav } from "./useShellNav";

export default function CommandPalette() {
  const router = useRouter();
  const { input } = useShellNav();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const hasSession = input.perms.length > 0;

  useEffect(() => {
    if (!hasSession) return;
    function onKey(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setQ(""); setSel(0); setOpen(true);
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [hasSession]);

  const rows = useMemo(() => {
    const screens = jumpTargets(input, q).map((i) => ({ key: i.id + i.href, href: i.href, label: i.label, sub: i.owner, icon: i.icon }));
    const text = q.trim();
    if (text && input.perms.includes("patients:view")) screens.push({ key: "patient-search", href: `/patients?q=${encodeURIComponent(text)}`, label: `Search patients for "${text}"`, sub: "Patients", icon: "users" });
    return screens;
  }, [input, q]);

  function close() { setOpen(false); }
  function go(href: string) { setOpen(false); router.push(href); }
  if (!open || !hasSession) return null;

  return (
    <Dialog title="Jump to a screen" onClose={close}>
      <div className={styles.palette}>
        <input className={styles.paletteInput} autoFocus role="combobox" aria-expanded="true" aria-controls="nb-jump-list" aria-label="Jump to a screen"
          aria-activedescendant={rows[sel] ? `nb-jump-${sel}` : undefined} placeholder="Jump to a screen…" value={q}
          onChange={(e) => { setQ(e.target.value); setSel(0); }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(s + 1, rows.length - 1)); }
            else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
            else if (e.key === "Enter" && rows[sel]) { e.preventDefault(); go(rows[sel].href); }
          }} />
        {rows.length === 0 ? <div className={styles.empty} role="status">No screen matches &ldquo;{q}&rdquo;.</div> : (
          <ul id="nb-jump-list" className={styles.results} role="listbox" aria-label="Screens">
            {rows.map((r, i) => (
              <li key={r.key} id={`nb-jump-${i}`} role="option" aria-selected={i === sel} className={styles.result} onMouseEnter={() => setSel(i)} onClick={() => go(r.href)}>
                <Icon name={r.icon} size={16} />{r.label}<span className={styles.resultSub}>{r.sub}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Dialog>
  );
}
