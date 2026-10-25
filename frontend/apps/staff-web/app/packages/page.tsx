"use client";

import { useCallback, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import styles from "./packages.module.css";
import { can } from "../lib/permissions";
import StatusPill from "../components/ui/StatusPill";
import { ErrorState, Skeleton } from "../components/ui/StateViews";
import { PAYMENT_METHODS, paymentMethodLabel, formatMoney } from "@nabd/domain";
import { getIdentity } from "../lib/session";
import { eventLabel } from "./eventLabel";
import { api, ApiError, intentKeys } from "../lib/api";
import { key, prefix } from "../lib/query";
import { ErrorSummary, FieldError, useFormErrors, type FormErrors } from "../lib/formErrors";
import { usePatientSearch } from "../lib/usePatientSearch";
import type { Full } from "../lib/gen";
import type {
  ExpiringSoonResponse, InstanceEventResponse, InstanceItemResponse, InstanceResponse, InvoiceResponse, LiabilityResponse, LineItemResponse,
  PackageItemResponse, PackageResponse, PackageSettingsResponse, RefundPreviewResponse, RefundResponse, StaffRosterEntry,
} from "@nabd/api-client/generated";

// GET/POST/PATCH /v1/packages/** (PackageController) — the generated types, with the fields the server always sends marked present.
type PackageT = Omit<Full<PackageResponse, "speciality" | "description" | "refundNote" | "doctorLeaveWarning">, "items"> & { items: Full<PackageItemResponse>[] };
type InstanceT = Omit<Full<InstanceResponse, "validityStart" | "validityEnd" | "events">, "items" | "events"> & {
  items: Full<InstanceItemResponse>[]; events: Full<InstanceEventResponse, "note" | "delta">[] | null;
};
type RefundT = Full<RefundResponse, "creditNoteNumber" | "recomputedAmount">;
type ExpiringT = Full<ExpiringSoonResponse>;
type LiabilityT = Full<LiabilityResponse>;
type StaffOption = Full<StaffRosterEntry>;
type ItemDraft = { itemType: string; name: string; quantity: string; unitListPrice: string; taxRatePercent: string };
type Tab = "packages" | "active" | "sales" | "sessions" | "expiring" | "refunds" | "liability" | "settings";

const TABS: { key: Tab; label: string }[] = [
  { key: "packages", label: "Packages" },
  { key: "active", label: "Active patient packages" },
  { key: "sales", label: "Sales" },
  { key: "sessions", label: "Sessions & redemptions" },
  { key: "expiring", label: "Expiring soon" },
  { key: "refunds", label: "Refunds & adjustments" },
  { key: "liability", label: "Package liability" },
  { key: "settings", label: "Package settings" },
];
const STATUS_PILL: Record<string, string> = {
  active: styles.pillActive, grace: styles.pillWarn, expired: styles.pillDanger,
  completed: styles.pillDone, refunded: styles.pillInactive, cancelled: styles.pillInactive,
  on_sale: styles.pillActive, draft: styles.pillWarn, inactive: styles.pillInactive,
  pending: styles.pillWarn, approved: styles.pillDone, rejected: styles.pillInactive,
};
// Sell-package wizard (wireframe pksell SELLSTEPS).
const SELL_STEPS = ["Patient", "Package", "Eligibility", "Review", "Payment", "Invoice & card"];
// POST /packages/sell refuses (duplicate-active-package) while the stored status is 'active'. The list shows an
// effective status computed by date (PackageInstanceService.effectiveStatus), so active, grace and expired copies
// are all still stored as 'active' and all block a new sale; only completed or refunded copies do not.
const BLOCKING_STATUSES = ["active", "grace", "expired"];
// Invoice issued by POST /packages/sell, read back from GET /billing/invoices/{id} (billing:view) so the
// final step shows the server's own net / tax / total and per-line tax, never a client-side estimate.
type SoldInvoice = Omit<Full<InvoiceResponse>, "lineItems"> & { lineItems: Full<LineItemResponse>[] };
const EMPTY_ITEM: ItemDraft = { itemType: "service_session", name: "", quantity: "1", unitListPrice: "0", taxRatePercent: "0" };

function money(n: number): string {
  return n.toFixed(2);
}

/** The words a person sees for a refused request: the server's reason, or the offline line. */
const why = (e: unknown, fallback: string) =>
  e instanceof ApiError ? e.detail || fallback : "Could not reach the server. Check your connection and try again.";

export default function PackagesPage() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<Tab>("packages");
  const [floorEdit, setFloorEdit] = useState<string | null>(null);

  const [catalogueFilter, setCatalogueFilter] = useState<"all" | "on_sale" | "draft" | "inactive">("all");
  const [mode, setMode] = useState<"list" | "form" | "sell">("list");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [selectedInstanceId, setSelectedInstanceId] = useState<string | null>(null);
  const [selectedInstance, setSelectedInstance] = useState<InstanceT | null>(null);
  const fe = useFormErrors("pk", { newValidityEnd: "New expiry date", reason: "Reason", priceFloorPercent: "Price floor", expectedAmount: "Amount" }); // page actions

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

  // ── new / edit package form ─────────────────────────────────────────────
  const [formName, setFormName] = useState("");
  const [formType, setFormType] = useState("combination");
  const [formSpeciality, setFormSpeciality] = useState("");
  const [formDescription, setFormDescription] = useState("");
  const [formPrice, setFormPrice] = useState("0");
  const [formTaxInclusive, setFormTaxInclusive] = useState(false);
  const [formValidityDays, setFormValidityDays] = useState("90");
  const [formValidityStarts, setFormValidityStarts] = useState("purchase_date");
  const [formGraceDays, setFormGraceDays] = useState("7");
  const [formRefundNote, setFormRefundNote] = useState("");
  const [formDoctorIds, setFormDoctorIds] = useState<string[]>([]);
  const [formItems, setFormItems] = useState<ItemDraft[]>([{ ...EMPTY_ITEM }]);
  const ffe = useFormErrors("pkf", { name: "Package name", price: "Price", validityDays: "Validity", graceDays: "Grace days", packageType: "Package type", speciality: "Speciality", description: "Description", refundNote: "Refund note", eligibleDoctorIds: "Doctors" });
  const [saving, setSaving] = useState(false);

  function resetForm() {
    setFormName(""); setFormType("combination"); setFormSpeciality(""); setFormDescription("");
    setFormPrice("0"); setFormTaxInclusive(false); setFormValidityDays("90"); setFormValidityStarts("purchase_date");
    setFormGraceDays("7"); setFormRefundNote(""); setFormDoctorIds([]); setFormItems([{ ...EMPTY_ITEM }]); ffe.clear();
  }

  function openNewForm() {
    resetForm();
    setEditingId(null);
    setMode("form");
  }

  function openEditForm(p: PackageT) {
    setFormName(p.name); setFormType(p.packageType); setFormSpeciality(p.speciality ?? ""); setFormDescription(p.description ?? "");
    setFormPrice(String(p.price)); setFormTaxInclusive(p.taxInclusive); setFormValidityDays(String(p.validityDays));
    setFormValidityStarts(p.validityStarts); setFormGraceDays(String(p.graceDays)); setFormRefundNote(p.refundNote ?? "");
    setFormDoctorIds(p.eligibleDoctorIds);
    setFormItems(p.items.map((i) => ({ itemType: i.itemType, name: i.name, quantity: String(i.quantity), unitListPrice: String(i.unitListPrice), taxRatePercent: String(i.taxRatePercent) })));
    ffe.clear();
    setEditingId(p.id);
    setMode("form");
  }

  const formListValue = formItems.reduce((sum, i) => sum + (Number(i.unitListPrice) || 0) * (Number(i.quantity) || 0), 0);
  const formSave = Math.max(0, formListValue - (Number(formPrice) || 0));

  async function submitForm(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    ffe.clear();
    const items = formItems.filter((i) => i.name.trim().length > 0);
    if (items.length === 0) {
      ffe.set("Add at least one included item.");
      return;
    }
    setSaving(true);
    try {
      const body = {
        name: formName, packageType: formType, speciality: formSpeciality || null, description: formDescription || null,
        price: Number(formPrice) || 0, taxInclusive: formTaxInclusive, validityDays: Number(formValidityDays) || 1,
        validityStarts: formValidityStarts, graceDays: Number(formGraceDays) || 0, refundNote: formRefundNote || null,
        eligibleDoctorIds: formDoctorIds,
        items: items.map((i) => ({ itemType: i.itemType, name: i.name, quantity: Number(i.quantity) || 1, unitListPrice: Number(i.unitListPrice) || 0, taxRatePercent: Number(i.taxRatePercent) || 0 })),
      };
      await (editingId ? api.patch(`/packages/${editingId}`, body) : api.post("/packages", body));
      setMode("list");
      await loadAll();
    } catch (err) {
      if (err instanceof ApiError && !(err.status === 400 && err.errors.length)) ffe.set(err.detail || "Couldn't save the package.");
      else ffe.fail(err, "Couldn't save the package.");
    } finally {
      setSaving(false);
    }
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
  const activate = (id: string) => setOnSale(id, true);
  const deactivate = (id: string) => setOnSale(id, false);

  // ── sell flow ────────────────────────────────────────────────────────────
  const [sellQuery, setSellQuery] = useState("");
  const [sellPatient, setSellPatient] = useState<{ id: string; name: string; phone: string; mrn: string } | null>(null);
  const { results: sellResults } = usePatientSearch(sellPatient ? "" : sellQuery, 10);
  const [sellPackageId, setSellPackageId] = useState("");
  const [sellMethod, setSellMethod] = useState("");
  const [sellStep, setSellStep] = useState(0);
  const [sold, setSold] = useState<InstanceT | null>(null);
  const [soldInvoice, setSoldInvoice] = useState<SoldInvoice | null>(null);
  const sfe = useFormErrors("pks", { patientId: "Patient", packageId: "Package", paymentMethod: "Payment method" });
  const [selling, setSelling] = useState(false);
  const sellKeys = useRef(intentKeys()); // one Idempotency-Key per sale intent, reused when the same sale is re-sent after an error

  function onSellQueryChange(value: string) {
    setSellQuery(value);
    setSellPatient(null);
  }

  function openSellForm() {
    setSellQuery(""); setSellPatient(null); setSellPackageId(""); setSellMethod(""); sfe.clear();
    setSellStep(0); setSold(null); setSoldInvoice(null);
    setMode("sell");
  }

  const sellPackage = packages.find((p) => p.id === sellPackageId) ?? null;

  async function submitSale() {
    if (selling) return;
    sfe.clear();
    if (!sellPatient || !sellPackageId) {
      sfe.set("Pick a patient and a package.");
      return;
    }
    if (!sellMethod) {
      sfe.set("Choose a payment method.");
      return;
    }
    setSelling(true);
    const body = { patientId: sellPatient.id, packageId: sellPackageId, paymentMethod: sellMethod };
    let instance: InstanceT;
    try {
      instance = await api.post<InstanceT>("/packages/sell", body, { idempotencyKey: sellKeys.current.get("sell", body) });
      sellKeys.current.done("sell");
    } catch (err) {
      if (err instanceof ApiError && !(err.status === 400 && err.errors.length)) sfe.set(err.detail || "Couldn't sell the package.");
      else if (err instanceof ApiError) sfe.fail(err, "Couldn't sell the package.");
      else sfe.set("Couldn't reach the server. The sale may or may not have gone through: check Active patient packages before trying again.");
      setSelling(false);
      return;
    }
    // The invoice exists from here on: stay in the wizard on the "Invoice & card" step.
    setSold(instance);
    setSellStep(5);
    if (can("billing:view")) {
      try { setSoldInvoice(await api.get<SoldInvoice>(`/billing/invoices/${instance.invoiceId}`)); } catch { /* the sale stands; the summary falls back to the instance */ }
    }
    await loadAll();
    setSelling(false);
  }

  // ── instance actions ─────────────────────────────────────────────────────
  // The instances list (loadAll) never carries ledger events — only the single-instance detail
  // endpoint does — so the detail view keeps its own freshly-fetched copy instead of looking itself
  // up in that list.
  async function loadSelectedInstance(id: string) {
    try { setSelectedInstance(await api.get<InstanceT>(`/packages/instances/${id}`)); } catch { /* the detail keeps its last copy */ }
  }

  async function openInstance(id: string) {
    fe.clear();
    setSelectedInstanceId(id);
    await loadSelectedInstance(id);
  }

  /** Run one write on an instance: a refusal shows beside its field / in the summary; the screen is always re-read after. */
  async function act(call: () => Promise<unknown>, fallback: string): Promise<boolean> {
    let ok = true;
    try { await call(); } catch (err) {
      ok = false;
      if (err instanceof ApiError && err.status === 400 && err.errors.length) fe.fail(err, fallback); else fe.set(why(err, fallback));
    }
    await loadAll();
    if (selectedInstanceId) await loadSelectedInstance(selectedInstanceId);
    return ok;
  }

  async function book(itemId: string) {
    fe.clear();
    await act(() => api.post(`/packages/instances/items/${itemId}/book`), "Action failed.");
  }

  async function redeem(itemId: string) {
    fe.clear();
    await act(() => api.post(`/packages/instances/items/${itemId}/redeem`), "Action failed.");
  }

  const [extendDate, setExtendDate] = useState("");
  const [extendReason, setExtendReason] = useState("");
  async function submitExtend(instanceId: string) {
    if (!extendDate || !extendReason.trim()) {
      fe.set("Pick a new date and give a reason.");
      return;
    }
    fe.clear();
    setExtendDate(""); setExtendReason("");
    await act(() => api.post(`/packages/instances/${instanceId}/extend`, { newValidityEnd: extendDate, reason: extendReason }), "Action failed.");
  }

  const [refundPreview, setRefundPreview] = useState<Full<RefundPreviewResponse, never> | null>(null);
  const [refundReason, setRefundReason] = useState("");
  async function loadRefundPreview(instanceId: string) {
    try { setRefundPreview(await api.get<Full<RefundPreviewResponse>>(`/packages/instances/${instanceId}/refund-preview`)); } catch { /* the button stays; press it again */ }
  }
  async function submitRefundRequest(instanceId: string) {
    if (!refundReason.trim()) {
      fe.set("Give a refund reason.");
      return;
    }
    fe.clear();
    const reason = refundReason;
    setRefundReason(""); setRefundPreview(null);
    if (await act(() => api.post(`/packages/instances/${instanceId}/refund`, { reason }), "Action failed.")) {
      setSelectedInstanceId(null);
      setSelectedInstance(null);
      setTab("refunds");
    }
  }

  // FIN-004: the approver sees request-time vs current amount and confirms the current one (expectedAmount).
  const [deciding, setDeciding] = useState<{ refund: RefundT; current: number; owed: number; changed: boolean } | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  async function openDecision(r: RefundT) {
    fe.clear(); setRejectReason("");
    try {
      const p = await api.get<Full<RefundPreviewResponse>>(`/packages/instances/${r.instanceId}/refund-preview`);
      setDeciding({ refund: r, current: p.refundAmount, owed: p.amountOwed, changed: p.refundAmount !== r.refundAmount });
    } catch { fe.set("Could not load the current refund amount. Try again."); }
  }
  const decideLock = useRef(false); // synchronous guard (as in checkout): a double-click must never send a second decision
  async function decide(fn: () => Promise<void>) {
    if (decideLock.current) return;
    decideLock.current = true;
    try { await fn(); } finally { decideLock.current = false; }
  }
  const approveRefund = () => decide(async () => {
    if (!deciding) return;
    fe.clear();
    try {
      await api.post(`/packages/refunds/${deciding.refund.id}/approve`, { expectedAmount: deciding.current });
    } catch (err) {
      const p = err instanceof ApiError && err.status === 409 ? err.problem : null;
      if (p && String(p.type).endsWith("amount-changed")) {
        setDeciding({ ...deciding, current: p.currentAmount as number, owed: (p.currentAmountOwed as number | undefined) ?? deciding.owed, changed: true });
        return;
      }
      fe.set(why(err, "Action failed."));
      await loadAll();
      return;
    }
    setDeciding(null);
    await act(async () => {}, "Action failed.");
  });
  const rejectRefund = () => decide(async () => {
    if (!deciding) return;
    if (!rejectReason.trim()) { fe.set("Give a reason to reject."); return; }
    fe.clear();
    const id = deciding.refund.id;
    const ok = await act(() => api.post(`/packages/refunds/${id}/reject`, { reason: rejectReason }), "Action failed.");
    if (ok) setDeciding(null);
  });

  const [reminderNotice, setReminderNotice] = useState<string | null>(null);
  async function sendReminder(instanceId: string) {
    // The endpoint only records the reminder; nothing is messaged (COM-044 wires real sending).
    setReminderNotice(null);
    if (await act(() => api.post(`/packages/instances/${instanceId}/send-reminder`), "Action failed.")) setReminderNotice("Recorded. No message was sent.");
  }

  async function saveSettings() {
    if (await act(() => api.patch("/packages/settings", { priceFloorPercent: Number(floorInput) || 0 }), "Action failed.")) setFloorEdit(null);
  }

  if (loading) return <main className={styles.page}><Skeleton rows={6} label="Loading treatment packages" /></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to Treatment Packages.</div></main>;
  if (error) return <main className={styles.page}><ErrorState message={error} onRetry={() => void loadAll()} /></main>;

  // Restricted actions are absent, not disabled (RBAC: Packages create / edit / approve are separate grants).
  const canCreate = can("packages:create");
  const canEdit = can("packages:edit");
  const canApprove = can("packages:approve");
  const visibleTabs = TABS.filter((t) => t.key === "liability" ? canApprove : t.key === "settings" ? canEdit : true);

  const visiblePackages = catalogueFilter === "all" ? packages : packages.filter((p) => p.status === catalogueFilter);

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Treatment Packages</h1>
          <p className={styles.subtitle}>Bundled sessions and combination programmes, sold once, redeemed over time.</p>
        </div>
        {mode === "list" && !selectedInstanceId && tab === "packages" && (canCreate) && (
          <div className={styles.headerActions}>
            <button className={styles.secondaryBtn} onClick={openSellForm}>Sell package</button>
            <button className={styles.wizardBtn} onClick={openNewForm}>New package</button>
          </div>
        )}
        {(mode !== "list" || selectedInstanceId) && (
          <button className={styles.backBtn} onClick={() => { setMode("list"); setSelectedInstanceId(null); setSelectedInstance(null); setRefundPreview(null); setSold(null); }}>← Back</button>
        )}
      </div>

      <ErrorSummary fe={fe} className={styles.bannerWarn} />

      {selectedInstanceId && selectedInstance ? (
        <InstanceDetail
          instance={selectedInstance}
          canEdit={canEdit} canApprove={canApprove} fe={fe}
          onBook={book}
          onRedeem={redeem}
          extendDate={extendDate} setExtendDate={setExtendDate}
          extendReason={extendReason} setExtendReason={setExtendReason}
          onExtend={() => submitExtend(selectedInstance.id)}
          refundPreview={refundPreview}
          onLoadRefundPreview={() => loadRefundPreview(selectedInstance.id)}
          refundReason={refundReason} setRefundReason={setRefundReason}
          onRequestRefund={() => submitRefundRequest(selectedInstance.id)}
        />
      ) : mode === "form" ? (
        <form className={styles.card} onSubmit={submitForm}>
          <h2 className={styles.cardTitle}>{editingId ? "Edit package" : "New package"}</h2>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Package name</label>
              <input className={styles.input} value={formName} {...ffe.attrs("name")} onChange={(e) => { ffe.clearField("name"); setFormName(e.target.value); }} required />
              <FieldError fe={ffe} name="name" className={styles.muted} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Package type</label>
              <select className={styles.select} value={formType} onChange={(e) => setFormType(e.target.value)}>
                <option value="combination">Combination package</option>
                <option value="session">Session package</option>
              </select>
            </div>
          </div>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Speciality</label>
              <input className={styles.input} value={formSpeciality} onChange={(e) => setFormSpeciality(e.target.value)} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Patient-facing description</label>
              <input className={styles.input} value={formDescription} onChange={(e) => setFormDescription(e.target.value)} />
            </div>
          </div>

          <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Included items</h2>
          {formItems.map((item, idx) => (
            <div key={idx} className={styles.row3} style={{ marginBottom: "8px", alignItems: "end" }}>
              <div className={styles.field} style={{ marginBottom: 0 }}>
                <label className={styles.label}>Name</label>
                <input className={styles.input} value={item.name} onChange={(e) => setFormItems((prev) => prev.map((it, i) => (i === idx ? { ...it, name: e.target.value } : it)))} />
              </div>
              <div className={styles.field} style={{ marginBottom: 0 }}>
                <label className={styles.label}>Type</label>
                <select className={styles.select} value={item.itemType} onChange={(e) => setFormItems((prev) => prev.map((it, i) => (i === idx ? { ...it, itemType: e.target.value } : it)))}>
                  <option value="service_session">Service session</option>
                  <option value="procedure">Procedure</option>
                  <option value="consultation">Consultation</option>
                  <option value="take_home_product">Take-home product</option>
                </select>
              </div>
              <div style={{ display: "flex", gap: "8px" }}>
                <div className={styles.field} style={{ marginBottom: 0, flex: 1 }}>
                  <label className={styles.label}>Qty</label>
                  <input className={styles.input} type="number" min="1" value={item.quantity} onChange={(e) => setFormItems((prev) => prev.map((it, i) => (i === idx ? { ...it, quantity: e.target.value } : it)))} />
                </div>
                <div className={styles.field} style={{ marginBottom: 0, flex: 1 }}>
                  <label className={styles.label}>List price</label>
                  <input className={styles.input} type="number" step="0.01" value={item.unitListPrice} onChange={(e) => setFormItems((prev) => prev.map((it, i) => (i === idx ? { ...it, unitListPrice: e.target.value } : it)))} />
                </div>
                <div className={styles.field} style={{ marginBottom: 0, flex: 1 }}>
                  <label className={styles.label}>Tax %</label>
                  <input className={styles.input} type="number" step="0.01" value={item.taxRatePercent} onChange={(e) => setFormItems((prev) => prev.map((it, i) => (i === idx ? { ...it, taxRatePercent: e.target.value } : it)))} />
                </div>
                <button type="button" className={styles.btnSmall} onClick={() => setFormItems((prev) => prev.filter((_, i) => i !== idx))}>Remove</button>
              </div>
            </div>
          ))}
          <button type="button" className={styles.btn} onClick={() => setFormItems((prev) => [...prev, { ...EMPTY_ITEM }])}>+ Add an item</button>

          <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Pricing & savings</h2>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Package price ({money(formListValue)} list value)</label>
              <input className={styles.input} type="number" step="0.01" value={formPrice} {...ffe.attrs("price")} onChange={(e) => { ffe.clearField("price"); setFormPrice(e.target.value); }} />
              <FieldError fe={ffe} name="price" className={styles.muted} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Patient saves</label>
              <input className={styles.input} value={money(formSave)} disabled />
            </div>
          </div>
          <label className={styles.label}><input type="checkbox" checked={formTaxInclusive} onChange={(e) => setFormTaxInclusive(e.target.checked)} /> Price is tax inclusive</label>

          <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Validity & eligibility</h2>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Validity period (days)</label>
              <input className={styles.input} type="number" value={formValidityDays} {...ffe.attrs("validityDays")} onChange={(e) => { ffe.clearField("validityDays"); setFormValidityDays(e.target.value); }} />
              <FieldError fe={ffe} name="validityDays" className={styles.muted} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Validity starts</label>
              <select className={styles.select} value={formValidityStarts} onChange={(e) => setFormValidityStarts(e.target.value)}>
                <option value="purchase_date">Purchase date</option>
                <option value="first_session">First session</option>
              </select>
            </div>
          </div>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Grace period (days)</label>
              <input className={styles.input} type="number" value={formGraceDays} {...ffe.attrs("graceDays")} onChange={(e) => { ffe.clearField("graceDays"); setFormGraceDays(e.target.value); }} />
              <FieldError fe={ffe} name="graceDays" className={styles.muted} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Allowed doctors (blank = all)</label>
              <div style={{ display: "flex", flexWrap: "wrap", gap: "8px" }}>
                {staff.map((s) => (
                  <label key={s.id} className={styles.muted}>
                    <input type="checkbox" checked={formDoctorIds.includes(s.id)}
                      onChange={(e) => setFormDoctorIds((prev) => e.target.checked ? [...prev, s.id] : prev.filter((id) => id !== s.id))} /> {s.name}
                  </label>
                ))}
              </div>
            </div>
          </div>

          <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Payment & refunds</h2>
          <div className={styles.field}>
            <label className={styles.label}>Refund note (shown to staff on the refund screen)</label>
            <textarea className={styles.textarea} value={formRefundNote} onChange={(e) => setFormRefundNote(e.target.value)} />
          </div>
          <p className={styles.muted}>Full payment only in this build — scheduled instalments and pay-per-session are a later phase.</p>

          <ErrorSummary fe={ffe} className={styles.errorState} />
          <div className={styles.actions}>
            <button type="button" className={styles.btn} onClick={() => setMode("list")}>Cancel</button>
            <button type="submit" className={styles.btnPrimary} disabled={saving}>{saving ? "Saving…" : editingId ? "Save changes" : "Save as draft"}</button>
          </div>
        </form>
      ) : mode === "sell" && canCreate ? (
        <div className={styles.card}>
          <h2 className={styles.cardTitle}>Sell a package</h2>
          <ol className={styles.steps} aria-label="Sell package steps">
            {SELL_STEPS.map((label, i) => (
              <li key={label} className={i === sellStep ? styles.stepOn : i < sellStep ? styles.stepDone : styles.step}
                aria-current={i === sellStep ? "step" : undefined}>
                <span className={styles.stepNum}>{i < sellStep ? "✓" : i + 1}</span> {label}
              </li>
            ))}
          </ol>

          {sellStep === 0 && (
            <div className={styles.field}>
              <label className={styles.label} htmlFor="sell-patient">Select the patient</label>
              {sellPatient ? (
                <div className={styles.itemLine}>
                  <span>{sellPatient.name} · {sellPatient.phone}</span>
                  <button type="button" className={styles.btnSmall} onClick={() => setSellPatient(null)}>Change</button>
                </div>
              ) : (
                <>
                  <input id="sell-patient" className={styles.input} placeholder="Search patient by name or phone…" value={sellQuery} onChange={(e) => onSellQueryChange(e.target.value)} />
                  {sellResults.map((p) => (
                    <button type="button" key={p.id} className={`${styles.btn} ${styles.resultBtn}`}
                      onClick={() => setSellPatient(p)}>{p.name} · {p.phone} · {p.mrn}</button>
                  ))}
                </>
              )}
              <p className={styles.muted}>Not registered yet? Register the patient at Arrivals first, then come back.</p>
            </div>
          )}

          {sellStep === 1 && (
            <div className={styles.field}>
              <div className={styles.label}>Select the package</div>
              {packages.filter((p) => p.status === "on_sale").length === 0 && <div className={styles.empty}>No packages are on sale.</div>}
              {packages.filter((p) => p.status === "on_sale").map((p) => (
                <button type="button" key={p.id} aria-pressed={sellPackageId === p.id}
                  className={`${sellPackageId === p.id ? styles.btnPrimary : styles.btn} ${styles.resultBtn}`}
                  onClick={() => setSellPackageId(p.id)}>
                  {p.name} — {money(p.price)} ({p.validityDays}d){p.saveAmount > 0 ? ` · save ${p.savePercent}%` : ""}
                </button>
              ))}
            </div>
          )}

          {sellStep === 2 && sellPackage && sellPatient && (() => {
            const conflict = instances.find((i) => i.patientId === sellPatient.id && i.packageId === sellPackage.id && BLOCKING_STATUSES.includes(i.status));
            const blocked = !!conflict;
            const doctorNames = sellPackage.eligibleDoctorIds.map((id) => staff.find((s) => s.id === id)?.name).filter(Boolean);
            return (
              <>
                <div className={styles.flagRow}>
                  {blocked
                    ? <StatusPill tone="danger" label="Not eligible" />
                    : <StatusPill tone="ok" label="Eligibility confirmed" />}
                  <span className={styles.muted}>
                    {conflict
                      ? `${sellPatient.name} already has a ${sellPackage.name} (${conflict.status}). Another copy can be sold only after it is completed or refunded.`
                      : `No conflicting package for ${sellPatient.name}.`}
                  </span>
                </div>
                {sellPackage.doctorLeaveWarning && <div className={styles.bannerWarn}>{sellPackage.doctorLeaveWarning}</div>}
                <h3 className={styles.cardTitle}>Eligibility rules</h3>
                <div className={styles.itemLine}><span>Doctors</span><span>{doctorNames.length > 0 ? doctorNames.join(", ") : "All doctors"}</span></div>
                <div className={styles.itemLine}><span>Validity starts</span><span>{sellPackage.validityStarts === "first_session" ? "First session" : "Purchase date"}</span></div>
                <div className={styles.itemLine}><span>Validity</span><span>{sellPackage.validityDays} days + {sellPackage.graceDays} days grace</span></div>
              </>
            );
          })()}

          {sellStep === 3 && sellPackage && (
            <>
              <div className={styles.statGrid}>
                <div className={styles.statCard}><div className={styles.statLabel}>Separate value</div><div className={styles.statValue}>{money(sellPackage.listValue)}</div></div>
                <div className={styles.statCard}><div className={styles.statLabel}>Package price</div><div className={styles.statValue}>{money(sellPackage.price)}</div><div className={styles.muted}>{sellPackage.taxInclusive ? "incl. tax" : "excl. tax"}</div></div>
                <div className={styles.statCard}><div className={styles.statLabel}>Patient saves</div><div className={styles.statValue}>{money(sellPackage.saveAmount)}</div><div className={styles.muted}>{sellPackage.savePercent}%</div></div>
              </div>
              <h3 className={styles.cardTitle}>Included items</h3>
              {sellPackage.items.map((i) => (
                <div key={i.id} className={styles.itemLine}><span>{i.name} × {i.quantity}</span><span>{money(i.listValue)}</span></div>
              ))}
              {sellPatient && <p className={styles.muted}>Selling to {sellPatient.name} · {sellPatient.phone}</p>}
            </>
          )}

          {sellStep === 4 && sellPackage && (
            <>
              <div className={styles.field}>
                <div className={styles.label}>Payment method</div>
                <div className={styles.methodRow}>
                  {PAYMENT_METHODS.map((v) => (
                    <button type="button" key={v} aria-pressed={sellMethod === v} className={sellMethod === v ? styles.btnPrimary : styles.btn} onClick={() => setSellMethod(v)}>
                      {sellMethod === v ? "✓ " : ""}{paymentMethodLabel(v, getIdentity()?.tenantRegion)}
                    </button>
                  ))}
                </div>
                <p className={styles.muted}>Full payment only in this build — scheduled instalments are a later phase.</p>
              </div>
              <div className={styles.itemLine}>
                <span>Due now</span>
                <strong>{money(sellPackage.price)}{sellPackage.taxInclusive ? " incl. tax" : " + tax at each item's rate"}</strong>
              </div>
              <div className={styles.flagRow}>
                {sellMethod ? <StatusPill tone="ok" label="Ready to collect" /> : <StatusPill tone="warn" label="Choose a method" />}
              </div>
            </>
          )}

          {sellStep === 5 && sold && (
            <>
              <div className={styles.flagRow}>
                <StatusPill tone="ok" label="Sold — invoice issued" />
                <span className={styles.muted}>{sold.packageName} · {sold.patientName}</span>
              </div>
              {soldInvoice ? (
                <div className={styles.statGrid}>
                  <div className={styles.statCard}><div className={styles.statLabel}>Net</div><div className={styles.statValue}>{formatMoney(soldInvoice.subtotal, soldInvoice.currency)}</div></div>
                  <div className={styles.statCard}><div className={styles.statLabel}>Tax</div><div className={styles.statValue}>{formatMoney(soldInvoice.tax, soldInvoice.currency)}</div></div>
                  <div className={styles.statCard}><div className={styles.statLabel}>Total</div><div className={styles.statValue}>{formatMoney(soldInvoice.total, soldInvoice.currency)}</div><div className={styles.muted}>paid {formatMoney(soldInvoice.paid, soldInvoice.currency)}</div></div>
                  <div className={styles.statCard}><div className={styles.statLabel}>Invoice</div><div className={styles.statValue}>{soldInvoice.invoiceNumber}</div></div>
                </div>
              ) : (
                <div className={styles.statGrid}>
                  <div className={styles.statCard}><div className={styles.statLabel}>Package price</div><div className={styles.statValue}>{money(sold.soldPrice)}</div></div>
                  <div className={styles.statCard}><div className={styles.statLabel}>Tax</div><div className={styles.statValue}>{money(sold.soldTax)}</div></div>
                  <div className={styles.statCard}><div className={styles.statLabel}>Invoice</div><div className={styles.statValue}>{sold.invoiceNumber}</div></div>
                </div>
              )}
              <h3 className={styles.cardTitle}>{soldInvoice ? "Invoice lines" : "Tax allocated by list-price ratio"}</h3>
              {soldInvoice ? (
                // One invoice line per package item: the server's ex-tax line total and its tax at the item's rate.
                soldInvoice.lineItems.map((l, n) => (
                  <div key={n} className={styles.itemLine}>
                    <span>
                      {l.chargeName} × {l.quantity}
                      <span className={styles.muted}> · net {formatMoney(l.lineTotal, soldInvoice.currency)} · {l.taxRatePercent > 0 ? `tax ${l.taxRatePercent}%` : "exempt"}</span>
                    </span>
                    <span>{formatMoney(Math.round(l.lineTotal * l.taxRatePercent) / 100, soldInvoice.currency)} tax</span>
                  </div>
                ))
              ) : (() => {
                const listTotal = sold.items.reduce((sum, i) => sum + i.unitListPrice * i.quantityTotal, 0);
                return sold.items.map((i) => (
                  <div key={i.id} className={styles.itemLine}>
                    <span>
                      {i.name}
                      <span className={styles.muted}> · {listTotal > 0 ? Math.round((i.unitListPrice * i.quantityTotal / listTotal) * 100) : 0}% of list · {i.taxRatePercent > 0 ? `tax ${i.taxRatePercent}%` : "exempt"}</span>
                    </span>
                    <span>{money(i.allocatedPrice)}</span>
                  </div>
                ));
              })()}
              <h3 className={styles.cardTitle}>Digital package card</h3>
              <p className={styles.muted}>Sending the card on WhatsApp isn&apos;t available yet. Open the active package to see its sessions and ledger.</p>
            </>
          )}

          <ErrorSummary fe={sfe} className={styles.errorState} />
          <div className={styles.actions}>
            {sellStep < 5 && <button type="button" className={styles.btn} onClick={() => setMode("list")}>Cancel</button>}
            {sellStep > 0 && sellStep < 5 && <button type="button" className={styles.btn} onClick={() => { sfe.clear(); setSellStep(sellStep - 1); }}>Back</button>}
            {sellStep < 4 && (
              <button type="button" className={styles.btnPrimary}
                disabled={(sellStep === 0 && !sellPatient) || (sellStep >= 1 && !sellPackageId)
                  || (sellStep === 2 && instances.some((i) => i.patientId === sellPatient?.id && i.packageId === sellPackageId && BLOCKING_STATUSES.includes(i.status)))}
                onClick={() => setSellStep(sellStep + 1)}>Continue</button>
            )}
            {sellStep === 4 && (
              <button type="button" className={styles.btnPrimary} disabled={selling || !sellMethod} onClick={() => void submitSale()}>
                {selling ? "Selling…" : sellMethod ? "Sell package" : "Choose a payment method"}
              </button>
            )}
            {sellStep === 5 && sold && (
              <button type="button" className={styles.btnPrimary} onClick={() => { const id = sold.id; setMode("list"); setSold(null); void openInstance(id); }}>Open the active package</button>
            )}
          </div>
        </div>
      ) : (
        <>
          <div className={styles.tabs}>
            {visibleTabs.map((t) => (
              <button key={t.key} className={tab === t.key ? styles.tabActive : styles.tab} onClick={() => setTab(t.key)}>{t.label}</button>
            ))}
          </div>

          {tab === "packages" && (
            <>
              <div className={styles.tabs}>
                {(["all", "on_sale", "draft", "inactive"] as const).map((f) => (
                  <button key={f} className={catalogueFilter === f ? styles.tabActive : styles.tab} onClick={() => setCatalogueFilter(f)}>
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
                          <button className={styles.btnSmall} onClick={() => openEditForm(p)}>Edit</button>
                          {p.status === "on_sale"
                            ? <button className={styles.btnSmall} onClick={() => deactivate(p.id)}>Deactivate</button>
                            : <button className={styles.btnSmall} onClick={() => activate(p.id)}>{p.belowFloor ? "Fix the price first" : "Activate"}</button>}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </>
          )}

          {tab === "active" && (
            <InstanceTable instances={instances.filter((i) => i.status !== "cancelled")} onOpen={openInstance} showInvoice={false} />
          )}
          {tab === "sales" && (
            <InstanceTable instances={instances} onOpen={openInstance} showInvoice />
          )}
          {tab === "sessions" && (
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
                          <div className={styles.progressBar}><div className={styles.progressFill} style={{ width: `${(item.quantityConsumed / item.quantityTotal) * 100}%` }} /></div>
                          <span className={styles.muted}>{item.quantityConsumed}/{item.quantityTotal}</span>
                        </div>
                      </td>
                      <td>{money(item.allocatedPrice)}</td>
                      <td>
                        {canEdit && <button className={styles.btnSmall} onClick={() => book(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Book</button>}{" "}
                        {canEdit && <button className={styles.btnSmall} onClick={() => redeem(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Redeem</button>}{" "}
                        <button className={styles.btnSmall} onClick={() => openInstance(inst.id)}>Ledger</button>
                      </td>
                    </tr>
                  )))}
                </tbody>
              </table>
              {instances.every((i) => i.items.length === 0) && <div className={styles.empty}>No sold packages yet.</div>}
            </div>
          )}

          {tab === "expiring" && (
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
                      <td>{canEdit && <button className={styles.btnSmall} onClick={() => sendReminder(e.instanceId)} disabled={e.reminderSentForTier}>Mark reminded</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {expiring.length === 0 && <div className={styles.empty}>Nothing expiring within 30 days.</div>}
            </div>
          )}

          {tab === "refunds" && deciding && canApprove && (
            <section className={styles.card} aria-label="Review refund">
              <h2 className={styles.cardTitle}>Refund · {deciding.refund.patientName} · {deciding.refund.packageName}</h2>
              {deciding.changed && <div className={styles.bannerWarn} role="alert">The amount changed since the request because sessions were used. Check the new amount before approving.</div>}
              <div className={styles.itemLine}><span>Amount at request</span><span data-testid="refund-requested">{money(deciding.refund.refundAmount)}</span></div>
              <div className={styles.itemLine}><strong>Amount now</strong><strong data-testid="refund-current">{money(deciding.current)}</strong></div>
              {deciding.owed > 0 && <div className={styles.bannerWarn}>Used sessions exceed the amount paid by {money(deciding.owed)}. No refund; the difference must be collected.</div>}
              <div className={styles.cardActions}>
                <button className={styles.decisionBtn} onClick={() => void approveRefund()}>Approve {money(deciding.current)}</button>
                <button className={styles.decisionBtn} onClick={() => setDeciding(null)}>Cancel</button>
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="refund-reject-reason">Reason to reject</label>
                <input id="refund-reject-reason" className={styles.input} value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} />
              </div>
              <div className={styles.cardActions}>
                <button className={styles.decisionBtn} onClick={() => void rejectRefund()}>Reject</button>
              </div>
            </section>
          )}
          {tab === "refunds" && (
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
                      <td>{canApprove && r.status === "pending" && <button className={styles.btnSmall} onClick={() => void openDecision(r)}>Review</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {refunds.length === 0 && <div className={styles.empty}>No refund requests.</div>}
            </div>
          )}

          {tab === "liability" && canApprove && liability && (
            <div className={styles.statGrid}>
              <div className={styles.statCard}><div className={styles.statLabel}>Active patient packages</div><div className={styles.statValue}>{liability.activePatientPackages}</div></div>
              <div className={styles.statCard}><div className={styles.statLabel}>Sessions owed</div><div className={styles.statValue}>{liability.sessionsOwed}</div></div>
              <div className={styles.statCard}><div className={styles.statLabel}>Remaining list value</div><div className={styles.statValue}>{money(liability.remainingListValue)}</div></div>
              <div className={styles.statCard}><div className={styles.statLabel}>Remaining allocated value</div><div className={styles.statValue}>{money(liability.remainingAllocatedValue)}</div></div>
              <div className={styles.statCard}><div className={styles.statLabel}>In grace period</div><div className={styles.statValue}>{liability.inGracePeriod}</div></div>
              <div className={styles.statCard}><div className={styles.statLabel}>Expiring in 30 days</div><div className={styles.statValue}>{liability.expiringIn30Days}</div></div>
              <div className={styles.statCard}><div className={styles.statLabel}>Potential expiry loss</div><div className={styles.statValue}>{money(liability.potentialExpiryLoss)}</div></div>
              <div className={styles.statCard}><div className={styles.statLabel}>Refunds awaiting approval</div><div className={styles.statValue}>{liability.refundsAwaitingApproval}</div></div>
            </div>
          )}

          {tab === "settings" && canEdit && (
            <div className={styles.card}>
              <h2 className={styles.cardTitle}>Pricing rules</h2>
              <div className={styles.field}>
                <label className={styles.label}>Price floor (% of list value — never silently overridable)</label>
                <input className={styles.input} type="number" step="0.1" value={floorInput} {...fe.attrs("priceFloorPercent")} onChange={(e) => { fe.clearField("priceFloorPercent"); setFloorEdit(e.target.value); }} style={{ maxWidth: "160px" }} />
              </div>
              <div className={styles.actions} style={{ justifyContent: "flex-start" }}>
                <button className={styles.btnPrimary} onClick={saveSettings}>Save</button>
              </div>
              <p className={styles.muted} style={{ marginTop: "16px" }}>
                Scheduled instalments, pay-per-session and transfer &amp; gifting are a later phase and not built yet.
                Membership and Family Plan package types are further out still.
              </p>
            </div>
          )}
        </>
      )}
    </main>
  );
}

function InstanceTable({ instances, onOpen, showInvoice }: { instances: InstanceT[]; onOpen: (id: string) => void; showInvoice: boolean }) {
  const styles_ = styles;
  if (instances.length === 0) return <div className={styles_.empty}>Nothing here yet.</div>;
  return (
    <div className={styles_.tableWrap}>
      <table className={styles_.table}>
        <thead>
          <tr><th scope="col">Patient</th><th scope="col">Package</th><th scope="col">Progress</th>{showInvoice && <th scope="col">Invoice</th>}<th scope="col">Expires</th><th scope="col">Status</th><th scope="col"></th></tr>
        </thead>
        <tbody>
          {instances.map((inst) => {
            const consumed = inst.items.reduce((s, i) => s + i.quantityConsumed, 0);
            const total = inst.items.reduce((s, i) => s + i.quantityTotal, 0);
            return (
              <tr key={inst.id}>
                <td>{inst.patientName}</td>
                <td>{inst.packageName}</td>
                <td>
                  <div className={styles_.progressWrap}>
                    <div className={styles_.progressBar}><div className={styles_.progressFill} style={{ width: total ? `${(consumed / total) * 100}%` : "0%" }} /></div>
                    <span className={styles_.muted}>{consumed}/{total}</span>
                  </div>
                </td>
                {showInvoice && <td>{inst.invoiceNumber}</td>}
                <td>{inst.validityEnd ?? "not started"}</td>
                <td><span className={`${styles_.pill} ${STATUS_PILL[inst.status] ?? styles_.pillInactive}`}>{inst.status}</span></td>
                <td><button className={styles_.btnSmall} onClick={() => onOpen(inst.id)}>Open</button></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
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

      <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Items</h2>
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

      <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Package ledger</h2>
      {(instance.events ?? []).map((e, idx) => (
        <div key={idx} className={styles.ledgerItem}>
          <div>
            <div>{eventLabel(e.eventType, e.note)}{e.delta != null && e.delta !== 0 ? ` (${e.delta})` : ""}</div>
            <div className={styles.ledgerNote}>{e.note} · {e.actorName}</div>
          </div>
          <div className={styles.muted}>{new Date(e.createdAt).toLocaleString()}</div>
        </div>
      ))}

      <div className={styles.row} style={{ marginTop: "24px" }}>
        {canApprove && <div className={styles.card} style={{ margin: 0 }}>
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

        {canEdit && <div className={styles.card} style={{ margin: 0 }}>
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
              <div className={styles.field} style={{ marginTop: "12px" }}>
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
