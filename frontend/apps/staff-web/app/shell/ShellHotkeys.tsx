"use client";
// FEF-053/054: role shortcuts, active only when the user is not typing: W = walk-in (reception), N on My Day = Call next (doctor).
import { useEffect, useRef } from "react";
import { usePathname, useRouter } from "next/navigation";
import { MY_DAY_HREF } from "./navRegistry";
import { useShellNav } from "./useShellNav";
import { useNavBadges, WAITING_STATUSES } from "./useNavBadges";

export default function ShellHotkeys() {
  const router = useRouter();
  const pathname = usePathname();
  const { skip, input, role } = useShellNav();
  const { me, mine } = useNavBadges(skip ? null : role, input.perms, pathname);
  const next = mine?.find((r) => (!r.doctorId || r.doctorId === me) && WAITING_STATUSES.includes(r.status ?? ""));
  const state = useRef({ role, perms: input.perms, pathname, nextId: next?.id });
  useEffect(() => { state.current = { role, perms: input.perms, pathname, nextId: next?.id }; });

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.ctrlKey || e.metaKey || e.altKey || e.repeat) return;
      const t = e.target as HTMLElement | null;
      if (t?.tagName === "INPUT" || t?.tagName === "TEXTAREA" || t?.tagName === "SELECT" || t?.isContentEditable) return;
      if (document.querySelector('[aria-modal="true"]')) return; // a modal is open
      const s = state.current;
      const k = e.key.toLowerCase();
      if (k === "w" && s.role === "reception" && s.perms.includes("queue:create") && s.perms.includes("patients:view")) {
        e.preventDefault();
        router.push("/arrivals?action=walkin");
      } else if (k === "n" && s.role === "doctor" && s.pathname === MY_DAY_HREF && s.nextId) {
        e.preventDefault();
        router.push(`/consult?entry=${encodeURIComponent(s.nextId)}`);
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [router]);
  return null;
}
