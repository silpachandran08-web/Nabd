import AccessGate from "../../components/ui/AccessGate";

// FEF-019: the gate decides before the page mounts, so a role without the grant sends no request that would 403.
export default function ScheduleLayout({ children }: { children: React.ReactNode }) {
  return <AccessGate permission="queue:view" surface="the schedule">{children}</AccessGate>;
}
