// PLT-022: the owner's branches model (GET/POST /v1/owner/branches, retry of a stopped job). Pure: unit-tested directly.
export type Branch = { tenantId: string; name: string; branchCode: string | null; status: string; region: string; timezone: string };
export type Step = { stepName: string; status: string };
export type BranchJob = { provisioningJobId: string; branchTenantId: string; name: string; branchCode: string; status: string; failedStep: string | null; steps: Step[] };
export type BranchList = { items: Branch[]; jobs: BranchJob[]; branchLimit: number | null; branchesInUse: number };

/** Same list and order as the server's ConfigBundle.SECTIONS. Patients, staff and clinical data are never copied. */
export const SECTIONS = ["departments", "charges", "policies", "roles", "templates", "hours"] as const;
export type Section = (typeof SECTIONS)[number];

/** Must equal OwnerBranchService.stepUpTarget: the MFA confirmation is good for this branch code only. */
export const stepUpTarget = (branchCode: string) => `branch-add:${branchCode.toLowerCase()}`;

/** Same rule as the server: 2 to 20 letters, digits or hyphens, not starting with a hyphen. */
export const codeOk = (code: string) => /^[A-Za-z0-9][A-Za-z0-9-]{1,19}$/.test(code);

/** The plan's branches are all in use (a job that has not made its clinic yet holds a place too). */
export const atLimit = (l: Pick<BranchList, "branchLimit" | "branchesInUse">) => l.branchLimit != null && l.branchesInUse >= l.branchLimit;

/** A job is still moving: keep polling for progress. A failed one waits for the owner's retry. */
export const moving = (jobs: BranchJob[]) => jobs.some((j) => j.status === "queued" || j.status === "running");

export const progress = (j: BranchJob) => ({ done: j.steps.filter((s) => s.status === "done").length, total: j.steps.length });

export type Draft = { name: string; branchCode: string; address: string; timezone: string; copyFromTenantId: string; copy: Section[] };

/** The wizard's step 1 and 2 are complete. */
export const draftOk = (d: Draft) => d.name.trim().length > 0 && codeOk(d.branchCode) && d.address.trim().length > 0 && d.timezone.length > 0 && d.copyFromTenantId.length > 0;
