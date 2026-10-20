// FEF-015 (ADR-06): one key shape for every server-state query, so the tenant scopes the cache and
// a prefix (["t", tenantId, "queue"]) invalidates a whole resource. React-free: only keys and times.

export type QueryParams = Record<string, string | number | boolean | undefined>;

/** ["t", tenantId, resource, params]. Params are always an object so prefix matching stays exact. */
export const qk = (tenantId: string, resource: string, params: QueryParams = {}) => ["t", tenantId, resource, params] as const;

/** Prefix of every key of one resource, for invalidateQueries / cancelQueries. */
export const qkPrefix = (tenantId: string, resource: string) => ["t", tenantId, resource] as const;

const MIN = 60_000;

/**
 * staleTime per data class (ADR-04). Queue-class data is always stale (push invalidation comes with T-094 SSE).
 * Patient demographics are 0, not ADR-04's 5 min: a name corrected in the registry must show on the next
 * focus (FEF-015 AC4) and focus only refetches stale queries (AC2).
 * shortcut: demographics refetch once per focus per row; move to 5 min once GET /patients?ids= (T-094) batches them.
 */
export const STALE = {
  roster: 10 * MIN,
  departments: 10 * MIN,
  patient: 0,
  callbacks: 5 * MIN,
  queue: 0,
} as const;
