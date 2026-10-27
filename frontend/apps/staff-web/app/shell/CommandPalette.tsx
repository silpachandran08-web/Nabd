"use client";
// FEF-052/034: Ctrl/Cmd+K. One list: the actions this role may run, the screens it may open, and patients found by name, phone or MRN.
// Registry-driven, so anything the role lacks is never listed. Opens synchronously on the key press (no fetch before first paint).
import { useEffect, useMemo, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import styles from "./shell.module.css";
import Icon, { type IconName } from "../components/ui/Icon";
import Dialog from "../components/ui/Dialog";
import { usePatientSearch } from "../lib/usePatientSearch";
import { jumpTargets } from "./navRegistry";
import { actionTargets } from "./paletteActions";
import { useShellNav } from "./useShellNav";
import { useNavBadges, WAITING_STATUSES } from "./useNavBadges";

type Row = { key: string; href: string; label: string; sub: string; icon: IconName; hint?: string };

export default function CommandPalette() {
  const router = useRouter();
  const pathname = usePathname();
  const { input, role } = useShellNav();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const hasSession = input.perms.length > 0;
  const { me, mine } = useNavBadges(role, input.perms, pathname, false);
  const next = mine?.find((r) => (!r.doctorId || r.doctorId === me) && WAITING_STATUSES.includes(r.status ?? ""));
  const canFindPatients = input.perms.includes("patients:view");
  const typed = q.trim();
  const { results: patients, failed: searchFailed } = usePatientSearch(canFindPatients && typed.length >= 3 ? typed : "", 5); // the API refuses < 3 characters

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

  const rows = useMemo<Row[]>(() => {
    const out: Row[] = actionTargets(input.perms, role, q, next ? `/consult?entry=${encodeURIComponent(next.id ?? "")}` : null)
      .map((a) => ({ key: `a-${a.id}`, href: a.href, label: a.label, sub: "Action", icon: "sparkle", hint: a.hint }));
    for (const i of jumpTargets(input, q)) out.push({ key: i.id + i.href, href: i.href, label: i.label, sub: i.owner, icon: i.icon });
    const canCheckIn = input.perms.includes("queue:create");
    for (const p of patients) {
      const sub = [p.mrn, p.phone].filter(Boolean).join(" · ");
      const find = encodeURIComponent(p.mrn || p.phone || p.name || "");
      if (canCheckIn) out.push({ key: `ci-${p.id}`, href: `/arrivals?action=walkin&q=${find}`, label: `Check in ${p.name}`, sub, icon: "users" });
      out.push({ key: `p-${p.id}`, href: `/patients?q=${find}`, label: `Open ${p.name}`, sub, icon: "user" });
    }
    return out;
  }, [input, role, q, next, patients]);

  const at = Math.min(sel, Math.max(0, rows.length - 1));
  function go(href: string) { setOpen(false); router.push(href); }
  if (!open || !hasSession) return null;

  return (
    <Dialog title="Command palette" onClose={() => setOpen(false)}>
      <div className={styles.palette}>
        <input className={styles.paletteInput} autoFocus role="combobox" aria-expanded="true" aria-controls="nb-jump-list" aria-label="Jump to a screen, action or patient"
          aria-activedescendant={rows[at] ? `nb-jump-${at}` : undefined} placeholder="Search patients (name, phone, MRN), actions, screens…" value={q}
          onChange={(e) => { setQ(e.target.value); setSel(0); }}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") { e.preventDefault(); setSel(Math.min(at + 1, rows.length - 1)); }
            else if (e.key === "ArrowUp") { e.preventDefault(); setSel(Math.max(at - 1, 0)); }
            else if (e.key === "Enter" && rows[at]) { e.preventDefault(); go(rows[at].href); }
          }} />
        {searchFailed && <div className={styles.empty} role="alert">Couldn&apos;t search patients. Actions and screens still work.</div>}
        {rows.length === 0 ? <div className={styles.empty} role="status">Nothing matches &ldquo;{q}&rdquo;.</div> : (
          <ul id="nb-jump-list" className={styles.results} role="listbox" aria-label="Results">
            {rows.map((r, i) => (
              <li key={r.key} id={`nb-jump-${i}`} role="option" aria-selected={i === at} className={styles.result} onMouseEnter={() => setSel(i)} onClick={() => go(r.href)}>
                <Icon name={r.icon} size={16} />{r.label}<span className={styles.resultSub}>{r.hint ? <kbd>{r.hint}</kbd> : r.sub}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Dialog>
  );
}
