"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./audit.module.css";
import Dialog from "../../components/ui/Dialog";
import Icon from "../../components/ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import RoleDenied from "../../components/platform/RoleDenied";
import { usePlatformFetch } from "../platformApi";

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


type Change = { key: string; before: unknown; after: unknown };

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function show(value: unknown): string {
  if (value === undefined || value === null) return "—";
  if (typeof value === "string") return value;
  try {
    return JSON.stringify(value);
  } catch {
    return "—";
  }
}

function pretty(value: unknown): string {
  if (value === undefined || value === null) return "—";
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return "—";
  }
}

// Key-level diff for object snapshots; any other shape is one whole-value change.
function changesOf(before: unknown, after: unknown): Change[] {
  if (isRecord(before) || isRecord(after)) {
    const b = isRecord(before) ? before : {};
    const a = isRecord(after) ? after : {};
    return Array.from(new Set([...Object.keys(b), ...Object.keys(a)]))
      .filter((key) => JSON.stringify(b[key]) !== JSON.stringify(a[key]))
      .map((key) => ({ key, before: b[key], after: a[key] }));
  }
  if (before == null && after == null) return [];
  return [{ key: "value", before, after }];
}

function changeSummary(r: AuditEntry): string {
  const changes = changesOf(r.before, r.after);
  if (changes.length === 0) return "No field changes recorded";
  const keys = changes.slice(0, 3).map((c) => c.key).join(", ");
  return changes.length > 3 ? `${keys} +${changes.length - 3} more` : keys;
}

// Spreadsheet formula injection: audit values are operator/clinic-entered, so neutralise a leading
// = + - @ before they reach Excel.
function csvCell(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value;
  return `"${safe.replace(/"/g, '""')}"`;
}

function exportCsv(rows: AuditEntry[]) {
  const header = ["When", "Clinic", "Clinic slug", "Actor", "Actor role", "Action", "Entity type", "Entity id", "Before", "After"];
  const lines = [header.map(csvCell).join(",")];
  for (const r of rows) {
    lines.push(
      [
        r.createdAt, r.tenantName, r.tenantSlug, r.actorName, r.actorRole, r.action, r.entityType, r.entityId ?? "",
        r.before == null ? "" : show(r.before), r.after == null ? "" : show(r.after),
      ].map((v) => csvCell(String(v))).join(",")
    );
  }
  const url = URL.createObjectURL(new Blob([lines.join("\r\n")], { type: "text/csv;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = `audit-log-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

export default function AuditLogPage() {
  const fetchAuthed = usePlatformFetch();
  const [rows, setRows] = useState<AuditEntry[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [tenants, setTenants] = useState<TenantOption[]>([]);
  const [tenantId, setTenantId] = useState("");
  const [action, setAction] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [viewing, setViewing] = useState<AuditEntry | null>(null);
  const [filterError, setFilterError] = useState<string | null>(null);

  const fetchPage = useCallback(
    async (cursor: string | null, append: boolean) => {
      if (append) setLoadingMore(true);
      else setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams({ limit: "50" });
        if (cursor) params.set("cursor", cursor);
        if (tenantId) params.set("tenantId", tenantId);
        if (action) params.set("action", action);
        // Operator-local day boundaries, sent as instants.
        if (fromDate) params.set("createdAfter", new Date(`${fromDate}T00:00:00`).toISOString());
        if (toDate) params.set("createdBefore", new Date(`${toDate}T23:59:59.999`).toISOString());

        const res = await fetchAuthed(`/platform/audit-log?${params}`);
        if (!res) return; // session gone, already redirecting to login
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
    [fetchAuthed, tenantId, action, fromDate, toDate]
  );

  useEffect(() => {
    void Promise.resolve().then(async () => {
      const tenantsRes = await fetchAuthed("/platform/tenants?limit=200");
      if (tenantsRes?.ok) {
        const body: { data: TenantOption[] } = await tenantsRes.json();
        setTenants(body.data);
      }
      fetchPage(null, false);
    });
    // Initial load only; later loads go through the Search button so typing a filter never refetches.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function search(e: React.FormEvent) {
    e.preventDefault();
    if (fromDate && toDate && fromDate > toDate) {
      setFilterError("The start date is after the end date.");
      return;
    }
    setFilterError(null);
    fetchPage(null, false);
  }

  const changes = viewing ? changesOf(viewing.before, viewing.after) : [];

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Audit &amp; Compliance</h1>
          <p className={styles.subtitle}>Immutable, append-only and cross-tenant — every platform action, with before and after values.</p>
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
          <div className={styles.field}>
            <label className={styles.label} htmlFor="from">From</label>
            <input id="from" type="date" className={styles.input} value={fromDate} max={toDate || undefined} onChange={(e) => setFromDate(e.target.value)} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="to">To</label>
            <input id="to" type="date" className={styles.input} value={toDate} min={fromDate || undefined} onChange={(e) => setToDate(e.target.value)} />
          </div>
          <button className={styles.submit} type="submit">Search</button>
          {rows.length > 0 && !loading && (
            <button type="button" className={styles.secondaryBtn} onClick={() => exportCsv(rows)}>
              <Icon name="receipt" size={14} /> Export CSV ({rows.length})
            </button>
          )}
          {filterError && <div className={styles.filterError} role="alert">{filterError}</div>}
        </form>
      )}
      {!forbidden && rows.length > 0 && !loading && (
        <p className={styles.exportHint}>
          Export covers the rows loaded on this page only. Exports are not yet recorded as their own audit event — handle the file as confidential.
        </p>
      )}

      <div className={styles.card}>
        {loading ? (
          <Skeleton rows={8} label="Loading audit log" />
        ) : forbidden ? (
          <RoleDenied surface="Audit & compliance" />
        ) : error ? (
          <ErrorState message={error} onRetry={() => fetchPage(null, false)} />
        ) : rows.length === 0 ? (
          <EmptyState icon="shield" title="No matching audit entries" hint="Widen the date range or clear the clinic and action filters." />
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
                    <th>Changes</th>
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
                      <td>
                        <span className={styles.diff}>{changeSummary(r)}</span>
                        {(r.before != null || r.after != null) && (
                          <button type="button" className={styles.linkBtn} onClick={() => setViewing(r)}>View diff</button>
                        )}
                      </td>
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

      {viewing && (
        <Dialog title={`${viewing.action} — before and after`} size="drawer" onClose={() => setViewing(null)}>
          <div className={styles.drawer}>
            <div className={styles.drawerMeta}>
              {new Date(viewing.createdAt).toLocaleString()} · {viewing.actorName} ({viewing.actorRole}) · {viewing.tenantName}
            </div>
            {changes.length === 0 ? (
              <p className={styles.muted}>No field changes recorded for this entry.</p>
            ) : (
              <table className={styles.diffTable}>
                <thead>
                  <tr><th>Field</th><th>Before</th><th>After</th></tr>
                </thead>
                <tbody>
                  {changes.map((c) => (
                    <tr key={c.key}>
                      <td className={styles.action}>{c.key}</td>
                      <td className={styles.diffBefore}>{show(c.before)}</td>
                      <td className={styles.diffAfter}>{show(c.after)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <details className={styles.raw}>
              <summary>Full snapshots</summary>
              <div className={styles.rawLabel}>Before</div>
              <pre className={styles.pre}>{pretty(viewing.before)}</pre>
              <div className={styles.rawLabel}>After</div>
              <pre className={styles.pre}>{pretty(viewing.after)}</pre>
            </details>
          </div>
        </Dialog>
      )}
    </main>
  );
}
