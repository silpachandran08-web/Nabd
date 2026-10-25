"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./billing.module.css";
import Dialog from "../../components/ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import RoleDenied, { ReadOnlyNote } from "../../components/platform/RoleDenied";
import { DiscountStatusPill, TenantStatusPill } from "../../components/platform/statusPills";
import { formatMoney } from "@nabd/domain";
import { problemDetail, useOperatorAccess, usePlatformFetch } from "../platformApi";

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
// SSA-08: sales approve discounts up to this share themselves; anything above waits for a super admin.
const DISCOUNT_CAP_PERCENT = 15;

// What each lifecycle move does to the clinic and its patients (wireframe pxtActionNote). Reason is
// mandatory and the move is audited by the server.
const TRANSITION_EFFECT: Record<string, { title: string; note: string }> = {
  overdue: {
    title: "Mark as payment overdue",
    note: "Starts the 14-day grace period. Everything keeps working; only the owner sees a payment banner. After day 14 the clinic is suspended.",
  },
  suspended: {
    title: "Suspend this clinic",
    note: "The clinic becomes read-only: no booking and no billing, history stays readable. Patients are told \"The clinic is temporarily offline — please call the clinic\". Reactivating restores full access.",
  },
  active: {
    title: "Reactivate this clinic",
    note: "Restores full access immediately: booking, billing and patient messaging resume.",
  },
};
const MIN_REASON = 10;

export default function BillingPage() {
  const router = useRouter();
  const access = useOperatorAccess();
  const [tab, setTab] = useState<"subscriptions" | "discounts">("subscriptions");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [plansUnavailable, setPlansUnavailable] = useState(false); // only surfaced to roles that can set plans
  const [forbidden, setForbidden] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [pendingMove, setPendingMove] = useState<{ tenantId: string; tenantName: string; toStatus: string } | null>(null);
  const [moveReason, setMoveReason] = useState("");
  const [moveError, setMoveError] = useState<string | null>(null);

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

  const authedFetch = usePlatformFetch();

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setLoadError(null);
    setPlansUnavailable(false);
    try {
      const subsRes = await authedFetch("/platform/billing/subscriptions?limit=100");
      if (!subsRes) return;
      if (subsRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!subsRes.ok) {
        setLoadError("Couldn't load billing data. Try again.");
        return;
      }
      setSubscriptions((await subsRes.json()).data);

      const discRes = await authedFetch("/platform/billing/discounts?limit=100");
      if (discRes?.ok) setDiscounts((await discRes.json()).data);

      const tenantsRes = await authedFetch("/platform/tenants?limit=100");
      if (tenantsRes?.ok) setTenants((await tenantsRes.json()).data);

      const plansRes = await authedFetch("/platform/plans");
      if (plansRes?.ok) {
        setPlans((await plansRes.json()).filter((p: PlanOption) => p.active));
      } else if (plansRes && plansRes.status !== 401) {
        // Pricing & Packaging access is a separate authority (pricing_packaging:view) from billing —
        // a role with billing access but not that one would otherwise see a silently empty Plan
        // dropdown with no clue why, same confusion an empty-but-successful load already causes.
        setPlansUnavailable(true);
      }
    } catch {
      setLoadError("Couldn't reach the server. Check your connection and try again.");
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
        setSubError(await problemDetail(res, "Couldn't save subscription."));
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

  function askTransition(s: Subscription, toStatus: string) {
    setMoveReason("");
    setMoveError(null);
    setPendingMove({ tenantId: s.tenantId, tenantName: s.tenantName, toStatus });
  }

  async function confirmTransition(e: React.FormEvent) {
    e.preventDefault();
    if (!pendingMove) return;
    const reason = moveReason.trim();
    if (reason.length < MIN_REASON) {
      setMoveError(`Give a reason of at least ${MIN_REASON} characters — it is written to the audit log.`);
      return;
    }
    const { tenantId, toStatus } = pendingMove;
    setBusyId(tenantId);
    setMoveError(null);
    try {
      const res = await authedFetch(`/platform/billing/subscriptions/${tenantId}/transitions`, {
        method: "POST",
        body: JSON.stringify({ toStatus, reason }),
      });
      if (!res) return;
      if (!res.ok) {
        setMoveError(await problemDetail(res, "Couldn't update status."));
        return;
      }
      setPendingMove(null);
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
        setDiscError(await problemDetail(res, "Couldn't request discount."));
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
        setError(await problemDetail(res, "Couldn't review discount."));
        return;
      }
      load();
    } finally {
      setBusyId(null);
    }
  }

  const effect = pendingMove ? TRANSITION_EFFECT[pendingMove.toStatus] : null;
  const dangerMove = pendingMove?.toStatus === "suspended";

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Billing & Revenue</h1>
          <p className={styles.subtitle}>Subscriptions, dunning and discount approvals.</p>
        </div>
      </div>

      {!forbidden && !loadError && (
        <div className={styles.tabs}>
          <button className={tab === "subscriptions" ? styles.tabActive : styles.tab} onClick={() => setTab("subscriptions")}>Subscriptions</button>
          <button className={tab === "discounts" ? styles.tabActive : styles.tab} onClick={() => setTab("discounts")}>Discounts</button>
        </div>
      )}

      {!forbidden && access.ready && access.readOnly && <ReadOnlyNote />}

      {error && <div className={styles.formError} role="alert">{error}</div>}
      {plansUnavailable && access.canBill && <div className={styles.formError} role="alert">Couldn&apos;t load plans — check you have Pricing &amp; Packaging access.</div>}

      {loading ? (
        <div className={styles.card}><Skeleton rows={6} label="Loading billing" /></div>
      ) : forbidden ? (
        <div className={styles.card}><RoleDenied surface="Billing & revenue" /></div>
      ) : loadError ? (
        <div className={styles.card}><ErrorState message={loadError} onRetry={load} /></div>
      ) : tab === "subscriptions" ? (
        <>
          {access.canBill && (
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
                  {plans.map((p) => <option key={p.id} value={p.id}>{p.name} ({formatMoney(p.monthlyPriceCents / 100, p.currency)})</option>)}
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
              {plans.length === 0 && (
                // Full-width so it wraps onto its own line below the field row instead of
                // stretching the Plan field taller than its siblings — .formCard bottom-aligns
                // every field (align-items: flex-end), so a taller Plan field used to knock
                // Clinic/MRR/Renewal date and Set plan out of alignment with it.
                <p className={styles.planHint}>
                  No plans exist yet.{" "}
                  <button type="button" className={styles.actionBtn} onClick={() => router.push("/platform/plans")}>
                    Create one in Pricing &amp; Packaging
                  </button>
                </p>
              )}
              {subError && <div className={styles.formError} role="alert">{subError}</div>}
            </form>
          )}

          <div className={styles.card}>
            {subscriptions.length === 0 ? (
              <EmptyState icon="receipt" title="No subscriptions yet" hint={access.canBill ? "Pick a clinic and a plan above to start its subscription." : undefined} />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th scope="col">Clinic</th><th scope="col">Plan</th><th scope="col">MRR</th><th scope="col">Renewal</th><th scope="col">Seats</th><th scope="col">Status</th>{access.canBill && <th scope="col"></th>}
                    </tr>
                  </thead>
                  <tbody>
                    {subscriptions.map((s) => (
                      <tr key={s.id}>
                        <td><span className={styles.tenantName}>{s.tenantName}</span><span className={styles.tenantSlug}>{s.tenantSlug}</span></td>
                        <td>{s.planName}</td>
                        <td>{formatMoney(s.mrrCents / 100, s.currency)}</td>
                        <td>{s.renewalDate}</td>
                        <td className={s.seatsUsed > s.seatLimit ? undefined : styles.muted}>{s.seatsUsed} / {s.seatLimit}</td>
                        <td><TenantStatusPill status={s.tenantStatus} /></td>
                        {access.canBill && (
                          <td>
                            {s.tenantStatus === "active" && (
                              <>
                                <button className={styles.actionBtn} disabled={busyId === s.tenantId} onClick={() => askTransition(s, "overdue")}>Mark overdue</button>
                                <button className={styles.rejectBtn} disabled={busyId === s.tenantId} onClick={() => askTransition(s, "suspended")}>Suspend</button>
                              </>
                            )}
                            {s.tenantStatus === "overdue" && (
                              <>
                                <button className={styles.actionBtn} disabled={busyId === s.tenantId} onClick={() => askTransition(s, "active")}>Reactivate</button>
                                <button className={styles.rejectBtn} disabled={busyId === s.tenantId} onClick={() => askTransition(s, "suspended")}>Suspend</button>
                              </>
                            )}
                            {s.tenantStatus === "suspended" && (
                              <button className={styles.actionBtn} disabled={busyId === s.tenantId} onClick={() => askTransition(s, "active")}>Reactivate</button>
                            )}
                          </td>
                        )}
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
          <p className={styles.capNote}>
            Sales approve up to {DISCOUNT_CAP_PERCENT}% themselves. Anything above stops here until a super admin approves — and the approval is logged by name.
          </p>
          {access.canBill && (
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
          )}

          <div className={styles.card}>
            {discounts.length === 0 ? (
              <EmptyState icon="receipt" title="No discount requests yet" />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th scope="col">Clinic</th><th scope="col">Requested by</th><th scope="col">%</th><th scope="col">Reason</th><th scope="col">Status</th><th scope="col">Reviewed by</th>{access.isSuper && <th scope="col"></th>}
                    </tr>
                  </thead>
                  <tbody>
                    {discounts.map((d) => (
                      <tr key={d.id}>
                        <td>{d.tenantName}</td>
                        <td>{d.requestedByName}</td>
                        <td>{d.percent}%</td>
                        <td>{d.reason}</td>
                        <td><DiscountStatusPill status={d.status} /></td>
                        <td className={d.reviewedByName ? undefined : styles.muted}>{d.reviewedByName ?? "—"}</td>
                        {access.isSuper && (
                          <td>
                            {d.status === "pending" && d.requestedBy !== access.profile?.id && ( // the requester can't review their own request (DiscountService)
                              <>
                                <button className={styles.actionBtn} disabled={busyId === d.id} onClick={() => reviewDiscount(d.id, "approve")}>Approve (super admin)</button>
                                <button className={styles.rejectBtn} disabled={busyId === d.id} onClick={() => reviewDiscount(d.id, "reject")}>Reject</button>
                              </>
                            )}
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {pendingMove && effect && (
        <Dialog title={`${effect.title} — ${pendingMove.tenantName}`} onClose={() => setPendingMove(null)}>
          <form className={styles.dialogForm} onSubmit={confirmTransition}>
            <p className={dangerMove ? styles.effectNoteDanger : styles.effectNote}>{effect.note}</p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="moveReason">Reason (written to the audit log)</label>
              <textarea
                id="moveReason"
                className={styles.textarea}
                value={moveReason}
                onChange={(e) => setMoveReason(e.target.value)}
                minLength={MIN_REASON}
                required
                autoFocus
              />
              <span className={styles.hint}>At least {MIN_REASON} characters.</span>
            </div>
            {moveError && <div className={styles.formError} role="alert">{moveError}</div>}
            <div className={styles.dialogActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setPendingMove(null)}>Cancel</button>
              <button
                type="submit"
                className={dangerMove ? styles.dangerSubmit : styles.submit}
                disabled={busyId === pendingMove.tenantId || moveReason.trim().length < MIN_REASON}
              >
                {busyId === pendingMove.tenantId ? "Saving…" : effect.title}
              </button>
            </div>
          </form>
        </Dialog>
      )}
    </main>
  );
}
