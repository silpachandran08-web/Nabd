"use client";

import Icon, { type IconName } from "../ui/Icon";
import type { TimelineEvent, TimelineKind } from "./consultFlow";
import s from "./consultParts.module.css";

const ICON: Record<TimelineKind, IconName> = { ok: "check", warn: "alert", info: "info", sync: "sync" };

const time = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

/** Visit timeline (DOC-025): pinned in the workspace on every step, built by buildTimeline(). */
export default function VisitTimeline({ events }: { events: TimelineEvent[] }) {
  return (
    <section className={s.card} aria-label="Visit timeline">
      <h3 className={s.cardTitle}><Icon name="clock" size={14} /> Visit timeline</h3>
      <ol className={s.timeline}>
        {events.map((e) => (
          <li key={e.id}>
            <span className={`${s.timelineDot} ${e.kind === "warn" ? s.timelineDotWarn : e.kind === "ok" ? s.timelineDotOk : ""}`}><Icon name={ICON[e.kind]} size={14} /></span>
            <span className={s.timelineText}>
              <span>{e.label}</span>
              {e.sub && <span className={s.muted}>{e.sub}</span>}
            </span>
            {e.at && <time className={s.timelineAt} dateTime={e.at}>{time(e.at)}</time>}
          </li>
        ))}
      </ol>
    </section>
  );
}
