"use client";

// FEF-032: the Invite staff form. Its values live in the page (not the dialog), so closing and reopening the dialog
// keeps what was typed, as before the split.
import { useState, type Dispatch, type SetStateAction } from "react";
import { api, ApiError, reasonText } from "../../lib/api";
import { isSeatLimit } from "./staffKpis";
import { inviteLink } from "./useStaffActions";
import type { Staff } from "./staffModel";

export function useInviteForm({ nextCursor, setStaff, setLastInvite, close }: {
  nextCursor: string | null; setStaff: Dispatch<SetStateAction<Staff[]>>; setLastInvite: (v: { name: string; link: string }) => void; close: () => void;
}) {
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteName, setInviteName] = useState("");
  const [invitePhone, setInvitePhone] = useState("");
  const [inviteRoleId, setInviteRoleId] = useState("");
  const [inviteScope, setInviteScope] = useState("all_clinic_patients");
  const [inviteDepartmentId, setInviteDepartmentId] = useState("");
  const [inviting, setInviting] = useState(false);
  const [inviteFormError, setInviteFormError] = useState<string | null>(null);
  const [seatLimitHit, setSeatLimitHit] = useState(false);

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
      setLastInvite({ name: body.staff.name, link: inviteLink(body.inviteToken) });
      setInviteEmail("");
      setInviteName("");
      setInvitePhone("");
      setInviteRoleId("");
      setInviteScope("all_clinic_patients");
      setInviteDepartmentId("");
      close();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && isSeatLimit(e.problem)) setSeatLimitHit(true);
      setInviteFormError(reasonText(e, "Couldn't send invite."));
    } finally {
      setInviting(false);
    }
  }

  return {
    inviteEmail, setInviteEmail, inviteName, setInviteName, invitePhone, setInvitePhone, inviteRoleId, setInviteRoleId,
    inviteScope, setInviteScope, inviteDepartmentId, setInviteDepartmentId, inviting, inviteFormError, setInviteFormError, seatLimitHit, submitInvite,
  };
}
