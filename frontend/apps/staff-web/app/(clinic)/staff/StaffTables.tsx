"use client";

// FEF-032: the three tabs of Staff & Access (People, Roles & permissions, Invites) and the team summary above them,
// moved unchanged from the old single page. The tab stays page state: all three read the one loaded staff list.
import StatusPill from "../../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import PermissionMatrix from "../../components/staff/PermissionMatrix";
import styles from "./staff.module.css";
import { mfaBadge } from "./mfaStatus";
import { needsMfaReminder, type staffKpis } from "./staffKpis";
import { LOGIN_METHOD_LABELS, MODULES, REVIEW_REASON, STATUS_PILL, relativeTime, restrictionOf, roleNameOf, type Role, type Staff } from "./staffModel";
import type { useStaffData } from "./useStaffData";
import type { useRoleBuilder, useRowActions } from "./useStaffActions";
import type { useStepUp } from "./useStepUp";

export type StaffTab = "people" | "roles" | "invites";
type Data = ReturnType<typeof useStaffData>;
type Rows = ReturnType<typeof useRowActions>;
type Grants = { canInvite: boolean; canSuspend: boolean; canResetMfa: boolean; canEditRoles: boolean };

export function TeamSummary({ kpis, tab, setTab }: { kpis: ReturnType<typeof staffKpis>; tab: StaffTab; setTab: (t: StaffTab) => void }) {
  return (
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
  );
}

export function InvitesTab({ pending, roles, canInvite, rows }: { pending: Staff[]; roles: Role[]; canInvite: boolean; rows: Rows }) {
  return (
    <div className={styles.card}>
      {pending.length === 0 ? <EmptyState icon="users" title="No pending invites." /> : (
        <div className={styles.tableWrap}>
          <table className={styles.table} aria-label="Pending invites">
            <thead><tr><th scope="col">Invitee</th><th scope="col">Role</th><th scope="col"></th></tr></thead>
            <tbody>
              {pending.map((s) => (
                <tr key={s.id}>
                  <td><span className={styles.staffName}>{s.name}</span><span className={styles.staffEmail}>{s.email}</span></td>
                  <td>{roleNameOf(roles, s.roleId)}</td>
                  <td>{canInvite && <button className={styles.actionBtn} disabled={rows.busyId === s.id} onClick={() => rows.resendInvite(s)}>Resend invite</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className={styles.note}>Invite links work once and last 72 hours. Resending replaces the old link.</p>
    </div>
  );
}

/** The People card stays mounted (hidden) on the other tabs, as before the split. */
export function PeopleCard({ d, hidden, g, rows, su, onRegEdit }: { d: Data; hidden: boolean; g: Grants; rows: Rows; su: ReturnType<typeof useStepUp>; onRegEdit: (s: Staff) => void }) {
  const { staff, roles } = d;
  // Login column only when the backend reports it (BE2 OWN-034); an older backend shows no column.
  const showLogin = staff.some((s) => typeof s.loginMethod === "string");
  return (
    <div className={styles.card} hidden={hidden}>
      {d.loading ? (
        <Skeleton rows={5} label="Loading staff" />
      ) : d.forbidden ? (
        <EmptyState icon="shield" title="Your role doesn't have access to staff management." />
      ) : d.error && staff.length === 0 ? (
        <ErrorState message={d.error} onRetry={d.load} />
      ) : staff.length === 0 ? (
        <EmptyState icon="users" title="No staff yet." hint={g.canInvite ? "Use Invite staff to add the first team member." : undefined} />
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
                  <td><span className={`${styles.pill} ${styles.pillRole}`}>{roleNameOf(roles, s.roleId)}</span></td>
                  <td><StatusPill tone={STATUS_PILL[s.status].tone} label={STATUS_PILL[s.status].label} /></td>
                  <td className={styles.muted}>{restrictionOf(s, roles)}</td>
                  <td className={styles.muted} title={s.lastSeenAt ? new Date(s.lastSeenAt).toLocaleString() : undefined}>{s.lastSeenAt ? relativeTime(s.lastSeenAt) : "—"}</td>
                  {showLogin && (
                    <td className={styles.muted}>{s.loginMethod ? (LOGIN_METHOD_LABELS[s.loginMethod] ?? s.loginMethod) : "—"}</td>
                  )}
                  {showLogin && (() => { const m = mfaBadge(s); return <td data-testid="mfa-status"><StatusPill tone={m.tone} label={m.label} /></td>; })()}
                  <td>
                    {/* CARRY-12: the one clinician control per row (CLN-005 dialog: number, clinician flag, expiry). */}
                    {g.canEditRoles && (
                      <button className={styles.actionBtn} onClick={() => onRegEdit(s)}>
                        {s.professionalRegistrationNo ? "Reg. no. & clinician" : "Add reg. no. & clinician"}
                      </button>
                    )}
                    {g.canResetMfa && needsMfaReminder(s) && (
                      <button className={styles.actionBtn} disabled={rows.busyId === s.id} onClick={() => rows.remindMfa(s)}>Send MFA reminder</button>
                    )}
                    {g.canInvite && s.status === "invited" && (
                      <button className={styles.actionBtn} disabled={rows.busyId === s.id} onClick={() => rows.resendInvite(s)}>Resend invite</button>
                    )}
                    {g.canResetMfa && s.status === "active" && (
                      <button className={styles.actionBtn} onClick={() => su.startReset(s.id)}>Reset authenticator</button>
                    )}
                    {g.canSuspend && s.status === "active" && (
                      <button className={styles.actionBtn} disabled={su.suspending === s.id} onClick={() => su.suspend(s.id)}>
                        {su.suspending === s.id ? "…" : "Suspend"}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {d.nextCursor && !d.loading && !d.forbidden && (
        <div className={styles.footer}>
          <button className={styles.loadMore} onClick={d.loadMore} disabled={d.loadingMore}>
            {d.loadingMore ? "Loading…" : "Load more"}
          </button>
        </div>
      )}
    </div>
  );
}

export function RolesTab({ d, selectedRole, setSelectedRoleId, g, rows, rb }: {
  d: Data; selectedRole: Role | null; setSelectedRoleId: (id: string) => void; g: Grants; rows: Rows; rb: ReturnType<typeof useRoleBuilder>;
}) {
  const { roles, staff, nextCursor } = d;
  // Counts come from the staff pages loaded so far (RoleResponse has no user count).
  const roleUserCount = (roleId: string) => staff.filter((s) => s.roleId === roleId).length;
  return (
    <>
      <div className={styles.sectionHeader}>
        <h2 className={styles.sectionTitle}>Role presets</h2>
        {g.canInvite && <button className={styles.actionBtn} onClick={rb.openNewRole}>+ New role</button>}
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
                    <button className={styles.actionBtn} onClick={() => rb.openRole(r, g.canEditRoles)}>
                      {r.builtIn || !g.canEditRoles ? "View" : "Edit"}
                    </button>
                    {g.canInvite && <button className={styles.actionBtn} onClick={() => rb.openCloneRole(r)}>Clone</button>}
                    {d.owner && g.canEditRoles && r.presetCode && !r.builtIn && (
                      <button className={styles.actionBtn} disabled={rows.busyId === r.id} onClick={() => rows.resetToPreset(r)}>Reset to preset</button>
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
      {rows.roleNotice && <div className={styles.formError} role="status">{rows.roleNotice}</div>}

    </>
  );
}

/** Below the People table: clinicians the backfill could not decide, and live emergency (break-glass) access. */
export function PeopleExtras({ d, canEditRoles, rows }: { d: Data; canEditRoles: boolean; rows: Rows }) {
  return (
    <>
      {d.reviewFailed && <div className={styles.formError} role="alert">Couldn&apos;t load the clinicians to confirm. Reload to try again.</div>}
      {d.review.length > 0 && (
        <>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Confirm clinicians</h2>
          </div>
          <div className={styles.card}>
            <div className={styles.tableWrap}>
              <table className={styles.table} aria-label="Clinicians to confirm">
                <thead><tr><th scope="col">Staff</th><th scope="col">Role</th><th scope="col">Why</th><th scope="col"></th></tr></thead>
                <tbody>
                  {d.review.map((r) => (
                    <tr key={r.staffId}>
                      <td className={styles.staffName}>{r.name}</td>
                      <td>{r.roleName}</td>
                      <td className={styles.muted}>{REVIEW_REASON[r.reason] ?? r.reason}</td>
                      <td>
                        {canEditRoles && (
                          <>
                            <button className={styles.actionBtn} disabled={rows.busyId === r.staffId} onClick={() => rows.setClinician(r.staffId, true)}>Clinician</button>
                            <button className={styles.actionBtn} disabled={rows.busyId === r.staffId} onClick={() => rows.setClinician(r.staffId, false)}>Not a clinician</button>
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

      {d.breakGlass.length > 0 && (
        <>
          <div className={styles.sectionHeader}>
            <h2 className={styles.sectionTitle}>Emergency access — currently elevated</h2>
          </div>
          <div className={styles.card}>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th scope="col">Staff</th><th scope="col">Reason</th><th scope="col">Activated</th><th scope="col">Expires</th></tr></thead>
                <tbody>
                  {d.breakGlass.map((g) => (
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
  );
}
