"use client";

// FEF-032: the list tabs of Treatment Packages (catalogue, sessions, expiring, refunds, liability, settings), moved
// unchanged from the old single page. State that must survive a tab switch (filter, decision, notices) stays in the page.
import styles from "./packages.module.css";
import type { FormErrors } from "../../lib/formErrors";
import { STATUS_PILL, money, type ExpiringT, type InstanceT, type LiabilityT, type PackageT, type RefundT } from "./packagesModel";
import type { Deciding } from "./useInstanceActions";

export type CatalogueFilter = "all" | "on_sale" | "draft" | "inactive";

export function CatalogueTab({ packages, filter, setFilter, canEdit, onEdit, onSetOnSale }: {
  packages: PackageT[]; filter: CatalogueFilter; setFilter: (f: CatalogueFilter) => void; canEdit: boolean;
  onEdit: (p: PackageT) => void; onSetOnSale: (id: string, on: boolean) => void;
}) {
  const visiblePackages = filter === "all" ? packages : packages.filter((p) => p.status === filter);
  return (
    <>
      <div className={styles.tabs}>
        {(["all", "on_sale", "draft", "inactive"] as const).map((f) => (
          <button key={f} className={filter === f ? styles.tabActive : styles.tab} onClick={() => setFilter(f)}>
            {f === "all" ? "All" : f === "on_sale" ? "On sale" : f[0].toUpperCase() + f.slice(1)}
          </button>
        ))}
      </div>
      {visiblePackages.length === 0 ? (
        <div className={styles.empty}>No packages here yet.</div>
      ) : (
        <div className={styles.grid}>
          {visiblePackages.map((p) => (
            <div key={p.id} className={styles.packageCard}>
              <div className={styles.packageHeader}>
                <div>
                  <div className={styles.packageName}>{p.name}</div>
                  <div className={styles.muted}>{p.packageType === "combination" ? "Combination package" : "Session package"} · {p.items.length} entitlements</div>
                </div>
                <span className={`${styles.pill} ${STATUS_PILL[p.status]}`}>{p.status.replace("_", " ")}</span>
              </div>
              {p.doctorLeaveWarning && <div className={styles.bannerWarn}>{p.doctorLeaveWarning}</div>}
              {p.items.map((i) => (
                <div key={i.id} className={styles.itemLine}><span>{i.name}</span><span>×{i.quantity}</span></div>
              ))}
              <div className={styles.priceRow}>
                {p.saveAmount > 0 && <div className={styles.listPrice}>{money(p.listValue)}</div>}
                <div className={styles.salePrice}>{money(p.price)}</div>
                <div className={styles.muted}>{p.taxInclusive ? "Tax inclusive" : "Tax exclusive"} · {p.validityDays} days + {p.graceDays}d grace</div>
              </div>
              {canEdit && (
                <div className={styles.cardActions}>
                  <button className={styles.btnSmall} onClick={() => onEdit(p)}>Edit</button>
                  {p.status === "on_sale"
                    ? <button className={styles.btnSmall} onClick={() => onSetOnSale(p.id, false)}>Deactivate</button>
                    : <button className={styles.btnSmall} onClick={() => onSetOnSale(p.id, true)}>{p.belowFloor ? "Fix the price first" : "Activate"}</button>}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </>
  );
}

export function SessionsTab({ instances, canEdit, onBook, onRedeem, onOpen }: {
  instances: InstanceT[]; canEdit: boolean; onBook: (itemId: string) => void; onRedeem: (itemId: string) => void; onOpen: (id: string) => void;
}) {
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead><tr><th scope="col">Patient</th><th scope="col">Item</th><th scope="col">Package</th><th scope="col">Entitlement</th><th scope="col">Value</th><th scope="col"></th></tr></thead>
        <tbody>
          {instances.flatMap((inst) => inst.items.map((item) => (
            <tr key={item.id}>
              <td>{inst.patientName}</td>
              <td>{item.name}</td>
              <td>{inst.packageName}</td>
              <td>
                <div className={styles.progressWrap}>
                  <progress className={styles.progressBar} value={item.quantityConsumed} max={item.quantityTotal || 1} aria-hidden="true" />
                  <span className={styles.muted}>{item.quantityConsumed}/{item.quantityTotal}</span>
                </div>
              </td>
              <td>{money(item.allocatedPrice)}</td>
              <td>
                {canEdit && <button className={styles.btnSmall} onClick={() => onBook(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Book</button>}{" "}
                {canEdit && <button className={styles.btnSmall} onClick={() => onRedeem(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Redeem</button>}{" "}
                <button className={styles.btnSmall} onClick={() => onOpen(inst.id)}>Ledger</button>
              </td>
            </tr>
          )))}
        </tbody>
      </table>
      {instances.every((i) => i.items.length === 0) && <div className={styles.empty}>No sold packages yet.</div>}
    </div>
  );
}

export function ExpiringTab({ expiring, canEdit, reminderNotice, onRemind }: {
  expiring: ExpiringT[]; canEdit: boolean; reminderNotice: string | null; onRemind: (instanceId: string) => void;
}) {
  return (
    <div className={styles.tableWrap}>
      {reminderNotice && <div className={styles.muted} role="status">{reminderNotice}</div>}
      <table className={styles.table}>
        <thead><tr><th scope="col">Patient</th><th scope="col">Package</th><th scope="col">Remaining</th><th scope="col">Expires</th><th scope="col">Value left</th><th scope="col">Contact</th><th scope="col"></th></tr></thead>
        <tbody>
          {expiring.map((e) => (
            <tr key={e.instanceId}>
              <td>{e.patientName}</td>
              <td>{e.packageName}</td>
              <td>{e.quantityTotal - e.quantityConsumed}/{e.quantityTotal}</td>
              <td>{e.expiresOn}</td>
              <td>{money(e.valueLeft)}</td>
              <td><span className={`${styles.pill} ${e.reminderSentForTier ? styles.pillDone : styles.pillWarn}`}>{e.reminderSentForTier ? "Marked reminded" : `${e.alertTier}-day reminder due`}</span></td>
              <td>{canEdit && <button className={styles.btnSmall} onClick={() => onRemind(e.instanceId)} disabled={e.reminderSentForTier}>Mark reminded</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {expiring.length === 0 && <div className={styles.empty}>Nothing expiring within 30 days.</div>}
    </div>
  );
}

export function RefundsTab({ refunds, canApprove, deciding, setDeciding, rejectReason, setRejectReason, onReview, onApprove, onReject }: {
  refunds: RefundT[]; canApprove: boolean; deciding: Deciding | null; setDeciding: (d: Deciding | null) => void;
  rejectReason: string; setRejectReason: (v: string) => void; onReview: (r: RefundT) => void; onApprove: () => void; onReject: () => void;
}) {
  return (
    <>
      {deciding && canApprove && (
        <section className={styles.card} aria-label="Review refund">
          <h2 className={styles.cardTitle}>Refund · {deciding.refund.patientName} · {deciding.refund.packageName}</h2>
          {deciding.changed && <div className={styles.bannerWarn} role="alert">The amount changed since the request because sessions were used. Check the new amount before approving.</div>}
          <div className={styles.itemLine}><span>Amount at request</span><span data-testid="refund-requested">{money(deciding.refund.refundAmount)}</span></div>
          <div className={styles.itemLine}><strong>Amount now</strong><strong data-testid="refund-current">{money(deciding.current)}</strong></div>
          {deciding.owed > 0 && <div className={styles.bannerWarn}>Used sessions exceed the amount paid by {money(deciding.owed)}. No refund; the difference must be collected.</div>}
          <div className={styles.cardActions}>
            <button className={styles.decisionBtn} onClick={onApprove}>Approve {money(deciding.current)}</button>
            <button className={styles.decisionBtn} onClick={() => setDeciding(null)}>Cancel</button>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="refund-reject-reason">Reason to reject</label>
            <input id="refund-reject-reason" className={styles.input} value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} />
          </div>
          <div className={styles.cardActions}>
            <button className={styles.decisionBtn} onClick={onReject}>Reject</button>
          </div>
        </section>
      )}
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead><tr><th scope="col">Patient</th><th scope="col">Package</th><th scope="col">Reason</th><th scope="col">Refund</th><th scope="col">Owed</th><th scope="col">Status</th><th scope="col"></th></tr></thead>
          <tbody>
            {refunds.map((r) => (
              <tr key={r.id}>
                <td>{r.patientName}</td>
                <td>{r.packageName}</td>
                <td>{r.reason}</td>
                <td>{money(r.recomputedAmount ?? r.refundAmount)}</td>
                <td>{money(r.amountOwed)}</td>
                <td><span className={`${styles.pill} ${STATUS_PILL[r.status]}`}>{r.status}{r.creditNoteNumber ? ` · ${r.creditNoteNumber}` : ""}</span></td>
                <td>{canApprove && r.status === "pending" && <button className={styles.btnSmall} onClick={() => onReview(r)}>Review</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {refunds.length === 0 && <div className={styles.empty}>No refund requests.</div>}
      </div>
    </>
  );
}

export function LiabilityTab({ liability }: { liability: LiabilityT }) {
  const stat = (label: string, value: string | number) => (
    <div className={styles.statCard}><div className={styles.statLabel}>{label}</div><div className={styles.statValue}>{value}</div></div>
  );
  return (
    <div className={styles.statGrid}>
      {stat("Active patient packages", liability.activePatientPackages)}
      {stat("Sessions owed", liability.sessionsOwed)}
      {stat("Remaining list value", money(liability.remainingListValue))}
      {stat("Remaining allocated value", money(liability.remainingAllocatedValue))}
      {stat("In grace period", liability.inGracePeriod)}
      {stat("Expiring in 30 days", liability.expiringIn30Days)}
      {stat("Potential expiry loss", money(liability.potentialExpiryLoss))}
      {stat("Refunds awaiting approval", liability.refundsAwaitingApproval)}
    </div>
  );
}

export function SettingsTab({ fe, floorInput, setFloorEdit, onSave }: { fe: FormErrors; floorInput: string; setFloorEdit: (v: string) => void; onSave: () => void }) {
  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Pricing rules</h2>
      <div className={styles.field}>
        <label className={styles.label}>Price floor (% of list value — never silently overridable)</label>
        <input className={`${styles.input} ${styles.floorInput}`} type="number" step="0.1" value={floorInput} {...fe.attrs("priceFloorPercent")} onChange={(e) => { fe.clearField("priceFloorPercent"); setFloorEdit(e.target.value); }} />
      </div>
      <div className={`${styles.actions} ${styles.actionsStart}`}>
        <button className={styles.btnPrimary} onClick={onSave}>Save</button>
      </div>
      <p className={`${styles.muted} ${styles.mutedSpaced}`}>
        Scheduled instalments, pay-per-session and transfer &amp; gifting are a later phase and not built yet.
        Membership and Family Plan package types are further out still.
      </p>
    </div>
  );
}
