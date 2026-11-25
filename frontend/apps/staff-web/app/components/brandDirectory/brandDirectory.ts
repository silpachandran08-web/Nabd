// PLT-024: a patient registered at one branch is recognised at a sibling branch through an explicit, logged link.
// Model of the brand directory screens (GET /v1/patients/brand-directory, POST /v1/patients/brand-links, the patient's links
// and opt-out, the owner's sharing switch, the compliance log). Pure: unit-tested directly.
export type Match = { sourceTenantId: string; branchLabel: string; sourcePatientRef: string; displayName: string; sex: string; birthYear: number };
export type Lookup = { matches: Match[]; checkInPerson: boolean };
export type BranchLink = { id: string; branchLabel: string; linkedAt: string };
export type PatientLinks = { optedOut: boolean; links: BranchLink[] };
export type Sharing = { profile: "NONE" | "DEMOGRAPHICS"; canEdit: boolean; siblingBranches: number };
export type LogItem = {
  id: number; at: string; direction: "outgoing" | "incoming"; kind: "lookup" | "link"; branchLabel: string | null; staffName: string | null;
  patientId: string | null; patientName: string | null; mrn: string | null;
};

/**
 * The number to check at the other branches, or null. Only a whole mobile number (10 to 15 digits) is ever sent: a name, an
 * MRN or a number still being typed never leaves the branch, and each check is a logged read at both branches.
 */
export function directoryPhone(q: string): string | null {
  const t = q.trim();
  if (!/^[0-9+()\-\s]+$/.test(t)) return null;
  const digits = t.replace(/\D/g, "").length;
  return digits >= 10 && digits <= 15 ? t : null;
}

export const lookupPath = (phone: string) => `/patients/brand-directory?${new URLSearchParams({ phone })}`;

/** A link needs the full date of birth the patient states (the server compares it exactly): a real past date. */
export function dobOk(dob: string, today: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(dob) && !Number.isNaN(Date.parse(dob)) && dob < today;
}

export const linkBody = (m: Match, phone: string, dateOfBirth: string) =>
  ({ sourceTenantId: m.sourceTenantId, sourcePatientRef: m.sourcePatientRef, phone, dateOfBirth });

export type LinkProblem = "dobMismatch" | "optedOut" | "alreadyLinked" | "disabled" | "tooMany" | "gone" | "failed";

/** The server's answer to a refused link as a message key (the desk reads a sentence, never a code). */
export function linkProblem(status: number, code?: string): LinkProblem {
  if (status === 422 && code === "DOB_MISMATCH") return "dobMismatch";
  if (status === 410) return "optedOut";
  if (status === 409) return "alreadyLinked";
  if (status === 403 && code === "DIRECTORY_DISABLED") return "disabled";
  if (status === 429) return "tooMany";
  if (status === 404) return "gone";
  return "failed";
}

export const sexKey = (sex: string) => (sex === "female" || sex === "male" ? `sex_${sex}` : "sex_other") as "sex_female" | "sex_male" | "sex_other";

/** Switching sharing on needs the owner's statement about the privacy notice; switching it off never does. */
export const profileBody = (on: boolean, noticeConfirmed: boolean) =>
  (on ? { profile: "DEMOGRAPHICS", noticeDisclosesDirectory: noticeConfirmed } : { profile: "NONE" });
export const canTurnOn = (s: Pick<Sharing, "canEdit" | "profile">, noticeConfirmed: boolean) => s.canEdit && s.profile === "NONE" && noticeConfirmed;

/** Message key of one log line. */
export const logKey = (i: Pick<LogItem, "direction" | "kind">) =>
  (i.direction === "outgoing" ? (i.kind === "link" ? "outgoingLink" : "outgoing") : i.kind === "link" ? "incomingLink" : "incoming");
