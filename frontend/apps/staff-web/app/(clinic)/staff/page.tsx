"use client";

// FEF-032: Staff & Access. The page owns the tab, the open dialogs and the last invite link; the data, step-up, role
// builder, row actions and invite form are hooks, the tabs and dialogs are components (all in this folder).
import { useState } from "react";
import { useRouter } from "next/navigation";
import Icon from "../../components/ui/Icon";
import RegistrationNoDialog from "../../components/staff/RegistrationNoDialog";
import styles from "./staff.module.css";
import { staffKpis } from "./staffKpis";
import type { Staff } from "./staffModel";
import { useStaffData } from "./useStaffData";
import { useStepUp } from "./useStepUp";
import { useRoleBuilder, useRowActions } from "./useStaffActions";
import { useInviteForm } from "./useInviteForm";
import { InvitesTab, PeopleCard, PeopleExtras, RolesTab, TeamSummary, type StaffTab } from "./StaffTables";
import { CloneRoleDialog, InviteDialog, RoleBuilderModal, StepUpDialog } from "./StaffDialogs";

export default function StaffAccessPage() {
  const router = useRouter();
  const d = useStaffData();
  const [inviteOpen, setInviteOpen] = useState(false);
  const [cloneOpen, setCloneOpen] = useState(false);
  const [cloneSourceId, setCloneSourceId] = useState("");
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [lastInvite, setLastInvite] = useState<{ name: string; link: string } | null>(null);
  const [regEdit, setRegEdit] = useState<Staff | null>(null);
  // ADM-018: tabs, seat tile, one-tap invite resend and MFA reminder.
  const [tab, setTab] = useState<StaffTab>("people");
  const su = useStepUp({ setError: d.setError, setStaff: d.setStaff });
  const rb = useRoleBuilder({ setRoles: d.setRoles });
  const rows = useRowActions({ setStaff: d.setStaff, setRoles: d.setRoles, setReview: d.setReview, setLastInvite });
  const invite = useInviteForm({ nextCursor: d.nextCursor, setStaff: d.setStaff, setLastInvite, close: () => setInviteOpen(false) });

  const { staff, roles, loading, forbidden, nextCursor, permissions } = d;
  const kpis = staffKpis(staff, d.seats);
  const pending = staff.filter((s) => s.status === "invited");
  const selectedRole = roles.find((r) => r.id === selectedRoleId) ?? roles[0] ?? null;
  const g = {
    canInvite: permissions.includes("staff:create"),
    canSuspend: permissions.includes("staff:delete"),
    canResetMfa: permissions.includes("staff:edit"),
    canEditRoles: permissions.includes("staff:edit"),
  };

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Staff & Access</h1>
          <p className={styles.subtitle}>{staff.length}{nextCursor ? "+" : ""} team member{staff.length === 1 && !nextCursor ? "" : "s"}</p>
        </div>
        <div className={styles.headerActions}>
          {g.canInvite && (
            <button className={styles.actionBtn} onClick={() => { setCloneSourceId(roles[0]?.id ?? ""); setCloneOpen(true); }}>
              <Icon name="list" size={14} /> Clone role
            </button>
          )}
          {g.canInvite && (
            <button className={styles.inviteBtn} onClick={() => { invite.setInviteFormError(null); setInviteOpen(true); }}>
              <Icon name="plus" size={14} /> Invite staff
            </button>
          )}
          <button className={styles.actionBtn} onClick={() => router.push("/account")}>My Account & Security</button>
        </div>
      </div>

      {d.error && !loading && staff.length > 0 && <div className={styles.formError} role="alert">{d.error}</div>}
      {su.resetNotice && <div className={styles.formError} role="status">{su.resetNotice}</div>}
      {d.rolesFailed && !loading && staff.length > 0 && (
        <div className={styles.formError} role="alert">Couldn&apos;t load roles, so Role and Restriction show &ldquo;—&rdquo;. Reload to try again.</div>
      )}

      {lastInvite && (
        <div className={styles.inviteBanner}>
          <span>Invite link for {lastInvite.name} — no email service yet, share this by hand:</span>
          <span className={styles.inviteLink}>{lastInvite.link}</span>
        </div>
      )}

      {rows.rowNotice && <div className={styles.formError} role="status">{rows.rowNotice}</div>}
      {!loading && !forbidden && staff.length > 0 && <TeamSummary kpis={kpis} tab={tab} setTab={setTab} />}

      {tab === "invites" && !loading && !forbidden && <InvitesTab pending={pending} roles={roles} canInvite={g.canInvite} rows={rows} />}

      <PeopleCard d={d} hidden={tab !== "people"} g={g} rows={rows} su={su} onRegEdit={setRegEdit} />

      {!loading && !forbidden && tab === "roles" && <RolesTab d={d} selectedRole={selectedRole} setSelectedRoleId={setSelectedRoleId} g={g} rows={rows} rb={rb} />}

      {!loading && !forbidden && tab === "people" && <PeopleExtras d={d} canEditRoles={g.canEditRoles} rows={rows} />}

      {regEdit && g.canEditRoles && (
        <RegistrationNoDialog staffId={regEdit.id} staffName={regEdit.name} current={regEdit.professionalRegistrationNo ?? null}
          onSave={(v) => rows.saveRegistrationNo(regEdit, v)} onClose={() => setRegEdit(null)}
          onClinicianSaved={(on) => rows.clinicianSaved(regEdit.id, on)} />
      )}

      {inviteOpen && g.canInvite && <InviteDialog f={invite} roles={roles} departments={d.departments} onClose={() => setInviteOpen(false)} />}

      {cloneOpen && g.canInvite && (
        <CloneRoleDialog roles={roles} sourceId={cloneSourceId} setSourceId={setCloneSourceId} onClose={() => setCloneOpen(false)}
          onContinue={(role) => { setCloneOpen(false); rb.openCloneRole(role); }} />
      )}

      <RoleBuilderModal rb={rb} roles={roles} canInvite={g.canInvite} />

      <StepUpDialog su={su} />
    </main>
  );
}
