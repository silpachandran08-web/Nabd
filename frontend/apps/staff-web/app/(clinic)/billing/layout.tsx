import AccessGate from "../../components/ui/AccessGate";

// FEF-019: the gate decides before the page mounts, so a role without the grant sends no request that would 403.
export default function BillingLayout({ children }: { children: React.ReactNode }) {
  return <AccessGate permission="billing:view" surface="billing">{children}</AccessGate>;
}
