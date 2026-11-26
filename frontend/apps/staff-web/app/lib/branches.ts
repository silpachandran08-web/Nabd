// PLT-023: one login across the branches of a brand. Pure model of the branch picker (sign-in) and the branch switcher
// (top bar): when each shows, the colour chip, and the order of a switch (save open drafts, ask for the other branch's
// session, and only when it is issued drop every cached answer of the branch left). Requests and storage are injected so
// tests/unit/plt023-branches.test.mjs covers the order and the failure paths.
export type BranchSummary = { tenantId: string; name: string; branchCode?: string | null };
/** POST /v1/auth/login, /auth/otp/verify and /auth/mfa/verify answer this when the person works in more than one open branch. */
export type BranchChoice = { branchSelectToken: string; expiresIn: number; branches: BranchSummary[] };
export type BranchList = { currentTenantId: string; reportsOnly: boolean; branches: BranchSummary[] };

export function isBranchChoice(body: unknown): body is BranchChoice {
  return typeof body === "object" && body !== null && typeof (body as BranchChoice).branchSelectToken === "string" && Array.isArray((body as BranchChoice).branches);
}

/** A single-branch user (the list holds only their own branch) never sees a switcher. */
export const showSwitcher = (list: BranchList | null): boolean => !!list && list.branches.length > 1;

/** One of the six chart tokens per branch, stable for a tenant id, so the same branch is the same colour on every screen. */
export function branchColorIndex(tenantId: string): number {
  let h = 0;
  for (const c of tenantId) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return (h % 6) + 1;
}
export const branchColor = (tenantId: string): string => `var(--nb-chart-${branchColorIndex(tenantId)})`;

export const BRANCH_FLUSH_EVENT = "nabd:branch-switch-flush";
const FLUSH_WAIT_MS = 3_000;

/** A consult section with unsaved text registers a save here; the switcher waits for every one (at most 3 s) before it switches. */
export function onBranchSwitchFlush(save: () => Promise<unknown> | void): () => void {
  const h = (e: Event) => (e as CustomEvent<{ wait: (p: Promise<unknown>) => void }>).detail.wait(Promise.resolve().then(save));
  window.addEventListener(BRANCH_FLUSH_EVENT, h);
  return () => window.removeEventListener(BRANCH_FLUSH_EVENT, h);
}

/** Asks every open draft to save; a failed save is not fatal (the encrypted local draft stays and is restored on return). */
export async function flushOpenDrafts(waitMs = FLUSH_WAIT_MS): Promise<void> {
  const pending: Promise<unknown>[] = [];
  window.dispatchEvent(new CustomEvent(BRANCH_FLUSH_EVENT, { detail: { wait: (p: Promise<unknown>) => pending.push(p.catch(() => {})) } }));
  if (pending.length) await Promise.race([Promise.all(pending), new Promise((r) => setTimeout(r, waitMs))]);
}

export type SwitchDeps<T> = {
  flush: () => Promise<void>;
  /** POST /auth/branches/select with the current access token; rejects with the server's refusal (nothing changed then). */
  select: (tenantId: string, reportsOnly: boolean) => Promise<T>;
  store: (pair: T) => void;
  /** Every cached answer of the branch left: query cache, payment intents, device-held indexes. */
  clearCaches: () => Promise<void> | void;
  reload: () => void;
};

/** Order matters: drafts first; the caches are dropped only once the new session exists, so a refused switch loses nothing. */
export async function switchBranch<T>(d: SwitchDeps<T>, tenantId: string, reportsOnly = false): Promise<void> {
  await d.flush();
  const pair = await d.select(tenantId, reportsOnly);
  d.store(pair);
  await d.clearCaches();
  d.reload();
}

/** The sign-in picker's choice: no drafts or caches to drop (nothing was open), the token comes from the sign-in answer. */
export const selectBody = (choice: Pick<BranchChoice, "branchSelectToken">, tenantId: string) => ({ tenantId, branchSelectToken: choice.branchSelectToken });
