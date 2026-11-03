"use client";

// FEF-032: the side reads of Staff & Access (roles, clinicians to confirm, departments, seats, emergency access).
// Each fails on its own (a flag or an empty list), never the whole page.
import { useCallback, useState } from "react";
import type { Seats } from "./staffKpis";
import { api } from "../../lib/api";
import { decodePermissions, type BreakGlassGrant, type ClinicianReview, type Department, type Role } from "./staffModel";

export function useStaffSide() {
  const [roles, setRoles] = useState<Role[]>([]);
  const [rolesFailed, setRolesFailed] = useState(false);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [breakGlass, setBreakGlass] = useState<BreakGlassGrant[]>([]);
  const [review, setReview] = useState<ClinicianReview[]>([]);
  const [reviewFailed, setReviewFailed] = useState(false);
  const [seats, setSeats] = useState<Seats>(null);

  const loadSide = useCallback(async (token: string) => {
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
  }, []);

  return { roles, setRoles, rolesFailed, departments, breakGlass, review, setReview, reviewFailed, seats, loadSide };
}
