"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./access.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/StateViews";
import StatusPill from "../../../components/ui/StatusPill";
import RoleDenied from "../../../components/platform/RoleDenied";
import { GrantStatusPill } from "../../../components/platform/statusPills";
import { isForbidden, platformApi, platformError } from "../platformApi";

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
  // PLT-057: a grant is a request until the clinic owner approves it; the window starts at approval.
  status: "requested" | "active" | "declined" | "expired" | "revoked";
  windowEndsAt: string | null;
  declineNote: string | null;
};

type TenantOption = { id: string; name: string; slug: string };
// PLT-056: a grant names one of the clinic's support tickets (GET /v1/platform/support/tickets).
type TicketOption = { id: string; tenantId: string; subject: string; status: string };

function grantState(g: Grant): Grant["status"] {
  if (g.status === "active" && !g.active) return "expired"; // window ran out before the sweep wrote it
  return g.status;
}

export default function SupportAccessPage() {
  const [grants, setGrants] = useState<Grant[]>([]);
  const [tenants, setTenants] = useState<TenantOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [tenantId, setTenantId] = useState("");
  const [reason, setReason] = useState("");
  const [tickets, setTickets] = useState<TicketOption[]>([]);
  const [ticketId, setTicketId] = useState("");
  const [scope, setScope] = useState("config_only");
  const [minutes, setMinutes] = useState("60");
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [actingOn, setActingOn] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setGrants(await platformApi.get<Grant[]>("/platform/support-access/grants"));

      const tenantList = await platformApi.get<{ data: TenantOption[] }>("/platform/tenants?limit=100").catch(() => null);
      if (tenantList) setTenants(tenantList.data);
      const ticketList = await platformApi.get<typeof tickets>("/platform/support/tickets").catch(() => null);
      if (ticketList) setTickets(ticketList);
    } catch (e) {
      if (isForbidden(e)) setForbidden(true);
      else setError(platformError(e, "Couldn't load support access grants. Try again."));
    } finally {
      setLoading(false);
    }
  }, []);

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
    const mins = Number(minutes);
    if (!ticketId) {
      setFormError("Pick the support ticket this access is for.");
      return;
    }
    if (!Number.isInteger(mins) || mins < 1 || mins > 60) {
      setFormError("Duration must be 1 to 60 minutes.");
      return;
    }
    setSubmitting(true);
    try {
      const grant = await platformApi.post<Grant>("/platform/support-access/grants", { tenantId, ticketId, scope, minutes: mins, reason });
      setGrants((prev) => [grant, ...prev]);
      setReason("");
    } catch (err) {
      setFormError(platformError(err, "Couldn't request access. Try again."));
    } finally {
      setSubmitting(false);
    }
  }

  async function revoke(id: string) {
    setActingOn(id);
    setRevokeError(null);
    try {
      await platformApi.post(`/platform/support-access/grants/${id}/revoke`);
      setGrants((prev) =>
        prev.map((g) => (g.id === id ? { ...g, active: false, status: "revoked", revokedAt: new Date().toISOString() } : g))
      );
    } catch (e) {
      setRevokeError(platformError(e, "Couldn't revoke the grant. Try again."));
    } finally {
      setActingOn(null);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Support Access</h1>
        <p className={styles.subtitle}>Starts only after the clinic owner approves — one clinic, one ticket, one scope, at most 60 minutes from approval, no clinical fields.</p>
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
                onChange={(e) => { setTenantId(e.target.value); setTicketId(""); }}
              >
                <option value="">Select a clinic…</option>
                {tenants.map((t) => (
                  <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>
                ))}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="ticket">Ticket</label>
              <select id="ticket" className={styles.select} value={ticketId} onChange={(e) => setTicketId(e.target.value)}>
                <option value="">{tenantId ? "Select a ticket…" : "Pick a clinic first"}</option>
                {tickets.filter((t) => t.tenantId === tenantId && t.status !== "closed").map((t) => (
                  <option key={t.id} value={t.id}>{t.subject} ({t.status})</option>
                ))}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="scope">Scope</label>
              <select id="scope" className={styles.select} value={scope} onChange={(e) => setScope(e.target.value)}>
                <option value="config_only">Configuration only</option>
                <option value="redacted_patient_view">Redacted patient view</option>
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="minutes">Minutes (max 60)</label>
              <input id="minutes" type="number" min={1} max={60} className={styles.input} value={minutes} onChange={(e) => setMinutes(e.target.value)} />
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
              {submitting ? "Requesting…" : "Request access"}
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
                  <th scope="col">Operator</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Requested</th>
                  <th scope="col">Open until</th>
                  <th scope="col">Status</th>
                  <th scope="col"></th>
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
                      <td>{new Date(g.windowEndsAt ?? g.expiresAt).toLocaleString()}</td>
                      <td>
                        <GrantStatusPill state={state} />
                        {state === "declined" && g.declineNote && <div className={styles.muted}>{g.declineNote}</div>}
                      </td>
                      <td>
                        {(state === "active" || state === "requested") && (
                          <button
                            className={styles.revokeBtn}
                            disabled={actingOn === g.id}
                            onClick={() => revoke(g.id)}
                          >
                            {actingOn === g.id ? "…" : state === "requested" ? "Withdraw" : "Revoke"}
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
