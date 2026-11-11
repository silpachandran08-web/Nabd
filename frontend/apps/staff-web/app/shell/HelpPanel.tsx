"use client";
// FEF-062: help for the current screen (F1 anywhere, or Help in the user menu via the "nabd:help" event). Content is keyed by
// screen id in the `help` catalogue (en + hi); the video link is config (help.ts HELP_VIDEOS) and is absent until supplied.
// ACC-035: replay of the role's tour from Help, and for reception the competency check with the desk task videos.
import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import Dialog from "../components/ui/Dialog";
import styles from "./shell.module.css";
import { HELP_VIDEOS, isTourRole, screenIdFor } from "./help";
import { useShellNav } from "./useShellNav";
import { TOUR_EVENT } from "./TourCard";
import CompetencyChecklist from "./CompetencyChecklist";

export const HELP_EVENT = "nabd:help";

export default function HelpPanel() {
  const t = useTranslations("help");
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const { role } = useShellNav();
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "F1" && !e.ctrlKey && !e.metaKey && !e.altKey) { e.preventDefault(); setOpen(true); } };
    const onEvent = () => setOpen(true);
    document.addEventListener("keydown", onKey);
    document.addEventListener(HELP_EVENT, onEvent);
    return () => { document.removeEventListener("keydown", onKey); document.removeEventListener(HELP_EVENT, onEvent); };
  }, []);
  if (!open) return null;
  const id = screenIdFor(pathname);
  const video = HELP_VIDEOS[id];
  return (
    <Dialog title={t("panel.title", { screen: t(`screens.${id}.title`) })} onClose={() => setOpen(false)}>
      <div className={styles.helpBody} data-testid="help-panel" data-screen={id}>
        {(["p1", "p2", "p3"] as const).filter((p) => t.has(`screens.${id}.${p}`)).map((p) => <p key={p}>{t(`screens.${id}.${p}`)}</p>)}
        {video ? <a href={video} target="_blank" rel="noreferrer" className={styles.helpLink}>{t("panel.video")}</a> : <p className={styles.empty}>{t("panel.videoMissing")}</p>}
        <p className={styles.empty}>{t("panel.shortcut")}</p>
        {isTourRole(role) && (
          <button type="button" className={styles.chip} onClick={() => { setOpen(false); document.dispatchEvent(new Event(TOUR_EVENT)); }}>{t("menu.replayTour")}</button>
        )}
        {role === "reception" && <CompetencyChecklist />}
      </div>
    </Dialog>
  );
}
