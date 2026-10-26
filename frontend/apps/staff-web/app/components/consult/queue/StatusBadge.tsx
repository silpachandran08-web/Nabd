"use client";

import { queueStatusLabel } from "@nabd/domain";
import StatusPill, { type PillTone } from "../../ui/StatusPill";

const STATUS_TONE: Record<string, { tone: PillTone; icon?: Parameters<typeof StatusPill>[0]["icon"] }> = {
  checked_in: { tone: "neutral", icon: "clock" },
  waiting: { tone: "neutral", icon: "clock" },
  vitals_pending: { tone: "warn", icon: "pulse" },
  vitals_done: { tone: "info", icon: "check" },
  in_consult: { tone: "sync", icon: "stethoscope" },
  checkout_pending: { tone: "ok", icon: "receipt" },
  completed: { tone: "ok", icon: "check" },
  no_show: { tone: "danger", icon: "x" },
};

export default function StatusBadge({ status }: { status: string }) {
  const t = STATUS_TONE[status] ?? { tone: "neutral" as PillTone };
  return <StatusPill tone={t.tone} icon={t.icon} label={queueStatusLabel(status)} />;
}
