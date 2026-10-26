"use client";

// ACC-031: the reception desk's keyboard layer. One document-level listener: F2 new walk-in, F3 search (phone or name), F4 check in
// today's appointment, F6 focus the doctor, Ctrl+Enter confirm the open form, ? the shortcut sheet in the user's language.
// Esc is the Dialog's own (back). On screens that are not the Arrivals board the three openers navigate there with ?action=.
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import Dialog from "../../ui/Dialog";
import { can } from "../../../lib/permissions";
import styles from "../arrivals.module.css";
import { deskAction, SHORTCUT_ROWS } from "./deskKeys";

export type DeskOpeners = { newWalkIn: () => void; search: () => void; checkIn: () => void };
const ACTION_PARAM = { newWalkIn: "new", search: "walkin", checkIn: "checkin" } as const;
const TEXT_FIELD = "input:not([type=checkbox]):not([type=radio]), textarea, select, [contenteditable='true']";

export default function DeskShortcuts({ openers }: { openers?: DeskOpeners }) {
  const t = useTranslations("arrivals.shortcuts");
  const router = useRouter();
  const [sheet, setSheet] = useState(false);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const el = e.target as HTMLElement | null;
      const act = deskAction(e, !!el?.closest(TEXT_FIELD));
      if (!act) return;
      const dialog = Array.from(document.querySelectorAll<HTMLElement>("[role=dialog]")).pop() ?? null; // innermost open dialog
      if (act === "help") { e.preventDefault(); setSheet(true); return; }
      if (act === "confirm") {
        const form = (el?.closest("form") ?? dialog?.querySelector("form")) as HTMLFormElement | null;
        if (form && dialog?.contains(form)) { e.preventDefault(); form.requestSubmit(); }
        return;
      }
      if (act === "changeDoctor") {
        const doc = (dialog ?? document).querySelector<HTMLElement>('[data-shortcut="doctor"]');
        if (doc) { e.preventDefault(); doc.focus(); }
        return;
      }
      // The three openers only act on a free desk and for roles that may use them (absent, not disabled).
      if (dialog) return;
      const allowed = act === "checkIn" ? can("queue:create") : can("queue:create") && can("patients:view") && (act !== "newWalkIn" || can("patients:create"));
      if (!allowed) return;
      e.preventDefault();
      if (openers) openers[act]();
      else router.push(`/arrivals?action=${ACTION_PARAM[act]}`);
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [openers, router]);

  if (!sheet) return null;
  const rows = SHORTCUT_ROWS.filter((r) => r.needs.every((p) => can(p)));
  return (
    <Dialog title={t("title")} onClose={() => setSheet(false)}>
      <p className={styles.muted}>{t("intro")}</p>
      <dl className={styles.shortcutList}>
        {rows.map((r) => (
          <div key={r.action}>
            <dt>{r.keys.map((k, i) => <span key={k}>{i > 0 && " + "}<kbd>{k}</kbd></span>)}</dt>
            <dd>{t(`items.${r.action}`)}</dd>
          </div>
        ))}
      </dl>
      <div className={styles.modalActions}>
        <button type="button" className={styles.cancelBtn} onClick={() => setSheet(false)}>{t("close")}</button>
      </div>
    </Dialog>
  );
}
