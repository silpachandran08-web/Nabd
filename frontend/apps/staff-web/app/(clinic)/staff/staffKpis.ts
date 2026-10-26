// ADM-018: the owner staff screen's tiles. Pure so a unit test can pin the arithmetic.
import { mfaBadge } from "./mfaStatus.ts";

type Row = { status: string; loginMethod?: string | null; loginFactors?: string[] | null };
export type Seats = { seatsUsed: number; seatsLimit: number | null } | null;

/** MFA coverage counts active people the role requires or has MFA for: enrolled (TOTP) vs enrolled + setup-required. Null when nobody is in scope. */
export function staffKpis(rows: Row[], seats: Seats) {
  const active = rows.filter((r) => r.status === "active");
  const badges = active.map((r) => mfaBadge(r).label);
  const enrolled = badges.filter((b) => b === "TOTP").length;
  const needed = enrolled + badges.filter((b) => b === "Setup required").length;
  return {
    active: active.length,
    pendingInvites: rows.filter((r) => r.status === "invited").length,
    mfaPercent: needed === 0 ? null : Math.round((enrolled / needed) * 100),
    mfaMissing: needed - enrolled,
    seatsLeft: seats && seats.seatsLimit != null ? Math.max(0, seats.seatsLimit - seats.seatsUsed) : null,
    seatsText: seats ? (seats.seatsLimit == null ? `${seats.seatsUsed} used` : `${seats.seatsUsed} of ${seats.seatsLimit}`) : null,
  };
}

/** The reminder applies to an active person whose role needs an authenticator they have not set up. */
export const needsMfaReminder = (r: Row) => r.status === "active" && mfaBadge(r).label === "Setup required";

export const isSeatLimit = (problem: { title?: unknown; code?: unknown } | null | undefined) => problem?.title === "SEAT_LIMIT_REACHED" || problem?.code === "SEAT_LIMIT_REACHED";
