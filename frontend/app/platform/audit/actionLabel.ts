// PLT-036: readable names for platform audit actions. Any code not listed here (including new ones the
// backend adds later) is shown as its raw code, never dropped and never a crash.
const ACTION_LABELS: Record<string, string> = {
  "audit_log.export": "Audit log exported",
  "support_access.grant": "Support access granted",
  "support_access.revoke": "Support access revoked",
  "patient.support_view": "Patient viewed under support access",
  "platform_billing.discount_requested": "Discount requested",
  "platform_billing.discount_auto_approved": "Discount auto-approved",
  "platform_billing.discount_approved": "Discount approved",
  "platform_billing.discount_rejected": "Discount rejected",
};

export function actionLabel(action: unknown): string {
  if (typeof action !== "string" || action === "") return "Unknown action";
  return ACTION_LABELS[action] ?? action;
}
