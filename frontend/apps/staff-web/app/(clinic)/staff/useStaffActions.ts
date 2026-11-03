"use client";

// FEF-032: role builder and per-row actions of Staff & Access (registration number, clinician flag, reset to preset,
// invite resend, MFA reminder), moved unchanged from the old single page.
import { useState, type Dispatch, type SetStateAction } from "react";
import { api, reasonText } from "../../lib/api";
import { ACTIONS, extraGrantsOf, grantsFromGrid, gridFromGrants, type ActionKey, type Builder, type ClinicianReview, type Role, type Staff } from "./staffModel";

export const inviteLink = (token: string) => `${window.location.origin}/accept-invite/${token}`;

export function useRoleBuilder({ setRoles }: { setRoles: Dispatch<SetStateAction<Role[]>> }) {
  const [builder, setBuilder] = useState<Builder | null>(null);
  const [builderError, setBuilderError] = useState<string | null>(null);
  const [builderSubmitting, setBuilderSubmitting] = useState(false);

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

  return { builder, setBuilder, builderError, builderSubmitting, openNewRole, openCloneRole, openRole, toggleCell, allChecked, toggleAllForModule, submitRole };
}

export function useRowActions({ setStaff, setRoles, setReview, setLastInvite }: {
  setStaff: Dispatch<SetStateAction<Staff[]>>; setRoles: Dispatch<SetStateAction<Role[]>>; setReview: Dispatch<SetStateAction<ClinicianReview[]>>;
  setLastInvite: (v: { name: string; link: string }) => void;
}) {
  const [busyId, setBusyId] = useState<string | null>(null);
  const [roleNotice, setRoleNotice] = useState<string | null>(null);
  const [rowNotice, setRowNotice] = useState<string | null>(null);

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
      setLastInvite({ name: s.name, link: inviteLink(body.inviteToken) });
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

  return { busyId, roleNotice, rowNotice, saveRegistrationNo, clinicianSaved, setClinician, resetToPreset, resendInvite, remindMfa };
}
