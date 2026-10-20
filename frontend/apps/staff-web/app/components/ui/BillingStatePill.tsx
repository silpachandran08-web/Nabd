import StatusPill, { type PillTone } from "./StatusPill";
import type { IconName } from "./Icon";

// REC-047: one billing-state vocabulary (invoice status from the API, plus the patient balance states) for every screen.
// shortcut: wireframe BILLSTATE (L5836) was not readable here, so labels cover the states the API can send; align wording when the wireframe is at hand.
const STATES: Record<string, { label: string; tone: PillTone; icon?: IconName }> = {
  unpaid: { label: "Unpaid", tone: "danger", icon: "clock" },
  partial: { label: "Part-paid", tone: "warn" },
  paid: { label: "Paid", tone: "ok" },
  void: { label: "Void", tone: "neutral", icon: "x" },
  outstanding: { label: "Outstanding", tone: "warn" },
  settled: { label: "Settled", tone: "ok" },
};

export default function BillingStatePill({ state }: { state: string }) {
  const s = STATES[state] ?? { label: state, tone: "neutral" as PillTone };
  return <StatusPill tone={s.tone} label={s.label} icon={s.icon} />;
}
