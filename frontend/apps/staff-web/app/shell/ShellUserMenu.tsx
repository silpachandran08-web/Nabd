"use client";
// FEF-052: top-bar user menu: who is signed in, the active role context (switchable without signing out), sign out.
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import styles from "./shell.module.css";
import Icon from "../components/ui/Icon";
import { getIdentity, signOut } from "../lib/session";
import { homeFor, ROLE_LABEL, type RoleContext } from "./navRegistry";
import { setRoleContext } from "./roleContext";
import { useShellNav } from "./useShellNav";
import { HELP_EVENT } from "./HelpPanel";
import { TOUR_EVENT } from "./TourCard";
import { isTourRole } from "./help";

export default function ShellUserMenu() {
  const router = useRouter();
  const th = useTranslations("help");
  const { input, role, roles, owner } = useShellNav();
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("keydown", k);
    return () => document.removeEventListener("keydown", k);
  }, [open]);
  if (input.perms.length === 0) return null;
  const who = getIdentity();

  function pick(r: RoleContext) {
    setOpen(false);
    if (r === role) return;
    setRoleContext(r); // no re-login: the grants in the token already cover both contexts
    router.push(homeFor({ ...input, override: r }));
  }
  async function out() { await signOut(); router.replace(owner ? "/owner/login" : "/login"); }

  return (
    <div className={styles.wrap}>
      <button type="button" className={styles.chip} aria-haspopup="menu" aria-expanded={open} aria-label={`Account menu, active role ${ROLE_LABEL[role]}`} onClick={() => setOpen((v) => !v)}>
        <span className={styles.chipIcon}><Icon name="user" size={16} /></span><span data-testid="active-role">{ROLE_LABEL[role]}</span>
      </button>
      {open && (
        <>
          <div className={styles.backdrop} onClick={() => setOpen(false)} />
          <div className={styles.menu} role="menu" aria-label="Account">
            {who && <div className={styles.menuHead}><bdi>{who.staffName}</bdi></div>}
            {roles.length > 1 && roles.map((r) => (
              <button key={r} type="button" role="menuitemradio" aria-checked={r === role} className={styles.menuItem} onClick={() => pick(r)}>
                {ROLE_LABEL[r]} context{r === role && <Icon name="check" size={14} />}
              </button>
            ))}
            {/* FEF-062: help for this screen (also F1) and, for roles with a first-run tour, its replay */}
            <button type="button" role="menuitem" className={styles.menuItem} onClick={() => { setOpen(false); document.dispatchEvent(new Event(HELP_EVENT)); }}>{th("menu.help")}<kbd>F1</kbd></button>
            {isTourRole(role) && <button type="button" role="menuitem" className={styles.menuItem} onClick={() => { setOpen(false); document.dispatchEvent(new Event(TOUR_EVENT)); }}>{th("menu.replayTour")}</button>}
            <button type="button" role="menuitem" className={styles.menuItem} onClick={() => void out()}>Sign out</button>
          </div>
        </>
      )}
    </div>
  );
}
