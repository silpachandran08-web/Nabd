"use client";

import { buildTimeline } from "@nabd/domain";
import type { ClinicalContext } from "../clinical";
import type { Row } from "../shared";
import VisitTimeline from "../VisitTimeline";
import type { NoteAmendment, SaveState } from "../visit/types";

type Props = {
  entry: Row; ctx: ClinicalContext; lastSavedAt: Date | null; dirty: boolean; saveState: SaveState; online: boolean;
  signedAt: string | null; noteSigned: boolean; amendments: NoteAmendment[];
};

// FEF-030: the visit timeline (DOC-025), built from what the workspace already holds.
export default function TimelinePanel({ entry, ctx, lastSavedAt, dirty, saveState, online, signedAt, noteSigned, amendments }: Props) {
  const vd = ctx.vitals.data;
  const events = buildTimeline({
    checkedInAt: entry.createdAt ?? null,
    vitals: {
      status: ctx.vitals.status, recordedAt: vd?.recordedAt ?? null,
      summary: vd ? [
        vd.bpSystolic != null && vd.bpDiastolic != null ? `BP ${vd.bpSystolic}/${vd.bpDiastolic}` : null,
        vd.pulseBpm != null ? `${vd.pulseBpm} bpm` : null,
        vd.tempCelsius != null ? `${vd.tempCelsius} °C` : null,
        vd.spo2Percent != null ? `SpO₂ ${vd.spo2Percent}%` : null,
      ].filter(Boolean).join(" · ") || null : null,
    },
    status: entry.status,
    draft: { savedAt: lastSavedAt ? lastSavedAt.toISOString() : null, pendingOnDevice: dirty && (saveState === "offline" || saveState === "error" || !online) },
    signedAt,
    noteSigned,
    amendments,
  });
  return <VisitTimeline events={events} />;
}
