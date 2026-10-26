// FEF-019: operator console capabilities come from the permissions GET /platform/auth/me returns
// (PlatformPermissions), never from role codes. The server stays the authority.
export type OperatorCapabilities = { canWrite: boolean; canBill: boolean; isSuper: boolean; readOnly: boolean; canSeeValues: boolean };

export function capabilitiesFrom(permissions: readonly string[] | undefined): OperatorCapabilities {
  const has = (p: string) => !!permissions?.includes(p);
  const canWrite = has("onboarding_provisioning:view"); // tenants, provisioning (super_admin, implementation)
  const canBill = has("billing_revenue:edit"); // plans, subscriptions, discount requests
  const isSuper = has("billing_revenue:approve"); // above-cap discount approvals
  return {
    canWrite, canBill, isSuper,
    readOnly: !!permissions && !canWrite && !canBill && !isSuper, // commercial, compliance_dpo and the rest: view only
    canSeeValues: has("audit_compliance:values"), // before/after audit values (compliance_dpo), PLT-056
  };
}
