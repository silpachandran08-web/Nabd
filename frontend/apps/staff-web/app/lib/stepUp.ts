import { ApiError, api } from "./api";

// A fresh MFA confirmation (X-Step-Up-Token) from the 6-digit authenticator code. keepSession: a wrong code also answers 401,
// which must not be read as an expired session (same reason as the staff page).
// ADM-037: `target` binds the token to one object (the server consumes it once for exactly that target, IDC-013 mechanism).
export async function requestStepUpToken(code: string, target?: string): Promise<{ token: string } | { error: string }> {
  if (!localStorage.getItem("nabd_access_token")) return { error: "Your session ended. Sign in again." };
  try {
    const body = await api.post<{ stepUpToken?: string }>("/auth/mfa/verify", { challengeId: "", code, ...(target ? { target } : {}) }, { keepSession: true });
    return body.stepUpToken ? { token: body.stepUpToken } : { error: "That code was not accepted." };
  } catch (e) {
    if (e instanceof ApiError) return { error: e.detail || "That code was not accepted." };
    return { error: "Could not reach the server. Check your connection." };
  }
}
