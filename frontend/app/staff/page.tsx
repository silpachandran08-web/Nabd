"use client";

import { decodeJwtPayload } from "../lib/jwt";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Dialog from "../components/ui/Dialog";
import Icon from "../components/ui/Icon";
import StatusPill, { type PillTone } from "../components/ui/StatusPill";
import { Skeleton, EmptyState, ErrorState } from "../components/ui/StateViews";
import PermissionMatrix from "../components/staff/PermissionMatrix";
import RegistrationNoDialog from "../components/staff/RegistrationNoDialog";
import styles from "./staff.module.css";
import { apiFetch, goLogin } from "../lib/apiClient";

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
  professionalRegistrationNo?: string | null;
  // OWN-034 (read-only, derived server-side from stored credentials). Optional: absent on an older backend.
  loginMethod?: string;
  loginFactors?: string[];
};
type StaffPage = { data: Staff[]; page: { nextCursor: string | null; limit: number } };
type ModuleGrant = {
  module: string; view: boolean; create: boolean; edit: boolean; delete: boolean;
  approve: boolean; refundDiscount: boolean; export: boolean;
};
type Role = { id: string; name: string; builtIn: boolean; grants: ModuleGrant[]; mfaRequired: boolean };
type Department = { id: string; name: string; isDefault: boolean; active: boolean };
// GlobalExceptionHandler serializes RFC 7807 ProblemDetail — the machine-readable
// error code lives in `type` (a "…/errors/<slug>" URI), not `title` (human text).
type Problem = { type?: string; title: string; detail: string };

type ActionKey = "view" | "create" | "edit" | "delete" | "approve" | "refundDiscount" | "export";
type Grid = Record<string, Record<ActionKey, boolean>>;
// mfaRequired and grants for modules this grid has no row for are carried through unchanged: the role
// PATCH replaces the whole role, so omitting them would silently switch MFA off / drop access.
type Builder = { mode: "create" | "edit" | "view"; sourceId?: string; name: string; grid: Grid; mfaRequired: boolean; extraGrants: ModuleGrant[] };

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
  { key: "departments", label: "Departments" },
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

function extraGrantsOf(role: Role): ModuleGrant[] {
  return role.grants.filter((g) => !MODULES.some((m) => m.key === g.module));
}

function grantsFromGrid(grid: Grid): ModuleGrant[] {
  return MODULES.filter((m) => ACTIONS.some((a) => grid[m.key][a.key])).map((m) => ({ module: m.key, ...grid[m.key] }));
}

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

const SCOPE_LABELS: Record<string, string> = {
  own_patients_only: "Own patients only",
  all_clinic_patients: "All clinic patients",
};

const STATUS_PILL: Record<Staff["status"], { tone: PillTone; label: string }> = {
  invited: { tone: "info", label: "Invited" },
  active: { tone: "ok", label: "Active" },
  suspended: { tone: "danger", label: "Suspended" },
};

// GET /v1/staff loginMethod codes (StaffService.loginMethod). Unknown codes render as-is.
const LOGIN_METHOD_LABELS: Record<string, string> = {
  authenticator: "Authenticator",
  authenticator_setup_required: "Authenticator (setup pending)",
  pin_or_whatsapp_otp: "PIN / WhatsApp OTP",
  pin: "PIN",
  whatsapp_otp: "WhatsApp OTP",
  owner_account: "Owner account",
  invite_pending: "Invite pending",
  none: "Not set",
};

// Short relative time for "Last active"; the absolute time stays in the cell's title.
function relativeTime(iso: string): string {
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} h ago`;
  const days = Math.floor(hrs / 24);
  return days < 30 ? `${days} d ago` : new Date(iso).toLocaleDateString();
}

function decodePermissions(token: string): string[] {
  const claims = decodeJwtPayload<{ permissions?: unknown }>(token);
  return Array.isArray(claims?.permissions) ? claims.permissions : [];
}

export default function StaffAccessPage() {
  const router = useRouter();
  const [permissions, setPermissions] = useState<string[]>([]);
  const [staff, setStaff] = useState<Staff[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [rolesFailed, setRolesFailed] = useState(false);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [breakGlass, setBreakGlass] = useState<{ grantId: string; staffName: string; reasonCode: string; activatedAt: string; expiresAt: string }[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [inviteOpen, setInviteOpen] = useState(false);
  const [cloneOpen, setCloneOpen] = useState(false);
  const [cloneSourceId, setCloneSourceId] = useState("");
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);

  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteName, setInviteName] = useState("");
  const [invitePhone, setInvitePhone] = useState("");
  const [inviteRoleId, setInviteRoleId] = useState("");
  const [inviteScope, setInviteScope] = useState("all_clinic_patients");
  const [inviteDepartmentId, setInviteDepartmentId] = useState("");
  const [inviting, setInviting] = useState(false);
  const [inviteFormError, setInviteFormError] = useState<string | null>(null);
  const [lastInvite, setLastInvite] = useState<{ name: string; link: string } | null>(null);

  const [builder, setBuilder] = useState<Builder | null>(null);
  const [builderError, setBuilderError] = useState<string | null>(null);
  const [builderSubmitting, setBuilderSubmitting] = useState(false);

  const [stepUpFor, setStepUpFor] = useState<string | null>(null);
  const [stepUpKind, setStepUpKind] = useState<"suspend" | "reset">("suspend"); // PLT-004: same step-up prompt serves the MFA reset
  const [resetNotice, setResetNotice] = useState<string | null>(null);
  const [stepUpCode, setStepUpCode] = useState("");
  const [stepUpBusy, setStepUpBusy] = useState(false);
  const [stepUpError, setStepUpError] = useState<string | null>(null);
  const [suspending, setSuspending] = useState<string | null>(null);
  const [regEdit, setRegEdit] = useState<Staff | null>(null);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        goLogin();
        return null;
      }
      const res = await apiFetch(path, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        goLogin();
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
        goLogin();
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
      setRolesFailed(!!rolesRes && !rolesRes.ok);

      const departmentsRes = await authedFetch("/departments");
      if (departmentsRes?.ok) setDepartments(await departmentsRes.json());

      const bgRes = await authedFetch("/auth/break-glass/active");
      if (bgRes?.ok) setBreakGlass((await bgRes.json()).items ?? []);
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

  // Restriction summary (wireframe staff table), derived only from what the API returns:
  // invite state, patient scope and the role's billing grant. Built-in (owner) role = full access.
  function restrictionOf(s: Staff): string {
    if (s.status === "invited") return "Pending first sign-in";
    const role = roles.find((r) => r.id === s.roleId);
    // Without the role's grants the restriction is unknown; never summarise it as "None".
    if (!role) return "—";
    if (role.builtIn) return "Full access";
    const parts: string[] = [];
    if (s.scope === "own_patients_only") parts.push(SCOPE_LABELS.own_patients_only);
    else if (s.scope !== "all_clinic_patients" && s.scope) parts.push(SCOPE_LABELS[s.scope] ?? s.scope);
    const billing = role.grants.find((g) => g.module === "billing");
    if (billing?.view && !billing.refundDiscount) parts.push("No refund, no discount");
    return parts.length ? parts.join(" · ") : "None";
  }

  // Counts come from the staff pages loaded so far (RoleResponse has no user count).
  function roleUserCount(roleId: string): number {
    return staff.filter((s) => s.roleId === roleId).length;
  }

  function openNewRole() {
    setBuilderError(null);
    setBuilder({ mode: "create", name: "", grid: gridFromGrants([]), mfaRequired: false, extraGrants: [] });
  }

  function openCloneRole(role: Role) {
    setBuilderError(null);
    setBuilder({ mode: "create", name: `${role.name} (copy)`, grid: gridFromGrants(role.grants), mfaRequired: role.mfaRequired, extraGrants: extraGrantsOf(role) });
  }

  function openRole(role: Role, canEdit: boolean) {
    setBuilderError(null);
    setBuilder({ mode: role.builtIn || !canEdit ? "view" : "edit", sourceId: role.id, name: role.name, grid: gridFromGrants(role.grants), mfaRequired: role.mfaRequired, extraGrants: extraGrantsOf(role) });
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
    const grants = [...grantsFromGrid(builder.grid), ...builder.extraGrants];
    if (grants.length === 0) {
      setBuilderError("Grant at least one permission.");
      return;
    }
    setBuilderSubmitting(true);
    try {
      const isEdit = builder.mode === "edit";
      const res = await authedFetch(isEdit ? `/roles/${builder.sourceId}` : "/roles", {
        method: isEdit ? "PATCH" : "POST",
        body: JSON.stringify({ name, grants, mfaRequired: builder.mfaRequired }),
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
        body: JSON.stringify({
          email: inviteEmail, name: inviteName, mobilePhone: invitePhone, roleId: inviteRoleId, scope: inviteScope,
          departmentId: inviteDepartmentId || null,
        }),
      });
      if (!res) return;
      if (!res.ok) {
        const body: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't send invite." }));
        setInviteFormError(body.detail || "Couldn't send invite.");
        return;
      }
      const body: { staff: Staff; inviteToken: string } = await res.json();
      // GET /v1/staff orders by created_at ascending, so the newest row belongs at the end; while
      // more pages remain, Load more will return it, and inserting it now would render it twice.
      if (!nextCursor) setStaff((prev) => [...prev, body.staff]);
      setLastInvite({ name: body.staff.name, link: `${window.location.origin}/accept-invite/${body.inviteToken}` });
      setInviteEmail("");
      setInviteName("");
      setInvitePhone("");
      setInviteRoleId("");
      setInviteScope("all_clinic_patients");
      setInviteDepartmentId("");
      setInviteOpen(false);
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

  function startReset(id: string) {
    setStepUpKind("reset");
    setStepUpFor(id);
    setStepUpCode("");
    setStepUpError(null);
    setResetNotice(null);
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
        goLogin();
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
      const reset = stepUpKind === "reset";
      const suspendRes = await authedFetch(reset ? `/staff/${stepUpFor}/mfa/reset` : `/staff/${stepUpFor}/suspend`, {
        method: "POST",
        headers: { "X-Step-Up-Token": stepUpToken },
        ...(reset ? { body: JSON.stringify({ reasonCode: "lost_device" }) } : {}),
      });
      if (!suspendRes) return;
      if (suspendRes.status === 204) {
        if (reset) {
          setResetNotice("Authenticator reset. They are signed out and must set up a new one at next sign-in.");
        } else {
          setStaff((prev) => prev.map((s) => (s.id === stepUpFor ? { ...s, status: "suspended" } : s)));
        }
        setStepUpFor(null);
        return;
      }
      const fallback = reset ? "Couldn't reset the authenticator." : "Couldn't suspend staff member.";
      const body: Problem = await suspendRes.json().catch(() => ({ title: "", detail: fallback }));
      setStepUpError(body.detail || fallback);
    } finally {
      setStepUpBusy(false);
    }
  }

  async function saveRegistrationNo(target: Staff, value: string): Promise<string | null> {
    try {
      // "" clears it (null/absent would leave it unchanged).
      const res = await authedFetch(`/staff/${target.id}`, { method: "PATCH", body: JSON.stringify({ professionalRegistrationNo: value }) });
      if (!res) return null;
      if (!res.ok) {
        const body: Problem = await res.json().catch(() => ({ title: "", detail: "" }));
        return body.detail || "Couldn't save the registration number.";
      }
      const saved: Staff = await res.json();
      setStaff((prev) => prev.map((x) => (x.id === saved.id ? { ...x, professionalRegistrationNo: saved.professionalRegistrationNo ?? null } : x)));
      setRegEdit(null);
      return null;
    } catch {
      return "Couldn't reach the server. The registration number was not saved.";
    }
  }

  const selectedRole = roles.find((r) => r.id === selectedRoleId) ?? roles[0] ?? null;

  const canInvite = permissions.includes("staff:create");
  const canSuspend = permissions.includes("staff:delete");
  const canResetMfa = permissions.includes("staff:edit");
  // Login column only when the backend reports it (BE2 OWN-034); an older backend shows no column.
  const showLogin = staff.some((s) => typeof s.loginMethod === "string");
  const canEditRoles = permissions.includes("staff:edit");

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Staff & Access</h1>
          <p className={styles.subtitle}>{staff.length}{nextCursor ? "+" : ""} team member{staff.length === 1 && !nextCursor ? "" : "s"}</p>
        </div>
        <div className={styles.headerActions}>
          {canInvite && (
            <button className={styles.actionBtn} onClick={() => { setCloneSourceId(roles[0]?.id ?? ""); setCloneOpen(true); }}>
              <Icon name="list" size={14} /> Clone role
            </button>
          )}
          {canInvite && (
            <button className={styles.inviteBtn} onClick={() => { setInviteFormError(null); setInviteOpen(true); }}>
              <Icon name="plus" size={14} /> Invite staff
            </button>
          )}
          <button className={styles.actionBtn} onClick={() => router.push("/account")}>My Account & Security</button>
        </div>
      </div>

      {error && !loading && staff.length > 0 && <div className={styles.formError} role="alert">{error}</div>}
      {resetNotice && <div className={styles.formError} role="status">{resetNotice}</div>}
      {rolesFailed && !loading && staff.length > 0 && (
        <div className={styles.formError} role="alert">Couldn&apos;t load roles, so Role and Restriction show &ldquo;—&rdquo;. Reload to try again.</div>
      )}

      {lastInvite && (
        <div className={styles.inviteBanner}>
          <span>Invite link for {lastInvite.name} — no email service yet, share this by hand:</span>
          <span className={styles.inviteLink}>{lastInvite.link}</span>
        </div>
      )}

      <div className={styles.card}>
        {loading ? (
          <Skeleton rows={5} label="Loading staff" />
        ) : forbidden ? (
          <EmptyState icon="shield" title="Your role doesn't have access to staff management." />
        ) : error && staff.length === 0 ? (
          <ErrorState message={error} onRetry={load} />
        ) : staff.length === 0 ? (
          <EmptyState icon="users" title="No staff yet." hint={canInvite ? "Use Invite staff to add the first team member." : undefined} />
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>Staff member</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th>Restriction</th>
                  <th>Active</th>
                  {showLogin && <th>Login</th>}
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
                    <td><StatusPill tone={STATUS_PILL[s.status].tone} label={STATUS_PILL[s.status].label} /></td>
                    <td className={styles.muted}>{restrictionOf(s)}</td>
                    <td className={styles.muted} title={s.lastSeenAt ? new Date(s.lastSeenAt).toLocaleString() : undefined}>{s.lastSeenAt ? relativeTime(s.lastSeenAt) : "—"}</td>
                    {showLogin && (
                      <td className={styles.muted}>{s.loginMethod ? (LOGIN_METHOD_LABELS[s.loginMethod] ?? s.loginMethod) : "—"}</td>
                    )}
                    <td>
                      {canEditRoles && (
                        <button className={styles.actionBtn} onClick={() => setRegEdit(s)}>
                          {s.professionalRegistrationNo ? "Reg. no." : "Add reg. no."}
                        </button>
                      )}
                      {canResetMfa && s.status === "active" && (
                        <button className={styles.actionBtn} onClick={() => startReset(s.id)}>Reset authenticator</button>
                      )}
                      {canSuspend && s.status === "active" && (
                        <button className={styles.actionBtn} disabled={suspending === s.id} onClick={() => { setStepUpKind("suspend"); suspend(s.id); }}>
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
            <h2 className={styles.sectionTitle}>Role presets</h2>
            {canInvite && <button className={styles.actionBtn} onClick={openNewRole}>+ New role</button>}
          </div>
          <div className={styles.rolesLayout}>
            <aside className={styles.card} aria-label="Role presets">
              {roles.length === 0 ? (
                <EmptyState icon="shield" title="No roles available." />
              ) : (
                <ul className={styles.presetList}>
                  {roles.map((r) => {
                    const active = r.id === selectedRole?.id;
                    return (
                      <li key={r.id} className={`${styles.preset} ${active ? styles.presetActive : ""}`}>
                        <button type="button" className={styles.presetMain} aria-pressed={active} onClick={() => setSelectedRoleId(r.id)}>
                          <span className={styles.staffName}>{r.name}</span>
                          <span className={styles.staffEmail}>
                            {roleUserCount(r.id)}{nextCursor ? "+" : ""} user{roleUserCount(r.id) === 1 && !nextCursor ? "" : "s"}
                          </span>
                        </button>
                        <span className={`${styles.pill} ${r.builtIn ? styles.pillBuiltIn : styles.pillCustom}`}>{r.builtIn ? "Built-in" : "Custom"}</span>
                        <button className={styles.actionBtn} onClick={() => openRole(r, canEditRoles)}>
                          {r.builtIn || !canEditRoles ? "View" : "Edit"}
                        </button>
                        {canInvite && <button className={styles.actionBtn} onClick={() => openCloneRole(r)}>Clone</button>}
                      </li>
                    );
                  })}
                </ul>
              )}
              <p className={styles.note}>Anything a user lacks is removed from the interface — hidden, not greyed out.</p>
            </aside>
            {selectedRole && <PermissionMatrix roleName={selectedRole.name} grants={selectedRole.grants} modules={MODULES} />}
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
                        <tr key={g.grantId}>
                          <td className={styles.staffName}>{g.staffName}</td>
                          <td>{g.reasonCode.replace(/_/g, " ")}</td>
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

      {regEdit && canEditRoles && (
        <RegistrationNoDialog staffName={regEdit.name} current={regEdit.professionalRegistrationNo ?? null}
          onSave={(v) => saveRegistrationNo(regEdit, v)} onClose={() => setRegEdit(null)} />
      )}

      {inviteOpen && canInvite && (
        <Dialog title="Invite staff" onClose={() => setInviteOpen(false)}>
      <form className={styles.dialogForm} onSubmit={submitInvite}>
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
          {departments.some((d) => d.active) && (
            <div className={styles.field}>
              <label className={styles.label} htmlFor="inviteDepartment">Department</label>
              <select id="inviteDepartment" className={styles.select} value={inviteDepartmentId} onChange={(e) => setInviteDepartmentId(e.target.value)}>
                <option value="">Clinic default</option>
                {departments.filter((d) => d.active).map((d) => (
                  <option key={d.id} value={d.id}>{d.name}</option>
                ))}
              </select>
            </div>
          )}
          <button className={styles.submit} type="submit" disabled={inviting}>
            {inviting ? "Inviting…" : "Invite staff"}
          </button>
          {inviteFormError && <div className={styles.formError} role="alert">{inviteFormError}</div>}
        </form>
        </Dialog>
      )}

      {cloneOpen && canInvite && (
        <Dialog title="Clone role" onClose={() => setCloneOpen(false)}>
          <form
            className={styles.dialogForm}
            onSubmit={(e) => {
              e.preventDefault();
              const role = roles.find((r) => r.id === cloneSourceId);
              if (!role) return;
              setCloneOpen(false);
              openCloneRole(role);
            }}
          >
            <div className={styles.field}>
              <label className={styles.label} htmlFor="cloneSource">Start from</label>
              <select id="cloneSource" className={styles.select} value={cloneSourceId} onChange={(e) => setCloneSourceId(e.target.value)} required>
                {roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
              </select>
            </div>
            <p className={styles.stepUpText}>You can rename the copy and adjust its permissions before saving.</p>
            <button className={styles.submit} type="submit" disabled={!cloneSourceId}>Continue</button>
          </form>
        </Dialog>
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
            <p className={styles.stepUpText}>{stepUpKind === "reset" ? "Resetting an authenticator" : "Suspending a staff member"} needs a fresh code from your authenticator app.</p>
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
                {stepUpBusy ? "Verifying…" : stepUpKind === "reset" ? "Confirm & reset" : "Confirm & suspend"}
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}
