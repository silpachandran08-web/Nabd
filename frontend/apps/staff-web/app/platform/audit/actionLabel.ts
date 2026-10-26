// PLT-036: readable names for platform audit actions. Any code not listed here (including new ones the
// backend adds later) is shown as its raw code, never dropped and never a crash.
const ACTION_LABELS: Record<string, string> = {
  "audit_log.export": "Audit log exported",
  "support_access.grant": "Support access granted",
  "support_access.revoke": "Support access revoked",
  "support_access.requested": "Support access requested",
  "support_access.session_started": "Support session started (owner approved)",
  "support_access.session_ended": "Support session ended",
  "support_access.declined": "Support access declined by owner",
  "support_access.request_expired": "Support access request expired",
  "patient.support_view": "Patient viewed under support access",
  "platform_billing.discount_requested": "Discount requested",
  "platform_billing.discount_auto_approved": "Discount auto-approved",
  "platform_billing.discount_approved": "Discount approved",
  "platform_billing.discount_rejected": "Discount rejected",
  // LIVE-08 platform-wide and per-clinic operator events
  "platform_auth.signed_in": "Operator signed in",
  "platform_auth.sign_in_failed": "Operator sign-in failed",
  "platform_auth.mfa_challenged": "MFA challenge sent",
  "platform_auth.mfa_failed": "MFA check failed",
  "platform_auth.mfa_setup_required": "MFA enrolment required",
  "platform_auth.mfa_enrollment_started": "MFA enrolment started",
  "platform_auth.mfa_enrolled": "MFA enrolled",
  "owner_auth.mfa_enrolled": "Owner MFA enrolled",
  "owner_auth.recovery_code_used": "Owner recovery code used",
  "platform_auth.signed_out": "Operator signed out",
  "platform_auth.session_revoked": "Operator session revoked",
  "platform_auth.refresh_reuse_detected": "Refresh token reuse detected",
  "platform_plan.created": "Plan created",
  "platform_plan.updated": "Plan updated",
  "platform_provisioning.job_created": "Provisioning job created",
  "platform_provisioning.job_approved": "Provisioning job approved",
  "platform_provisioning.step_run": "Provisioning step run",
  "platform_provisioning.job_rolled_back": "Provisioning job rolled back",
  "platform_tenant.lifecycle_changed": "Clinic status changed",
  "platform_billing.subscription_created": "Subscription created",
  "platform_billing.subscription_updated": "Subscription updated",
  "platform_ticket.status_changed": "Support ticket status changed",
};

export function actionLabel(action: unknown): string {
  if (typeof action !== "string" || action === "") return "Unknown action";
  return ACTION_LABELS[action] ?? action;
}
