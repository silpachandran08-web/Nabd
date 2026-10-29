import { useTranslations } from "next-intl";
import StatusPill, { type PillTone } from "../../ui/StatusPill";
import type { IconName } from "../../ui/Icon";

// Status is icon + text, never colour alone (DESIGN.md §8), rendered through the shared StatusPill.
const STATUS_PILL: Record<string, { tone: PillTone; icon: IconName }> = {
  checked_in: { tone: "info", icon: "check" },
  waiting: { tone: "warn", icon: "clock" },
  billing_pending: { tone: "warn", icon: "receipt" },
  vitals_pending: { tone: "warn", icon: "pulse" },
  vitals_done: { tone: "ok", icon: "check" },
  in_consult: { tone: "sync", icon: "stethoscope" },
  procedures_pending: { tone: "sync", icon: "syringe" },
  checkout_pending: { tone: "info", icon: "receipt" },
  completed: { tone: "ok", icon: "check" },
  no_show: { tone: "danger", icon: "alert" },
};

export default function StatusBadge({ status }: { status: string }) {
  const t = useTranslations("arrivals.status");
  const s = STATUS_PILL[status];
  const fallback = status.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
  return <StatusPill tone={s?.tone ?? "neutral"} label={t.has(status) ? t(status) : fallback} icon={s?.icon} />;
}
