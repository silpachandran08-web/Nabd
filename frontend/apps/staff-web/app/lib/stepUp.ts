// A fresh MFA confirmation (X-Step-Up-Token) from the 6-digit authenticator code. Deliberately a plain fetch, not apiFetch:
// a wrong code also answers 401, which apiFetch would misread as an expired session (same reason as the staff page).
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export async function requestStepUpToken(code: string): Promise<{ token: string } | { error: string }> {
  const access = localStorage.getItem("nabd_access_token");
  if (!access) return { error: "Your session ended. Sign in again." };
  try {
    const res = await fetch(`${API_BASE}/auth/mfa/verify`, {
      method: "POST", headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
      body: JSON.stringify({ challengeId: "", code }),
    });
    if (!res.ok) return { error: ((await res.json().catch(() => null)) as { detail?: string } | null)?.detail || "That code was not accepted." };
    const body = (await res.json()) as { stepUpToken?: string };
    return body.stepUpToken ? { token: body.stepUpToken } : { error: "That code was not accepted." };
  } catch {
    return { error: "Could not reach the server. Check your connection." };
  }
}
