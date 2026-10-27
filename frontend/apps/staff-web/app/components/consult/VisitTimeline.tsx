"use client";

import { useTranslations } from "next-intl";
import Icon, { type IconName } from "../ui/Icon";
import type { TimelineEvent, TimelineKind } from "@nabd/domain";
import s from "./consultParts.module.css";

const ICON: Record<TimelineKind, IconName> = { ok: "check", warn: "alert", info: "info", sync: "sync" };

// buildTimeline() (shared domain code) emits English labels; they map to catalogue keys here. Unknown text is shown as given.
const LABEL: Record<string, string> = {
  "Checked in": "event.checkedIn", "Vitals recorded": "event.vitalsRecorded", "Consultation started": "event.consultStarted", "Draft saved": "event.draftSaved",
  "Note signed": "event.noteSigned", "Visit completed": "event.visitCompleted", "Sent to checkout": "event.sentToCheckout",
  "Transferred to another department": "event.transferred", "Amendment added": "event.amendmentAdded",
};
const SUB: Record<string, string> = { "not recorded yet": "sub.notRecordedYet", "status unavailable": "sub.statusUnavailable", "on this device · waiting to sync": "sub.onDeviceWaiting" };
export const TIMELINE_TEXT = { LABEL, SUB };

const time = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

/** Visit timeline (DOC-025): pinned in the workspace on every step, built by buildTimeline(). */
export default function VisitTimeline({ events }: { events: TimelineEvent[] }) {
  const t = useTranslations("consult.visitTimeline");
  const g = useTranslations("glossary");
  const label = (e: TimelineEvent) => (e.id === "vitals" && e.label === "Vitals" ? g("vitals.label") : LABEL[e.label] ? t(LABEL[e.label]) : e.label);
  return (
    <section className={s.card} aria-label={t("visitTimeline")}>
      <h3 className={s.cardTitle}><Icon name="clock" size={14} /> {" "}{t("visitTimeline")}</h3>
      <ol className={s.timeline}>
        {events.map((e) => (
          <li key={e.id}>
            <span className={`${s.timelineDot} ${e.kind === "warn" ? s.timelineDotWarn : e.kind === "ok" ? s.timelineDotOk : ""}`}><Icon name={ICON[e.kind]} size={14} /></span>
            <span className={s.timelineText}>
              <span>{label(e)}</span>
              {e.sub && <span className={s.muted}>{SUB[e.sub] ? t(SUB[e.sub]) : e.sub}</span>}
            </span>
            {e.at && <time className={s.timelineAt} dateTime={e.at}>{time(e.at)}</time>}
          </li>
        ))}
      </ol>
    </section>
  );
}
