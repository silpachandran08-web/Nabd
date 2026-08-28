"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./access.module.css";

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

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

function status(g: Grant): { label: string; className: string } {
  if (g.revokedAt) return { label: "Revoked", className: styles.pillRevoked };
  if (!g.active) return { label: "Expired", className: styles.pillExpired };
  return { label: "Active", className: styles.pillActive };
}

export default function SupportAccessPage() {
  const router = useRouter();
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

  const authedFetch = useCallback(
    (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_platform_access_token");
      if (!token) {
        router.replace("/platform/login");
        return null;
      }
      return fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}` },
      });
    },
    [router]
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const grantsRes = await authedFetch("/platform/support-access/grants");
      if (!grantsRes) return;
      if (grantsRes.status === 401) {
        localStorage.removeItem("nabd_platform_access_token");
        router.replace("/platform/login");
        return;
      }
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
  }, [authedFetch, router]);

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
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantId, reason }),
      });
      if (!res) return;
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        setFormError(body?.detail ?? "Couldn't request access. Try again.");
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
    try {
      const res = await authedFetch(`/platform/support-access/grants/${id}/revoke`, { method: "POST" });
      if (res?.ok) {
        setGrants((prev) =>
          prev.map((g) => (g.id === id ? { ...g, active: false, revokedAt: new Date().toISOString() } : g))
        );
      }
    } finally {
      setActingOn(null);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Support access</h1>
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
            <span className={styles.policyItem}><span className={`${styles.dot} ${styles.dotAllowed}`} /> Tenant metadata</span>
            <span className={styles.policyItem}><span className={`${styles.dot} ${styles.dotAllowed}`} /> Audit logs</span>
            <span className={styles.policyItem}><span className={`${styles.dot} ${styles.dotBlocked}`} /> Full clinical fields</span>
            <span className={styles.policyItem}><span className={`${styles.dot} ${styles.dotBlocked}`} /> Prescription content</span>
          </div>
        </>
      )}

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to support sessions.</div>
        ) : error ? (
          <div className={styles.errorState}>{error}</div>
        ) : grants.length === 0 ? (
          <div className={styles.state}>No support access grants yet.</div>
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
                  const s = status(g);
                  return (
                    <tr key={g.id}>
                      <td>{g.operatorName} <span className={styles.muted}>({g.operatorRole})</span></td>
                      <td>{g.reason}</td>
                      <td>{new Date(g.grantedAt).toLocaleString()}</td>
                      <td>{new Date(g.expiresAt).toLocaleString()}</td>
                      <td><span className={`${styles.pill} ${s.className}`}>{s.label}</span></td>
                      <td>
                        {s.label === "Active" && (
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
