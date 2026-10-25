"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import styles from "./audit.module.css";
import Dialog from "../../components/ui/Dialog";
import Icon from "../../components/ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import RoleDenied from "../../components/platform/RoleDenied";
import { isForbidden, platformApi, platformError, useOperatorAccess } from "../platformApi";
import { actionLabel } from "./actionLabel";

// Matches GET /v1/platform/audit-log (AuditSearchController) — cross-tenant by default. Filters
// the controller accepts and search_audit_log (V16) applies: tenantId, action and entityType (exact
// match), createdAfter / createdBefore (ISO instants, both inclusive), limit, cursor. There is no
// actor filter on this endpoint yet, so none is offered.
type AuditEntry = {
  id: number;
  tenantId: string;
  tenantName: string | null;
  tenantSlug: string | null;
  actorType: string;
  actorName: string | null;
  ipAddress?: string | null;
  actorRole: string | null;
  action: string;
  entityType: string | null;
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

function when(iso: unknown): string {
  const d = new Date(typeof iso === "string" ? iso : NaN);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
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
      ].map((v) => csvCell(String(v ?? ""))).join(",")
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
  // PLT-056: before/after come back only for compliance_dpo, on request, with a reason (audited server-side).
  const isDpo = useOperatorAccess().canSeeValues;
  const [valuesReason, setValuesReason] = useState("");
  const [valuesOn, setValuesOn] = useState(false);
  const [rows, setRows] = useState<AuditEntry[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [tenants, setTenants] = useState<TenantOption[]>([]);
  const [tenantId, setTenantId] = useState("");
  const [action, setAction] = useState("");
  const [entityType, setEntityType] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [viewing, setViewing] = useState<AuditEntry | null>(null);
  const [filterError, setFilterError] = useState<string | null>(null);
  const [moreError, setMoreError] = useState<string | null>(null);
  // Filters of the search on screen. Load more pages through these, never through what has been typed since.
  const searched = useRef<URLSearchParams>(new URLSearchParams());

  const fetchPage = useCallback(
    async (cursor: string | null, append: boolean, reason: string | null = null) => {
      if (append) setLoadingMore(true);
      else setLoading(true);
      setMoreError(null);
      if (!append) setError(null);
      if (!append) {
        const filters = new URLSearchParams({ limit: "50" });
        if (tenantId) filters.set("tenantId", tenantId);
        if (action.trim()) filters.set("action", action.trim());
        if (entityType.trim()) filters.set("entityType", entityType.trim());
        // Operator-local day boundaries, sent as instants.
        if (fromDate) filters.set("createdAfter", new Date(`${fromDate}T00:00:00`).toISOString());
        if (toDate) filters.set("createdBefore", new Date(`${toDate}T23:59:59.999`).toISOString());
        if (reason) {
          filters.set("includeValues", "true");
          filters.set("reason", reason);
        }
        searched.current = filters;
      }
      // A failed append keeps the loaded rows and offers a retry of that page only.
      const fail = (msg: string) => (append ? setMoreError(msg) : setError(msg));
      try {
        const params = new URLSearchParams(searched.current);
        if (cursor) params.set("cursor", cursor);

        const body = await platformApi.get<AuditPage>(`/platform/audit-log?${params}`);
        setValuesOn(searched.current.get("includeValues") === "true");
        const data = Array.isArray(body?.data) ? body.data : [];
        setRows((prev) => (append ? [...prev, ...data] : data));
        setNextCursor(body?.page?.nextCursor ?? null);
      } catch (e) {
        if (isForbidden(e)) setForbidden(true);
        else fail(platformError(e, append ? "Couldn't load more entries. Try again." : "Couldn't load the audit log. Try again."));
      } finally {
        setLoading(false);
        setLoadingMore(false);
      }
    },
    [tenantId, action, entityType, fromDate, toDate]
  );

  useEffect(() => {
    void Promise.resolve().then(async () => {
      const tenantList = await platformApi.get<{ data: TenantOption[] }>("/platform/tenants?limit=100").catch(() => null);
      if (tenantList) setTenants(tenantList.data);
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
    fetchPage(null, false, valuesOn ? valuesReason.trim() : null);
  }

  function showValues(e: React.FormEvent) {
    e.preventDefault();
    if (!valuesReason.trim()) {
      setFilterError("Enter a reason to see before and after values.");
      return;
    }
    setFilterError(null);
    fetchPage(null, false, valuesReason.trim());
  }

  const changes = viewing ? changesOf(viewing.before, viewing.after) : [];

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Audit &amp; Compliance</h1>
          <p className={styles.subtitle}>Immutable, append-only and cross-tenant — every platform action. Every search is itself audited; 50 entries per page.</p>
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
              placeholder="Exact code, e.g. patient.update"
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="entityType">Entity type</label>
            <input
              id="entityType"
              className={styles.input}
              value={entityType}
              onChange={(e) => setEntityType(e.target.value)}
              placeholder="Exact type, e.g. patient"
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
              <Icon name="list" size={14} /> Export CSV ({rows.length})
            </button>
          )}
          {filterError && <div className={styles.filterError} role="alert">{filterError}</div>}
        </form>
      )}
      {!forbidden && isDpo && (
        <form className={styles.filterCard} onSubmit={showValues}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="valuesReason">Reason to see before/after values</label>
            <input
              id="valuesReason"
              className={styles.input}
              value={valuesReason}
              maxLength={500}
              onChange={(e) => setValuesReason(e.target.value)}
              placeholder="e.g. DSAR or incident reference"
            />
          </div>
          <button className={styles.submit} type="submit">{valuesOn ? "Search again with values" : "Show values"}</button>
        </form>
      )}
      {!forbidden && rows.length > 0 && !loading && (
        <p className={styles.exportHint}>
          Export is built in your browser from the {rows.length} rows loaded here (use Load more to include more). Each search that loaded them was audited; handle the file as confidential.
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
          <EmptyState icon="shield" title="No matching audit entries" hint="Widen the date range or clear the clinic, action and entity filters." />
        ) : (
          <>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th scope="col">When</th>
                    <th scope="col">Clinic</th>
                    <th scope="col">Actor</th>
                    <th scope="col">Action</th>
                    <th scope="col">Entity</th>
                    <th scope="col">Changes</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      <td className={styles.when}>{when(r.createdAt)}</td>
                      <td>
                        <span className={styles.tenantName}>{r.tenantName ?? "Platform"}</span>
                        <span className={styles.tenantSlug}>{r.tenantSlug}</span>
                      </td>
                      <td>{r.actorName ?? "—"}{r.actorRole && <span className={styles.muted}> ({r.actorRole})</span>}</td>
                      <td>
                        {actionLabel(r.action)}
                        {actionLabel(r.action) !== r.action && r.action && <span className={styles.tenantSlug}>{r.action}</span>}
                      </td>
                      <td>
                        {r.entityType}
                        {r.entityId && <span className={styles.tenantSlug}>{r.entityId}</span>}
                      </td>
                      <td>
                        <span className={styles.diff}>{valuesOn ? changeSummary(r) : "Values hidden"}</span>
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
                  {loadingMore ? "Loading…" : moreError ? "Retry load more" : "Load more"}
                </button>
                {moreError && <span className={styles.filterError} role="alert">{moreError}</span>}
              </div>
            )}
          </>
        )}
      </div>

      {viewing && (
        <Dialog title={`${actionLabel(viewing.action)} — before and after`} size="drawer" onClose={() => setViewing(null)}>
          <div className={styles.drawer}>
            <dl className={styles.metaGrid}>
              <dt>When</dt><dd>{when(viewing.createdAt)}</dd>
              <dt>Clinic</dt><dd>{viewing.tenantName ? `${viewing.tenantName} (${viewing.tenantSlug ?? "—"})` : "Platform"}</dd>
              <dt>Actor</dt><dd>{viewing.actorName ?? "—"} ({[viewing.actorRole, viewing.actorType].filter(Boolean).join(", ") || "—"})</dd>
              {viewing.ipAddress && <><dt>IP address</dt><dd>{viewing.ipAddress}</dd></>}
              <dt>Entity</dt><dd>{viewing.entityType ?? "—"}{viewing.entityId ? ` · ${viewing.entityId}` : ""}</dd>
            </dl>
            {changes.length === 0 ? (
              <p className={styles.muted}>No field changes recorded for this entry.</p>
            ) : (
              <table className={styles.diffTable}>
                <thead>
                  <tr><th scope="col">Field</th><th scope="col">Before</th><th scope="col">After</th></tr>
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
