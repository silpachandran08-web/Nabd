import StatusPill, { type PillTone } from "../../ui/StatusPill";
import type { IconName } from "../../ui/Icon";

// Status is icon + text, never colour alone (DESIGN.md §8), rendered through the shared StatusPill.
const STATUS_PILL: Record<string, { label: string; tone: PillTone; icon: IconName }> = {
  checked_in: { label: "Checked in", tone: "info", icon: "check" },
  waiting: { label: "Waiting", tone: "warn", icon: "clock" },
  billing_pending: { label: "Billing pending", tone: "warn", icon: "receipt" },
  vitals_pending: { label: "Vitals pending", tone: "warn", icon: "pulse" },
  vitals_done: { label: "Vitals done", tone: "ok", icon: "check" },
  in_consult: { label: "In consultation", tone: "sync", icon: "stethoscope" },
  procedures_pending: { label: "Procedures pending", tone: "sync", icon: "syringe" },
  checkout_pending: { label: "Checkout pending", tone: "info", icon: "receipt" },
  completed: { label: "Completed", tone: "ok", icon: "check" },
  no_show: { label: "No show", tone: "danger", icon: "alert" },
};

export default function StatusBadge({ status }: { status: string }) {
  const s = STATUS_PILL[status];
  const fallback = status.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
  return <StatusPill tone={s?.tone ?? "neutral"} label={s?.label ?? fallback} icon={s?.icon} />;
}
