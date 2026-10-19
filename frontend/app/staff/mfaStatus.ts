// PLT-005: the staff list's MFA badge, derived from GET /v1/staff loginMethod + loginFactors (StaffService).
// Only TOTP exists today (no passkeys), so the badge is TOTP / Setup required / Owner sign-in / None.
export type MfaBadge = { label: string; tone: "ok" | "warn" | "neutral" | "info" };

export function mfaBadge(s: { loginMethod?: string | null; loginFactors?: string[] | null; status?: string }): MfaBadge {
  if (s.loginMethod === "owner_account") return { label: "Owner sign-in", tone: "info" }; // MFA lives on the owner account
  if (s.loginFactors?.includes("authenticator") || s.loginMethod === "authenticator") return { label: "TOTP", tone: "ok" };
  if (s.loginMethod === "authenticator_setup_required") return { label: "Setup required", tone: "warn" };
  return { label: "None", tone: "neutral" };
}
