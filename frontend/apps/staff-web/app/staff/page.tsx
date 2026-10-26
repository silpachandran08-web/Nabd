"use client";

import { decodeJwtPayload } from "../lib/jwt";
import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import Dialog from "../components/ui/Dialog";
import Icon from "../components/ui/Icon";
import StatusPill, { type PillTone } from "../components/ui/StatusPill";
import { mfaBadge } from "./mfaStatus";
import { isSeatLimit, needsMfaReminder, staffKpis, type Seats } from "./staffKpis";
import { Skeleton, EmptyState, ErrorState } from "../components/ui/StateViews";
import PermissionMatrix from "../components/staff/PermissionMatrix";
import RegistrationNoDialog from "../components/staff/RegistrationNoDialog";
import styles from "./staff.module.css";
import { goLogin } from "../lib/apiClient";
import { api, ApiError, reasonText, setStepUpHandler } from "../lib/api";
import { isOwner } from "../lib/session";

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
  clinician?: boolean; // PLT-016: drives doctor pickers instead of role names
};
type StaffPage = { data: Staff[]; page: { nextCursor: string | null; limit: number } };
type ModuleGrant = {
  module: string; view: boolean; create: boolean; edit: boolean; delete: boolean;
  approve: boolean; refundDiscount: boolean; export: boolean;
};
type Role = { id: string; name: string; builtIn: boolean; grants: ModuleGrant[]; mfaRequired: boolean; presetCode?: string | null; presetVersion?: number | null };
// PLT-016: rows the clinician backfill could not decide (GET /v1/staff/clinician-review).
type ClinicianReview = { staffId: string; name: string; roleName: string; reason: string; clinician: boolean };
const REVIEW_REASON: Record<string, string> = {
  registration_without_doctor_role: "Has a registration number but no doctor role",
  doctor_role_without_registration: "Doctor role but no registration number",
};
type BreakGlassGrant = { grantId: string; staffName: string; reasonCode: string; activatedAt: string; expiresAt: string };
type Department = { id: string; name: string; isDefault: boolean; active: boolean };
// GlobalExceptionHandler serializes RFC 7807 ProblemDetail — the machine-readable
// error code lives in `type` (a "…/errors/<slug>" URI), not `title` (human text).

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
  const [breakGlass, setBreakGlass] = useState<BreakGlassGrant[]>([]);
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
  const [review, setReview] = useState<ClinicianReview[]>([]);
  const [reviewFailed, setReviewFailed] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [roleNotice, setRoleNotice] = useState<string | null>(null);
  const [owner, setOwner] = useState(false);
  // ADM-018: tabs, seat tile, one-tap invite resend and MFA reminder.
  const [tab, setTab] = useState<"people" | "roles" | "invites">("people");
  const [seats, setSeats] = useState<Seats>(null);
  const [seatLimitHit, setSeatLimitHit] = useState(false);
  const [rowNotice, setRowNotice] = useState<string | null>(null);

  // FEF-018 step-up: the client asks this when a call answers "step-up-required"; the dialog resolves with a step-up
  // token (the client then replays the original request once) or null (Cancel).
  const stepUpWait = useRef<((token: string | null) => void) | null>(null);
  const stepUpTarget = useRef<string | null>(null);
  const stepUpCancelled = useRef(false);
  const stepUpShown = useRef(false);
  const askStepUp = useRef<() => Promise<string | null>>(() => Promise.resolve(null));
  useEffect(() => {
    askStepUp.current = () => new Promise<string | null>((resolve) => {
      stepUpWait.current = resolve;
      stepUpShown.current = true;
      setStepUpFor(stepUpTarget.current);
      setStepUpCode("");
      setStepUpError(null);
    });
    setStepUpHandler(() => askStepUp.current());
    return () => setStepUpHandler(null);
  }, []);
  function cancelStepUp() {
    stepUpCancelled.current = true;
    stepUpWait.current?.(null);
    stepUpWait.current = null;
    setStepUpFor(null);
  }

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
      setOwner(isOwner());

      let staffBody: StaffPage;
      try {
        staffBody = await api.get<StaffPage>("/staff?limit=50");
      } catch (e) {
        if (e instanceof ApiError && e.status === 403) setForbidden(true);
        else setError(e instanceof ApiError && e.status < 500 ? "Couldn't load staff. Try again." : reasonText(e, ""));
        return;
      }
      setStaff(staffBody.data);
      setNextCursor(staffBody.page.nextCursor);

      // The side reads fail on their own (a flag or an empty list), never the whole page.
      const side = async <T,>(path: string, set: (v: T) => void, failed?: (f: boolean) => void) => {
        try { set(await api.get<T>(path)); failed?.(false); } catch { failed?.(true); }
      };
      await Promise.all([
        side<Role[]>("/roles", setRoles, setRolesFailed),
        side<ClinicianReview[]>("/staff/clinician-review", setReview, setReviewFailed),
        side<Department[]>("/departments", setDepartments),
        decodePermissions(token).includes("setup:view") ? side<{ seatsUsed: number; seatsLimit: number | null }>("/setup/subscription", (b) => setSeats({ seatsUsed: b.seatsUsed, seatsLimit: b.seatsLimit })) : null,
        side<{ items?: BreakGlassGrant[] }>("/auth/break-glass/active", (b) => setBreakGlass(b.items ?? [])),
      ]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function loadMore() {
    if (!nextCursor) return;
    setLoadingMore(true);
    try {
      const body = await api.get<StaffPage>(`/staff?limit=50&cursor=${encodeURIComponent(nextCursor)}`);
      setStaff((prev) => [...prev, ...body.data]);
      setNextCursor(body.page.nextCursor);
    } catch {
      /* the Load more button stays; pressing it again retries */
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
      // PLT-005: false is never sent; the server forces MFA on approve/refund roles and 422s an explicit false
      const saved = await api.request<Role>(isEdit ? "PATCH" : "POST", isEdit ? `/roles/${builder.sourceId}` : "/roles", { body: { name, grants, ...(builder.mfaRequired ? { mfaRequired: true } : {}) } });
      setRoles((prev) => (isEdit ? prev.map((r) => (r.id === saved.id ? saved : r)) : [...prev, saved]));
      setBuilder(null);
    } catch (e) {
      setBuilderError(reasonText(e, "Couldn't save the role."));
    } finally {
      setBuilderSubmitting(false);
    }
  }

  async function submitInvite(e: React.FormEvent) {
    e.preventDefault();
    setInviteFormError(null);
    setSeatLimitHit(false);
    if (!inviteEmail || !inviteName || !invitePhone || !inviteRoleId) {
      setInviteFormError("Fill in every field.");
      return;
    }
    setInviting(true);
    try {
      const body = await api.post<{ staff: Staff; inviteToken: string }>("/staff", {
        email: inviteEmail, name: inviteName, mobilePhone: invitePhone, roleId: inviteRoleId, scope: inviteScope,
        departmentId: inviteDepartmentId || null,
      });
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
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && isSeatLimit(e.problem)) setSeatLimitHit(true);
      setInviteFormError(reasonText(e, "Couldn't send invite."));
    } finally {
      setInviting(false);
    }
  }

  // Suspend and authenticator reset both need a step-up: the client middleware asks the dialog below, then replays
  // the call once with the token. A wrong code keeps the dialog open; Cancel ends the action quietly.
  async function stepUpAction(kind: "suspend" | "reset", id: string, preset?: string) {
    const reset = kind === "reset";
    setError(null);
    setResetNotice(null);
    setStepUpKind(kind);
    stepUpTarget.current = id;
    stepUpCancelled.current = false;
    stepUpShown.current = !!preset;
    setSuspending(id);
    try {
      if (reset) {
        // Resetting an authenticator always needs a fresh code: ask first, no failed round trip (PLT-004).
        const token = preset ?? (await askStepUp.current());
        if (!token) return;
        await api.post(`/staff/${id}/mfa/reset`, { reasonCode: "lost_device" }, { headers: { "X-Step-Up-Token": token } });
      } else await api.post(`/staff/${id}/suspend`, undefined, preset ? { headers: { "X-Step-Up-Token": preset } } : undefined);
      if (reset) setResetNotice("Authenticator reset. They are signed out and must set up a new one at next sign-in.");
      else setStaff((prev) => prev.map((s) => (s.id === id ? { ...s, status: "suspended" } : s)));
      setStepUpFor(null);
    } catch (e) {
      if (stepUpCancelled.current) return;
      const text = reasonText(e, reset ? "Couldn't reset the authenticator." : "Couldn't suspend staff member.");
      if (stepUpShown.current) setStepUpError(text);
      else setError(text);
    } finally {
      stepUpWait.current = null;
      setStepUpBusy(false);
      setSuspending(null);
    }
  }
  const suspend = (id: string) => stepUpAction("suspend", id);
  const startReset = (id: string) => stepUpAction("reset", id);

  async function confirmStepUp(e: React.FormEvent) {
    e.preventDefault();
    if (!stepUpFor) return;
    setStepUpBusy(true);
    setStepUpError(null);
    try {
      // keepSession: a wrong code also answers 401 ("mfa-failed"); that must not sign the caller out mid-action.
      const { stepUpToken } = await api.post<{ stepUpToken: string }>("/auth/mfa/verify", { challengeId: "", code: stepUpCode }, { keepSession: true });
      if (stepUpWait.current) {
        stepUpWait.current(stepUpToken); // the original request is replayed by the client; stepUpAction finishes the flow
        stepUpWait.current = null;
      } else await stepUpAction(stepUpKind, stepUpFor, stepUpToken); // the action already failed and the dialog stayed open: retry with the new token
    } catch (err) {
      setStepUpError(reasonText(err, "Verification failed."));
      setStepUpBusy(false);
    }
  }

  async function saveRegistrationNo(target: Staff, value: string): Promise<string | null> {
    try {
      // "" clears it (null/absent would leave it unchanged).
      const saved = await api.patch<Staff>(`/staff/${target.id}`, { professionalRegistrationNo: value });
      setStaff((prev) => prev.map((x) => (x.id === saved.id ? { ...x, professionalRegistrationNo: saved.professionalRegistrationNo ?? null, clinician: saved.clinician } : x)));
      return null; // the dialog closes itself once the clinician status (CLN-005) is saved too
    } catch (e) {
      return reasonText(e, "Couldn't save the registration number.");
    }
  }

  function clinicianSaved(staffId: string, clinician: boolean) {
    setStaff((prev) => prev.map((x) => (x.id === staffId ? { ...x, clinician } : x)));
    setReview((prev) => prev.filter((r) => r.staffId !== staffId));
  }

  // PLT-016: the Clinician flag (needs a registration number; the server refuses it otherwise).
  async function setClinician(staffId: string, value: boolean) {
    setBusyId(staffId);
    setRoleNotice(null);
    try {
      const saved = await api.patch<Staff>(`/staff/${staffId}`, { clinician: value });
      clinicianSaved(saved.id, !!saved.clinician);
    } catch (e) {
      setRoleNotice(reasonText(e, "Couldn't change the clinician flag."));
    } finally {
      setBusyId(null);
    }
  }

  // PLT-016: Owner only; grants go back to the current preset definition (audited server-side).
  async function resetToPreset(role: Role) {
    setBusyId(role.id);
    setRoleNotice(null);
    try {
      const saved = await api.post<Role>(`/roles/${role.id}/reset-to-preset`, {});
      setRoles((prev) => prev.map((r) => (r.id === saved.id ? saved : r)));
      setRoleNotice(`${saved.name} is back to its preset.`);
    } catch (e) {
      setRoleNotice(reasonText(e, "Couldn't reset the role."));
    } finally {
      setBusyId(null);
    }
  }

  // ADM-018: a new 72 h link in one tap; the owner relays it like a first invite.
  async function resendInvite(s: Staff) {
    setBusyId(s.id);
    setRowNotice(null);
    try {
      const body = await api.post<{ inviteToken: string }>(`/staff/${s.id}/invite/resend`, {});
      setLastInvite({ name: s.name, link: `${window.location.origin}/accept-invite/${body.inviteToken}` });
    } catch (e) {
      setRowNotice(reasonText(e, "Couldn't resend the invite."));
    } finally {
      setBusyId(null);
    }
  }

  async function remindMfa(s: Staff) {
    setBusyId(s.id);
    setRowNotice(null);
    try {
      await api.post(`/staff/${s.id}/mfa/remind`, {});
      setRowNotice(`MFA reminder queued for ${s.name}.`);
    } catch (e) {
      setRowNotice(reasonText(e, "Couldn't send the reminder."));
    } finally {
      setBusyId(null);
    }
  }

  const kpis = staffKpis(staff, seats);
  const pending = staff.filter((s) => s.status === "invited");

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

      {rowNotice && <div className={styles.formError} role="status">{rowNotice}</div>}
      {!loading && !forbidden && staff.length > 0 && (
        <>
          <ul className={styles.kpis} aria-label="Team summary">
            <li className={styles.kpi}><span className={styles.kpiValue}>{kpis.active}</span><span className={styles.kpiLabel}>Active staff</span></li>
            <li className={styles.kpi} data-testid="kpi-mfa">
              <span className={styles.kpiValue}>{kpis.mfaPercent == null ? "—" : `${kpis.mfaPercent}%`}</span>
              <span className={styles.kpiLabel}>MFA coverage{kpis.mfaMissing > 0 ? ` · ${kpis.mfaMissing} to set up` : ""}</span>
            </li>
            <li className={styles.kpi}><span className={styles.kpiValue}>{kpis.pendingInvites}</span><span className={styles.kpiLabel}>Pending invites</span></li>
            {kpis.seatsText && <li className={styles.kpi} data-testid="kpi-seats"><span className={styles.kpiValue}>{kpis.seatsText}</span><span className={styles.kpiLabel}>Seats{kpis.seatsLeft != null ? ` · ${kpis.seatsLeft} left` : ""}</span></li>}
          </ul>
          <div className={styles.tabs} role="tablist" aria-label="Staff sections">
            {([["people", "People"], ["roles", "Roles & permissions"], ["invites", `Invites${kpis.pendingInvites ? ` (${kpis.pendingInvites})` : ""}`]] as const).map(([k, label]) => (
              <button key={k} type="button" role="tab" aria-selected={tab === k} className={`${styles.tab} ${tab === k ? styles.tabActive : ""}`} onClick={() => setTab(k)}>{label}</button>
            ))}
          </div>
        </>
      )}

      {tab === "invites" && !loading && !forbidden && (
        <div className={styles.card}>
          {pending.length === 0 ? <EmptyState icon="users" title="No pending invites." /> : (
            <div className={styles.tableWrap}>
              <table className={styles.table} aria-label="Pending invites">
                <thead><tr><th scope="col">Invitee</th><th scope="col">Role</th><th scope="col"></th></tr></thead>
                <tbody>
                  {pending.map((s) => (
                    <tr key={s.id}>
                      <td><span className={styles.staffName}>{s.name}</span><span className={styles.staffEmail}>{s.email}</span></td>
                      <td>{roleName(s.roleId)}</td>
                      <td>{canInvite && <button className={styles.actionBtn} disabled={busyId === s.id} onClick={() => resendInvite(s)}>Resend invite</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className={styles.note}>Invite links work once and last 72 hours. Resending replaces the old link.</p>
        </div>
      )}

      <div className={styles.card} hidden={tab !== "people"}>
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
                  <th scope="col">Staff member</th>
                  <th scope="col">Role</th>
                  <th scope="col">Status</th>
                  <th scope="col">Restriction</th>
                  <th scope="col">Active</th>
                  {showLogin && <th scope="col">Login</th>}
                  {showLogin && <th scope="col">MFA</th>}
                  <th scope="col"></th>
                </tr>
              </thead>
              <tbody>
                {staff.map((s) => (
                  <tr key={s.id}>
                    <td>
                      <span className={styles.staffName}>{s.name}</span>
                      <span className={styles.staffEmail}>{s.email}</span>
                      {s.clinician && <span className={`${styles.pill} ${styles.pillCustom}`}>Clinician</span>}
                    </td>
                    <td><span className={`${styles.pill} ${styles.pillRole}`}>{roleName(s.roleId)}</span></td>
                    <td><StatusPill tone={STATUS_PILL[s.status].tone} label={STATUS_PILL[s.status].label} /></td>
                    <td className={styles.muted}>{restrictionOf(s)}</td>
                    <td className={styles.muted} title={s.lastSeenAt ? new Date(s.lastSeenAt).toLocaleString() : undefined}>{s.lastSeenAt ? relativeTime(s.lastSeenAt) : "—"}</td>
                    {showLogin && (
                      <td className={styles.muted}>{s.loginMethod ? (LOGIN_METHOD_LABELS[s.loginMethod] ?? s.loginMethod) : "—"}</td>
                    )}
                    {showLogin && (() => { const m = mfaBadge(s); return <td data-testid="mfa-status"><StatusPill tone={m.tone} label={m.label} /></td>; })()}
                    <td>
                      {/* CARRY-12: the one clinician control per row (CLN-005 dialog: number, clinician flag, expiry). */}
                      {canEditRoles && (
                        <button className={styles.actionBtn} onClick={() => setRegEdit(s)}>
                          {s.professionalRegistrationNo ? "Reg. no. & clinician" : "Add reg. no. & clinician"}
                        </button>
                      )}
                      {canResetMfa && needsMfaReminder(s) && (
                        <button className={styles.actionBtn} disabled={busyId === s.id} onClick={() => remindMfa(s)}>Send MFA reminder</button>
                      )}
                      {canInvite && s.status === "invited" && (
                        <button className={styles.actionBtn} disabled={busyId === s.id} onClick={() => resendInvite(s)}>Resend invite</button>
                      )}
                      {canResetMfa && s.status === "active" && (
                        <button className={styles.actionBtn} onClick={() => startReset(s.id)}>Reset authenticator</button>
                      )}
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

      {!loading && !forbidden && tab === "roles" && (
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
                        <span className={`${styles.pill} ${r.builtIn || r.presetCode ? styles.pillBuiltIn : styles.pillCustom}`}>{r.builtIn ? "Built-in" : r.presetCode ? "Preset" : "Custom"}</span>
                        <button className={styles.actionBtn} onClick={() => openRole(r, canEditRoles)}>
                          {r.builtIn || !canEditRoles ? "View" : "Edit"}
                        </button>
                        {canInvite && <button className={styles.actionBtn} onClick={() => openCloneRole(r)}>Clone</button>}
                        {owner && canEditRoles && r.presetCode && !r.builtIn && (
                          <button className={styles.actionBtn} disabled={busyId === r.id} onClick={() => resetToPreset(r)}>Reset to preset</button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
              <p className={styles.note}>Anything a user lacks is removed from the interface — hidden, not greyed out.</p>
            </aside>
            {selectedRole && <PermissionMatrix roleName={selectedRole.name} grants={selectedRole.grants} modules={MODULES} />}
          </div>
          {roleNotice && <div className={styles.formError} role="status">{roleNotice}</div>}

        </>
      )}

      {!loading && !forbidden && tab === "people" && (
        <>
          {reviewFailed && <div className={styles.formError} role="alert">Couldn&apos;t load the clinicians to confirm. Reload to try again.</div>}
          {review.length > 0 && (
            <>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Confirm clinicians</h2>
              </div>
              <div className={styles.card}>
                <div className={styles.tableWrap}>
                  <table className={styles.table} aria-label="Clinicians to confirm">
                    <thead><tr><th scope="col">Staff</th><th scope="col">Role</th><th scope="col">Why</th><th scope="col"></th></tr></thead>
                    <tbody>
                      {review.map((r) => (
                        <tr key={r.staffId}>
                          <td className={styles.staffName}>{r.name}</td>
                          <td>{r.roleName}</td>
                          <td className={styles.muted}>{REVIEW_REASON[r.reason] ?? r.reason}</td>
                          <td>
                            {canEditRoles && (
                              <>
                                <button className={styles.actionBtn} disabled={busyId === r.staffId} onClick={() => setClinician(r.staffId, true)}>Clinician</button>
                                <button className={styles.actionBtn} disabled={busyId === r.staffId} onClick={() => setClinician(r.staffId, false)}>Not a clinician</button>
                              </>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}

          {breakGlass.length > 0 && (
            <>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Emergency access — currently elevated</h2>
              </div>
              <div className={styles.card}>
                <div className={styles.tableWrap}>
                  <table className={styles.table}>
                    <thead><tr><th scope="col">Staff</th><th scope="col">Reason</th><th scope="col">Activated</th><th scope="col">Expires</th></tr></thead>
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
        <RegistrationNoDialog staffId={regEdit.id} staffName={regEdit.name} current={regEdit.professionalRegistrationNo ?? null}
          onSave={(v) => saveRegistrationNo(regEdit, v)} onClose={() => setRegEdit(null)}
          onClinicianSaved={(on) => clinicianSaved(regEdit.id, on)} />
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
          {inviteFormError && <div className={styles.formError} role="alert">{inviteFormError}{seatLimitHit && <> <Link href="/setup?tab=subscription">Upgrade your plan</Link></>}</div>}
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
                    <th scope="col">Module</th>
                    <th scope="col">All</th>
                    {ACTIONS.map((a) => <th scope="col" key={a.key}>{a.label}</th>)}
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
        <Dialog title="Confirm it's you" onClose={() => setStepUpFor(null)}>
          <form onSubmit={confirmStepUp}>
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
              <button type="button" className={styles.cancelBtn} onClick={cancelStepUp}>Cancel</button>
              <button type="submit" className={styles.submit} disabled={stepUpBusy || stepUpCode.length !== 6}>
                {stepUpBusy ? "Verifying…" : stepUpKind === "reset" ? "Confirm & reset" : "Confirm & suspend"}
              </button>
            </div>
          </form>
        </Dialog>
      )}
    </main>
  );
}
