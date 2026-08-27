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
type ModuleGrant = {
  module: string; view: boolean; create: boolean; edit: boolean; delete: boolean;
  approve: boolean; refundDiscount: boolean; export: boolean;
};
type Role = { id: string; name: string; builtIn: boolean; grants: ModuleGrant[] };
// GlobalExceptionHandler serializes RFC 7807 ProblemDetail — the machine-readable
// error code lives in `type` (a "…/errors/<slug>" URI), not `title` (human text).
type Problem = { type?: string; title: string; detail: string };

type ActionKey = "view" | "create" | "edit" | "delete" | "approve" | "refundDiscount" | "export";
type Grid = Record<string, Record<ActionKey, boolean>>;
type Builder = { mode: "create" | "edit" | "view"; sourceId?: string; name: string; grid: Grid };

// NB-049's module list — matches what every controller's @PreAuthorize actually checks.
const MODULES: { key: string; label: string }[] = [
  { key: "patients", label: "Patients" },
  { key: "queue", label: "Queue & Appointments" },
  { key: "clinical", label: "Clinical Records" },
  { key: "billing", label: "Billing & Payments" },
  { key: "packages", label: "Packages" },
  { key: "pharmacy", label: "Pharmacy" },
  { key: "reports", label: "Reports" },
  { key: "staff", label: "Staff & Access" },
  { key: "setup", label: "Clinic Setup" },
  { key: "specialty_dental", label: "Specialty (Dental)" },
  { key: "nursing", label: "Nursing" },
];
const ACTIONS: { key: ActionKey; label: string }[] = [
  { key: "view", label: "View" },
  { key: "create", label: "Create" },
  { key: "edit", label: "Edit" },
  { key: "delete", label: "Delete" },
  { key: "approve", label: "Approve" },
  { key: "refundDiscount", label: "Refund" },
  { key: "export", label: "Export" },
];

function gridFromGrants(grants: ModuleGrant[]): Grid {
  const grid = {} as Grid;
  for (const m of MODULES) {
    const g = grants.find((x) => x.module === m.key);
    grid[m.key] = Object.fromEntries(ACTIONS.map((a) => [a.key, g ? g[a.key] : false])) as Record<ActionKey, boolean>;
  }
  return grid;
}

function grantsFromGrid(grid: Grid): ModuleGrant[] {
  return MODULES.filter((m) => ACTIONS.some((a) => grid[m.key][a.key])).map((m) => ({ module: m.key, ...grid[m.key] }));
}

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
  const [breakGlass, setBreakGlass] = useState<{ id: string; staffId: string; staffName: string; reason: string; activatedAt: string; expiresAt: string }[]>([]);
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

  const [builder, setBuilder] = useState<Builder | null>(null);
  const [builderError, setBuilderError] = useState<string | null>(null);
  const [builderSubmitting, setBuilderSubmitting] = useState(false);

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

      const bgRes = await authedFetch("/auth/break-glass/active");
      if (bgRes?.ok) setBreakGlass(await bgRes.json());
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

  function openNewRole() {
    setBuilderError(null);
    setBuilder({ mode: "create", name: "", grid: gridFromGrants([]) });
  }

  function openCloneRole(role: Role) {
    setBuilderError(null);
    setBuilder({ mode: "create", name: `${role.name} (copy)`, grid: gridFromGrants(role.grants) });
  }

  function openRole(role: Role, canEdit: boolean) {
    setBuilderError(null);
    setBuilder({ mode: role.builtIn || !canEdit ? "view" : "edit", sourceId: role.id, name: role.name, grid: gridFromGrants(role.grants) });
  }

  function toggleCell(moduleKey: string, actionKey: ActionKey) {
    if (!builder || builder.mode === "view") return;
    setBuilder({ ...builder, grid: { ...builder.grid, [moduleKey]: { ...builder.grid[moduleKey], [actionKey]: !builder.grid[moduleKey][actionKey] } } });
  }

  // "All" is derived from the same 7 booleans every other cell already uses, not a stored 8th
  // grant field — the backend's escalation check (RoleService.requireNoPrivilegeEscalation) still
  // runs on the real per-action grants either way, so ticking this can only ever request what the
  // individual checkboxes could already request one at a time.
  function allChecked(moduleKey: string): boolean {
    return ACTIONS.every((a) => builder!.grid[moduleKey][a.key]);
  }

  function toggleAllForModule(moduleKey: string) {
    if (!builder || builder.mode === "view") return;
    const next = !allChecked(moduleKey);
    setBuilder({
      ...builder,
      grid: { ...builder.grid, [moduleKey]: Object.fromEntries(ACTIONS.map((a) => [a.key, next])) as Record<ActionKey, boolean> },
    });
  }

  async function submitRole(e: React.FormEvent) {
    e.preventDefault();
    if (!builder || builder.mode === "view") return;
    setBuilderError(null);
    const name = builder.name.trim();
    if (!name) {
      setBuilderError("Name the role.");
      return;
    }
    const grants = grantsFromGrid(builder.grid);
    if (grants.length === 0) {
      setBuilderError("Grant at least one permission.");
      return;
    }
    setBuilderSubmitting(true);
    try {
      const isEdit = builder.mode === "edit";
      const res = await authedFetch(isEdit ? `/roles/${builder.sourceId}` : "/roles", {
        method: isEdit ? "PATCH" : "POST",
        body: JSON.stringify({ name, grants }),
      });
      if (!res) return;
      if (!res.ok) {
        const body: Problem = await res.json().catch(() => ({ title: "", detail: "Couldn't save the role." }));
        setBuilderError(body.detail || "Couldn't save the role.");
        return;
      }
      const saved: Role = await res.json();
      setRoles((prev) => (isEdit ? prev.map((r) => (r.id === saved.id ? saved : r)) : [...prev, saved]));
      setBuilder(null);
    } finally {
      setBuilderSubmitting(false);
    }
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
  const canEditRoles = permissions.includes("staff:edit");

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Staff & Access</h1>
          <p className={styles.subtitle}>{staff.length} team member{staff.length === 1 ? "" : "s"}</p>
        </div>
        <button className={styles.actionBtn} onClick={() => router.push("/account")}>My Account & Security</button>
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

      {!loading && !forbidden && (
        <>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Roles</h2>
            {canInvite && <button className={styles.actionBtn} onClick={openNewRole}>+ New role</button>}
          </div>
          <div className={styles.card}>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Name</th><th>Type</th><th></th></tr></thead>
                <tbody>
                  {roles.map((r) => (
                    <tr key={r.id}>
                      <td className={styles.staffName}>{r.name}</td>
                      <td><span className={`${styles.pill} ${r.builtIn ? styles.pillBuiltIn : styles.pillCustom}`}>{r.builtIn ? "Built-in" : "Custom"}</span></td>
                      <td>
                        <button className={styles.actionBtn} onClick={() => openRole(r, canEditRoles)}>
                          {r.builtIn || !canEditRoles ? "View" : "Edit"}
                        </button>
                        {canInvite && <button className={styles.actionBtn} style={{ marginLeft: "8px" }} onClick={() => openCloneRole(r)}>Clone</button>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {breakGlass.length > 0 && (
            <>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Emergency access — currently elevated</h2>
              </div>
              <div className={styles.card}>
                <div className={styles.tableWrap}>
                  <table className={styles.table}>
                    <thead><tr><th>Staff</th><th>Reason</th><th>Activated</th><th>Expires</th></tr></thead>
                    <tbody>
                      {breakGlass.map((g) => (
                        <tr key={g.id}>
                          <td className={styles.staffName}>{g.staffName}</td>
                          <td>{g.reason}</td>
                          <td className={styles.muted}>{new Date(g.activatedAt).toLocaleString()}</td>
                          <td className={styles.muted}>{new Date(g.expiresAt).toLocaleTimeString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}
        </>
      )}

      {builder && (
        <div className={styles.stepUpOverlay} onClick={() => setBuilder(null)}>
          <form className={styles.roleModal} onClick={(e) => e.stopPropagation()} onSubmit={submitRole}>
            <h2 className={styles.stepUpTitle}>
              {builder.mode === "create" ? "New role" : builder.mode === "edit" ? "Edit role" : "Permissions"}
            </h2>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="roleName">Role name</label>
              <input
                id="roleName"
                className={styles.input}
                style={{ width: "100%" }}
                value={builder.name}
                onChange={(e) => setBuilder({ ...builder, name: e.target.value })}
                disabled={builder.mode === "view"}
                required
              />
            </div>
            <div className={styles.matrixWrap}>
              <table className={styles.matrixTable}>
                <thead>
                  <tr>
                    <th>Module</th>
                    <th>All</th>
                    {ACTIONS.map((a) => <th key={a.key}>{a.label}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {MODULES.map((m) => (
                    <tr key={m.key}>
                      <td>{m.label}</td>
                      <td>
                        <input
                          type="checkbox"
                          checked={allChecked(m.key)}
                          disabled={builder.mode === "view"}
                          onChange={() => toggleAllForModule(m.key)}
                        />
                      </td>
                      {ACTIONS.map((a) => (
                        <td key={a.key}>
                          <input
                            type="checkbox"
                            checked={builder.grid[m.key][a.key]}
                            disabled={builder.mode === "view"}
                            onChange={() => toggleCell(m.key, a.key)}
                          />
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {builderError && <div className={styles.formError} role="alert" style={{ marginTop: "12px" }}>{builderError}</div>}
            <div className={styles.stepUpActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setBuilder(null)}>
                {builder.mode === "view" ? "Close" : "Cancel"}
              </button>
              {builder.mode === "view" ? (
                canInvite && (
                  <button
                    type="button"
                    className={styles.submit}
                    onClick={() => {
                      const role = roles.find((r) => r.id === builder.sourceId);
                      if (role) openCloneRole(role);
                    }}
                  >
                    Clone this role
                  </button>
                )
              ) : (
                <button type="submit" className={styles.submit} disabled={builderSubmitting}>
                  {builderSubmitting ? "Saving…" : builder.mode === "edit" ? "Save changes" : "Create role"}
                </button>
              )}
            </div>
          </form>
        </div>
      )}

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
