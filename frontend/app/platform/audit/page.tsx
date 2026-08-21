"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./audit.module.css";

// Matches GET /v1/platform/audit-log (AuditSearchController) — cross-tenant by
// default, narrowed by the tenantId/action filters below.
type AuditEntry = {
  id: number;
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  actorType: string;
  actorName: string;
  actorRole: string;
  action: string;
  entityType: string;
  entityId: string | null;
  before: unknown;
  after: unknown;
  createdAt: string;
};
type AuditPage = { data: AuditEntry[]; page: { nextCursor: string | null; limit: number } };
type TenantOption = { id: string; name: string; slug: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

function diffText(value: unknown): string {
  if (value == null) return "—";
  try {
    return JSON.stringify(value);
  } catch {
    return "—";
  }
}

export default function AuditLogPage() {
  const router = useRouter();
  const [rows, setRows] = useState<AuditEntry[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [tenants, setTenants] = useState<TenantOption[]>([]);
  const [tenantId, setTenantId] = useState("");
  const [action, setAction] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const authedFetch = useCallback(
    (path: string) => {
      const token = localStorage.getItem("nabd_platform_access_token");
      if (!token) {
        router.replace("/platform/login");
        return null;
      }
      return fetch(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` } });
    },
    [router]
  );

  const fetchPage = useCallback(
    async (cursor: string | null, append: boolean) => {
      if (append) setLoadingMore(true);
      else setLoading(true);
      setError(null);
      try {
        const url = new URL(`${API_BASE}/platform/audit-log`);
        url.searchParams.set("limit", "50");
        if (cursor) url.searchParams.set("cursor", cursor);
        if (tenantId) url.searchParams.set("tenantId", tenantId);
        if (action) url.searchParams.set("action", action);

        const token = localStorage.getItem("nabd_platform_access_token");
        if (!token) {
          router.replace("/platform/login");
          return;
        }
        const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
        if (res.status === 401) {
          localStorage.removeItem("nabd_platform_access_token");
          router.replace("/platform/login");
          return;
        }
        if (res.status === 403) {
          setForbidden(true);
          return;
        }
        if (!res.ok) {
          setError("Couldn't load the audit log. Try again.");
          return;
        }
        const body: AuditPage = await res.json();
        setRows((prev) => (append ? [...prev, ...body.data] : body.data));
        setNextCursor(body.page.nextCursor);
      } catch {
        setError("Couldn't reach the server. Check your connection and try again.");
      } finally {
        setLoading(false);
        setLoadingMore(false);
      }
    },
    [router, tenantId, action]
  );

  useEffect(() => {
    void Promise.resolve().then(async () => {
      const tenantsRes = await authedFetch("/platform/tenants?limit=200");
      if (tenantsRes?.ok) {
        const body: { data: TenantOption[] } = await tenantsRes.json();
        setTenants(body.data);
      }
      fetchPage(null, false);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function search(e: React.FormEvent) {
    e.preventDefault();
    fetchPage(null, false);
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Audit log</h1>
          <p className={styles.subtitle}>Cross-tenant, read-only — every platform action, searchable.</p>
        </div>
        {!loading && !error && !forbidden && <span className={styles.count}>{rows.length} loaded</span>}
      </div>

      {!forbidden && (
        <form className={styles.filterCard} onSubmit={search}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="tenant">Clinic</label>
            <select id="tenant" className={styles.select} value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
              <option value="">All clinics</option>
              {tenants.map((t) => (
                <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>
              ))}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="action">Action</label>
            <input
              id="action"
              className={styles.input}
              value={action}
              onChange={(e) => setAction(e.target.value)}
              placeholder="e.g. patient.update"
            />
          </div>
          <button className={styles.submit} type="submit">Search</button>
        </form>
      )}

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to the audit log.</div>
        ) : error ? (
          <div className={styles.errorState}>{error}</div>
        ) : rows.length === 0 ? (
          <div className={styles.state}>No matching audit entries.</div>
        ) : (
          <>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>When</th>
                    <th>Clinic</th>
                    <th>Actor</th>
                    <th>Action</th>
                    <th>Entity</th>
                    <th>Before → After</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      <td className={styles.when}>{new Date(r.createdAt).toLocaleString()}</td>
                      <td>
                        <span className={styles.tenantName}>{r.tenantName}</span>
                        <span className={styles.tenantSlug}>{r.tenantSlug}</span>
                      </td>
                      <td>{r.actorName} <span className={styles.muted}>({r.actorRole})</span></td>
                      <td className={styles.action}>{r.action}</td>
                      <td>
                        {r.entityType}
                        {r.entityId && <span className={styles.tenantSlug}>{r.entityId}</span>}
                      </td>
                      <td className={styles.diff}>{diffText(r.before)} → {diffText(r.after)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {nextCursor && (
              <div className={styles.footer}>
                <button
                  className={styles.loadMore}
                  disabled={loadingMore}
                  onClick={() => fetchPage(nextCursor, true)}
                >
                  {loadingMore ? "Loading…" : "Load more"}
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </main>
  );
}
