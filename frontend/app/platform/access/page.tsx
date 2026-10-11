"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./access.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import StatusPill from "../../components/ui/StatusPill";
import RoleDenied from "../../components/platform/RoleDenied";
import { GrantStatusPill } from "../../components/platform/statusPills";
import { problemDetail, usePlatformFetch } from "../platformApi";

// Matches GET /v1/platform/support-access/grants (SupportAccessController).
type Grant = {
  id: string;
  tenantId: string;
  operatorId: string;
  operatorName: string;
  operatorRole: string;
  reason: string;
  grantedAt: string;
  expiresAt: string;
  revokedAt: string | null;
  active: boolean;
};

type TenantOption = { id: string; name: string; slug: string };

function grantState(g: Grant): "active" | "expired" | "revoked" {
  if (g.revokedAt) return "revoked";
  return g.active ? "active" : "expired";
}

export default function SupportAccessPage() {
  const authedFetch = usePlatformFetch();
  const [grants, setGrants] = useState<Grant[]>([]);
  const [tenants, setTenants] = useState<TenantOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [tenantId, setTenantId] = useState("");
  const [reason, setReason] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [actingOn, setActingOn] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const grantsRes = await authedFetch("/platform/support-access/grants");
      if (!grantsRes) return; // session gone, already redirecting to login
      if (grantsRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!grantsRes.ok) {
        setError("Couldn't load support access grants. Try again.");
        return;
      }
      setGrants(await grantsRes.json());

      const tenantsRes = await authedFetch("/platform/tenants?limit=200");
      if (tenantsRes?.ok) {
        const body: { data: TenantOption[] } = await tenantsRes.json();
        setTenants(body.data);
      }
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  // Fleet's per-row "Support access" button hands off the tenant this way rather than a query
  // param — this page's own <select> just needs tenantId to eventually match one of its options,
  // so the fetch above populating `tenants` doesn't need to be sequenced with this at all.
  useEffect(() => {
    const prefill = sessionStorage.getItem("nabd_fleet_support_access_tenant_id");
    if (prefill) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setTenantId(prefill);
      sessionStorage.removeItem("nabd_fleet_support_access_tenant_id");
    }
  }, []);

  async function requestGrant(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!tenantId) {
      setFormError("Pick a clinic first.");
      return;
    }
    setSubmitting(true);
    try {
      const res = await authedFetch("/platform/support-access/grants", {
        method: "POST",
        body: JSON.stringify({ tenantId, reason }),
      });
      if (!res) return;
      if (!res.ok) {
        setFormError(await problemDetail(res, "Couldn't request access. Try again."));
        return;
      }
      const grant: Grant = await res.json();
      setGrants((prev) => [grant, ...prev]);
      setReason("");
    } finally {
      setSubmitting(false);
    }
  }

  async function revoke(id: string) {
    setActingOn(id);
    setRevokeError(null);
    try {
      const res = await authedFetch(`/platform/support-access/grants/${id}/revoke`, { method: "POST" });
      if (!res) return;
      if (!res.ok) {
        setRevokeError(await problemDetail(res, "Couldn't revoke the grant. Try again."));
        return;
      }
      setGrants((prev) =>
        prev.map((g) => (g.id === id ? { ...g, active: false, revokedAt: new Date().toISOString() } : g))
      );
    } catch {
      setRevokeError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setActingOn(null);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Support Access</h1>
        <p className={styles.subtitle}>Consented, time-boxed and audited — 60 minutes, no clinical fields.</p>
      </div>

      {!forbidden && (
        <>
          <form className={styles.formCard} onSubmit={requestGrant}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="tenant">Clinic</label>
              <select
                id="tenant"
                className={styles.select}
                value={tenantId}
                onChange={(e) => setTenantId(e.target.value)}
              >
                <option value="">Select a clinic…</option>
                {tenants.map((t) => (
                  <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>
                ))}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="reason">Reason</label>
              <input
                id="reason"
                className={styles.input}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="Why do you need access?"
                required
              />
            </div>
            <button className={styles.submit} type="submit" disabled={submitting}>
              {submitting ? "Requesting…" : "Request grant"}
            </button>
            {formError && <div className={styles.formError} role="alert">{formError}</div>}
          </form>

          <div className={styles.policy}>
            <span className={styles.policyItem}><StatusPill tone="ok" icon="check" label="Tenant metadata" /></span>
            <span className={styles.policyItem}><StatusPill tone="ok" icon="check" label="Audit logs" /></span>
            <span className={styles.policyItem}><StatusPill tone="danger" icon="x" label="Full clinical fields — blocked" /></span>
            <span className={styles.policyItem}><StatusPill tone="danger" icon="x" label="Prescription content — blocked" /></span>
          </div>
        </>
      )}

      {revokeError && <div className={styles.formError} role="alert">{revokeError}</div>}

      <div className={styles.card}>
        {loading ? (
          <Skeleton rows={5} label="Loading grants" />
        ) : forbidden ? (
          <RoleDenied surface="Support access" />
        ) : error ? (
          <ErrorState message={error} onRetry={load} />
        ) : grants.length === 0 ? (
          <EmptyState icon="user" title="No support access grants yet" hint="Request a time-boxed grant above when a clinic asks for help." />
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>Operator</th>
                  <th>Reason</th>
                  <th>Granted</th>
                  <th>Expires</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {grants.map((g) => {
                  const state = grantState(g);
                  return (
                    <tr key={g.id}>
                      <td>{g.operatorName} <span className={styles.muted}>({g.operatorRole})</span></td>
                      <td>{g.reason}</td>
                      <td>{new Date(g.grantedAt).toLocaleString()}</td>
                      <td>{new Date(g.expiresAt).toLocaleString()}</td>
                      <td><GrantStatusPill state={state} /></td>
                      <td>
                        {state === "active" && (
                          <button
                            className={styles.revokeBtn}
                            disabled={actingOn === g.id}
                            onClick={() => revoke(g.id)}
                          >
                            {actingOn === g.id ? "…" : "Revoke"}
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </main>
  );
}
