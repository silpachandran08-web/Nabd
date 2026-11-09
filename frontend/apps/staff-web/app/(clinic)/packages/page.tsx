"use client";

// FEF-032: the Treatment Packages screen. The page owns the one data bundle, the view (list / form / sell / one sold
// package) and the tab; the form, the sell wizard, the list tabs and the instance writes live in this folder.
import { useCallback, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import styles from "./packages.module.css";
import { can } from "../../lib/permissions";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";
import { eventLabel } from "./eventLabel";
import { api, ApiError, intentKeys } from "../../lib/api";
import { key, prefix } from "../../lib/query";
import { ErrorSummary, FieldError, useFormErrors, type FormErrors } from "../../lib/formErrors";
import type { Full } from "../../lib/gen";
import type { PackageSettingsResponse } from "@nabd/api-client/generated";
import { STATUS_PILL, TABS, money, why, type ExpiringT, type InstanceT, type LiabilityT, type PackageT, type RefundT, type StaffOption, type Tab } from "./packagesModel";
import PackageForm from "./PackageForm";
import SellWizard from "./SellWizard";
import InstanceTable from "./InstanceTable";
import { CatalogueTab, ExpiringTab, LiabilityTab, RefundsTab, SessionsTab, SettingsTab, type CatalogueFilter } from "./PackageTabs";
import { useInstanceActions } from "./useInstanceActions";

export default function PackagesPage() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<Tab>("packages");
  const [floorEdit, setFloorEdit] = useState<string | null>(null);
  const [catalogueFilter, setCatalogueFilter] = useState<CatalogueFilter>("all");
  const [mode, setMode] = useState<"list" | "form" | "sell">("list");
  const [editing, setEditing] = useState<PackageT | null>(null);
  const fe = useFormErrors("pk", { newValidityEnd: "New expiry date", reason: "Reason", priceFloorPercent: "Price floor", expectedAmount: "Amount" }); // page actions
  const sellKeys = useRef(intentKeys()); // one Idempotency-Key per sale intent, reused when the same sale is re-sent after an error

  // One bundle for the screen: the catalogue and the instances must load (403 = no access); the rest are best effort.
  const bundleQ = useQuery({
    queryKey: key("packages"),
    queryFn: async ({ signal }) => {
      const get = <T,>(path: string) => api.get<T>(path, { signal });
      const [pkgs, insts, refunds, expiring, liability, settings, staff] = await Promise.allSettled([
        get<PackageT[]>("/packages"), get<InstanceT[]>("/packages/instances"), get<RefundT[]>("/packages/refunds"),
        get<ExpiringT[]>("/packages/expiring-soon"), get<LiabilityT>("/packages/liability"), get<Full<PackageSettingsResponse>>("/packages/settings"),
        can("queue:view") ? get<StaffOption[]>("/staff/roster") : Promise.resolve([] as StaffOption[]),
      ]);
      if (pkgs.status === "rejected") throw pkgs.reason;
      if (insts.status === "rejected") throw insts.reason;
      const ok = <T,>(r: PromiseSettledResult<T>, d: T): T => (r.status === "fulfilled" ? r.value : d);
      return {
        packages: pkgs.value, instances: insts.value, refunds: ok(refunds, [] as RefundT[]), expiring: ok(expiring, [] as ExpiringT[]),
        liability: ok<LiabilityT | null>(liability, null), floor: settings.status === "fulfilled" ? String(settings.value.priceFloorPercent) : "",
        staff: ok(staff, [] as StaffOption[]),
      };
    },
  });
  const packages = bundleQ.data?.packages ?? [];
  const instances = bundleQ.data?.instances ?? [];
  const refunds = bundleQ.data?.refunds ?? [];
  const expiring = bundleQ.data?.expiring ?? [];
  const liability = bundleQ.data?.liability ?? null;
  const staff = bundleQ.data?.staff ?? [];
  const floorInput = floorEdit ?? bundleQ.data?.floor ?? "";
  const loading = bundleQ.isPending;
  const forbidden = bundleQ.error instanceof ApiError && bundleQ.error.status === 403;
  const error = !bundleQ.error || forbidden ? null : bundleQ.error instanceof ApiError ? "Couldn't load treatment packages. Try again." : "Couldn't reach the server. Check your connection and try again.";
  /** Re-read everything on the screen (resolves when the refetch is done). */
  const loadAll = useCallback(() => qc.invalidateQueries({ queryKey: prefix("packages") }), [qc]);
  const x = useInstanceActions({ fe, loadAll, setTab });

  function openForm(p: PackageT | null) {
    setEditing(p);
    setMode("form");
  }

  /** Activate or deactivate a catalogue package. */
  async function setOnSale(id: string, on: boolean) {
    fe.clear();
    try {
      await api.post(`/packages/${id}/${on ? "activate" : "deactivate"}`);
    } catch (err) {
      fe.set(why(err, on ? "Couldn't activate." : "Couldn't deactivate."));
      return;
    }
    await loadAll();
  }

  async function saveSettings() {
    if (await x.act(() => api.patch("/packages/settings", { priceFloorPercent: Number(floorInput) || 0 }), "Action failed.")) setFloorEdit(null);
  }

  if (loading) return <main className={styles.page}><Skeleton rows={6} label="Loading treatment packages" /></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to Treatment Packages.</div></main>;
  if (error) return <main className={styles.page}><ErrorState message={error} onRetry={() => void loadAll()} /></main>;

  // Restricted actions are absent, not disabled (RBAC: Packages create / edit / approve are separate grants).
  const canCreate = can("packages:create");
  const canEdit = can("packages:edit");
  const canApprove = can("packages:approve");
  const visibleTabs = TABS.filter((t) => t.key === "liability" ? canApprove : t.key === "settings" ? canEdit : true);
  const inst = x.selectedInstanceId ? x.selectedInstance : null;

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Treatment Packages</h1>
          <p className={styles.subtitle}>Bundled sessions and combination programmes, sold once, redeemed over time.</p>
        </div>
        {mode === "list" && !x.selectedInstanceId && tab === "packages" && (canCreate) && (
          <div className={styles.headerActions}>
            <button className={styles.secondaryBtn} onClick={() => setMode("sell")}>Sell package</button>
            <button className={styles.wizardBtn} onClick={() => openForm(null)}>New package</button>
          </div>
        )}
        {(mode !== "list" || x.selectedInstanceId) && (
          <button className={styles.backBtn} onClick={() => { setMode("list"); x.closeInstance(); }}>← Back</button>
        )}
      </div>

      <ErrorSummary fe={fe} className={styles.bannerWarn} />

      {inst ? (
        <InstanceDetail
          instance={inst}
          canEdit={canEdit} canApprove={canApprove} fe={fe}
          onBook={x.book}
          onRedeem={x.redeem}
          extendDate={x.extendDate} setExtendDate={x.setExtendDate}
          extendReason={x.extendReason} setExtendReason={x.setExtendReason}
          onExtend={() => x.submitExtend(inst.id)}
          refundPreview={x.refundPreview}
          onLoadRefundPreview={() => x.loadRefundPreview(inst.id)}
          refundReason={x.refundReason} setRefundReason={x.setRefundReason}
          onRequestRefund={() => x.submitRefundRequest(inst.id)}
        />
      ) : mode === "form" ? (
        <PackageForm editing={editing} staff={staff} onCancel={() => setMode("list")} onSaved={async () => { setMode("list"); await loadAll(); }} />
      ) : mode === "sell" && canCreate ? (
        <SellWizard packages={packages} instances={instances} staff={staff} sellKeys={sellKeys} loadAll={loadAll}
          onCancel={() => setMode("list")} onOpenInstance={(id) => { setMode("list"); void x.openInstance(id); }} />
      ) : (
        <>
          <div className={styles.tabs}>
            {visibleTabs.map((t) => (
              <button key={t.key} className={tab === t.key ? styles.tabActive : styles.tab} onClick={() => setTab(t.key)}>{t.label}</button>
            ))}
          </div>

          {tab === "packages" && (
            <CatalogueTab packages={packages} filter={catalogueFilter} setFilter={setCatalogueFilter} canEdit={canEdit} onEdit={openForm} onSetOnSale={(id, on) => void setOnSale(id, on)} />
          )}
          {tab === "active" && (
            <InstanceTable instances={instances.filter((i) => i.status !== "cancelled")} onOpen={x.openInstance} showInvoice={false} />
          )}
          {tab === "sales" && (
            <InstanceTable instances={instances} onOpen={x.openInstance} showInvoice />
          )}
          {tab === "sessions" && <SessionsTab instances={instances} canEdit={canEdit} onBook={x.book} onRedeem={x.redeem} onOpen={x.openInstance} />}
          {tab === "expiring" && <ExpiringTab expiring={expiring} canEdit={canEdit} reminderNotice={x.reminderNotice} onRemind={x.sendReminder} />}
          {tab === "refunds" && (
            <RefundsTab refunds={refunds} canApprove={canApprove} deciding={x.deciding} setDeciding={x.setDeciding} rejectReason={x.rejectReason} setRejectReason={x.setRejectReason}
              onReview={(r) => void x.openDecision(r)} onApprove={() => void x.approveRefund()} onReject={() => void x.rejectRefund()} />
          )}
          {tab === "liability" && canApprove && liability && <LiabilityTab liability={liability} />}
          {tab === "settings" && canEdit && <SettingsTab fe={fe} floorInput={floorInput} setFloorEdit={setFloorEdit} onSave={saveSettings} />}
        </>
      )}
    </main>
  );
}

function InstanceDetail({
  instance, canEdit, canApprove, fe, onBook, onRedeem, extendDate, setExtendDate, extendReason, setExtendReason, onExtend,
  refundPreview, onLoadRefundPreview, refundReason, setRefundReason, onRequestRefund,
}: {
  instance: InstanceT;
  canEdit: boolean; canApprove: boolean; fe: FormErrors;
  onBook: (itemId: string) => void;
  onRedeem: (itemId: string) => void;
  extendDate: string; setExtendDate: (v: string) => void;
  extendReason: string; setExtendReason: (v: string) => void;
  onExtend: () => void;
  refundPreview: { paid: number; usedListValue: number; refundAmount: number; amountOwed: number } | null;
  onLoadRefundPreview: () => void;
  refundReason: string; setRefundReason: (v: string) => void;
  onRequestRefund: () => void;
}) {
  return (
    <div className={styles.card}>
      <div className={styles.packageHeader}>
        <div>
          <div className={styles.packageName}>{instance.patientName} — {instance.packageName}</div>
          <div className={styles.muted}>Invoice {instance.invoiceNumber} · expires {instance.validityEnd ?? "not started yet"}</div>
        </div>
        <span className={`${styles.pill} ${STATUS_PILL[instance.status] ?? styles.pillInactive}`}>{instance.status}</span>
      </div>

      <h2 className={`${styles.cardTitle} ${styles.cardTitleSpaced}`}>Items</h2>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead><tr><th scope="col">Item</th><th scope="col">Progress</th><th scope="col">Allocated</th><th scope="col"></th></tr></thead>
          <tbody>
            {instance.items.map((item) => (
              <tr key={item.id}>
                <td>{item.name}</td>
                <td>{item.quantityConsumed}/{item.quantityTotal}</td>
                <td>{money(item.allocatedPrice)}</td>
                <td>
                  {canEdit && <button className={styles.btnSmall} onClick={() => onBook(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Book next session</button>}{" "}
                  {canEdit && <button className={styles.btnSmall} onClick={() => onRedeem(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Redeem after completed service</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2 className={`${styles.cardTitle} ${styles.cardTitleSpaced}`}>Package ledger</h2>
      {(instance.events ?? []).map((e, idx) => (
        <div key={idx} className={styles.ledgerItem}>
          <div>
            <div>{eventLabel(e.eventType, e.note)}{e.delta != null && e.delta !== 0 ? ` (${e.delta})` : ""}</div>
            <div className={styles.ledgerNote}>{e.note} · {e.actorName}</div>
          </div>
          <div className={styles.muted}>{new Date(e.createdAt).toLocaleString()}</div>
        </div>
      ))}

      <div className={`${styles.row} ${styles.rowSpaced}`}>
        {canApprove && <div className={`${styles.card} ${styles.cardFlush}`}>
          <h2 className={styles.cardTitle}>Extend (manager approval required)</h2>
          <div className={styles.field}>
            <label className={styles.label}>New expiry date</label>
            <input className={styles.input} type="date" value={extendDate} {...fe.attrs("newValidityEnd")} onChange={(e) => { fe.clearField("newValidityEnd"); setExtendDate(e.target.value); }} />
            <FieldError fe={fe} name="newValidityEnd" className={styles.muted} />
          </div>
          <div className={styles.field}>
            <label className={styles.label}>Reason</label>
            <input className={styles.input} value={extendReason} {...fe.attrs("reason")} onChange={(e) => { fe.clearField("reason"); setExtendReason(e.target.value); }} />
            <FieldError fe={fe} name="reason" className={styles.muted} />
          </div>
          <button className={styles.btn} onClick={onExtend}>Extend</button>
        </div>}

        {canEdit && <div className={`${styles.card} ${styles.cardFlush}`}>
          <h2 className={styles.cardTitle}>Refund (consumed items valued at full list price)</h2>
          {!refundPreview ? (
            <button className={styles.btn} onClick={onLoadRefundPreview}>Calculate refund</button>
          ) : (
            <>
              <div className={styles.itemLine}><span>Paid</span><span>{money(refundPreview.paid)}</span></div>
              <div className={styles.itemLine}><span>Used at list price</span><span>{money(refundPreview.usedListValue)}</span></div>
              {refundPreview.amountOwed > 0 ? (
                <div className={styles.bannerWarn}>Consumed services exceed the amount paid by {money(refundPreview.amountOwed)}. There is no refund; the difference must be collected.</div>
              ) : (
                <div className={styles.itemLine}><strong>Refund amount</strong><strong>{money(refundPreview.refundAmount)}</strong></div>
              )}
              <div className={`${styles.field} ${styles.fieldSpaced}`}>
                <label className={styles.label}>Reason (required)</label>
                <input className={styles.input} value={refundReason} onChange={(e) => setRefundReason(e.target.value)} placeholder="Patient relocated, sold in error…" />
              </div>
              <button className={styles.btn} onClick={onRequestRefund}>Request refund</button>
            </>
          )}
        </div>}
      </div>
    </div>
  );
}
