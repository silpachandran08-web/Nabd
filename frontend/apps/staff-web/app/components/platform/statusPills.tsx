import StatusPill, { type PillTone } from "../ui/StatusPill";
import type { IconName } from "../ui/Icon";

// Platform-console status vocabularies mapped onto the shared StatusPill (icon + text, --nb-* tones).
// Wireframe colours: ok green, warn amber, info blue, sync violet, danger red (DESIGN §1.4/1.6).
type Entry = { tone: PillTone; icon?: IconName; label?: string };

const TENANT: Record<string, Entry> = {
  provisioning: { tone: "sync" },
  trialing: { tone: "info", icon: "clock" },
  active: { tone: "ok" },
  overdue: { tone: "warn", icon: "alert" },
  suspended: { tone: "danger" },
  offboarding: { tone: "warn" },
  offboarded: { tone: "neutral", icon: "x" },
};

const DISCOUNT: Record<string, Entry> = {
  auto_approved: { tone: "ok", label: "Auto-approved" },
  approved: { tone: "ok", label: "Approved" },
  pending: { tone: "warn", label: "Awaiting super admin" },
  rejected: { tone: "danger", icon: "x", label: "Rejected" },
};

const JOB: Record<string, Entry> = {
  queued: { tone: "neutral", icon: "clock" },
  running: { tone: "sync" },
  done: { tone: "ok" },
  failed: { tone: "danger" },
  rolled_back: { tone: "warn", icon: "refresh" },
};

const TICKET_STATUS: Record<string, Entry> = {
  open: { tone: "info", icon: "inbox" },
  in_progress: { tone: "sync" },
  resolved: { tone: "ok" },
  closed: { tone: "neutral", icon: "x" },
};

const PRIORITY: Record<string, Entry> = {
  low: { tone: "neutral", icon: "list" },
  normal: { tone: "neutral", icon: "list" },
  high: { tone: "warn", icon: "alert" },
  urgent: { tone: "urgent", icon: "alert" },
};

function pill(map: Record<string, Entry>, value: string) {
  const e = map[value] ?? { tone: "neutral" as PillTone };
  return <StatusPill tone={e.tone} icon={e.icon} label={e.label ?? value.replace(/_/g, " ")} />;
}

export const TenantStatusPill = ({ status }: { status: string }) => pill(TENANT, status);
export const DiscountStatusPill = ({ status }: { status: string }) => pill(DISCOUNT, status);
export const JobStatusPill = ({ status }: { status: string }) => pill(JOB, status);
export const TicketStatusPill = ({ status }: { status: string }) => pill(TICKET_STATUS, status);
export const PriorityPill = ({ priority }: { priority: string }) => pill(PRIORITY, priority);

export function GrantStatusPill({ state }: { state: "active" | "expired" | "revoked" }) {
  if (state === "active") return <StatusPill tone="ok" label="Active" />;
  if (state === "revoked") return <StatusPill tone="danger" icon="x" label="Revoked" />;
  return <StatusPill tone="neutral" icon="clock" label="Expired" />;
}

export function PlanStatusPill({ active }: { active: boolean }) {
  return active ? <StatusPill tone="ok" label="Active" /> : <StatusPill tone="neutral" icon="x" label="Inactive" />;
}
