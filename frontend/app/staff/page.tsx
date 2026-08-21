"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./staff.module.css";

// Matches GET /v1/staff (StaffController) and GET /v1/roles (RoleController).
type Staff = {
  id: string;
  email: string;
  name: string;
  mobilePhone: string;
  roleId: string;
  status: "invited" | "active" | "suspended";
  scope: string;
  lastSeenAt: string | null;
};
type StaffPage = { data: Staff[]; page: { nextCursor: string | null; limit: number } };
type Role = { id: string; name: string; builtIn: boolean };
// GlobalExceptionHandler serializes RFC 7807 ProblemDetail — the machine-readable
// error code lives in `type` (a "…/errors/<slug>" URI), not `title` (human text).
type Problem = { type?: string; title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

const SCOPE_LABELS: Record<string, string> = {
  own_patients_only: "Own patients only",
  all_clinic_patients: "All clinic patients",
};

const STATUS_PILL: Record<Staff["status"], string> = {
  invited: styles.pillInvited,
  active: styles.pillActive,
  suspended: styles.pillSuspended,
};

function decodePermissions(token: string): string[] {
  try {
    const payload = token.split(".")[1];
    const b64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), "=");
    const claims = JSON.parse(atob(padded));
    return Array.isArray(claims.permissions) ? claims.permissions : [];
  } catch {
    return [];
  }
}

export default function StaffAccessPage() {
  const router = useRouter();
  const [permissions, setPermissions] = useState<string[]>([]);
  const [staff, setStaff] = useState<Staff[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteName, setInviteName] = useState("");
  const [invitePhone, setInvitePhone] = useState("");
  const [inviteRoleId, setInviteRoleId] = useState("");
  const [inviteScope, setInviteScope] = useState("all_clinic_patients");
  const [inviting, setInviting] = useState(false);
  const [inviteFormError, setInviteFormError] = useState<string | null>(null);
  const [lastInvite, setLastInvite] = useState<{ name: string; link: string } | null>(null);

  const [stepUpFor, setStepUpFor] = useState<string | null>(null);
  const [stepUpCode, setStepUpCode] = useState("");
  const [stepUpBusy, setStepUpBusy] = useState(false);
  const [stepUpError, setStepUpError] = useState<string | null>(null);
  const [suspending, setSuspending] = useState<string | null>(null);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setForbidden(false);
    try {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return;
      }
      setPermissions(decodePermissions(token));

      const staffRes = await authedFetch("/staff?limit=50");
      if (!staffRes) return;
      if (staffRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!staffRes.ok) {
        setError("Couldn't load staff. Try again.");
        return;
      }
      const staffBody: StaffPage = await staffRes.json();
      setStaff(staffBody.data);
      setNextCursor(staffBody.page.nextCursor);

      const rolesRes = await authedFetch("/roles");
      if (rolesRes?.ok) setRoles(await rolesRes.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch, router]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const res = await authedFetch(`/staff?limit=50&cursor=${encodeURIComponent(nextCursor)}`);
      if (!res?.ok) return;
      const body: StaffPage = await res.json();
      setStaff((prev) => [...prev, ...body.data]);
      setNextCursor(body.page.nextCursor);
    } finally {
      setLoadingMore(false);
    }
  }

  function roleName(roleId: string): string {
    return roles.find((r) => r.id === roleId)?.name ?? "—";
  }

  async function submitInvite(e: React.FormEvent) {
    e.preventDefault();
    setInviteFormError(null);
    if (!inviteEmail || !inviteName || !invitePhone || !inviteRoleId) {
      setInviteFormError("Fill in every field.");
      return;
    }
    setInviting(true);
    try {
      const res = await authedFetch("/staff", {
        method: "POST",
        body: JSON.stringify({ email: inviteEmail, name: inviteName, mobilePhone: invitePhone, roleId: inviteRoleId, scope: inviteScope }),
      });
      if (!res) return;
      if (!res.ok) {
        const body: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't send invite." }));
        setInviteFormError(body.detail || "Couldn't send invite.");
        return;
      }
      const body: { staff: Staff; inviteToken: string } = await res.json();
      setStaff((prev) => [body.staff, ...prev]);
      setLastInvite({ name: body.staff.name, link: `${window.location.origin}/accept-invite/${body.inviteToken}` });
      setInviteEmail("");
      setInviteName("");
      setInvitePhone("");
      setInviteRoleId("");
      setInviteScope("all_clinic_patients");
    } finally {
      setInviting(false);
    }
  }

  async function suspend(id: string) {
    setError(null);
    setSuspending(id);
    try {
      const res = await authedFetch(`/staff/${id}/suspend`, { method: "POST" });
      if (!res) return;
      if (res.status === 204) {
        setStaff((prev) => prev.map((s) => (s.id === id ? { ...s, status: "suspended" } : s)));
        return;
      }
      const body: Problem = await res.json().catch(() => ({ title: "", detail: "Couldn't suspend staff member." }));
      if (res.status === 403 && body.type?.endsWith("/step-up-required")) {
        setStepUpFor(id);
        setStepUpCode("");
        setStepUpError(null);
        return;
      }
      setError(body.detail || "Couldn't suspend staff member.");
    } finally {
      setSuspending(null);
    }
  }

  async function confirmStepUp(e: React.FormEvent) {
    e.preventDefault();
    if (!stepUpFor) return;
    setStepUpBusy(true);
    setStepUpError(null);
    try {
      // Deliberately not authedFetch: a wrong step-up code also comes back as 401
      // ("mfa-failed"), which authedFetch would misread as an expired session and
      // log the caller out mid-suspend instead of just letting them retry the code.
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return;
      }
      const mfaRes = await fetch(`${API_BASE}/auth/mfa/verify`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({ challengeId: "", code: stepUpCode }),
      });
      if (!mfaRes.ok) {
        const body: Problem = await mfaRes.json().catch(() => ({ title: "", detail: "Verification failed." }));
        setStepUpError(body.detail || "Verification failed.");
        return;
      }
      const { stepUpToken }: { stepUpToken: string } = await mfaRes.json();
      const suspendRes = await authedFetch(`/staff/${stepUpFor}/suspend`, {
        method: "POST",
        headers: { "X-Step-Up-Token": stepUpToken },
      });
      if (!suspendRes) return;
      if (suspendRes.status === 204) {
        setStaff((prev) => prev.map((s) => (s.id === stepUpFor ? { ...s, status: "suspended" } : s)));
        setStepUpFor(null);
        return;
      }
      const body: Problem = await suspendRes.json().catch(() => ({ title: "", detail: "Couldn't suspend staff member." }));
      setStepUpError(body.detail || "Couldn't suspend staff member.");
    } finally {
      setStepUpBusy(false);
    }
  }

  const canInvite = permissions.includes("staff:create");
  const canSuspend = permissions.includes("staff:delete");

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Staff & Access</h1>
          <p className={styles.subtitle}>{staff.length} team member{staff.length === 1 ? "" : "s"}</p>
        </div>
      </div>

      {error && <div className={styles.formError} role="alert">{error}</div>}

      {canInvite && (
        <form className={styles.formCard} onSubmit={submitInvite}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="inviteName">Name</label>
            <input id="inviteName" className={styles.input} value={inviteName} onChange={(e) => setInviteName(e.target.value)} required />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="inviteEmail">Email</label>
            <input id="inviteEmail" type="email" className={styles.input} value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} required />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="invitePhone">Mobile</label>
            <input id="invitePhone" className={styles.input} value={invitePhone} onChange={(e) => setInvitePhone(e.target.value)} required />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="inviteRole">Role</label>
            <select id="inviteRole" className={styles.select} value={inviteRoleId} onChange={(e) => setInviteRoleId(e.target.value)} required>
              <option value="">Select a role…</option>
              {roles.map((r) => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="inviteScope">Scope</label>
            <select id="inviteScope" className={styles.select} value={inviteScope} onChange={(e) => setInviteScope(e.target.value)}>
              <option value="all_clinic_patients">All clinic patients</option>
              <option value="own_patients_only">Own patients only</option>
            </select>
          </div>
          <button className={styles.submit} type="submit" disabled={inviting}>
            {inviting ? "Inviting…" : "Invite staff"}
          </button>
          {inviteFormError && <div className={styles.formError} role="alert">{inviteFormError}</div>}
          {lastInvite && (
            <div className={styles.inviteBanner}>
              <span>Invite link for {lastInvite.name} — no email service yet, share this by hand:</span>
              <span className={styles.inviteLink}>{lastInvite.link}</span>
            </div>
          )}
        </form>
      )}

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to staff management.</div>
        ) : staff.length === 0 ? (
          <div className={styles.state}>No staff yet.</div>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>Staff member</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th>Scope</th>
                  <th>Last active</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {staff.map((s) => (
                  <tr key={s.id}>
                    <td>
                      <span className={styles.staffName}>{s.name}</span>
                      <span className={styles.staffEmail}>{s.email}</span>
                    </td>
                    <td><span className={`${styles.pill} ${styles.pillRole}`}>{roleName(s.roleId)}</span></td>
                    <td><span className={`${styles.pill} ${STATUS_PILL[s.status]}`}>{s.status}</span></td>
                    <td className={styles.muted}>{SCOPE_LABELS[s.scope] ?? s.scope}</td>
                    <td className={styles.muted}>{s.lastSeenAt ? new Date(s.lastSeenAt).toLocaleString() : "—"}</td>
                    <td>
                      {canSuspend && s.status === "active" && (
                        <button className={styles.actionBtn} disabled={suspending === s.id} onClick={() => suspend(s.id)}>
                          {suspending === s.id ? "…" : "Suspend"}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {nextCursor && !loading && !forbidden && (
          <div className={styles.footer}>
            <button className={styles.loadMore} onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? "Loading…" : "Load more"}
            </button>
          </div>
        )}
      </div>

      {stepUpFor && (
        <div className={styles.stepUpOverlay}>
          <form className={styles.stepUpCard} onSubmit={confirmStepUp}>
            <h2 className={styles.stepUpTitle}>Confirm it&apos;s you</h2>
            <p className={styles.stepUpText}>Suspending a staff member needs a fresh code from your authenticator app.</p>
            {stepUpError && <div className={styles.formError} role="alert">{stepUpError}</div>}
            <div className={styles.field}>
              <label className={styles.label} htmlFor="stepUpCode">Authentication code</label>
              <input
                id="stepUpCode"
                className={styles.input}
                inputMode="numeric"
                pattern="[0-9]{6}"
                maxLength={6}
                autoFocus
                value={stepUpCode}
                onChange={(e) => setStepUpCode(e.target.value.replace(/\D/g, ""))}
                required
              />
            </div>
            <div className={styles.stepUpActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setStepUpFor(null)}>Cancel</button>
              <button type="submit" className={styles.submit} disabled={stepUpBusy || stepUpCode.length !== 6}>
                {stepUpBusy ? "Verifying…" : "Confirm & suspend"}
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}
