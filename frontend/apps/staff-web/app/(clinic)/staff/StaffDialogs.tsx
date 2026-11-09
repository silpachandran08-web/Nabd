"use client";

// FEF-032: the dialogs of Staff & Access (invite, clone role, role builder, step-up), moved unchanged from the old page.
import Link from "next/link";
import Dialog from "../../components/ui/Dialog";
import styles from "./staff.module.css";
import { ACTIONS, MODULES, type Department, type Role } from "./staffModel";
import type { useInviteForm } from "./useInviteForm";
import type { useRoleBuilder } from "./useStaffActions";
import type { useStepUp } from "./useStepUp";

export function InviteDialog({ f, roles, departments, onClose }: { f: ReturnType<typeof useInviteForm>; roles: Role[]; departments: Department[]; onClose: () => void }) {
  return (
    <Dialog title="Invite staff" onClose={onClose}>
      <form className={styles.dialogForm} onSubmit={f.submitInvite}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="inviteName">Name</label>
          <input id="inviteName" className={styles.input} value={f.inviteName} onChange={(e) => f.setInviteName(e.target.value)} required />
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="inviteEmail">Email</label>
          <input id="inviteEmail" type="email" className={styles.input} value={f.inviteEmail} onChange={(e) => f.setInviteEmail(e.target.value)} required />
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="invitePhone">Mobile</label>
          <input id="invitePhone" className={styles.input} value={f.invitePhone} onChange={(e) => f.setInvitePhone(e.target.value)} required />
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="inviteRole">Role</label>
          <select id="inviteRole" className={styles.select} value={f.inviteRoleId} onChange={(e) => f.setInviteRoleId(e.target.value)} required>
            <option value="">Select a role…</option>
            {roles.map((r) => (
              <option key={r.id} value={r.id}>{r.name}</option>
            ))}
          </select>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="inviteScope">Scope</label>
          <select id="inviteScope" className={styles.select} value={f.inviteScope} onChange={(e) => f.setInviteScope(e.target.value)}>
            <option value="all_clinic_patients">All clinic patients</option>
            <option value="own_patients_only">Own patients only</option>
          </select>
        </div>
        {departments.some((d) => d.active) && (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="inviteDepartment">Department</label>
            <select id="inviteDepartment" className={styles.select} value={f.inviteDepartmentId} onChange={(e) => f.setInviteDepartmentId(e.target.value)}>
              <option value="">Clinic default</option>
              {departments.filter((d) => d.active).map((d) => (
                <option key={d.id} value={d.id}>{d.name}</option>
              ))}
            </select>
          </div>
        )}
        <button className={styles.submit} type="submit" disabled={f.inviting}>
          {f.inviting ? "Inviting…" : "Invite staff"}
        </button>
        {f.inviteFormError && <div className={styles.formError} role="alert">{f.inviteFormError}{f.seatLimitHit && <> <Link href="/setup?tab=subscription">Upgrade your plan</Link></>}</div>}
      </form>
    </Dialog>
  );
}

export function CloneRoleDialog({ roles, sourceId, setSourceId, onClose, onContinue }: {
  roles: Role[]; sourceId: string; setSourceId: (id: string) => void; onClose: () => void; onContinue: (role: Role) => void;
}) {
  return (
    <Dialog title="Clone role" onClose={onClose}>
      <form
        className={styles.dialogForm}
        onSubmit={(e) => {
          e.preventDefault();
          const role = roles.find((r) => r.id === sourceId);
          if (!role) return;
          onContinue(role);
        }}
      >
        <div className={styles.field}>
          <label className={styles.label} htmlFor="cloneSource">Start from</label>
          <select id="cloneSource" className={styles.select} value={sourceId} onChange={(e) => setSourceId(e.target.value)} required>
            {roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
        </div>
        <p className={styles.stepUpText}>You can rename the copy and adjust its permissions before saving.</p>
        <button className={styles.submit} type="submit" disabled={!sourceId}>Continue</button>
      </form>
    </Dialog>
  );
}

export function RoleBuilderModal({ rb, roles, canInvite }: { rb: ReturnType<typeof useRoleBuilder>; roles: Role[]; canInvite: boolean }) {
  const builder = rb.builder;
  if (!builder) return null;
  return (
    <div className={styles.stepUpOverlay} onClick={() => rb.setBuilder(null)}>
      <form className={styles.roleModal} onClick={(e) => e.stopPropagation()} onSubmit={rb.submitRole}>
        <h2 className={styles.stepUpTitle}>
          {builder.mode === "create" ? "New role" : builder.mode === "edit" ? "Edit role" : "Permissions"}
        </h2>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="roleName">Role name</label>
          <input
            id="roleName"
            className={`${styles.input} ${styles.inputFull}`}
            value={builder.name}
            onChange={(e) => rb.setBuilder({ ...builder, name: e.target.value })}
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
                      checked={rb.allChecked(m.key)}
                      disabled={builder.mode === "view"}
                      onChange={() => rb.toggleAllForModule(m.key)}
                    />
                  </td>
                  {ACTIONS.map((a) => (
                    <td key={a.key}>
                      <input
                        type="checkbox"
                        checked={builder.grid[m.key][a.key]}
                        disabled={builder.mode === "view"}
                        onChange={() => rb.toggleCell(m.key, a.key)}
                      />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {rb.builderError && <div className={`${styles.formError} ${styles.formErrorSpaced}`} role="alert">{rb.builderError}</div>}
        <div className={styles.stepUpActions}>
          <button type="button" className={styles.cancelBtn} onClick={() => rb.setBuilder(null)}>
            {builder.mode === "view" ? "Close" : "Cancel"}
          </button>
          {builder.mode === "view" ? (
            canInvite && (
              <button
                type="button"
                className={styles.submit}
                onClick={() => {
                  const role = roles.find((r) => r.id === builder.sourceId);
                  if (role) rb.openCloneRole(role);
                }}
              >
                Clone this role
              </button>
            )
          ) : (
            <button type="submit" className={styles.submit} disabled={rb.builderSubmitting}>
              {rb.builderSubmitting ? "Saving…" : builder.mode === "edit" ? "Save changes" : "Create role"}
            </button>
          )}
        </div>
      </form>
    </div>
  );
}

export function StepUpDialog({ su }: { su: ReturnType<typeof useStepUp> }) {
  if (!su.stepUpFor) return null;
  return (
    <Dialog title="Confirm it's you" onClose={() => su.setStepUpFor(null)}>
      <form onSubmit={su.confirmStepUp}>
        <p className={styles.stepUpText}>{su.stepUpKind === "reset" ? "Resetting an authenticator" : "Suspending a staff member"} needs a fresh code from your authenticator app.</p>
        {su.stepUpError && <div className={styles.formError} role="alert">{su.stepUpError}</div>}
        <div className={styles.field}>
          <label className={styles.label} htmlFor="stepUpCode">Authentication code</label>
          <input
            id="stepUpCode"
            className={styles.input}
            inputMode="numeric"
            pattern="[0-9]{6}"
            maxLength={6}
            autoFocus
            value={su.stepUpCode}
            onChange={(e) => su.setStepUpCode(e.target.value.replace(/\D/g, ""))}
            required
          />
        </div>
        <div className={styles.stepUpActions}>
          <button type="button" className={styles.cancelBtn} onClick={su.cancelStepUp}>Cancel</button>
          <button type="submit" className={styles.submit} disabled={su.stepUpBusy || su.stepUpCode.length !== 6}>
            {su.stepUpBusy ? "Verifying…" : su.stepUpKind === "reset" ? "Confirm & reset" : "Confirm & suspend"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
