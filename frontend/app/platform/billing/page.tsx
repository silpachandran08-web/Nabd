"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./billing.module.css";

// Matches GET/POST /v1/platform/billing/* (BillingController) and GET /v1/platform/tenants (FleetController)
// and GET /v1/platform/plans (PlanController) for the tenant/plan pickers.
type Subscription = {
  id: string;
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  region: string;
  tenantStatus: string;
  planId: string;
  planCode: string;
  planName: string;
  mrrCents: number;
  currency: string;
  renewalDate: string;
  seatLimit: number;
  seatsUsed: number;
};
type Discount = {
  id: string;
  tenantId: string;
  tenantName: string;
  requestedBy: string;
  requestedByName: string;
  percent: number;
  reason: string;
  status: string;
  reviewedByName: string | null;
  reviewedAt: string | null;
  createdAt: string;
};
type TenantOption = { id: string; name: string; slug: string };
type PlanOption = { id: string; code: string; name: string; monthlyPriceCents: number; currency: string; active: boolean };
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

const STATUS_CLASS: Record<string, string> = {
  provisioning: styles.pillProvisioning,
  trialing: styles.pillTrialing,
  active: styles.pillActive,
  overdue: styles.pillOverdue,
  suspended: styles.pillSuspended,
  offboarding: styles.pillOffboarding,
  offboarded: styles.pillOffboarded,
};

const DISCOUNT_STATUS_CLASS: Record<string, string> = {
  auto_approved: styles.pillAuto,
  approved: styles.pillApproved,
  pending: styles.pillPending,
  rejected: styles.pillRejected,
};

export default function BillingPage() {
  const router = useRouter();
  const [tab, setTab] = useState<"subscriptions" | "discounts">("subscriptions");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [discounts, setDiscounts] = useState<Discount[]>([]);
  const [tenants, setTenants] = useState<TenantOption[]>([]);
  const [plans, setPlans] = useState<PlanOption[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);

  const [subTenantId, setSubTenantId] = useState("");
  const [subPlanId, setSubPlanId] = useState("");
  const [subMrr, setSubMrr] = useState("");
  const [subRenewal, setSubRenewal] = useState("");
  const [subSaving, setSubSaving] = useState(false);
  const [subError, setSubError] = useState<string | null>(null);

  const [discTenantId, setDiscTenantId] = useState("");
  const [discPercent, setDiscPercent] = useState("");
  const [discReason, setDiscReason] = useState("");
  const [discSaving, setDiscSaving] = useState(false);
  const [discError, setDiscError] = useState<string | null>(null);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_platform_access_token");
      if (!token) {
        router.replace("/platform/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_platform_access_token");
        router.replace("/platform/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const subsRes = await authedFetch("/platform/billing/subscriptions?limit=100");
      if (!subsRes) return;
      if (subsRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!subsRes.ok) {
        setError("Couldn't load billing data. Try again.");
        return;
      }
      setSubscriptions((await subsRes.json()).data);

      const discRes = await authedFetch("/platform/billing/discounts?limit=100");
      if (discRes?.ok) setDiscounts((await discRes.json()).data);

      const tenantsRes = await authedFetch("/platform/tenants?limit=200");
      if (tenantsRes?.ok) setTenants((await tenantsRes.json()).data);

      const plansRes = await authedFetch("/platform/plans");
      if (plansRes?.ok) setPlans((await plansRes.json()).filter((p: PlanOption) => p.active));
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function submitSubscription(e: React.FormEvent) {
    e.preventDefault();
    setSubError(null);
    if (!subTenantId || !subPlanId || !subMrr || !subRenewal) {
      setSubError("Fill in every field.");
      return;
    }
    setSubSaving(true);
    try {
      const res = await authedFetch(`/platform/billing/subscriptions/${subTenantId}`, {
        method: "POST",
        body: JSON.stringify({ planId: subPlanId, mrrCents: Math.round(parseFloat(subMrr) * 100), renewalDate: subRenewal }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't save subscription." }));
        setSubError(p.detail || "Couldn't save subscription.");
        return;
      }
      setSubTenantId("");
      setSubPlanId("");
      setSubMrr("");
      setSubRenewal("");
      load();
    } finally {
      setSubSaving(false);
    }
  }

  async function transition(tenantId: string, toStatus: string) {
    const reason = window.prompt(`Reason for moving this clinic to "${toStatus}"?`);
    if (!reason) return;
    setBusyId(tenantId);
    setError(null);
    try {
      const res = await authedFetch(`/platform/billing/subscriptions/${tenantId}/transitions`, {
        method: "POST",
        body: JSON.stringify({ toStatus, reason }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't update status." }));
        setError(p.detail || "Couldn't update status.");
        return;
      }
      load();
    } finally {
      setBusyId(null);
    }
  }

  async function submitDiscount(e: React.FormEvent) {
    e.preventDefault();
    setDiscError(null);
    const percent = parseFloat(discPercent);
    if (!discTenantId || !discReason || Number.isNaN(percent) || percent <= 0 || percent > 100) {
      setDiscError("Fill in every field with a valid percentage.");
      return;
    }
    setDiscSaving(true);
    try {
      const res = await authedFetch("/platform/billing/discounts", {
        method: "POST",
        body: JSON.stringify({ tenantId: discTenantId, percent, reason: discReason }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't request discount." }));
        setDiscError(p.detail || "Couldn't request discount.");
        return;
      }
      setDiscTenantId("");
      setDiscPercent("");
      setDiscReason("");
      load();
    } finally {
      setDiscSaving(false);
    }
  }

  async function reviewDiscount(id: string, action: "approve" | "reject") {
    setBusyId(id);
    setError(null);
    try {
      const res = await authedFetch(`/platform/billing/discounts/${id}/${action}`, { method: "POST" });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't review discount." }));
        setError(p.detail || "Couldn't review discount.");
        return;
      }
      load();
    } finally {
      setBusyId(null);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Billing & Revenue</h1>
          <p className={styles.subtitle}>Subscriptions, dunning and discount approvals.</p>
        </div>
      </div>

      {!forbidden && (
        <div className={styles.tabs}>
          <button className={tab === "subscriptions" ? styles.tabActive : styles.tab} onClick={() => setTab("subscriptions")}>Subscriptions</button>
          <button className={tab === "discounts" ? styles.tabActive : styles.tab} onClick={() => setTab("discounts")}>Discounts</button>
        </div>
      )}

      {error && <div className={styles.formError} role="alert">{error}</div>}

      {loading ? (
        <div className={styles.card}><div className={styles.state}>Loading…</div></div>
      ) : forbidden ? (
        <div className={styles.card}><div className={styles.state}>Your role doesn&apos;t have access to billing & revenue.</div></div>
      ) : tab === "subscriptions" ? (
        <>
          <form className={styles.formCard} onSubmit={submitSubscription}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="subTenant">Clinic</label>
              <select id="subTenant" className={styles.select} value={subTenantId} onChange={(e) => setSubTenantId(e.target.value)}>
                <option value="">Select a clinic…</option>
                {tenants.map((t) => <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="subPlan">Plan</label>
              <select id="subPlan" className={styles.select} value={subPlanId} onChange={(e) => setSubPlanId(e.target.value)}>
                <option value="">Select a plan…</option>
                {plans.map((p) => <option key={p.id} value={p.id}>{p.name} ({(p.monthlyPriceCents / 100).toFixed(2)} {p.currency})</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="subMrr">MRR</label>
              <input id="subMrr" type="number" min="0" step="0.01" className={styles.input} value={subMrr} onChange={(e) => setSubMrr(e.target.value)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="subRenewal">Renewal date</label>
              <input id="subRenewal" type="date" className={styles.input} value={subRenewal} onChange={(e) => setSubRenewal(e.target.value)} />
            </div>
            <button className={styles.submit} type="submit" disabled={subSaving}>{subSaving ? "Saving…" : "Set plan"}</button>
            {subError && <div className={styles.formError} role="alert">{subError}</div>}
          </form>

          <div className={styles.card}>
            {subscriptions.length === 0 ? (
              <div className={styles.state}>No subscriptions yet.</div>
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>Clinic</th><th>Plan</th><th>MRR</th><th>Renewal</th><th>Seats</th><th>Status</th><th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {subscriptions.map((s) => (
                      <tr key={s.id}>
                        <td><span className={styles.tenantName}>{s.tenantName}</span><span className={styles.tenantSlug}>{s.tenantSlug}</span></td>
                        <td>{s.planName}</td>
                        <td>{(s.mrrCents / 100).toFixed(2)} {s.currency}</td>
                        <td>{s.renewalDate}</td>
                        <td className={s.seatsUsed > s.seatLimit ? undefined : styles.muted}>{s.seatsUsed} / {s.seatLimit}</td>
                        <td><span className={`${styles.pill} ${STATUS_CLASS[s.tenantStatus] ?? ""}`}>{s.tenantStatus}</span></td>
                        <td>
                          {s.tenantStatus === "active" && (
                            <>
                              <button className={styles.actionBtn} disabled={busyId === s.tenantId} onClick={() => transition(s.tenantId, "overdue")}>Mark overdue</button>
                              <button className={styles.actionBtn} disabled={busyId === s.tenantId} onClick={() => transition(s.tenantId, "suspended")}>Suspend</button>
                            </>
                          )}
                          {s.tenantStatus === "overdue" && (
                            <>
                              <button className={styles.actionBtn} disabled={busyId === s.tenantId} onClick={() => transition(s.tenantId, "active")}>Reactivate</button>
                              <button className={styles.actionBtn} disabled={busyId === s.tenantId} onClick={() => transition(s.tenantId, "suspended")}>Suspend</button>
                            </>
                          )}
                          {s.tenantStatus === "suspended" && (
                            <button className={styles.actionBtn} disabled={busyId === s.tenantId} onClick={() => transition(s.tenantId, "active")}>Reactivate</button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      ) : (
        <>
          <form className={styles.formCard} onSubmit={submitDiscount}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="discTenant">Clinic</label>
              <select id="discTenant" className={styles.select} value={discTenantId} onChange={(e) => setDiscTenantId(e.target.value)}>
                <option value="">Select a clinic…</option>
                {tenants.map((t) => <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="discPercent">Percent off</label>
              <input id="discPercent" type="number" min="0.01" max="100" step="0.01" className={styles.input} value={discPercent} onChange={(e) => setDiscPercent(e.target.value)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="discReason">Reason</label>
              <input id="discReason" className={styles.input} value={discReason} onChange={(e) => setDiscReason(e.target.value)} placeholder="Why does this clinic get a discount?" />
            </div>
            <button className={styles.submit} type="submit" disabled={discSaving}>{discSaving ? "Requesting…" : "Request discount"}</button>
            {discError && <div className={styles.formError} role="alert">{discError}</div>}
          </form>

          <div className={styles.card}>
            {discounts.length === 0 ? (
              <div className={styles.state}>No discount requests yet.</div>
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>Clinic</th><th>Requested by</th><th>%</th><th>Reason</th><th>Status</th><th>Reviewed by</th><th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {discounts.map((d) => (
                      <tr key={d.id}>
                        <td>{d.tenantName}</td>
                        <td>{d.requestedByName}</td>
                        <td>{d.percent}%</td>
                        <td>{d.reason}</td>
                        <td><span className={`${styles.pill} ${DISCOUNT_STATUS_CLASS[d.status] ?? ""}`}>{d.status.replace("_", " ")}</span></td>
                        <td className={d.reviewedByName ? undefined : styles.muted}>{d.reviewedByName ?? "—"}</td>
                        <td>
                          {d.status === "pending" && (
                            <>
                              <button className={styles.actionBtn} disabled={busyId === d.id} onClick={() => reviewDiscount(d.id, "approve")}>Approve</button>
                              <button className={styles.rejectBtn} disabled={busyId === d.id} onClick={() => reviewDiscount(d.id, "reject")}>Reject</button>
                            </>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </main>
  );
}
