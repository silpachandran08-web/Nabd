"use client";
// FEF-053/054/034: shell shortcuts from the registry (shortcuts.ts): W walk-in (reception), N call next (doctor, My Day), '?' the shortcut
// list, Ctrl+Enter submits the open dialog. Single keys are ignored while typing or while a modal is open. The desk screens
// (/arrivals, /schedule, /patients) keep their own '?' sheet and Ctrl+Enter (ACC-031).
import { useEffect, useRef, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import ShortcutHelp from "./ShortcutHelp";
import { DESK_SCREENS, isTyping, shortcutsFor } from "./shortcuts";
import { useShellNav } from "./useShellNav";
import { useNavBadges, WAITING_STATUSES } from "./useNavBadges";

export default function ShellHotkeys() {
  const router = useRouter();
  const pathname = usePathname();
  const { input, role } = useShellNav();
  const { me, mine } = useNavBadges(role, input.perms, pathname, false);
  const next = mine?.find((r) => (!r.doctorId || r.doctorId === me) && WAITING_STATUSES.includes(r.status ?? ""));
  const [help, setHelp] = useState(false);
  const state = useRef({ role, perms: input.perms, pathname, nextId: next?.id });
  useEffect(() => { state.current = { role, perms: input.perms, pathname, nextId: next?.id }; });

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const s = state.current;
      const desk = DESK_SCREENS.some((p) => s.pathname === p || s.pathname?.startsWith(`${p}/`));
      const has = (id: string) => shortcutsFor(s.pathname ?? "", s.perms, s.role).some((x) => x.id === id);
      const modal = document.querySelector('[aria-modal="true"]');
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
        const form = modal?.querySelector("form");
        if (!desk && form && has("submit")) { e.preventDefault(); form.requestSubmit(); }
        return;
      }
      if (e.ctrlKey || e.metaKey || e.altKey || e.repeat) return;
      if (isTyping(e.target as HTMLElement | null) || modal) return;
      const k = e.key.toLowerCase();
      if (e.key === "?" && !desk && has("help")) {
        e.preventDefault();
        setHelp(true);
      } else if (k === "w" && has("walkin")) {
        e.preventDefault();
        router.push("/arrivals?action=walkin");
      } else if (k === "n" && has("next") && s.nextId) {
        e.preventDefault();
        router.push(`/consult?entry=${encodeURIComponent(s.nextId)}`);
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [router]);
  return help ? <ShortcutHelp rows={shortcutsFor(pathname ?? "", input.perms, role)} onClose={() => setHelp(false)} /> : null;
}
