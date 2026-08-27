"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./packages.module.css";

// Matches GET/POST/PATCH /v1/packages/** (PackageController) — E14 Treatment Packages.
type PackageItem = { id: string; itemType: string; name: string; quantity: number; unitListPrice: number; taxRatePercent: number; listValue: number };
type PackageT = {
  id: string; name: string; packageType: string; speciality: string | null; description: string | null; status: string;
  price: number; taxInclusive: boolean; validityDays: number; validityStarts: string; graceDays: number; refundNote: string | null;
  listValue: number; saveAmount: number; savePercent: number; priceFloor: number; belowFloor: boolean;
  eligibleDoctorIds: string[]; items: PackageItem[]; doctorLeaveWarning: string | null;
};
type InstanceItem = { id: string; itemType: string; name: string; quantityTotal: number; quantityConsumed: number; unitListPrice: number; allocatedPrice: number; taxRatePercent: number };
type InstanceEvent = { eventType: string; note: string | null; delta: number | null; actorName: string; createdAt: string };
type InstanceT = {
  id: string; packageId: string; packageName: string; patientId: string; patientName: string;
  invoiceId: string; invoiceNumber: string; soldPrice: number; soldTax: number;
  validityStart: string | null; validityEnd: string | null; graceDays: number; status: string;
  items: InstanceItem[]; events: InstanceEvent[] | null;
};
type RefundT = {
  id: string; instanceId: string; patientName: string; packageName: string; reason: string;
  usedListValue: number; refundAmount: number; amountOwed: number; status: string; creditNoteNumber: string | null; createdAt: string;
};
type ExpiringT = {
  instanceId: string; patientName: string; packageName: string; quantityConsumed: number; quantityTotal: number;
  expiresOn: string; valueLeft: number; alertTier: number; reminderSentForTier: boolean;
};
type LiabilityT = {
  activePatientPackages: number; sessionsOwed: number; remainingListValue: number; remainingAllocatedValue: number;
  inGracePeriod: number; expiringIn30Days: number; potentialExpiryLoss: number; refundsAwaitingApproval: number;
};
type SettingsT = { priceFloorPercent: number };
type StaffOption = { id: string; name: string };
type PatientOption = { id: string; name: string; phone: string; mrn: string };
type Problem = { title: string; detail: string };
type ItemDraft = { itemType: string; name: string; quantity: string; unitListPrice: string; taxRatePercent: string };
type Tab = "packages" | "active" | "sales" | "sessions" | "expiring" | "refunds" | "liability" | "settings";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
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
  pending: styles.pillWarn, approved: styles.pillDone,
};
const EMPTY_ITEM: ItemDraft = { itemType: "service_session", name: "", quantity: "1", unitListPrice: "0", taxRatePercent: "0" };

function money(n: number): string {
  return n.toFixed(2);
}

export default function PackagesPage() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("packages");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [packages, setPackages] = useState<PackageT[]>([]);
  const [instances, setInstances] = useState<InstanceT[]>([]);
  const [refunds, setRefunds] = useState<RefundT[]>([]);
  const [expiring, setExpiring] = useState<ExpiringT[]>([]);
  const [liability, setLiability] = useState<LiabilityT | null>(null);
  const [floorInput, setFloorInput] = useState("");
  const [staff, setStaff] = useState<StaffOption[]>([]);

  const [catalogueFilter, setCatalogueFilter] = useState<"all" | "on_sale" | "draft" | "inactive">("all");
  const [mode, setMode] = useState<"list" | "form" | "sell">("list");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [selectedInstanceId, setSelectedInstanceId] = useState<string | null>(null);
  const [selectedInstance, setSelectedInstance] = useState<InstanceT | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [pkgRes, instRes, refundRes, expRes, liabRes, settingsRes, staffRes] = await Promise.all([
        authedFetch("/packages"), authedFetch("/packages/instances"), authedFetch("/packages/refunds"),
        authedFetch("/packages/expiring-soon"), authedFetch("/packages/liability"), authedFetch("/packages/settings"),
        authedFetch("/staff/roster"),
      ]);
      if (!pkgRes) return;
      if (pkgRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!pkgRes.ok || !instRes?.ok) {
        setError("Couldn't load treatment packages. Try again.");
        return;
      }
      setPackages(await pkgRes.json());
      setInstances(await instRes.json());
      if (refundRes?.ok) setRefunds(await refundRes.json());
      if (expRes?.ok) setExpiring(await expRes.json());
      if (liabRes?.ok) setLiability(await liabRes.json());
      if (settingsRes?.ok) {
        const s: SettingsT = await settingsRes.json();
        setFloorInput(String(s.priceFloorPercent));
      }
      if (staffRes?.ok) setStaff(await staffRes.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(loadAll);
  }, [loadAll]);

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
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function resetForm() {
    setFormName(""); setFormType("combination"); setFormSpeciality(""); setFormDescription("");
    setFormPrice("0"); setFormTaxInclusive(false); setFormValidityDays("90"); setFormValidityStarts("purchase_date");
    setFormGraceDays("7"); setFormRefundNote(""); setFormDoctorIds([]); setFormItems([{ ...EMPTY_ITEM }]); setFormError(null);
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
    setFormError(null);
    setEditingId(p.id);
    setMode("form");
  }

  const formListValue = formItems.reduce((sum, i) => sum + (Number(i.unitListPrice) || 0) * (Number(i.quantity) || 0), 0);
  const formSave = Math.max(0, formListValue - (Number(formPrice) || 0));

  async function submitForm(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    const items = formItems.filter((i) => i.name.trim().length > 0);
    if (items.length === 0) {
      setFormError("Add at least one included item.");
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
      const res = await authedFetch(editingId ? `/packages/${editingId}` : "/packages", {
        method: editingId ? "PATCH" : "POST",
        body: JSON.stringify(body),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't save the package." }));
        setFormError(p.detail || "Couldn't save the package.");
        return;
      }
      setMode("list");
      await loadAll();
    } finally {
      setSaving(false);
    }
  }

  async function activate(id: string) {
    setActionError(null);
    const res = await authedFetch(`/packages/${id}/activate`, { method: "POST" });
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't activate." }));
      setActionError(p.detail || "Couldn't activate.");
      return;
    }
    await loadAll();
  }

  async function deactivate(id: string) {
    await authedFetch(`/packages/${id}/deactivate`, { method: "POST" });
    await loadAll();
  }

  // ── sell flow ────────────────────────────────────────────────────────────
  const [sellQuery, setSellQuery] = useState("");
  const [sellResults, setSellResults] = useState<PatientOption[]>([]);
  const [sellPatient, setSellPatient] = useState<PatientOption | null>(null);
  const [sellPackageId, setSellPackageId] = useState("");
  const [sellMethod, setSellMethod] = useState("upi");
  const [sellError, setSellError] = useState<string | null>(null);
  const [selling, setSelling] = useState(false);

  async function onSellQueryChange(value: string) {
    setSellQuery(value);
    setSellPatient(null);
    if (value.trim().length < 2) {
      setSellResults([]);
      return;
    }
    const res = await authedFetch(`/patients?q=${encodeURIComponent(value.trim())}`);
    if (res?.ok) setSellResults((await res.json()).data);
  }

  function openSellForm() {
    setSellQuery(""); setSellResults([]); setSellPatient(null); setSellPackageId(""); setSellMethod("upi"); setSellError(null);
    setMode("sell");
  }

  const sellPackage = packages.find((p) => p.id === sellPackageId) ?? null;

  async function submitSale(e: React.FormEvent) {
    e.preventDefault();
    setSellError(null);
    if (!sellPatient || !sellPackageId) {
      setSellError("Pick a patient and a package.");
      return;
    }
    setSelling(true);
    try {
      const res = await authedFetch("/packages/sell", {
        method: "POST",
        body: JSON.stringify({ patientId: sellPatient.id, packageId: sellPackageId, paymentMethod: sellMethod }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't sell the package." }));
        setSellError(p.detail || "Couldn't sell the package.");
        return;
      }
      const instance: InstanceT = await res.json();
      setMode("list");
      await loadAll();
      await openInstance(instance.id);
    } finally {
      setSelling(false);
    }
  }

  // ── instance actions ─────────────────────────────────────────────────────
  // The instances list (loadAll) never carries ledger events — only the single-instance detail
  // endpoint does — so the detail view keeps its own freshly-fetched copy instead of looking itself
  // up in that list.
  async function loadSelectedInstance(id: string) {
    const res = await authedFetch(`/packages/instances/${id}`);
    if (res?.ok) setSelectedInstance(await res.json());
  }

  async function openInstance(id: string) {
    setActionError(null);
    setSelectedInstanceId(id);
    await loadSelectedInstance(id);
  }

  async function book(itemId: string) {
    setActionError(null);
    const res = await authedFetch(`/packages/instances/items/${itemId}/book`, { method: "POST" });
    await afterInstanceAction(res);
  }

  async function redeem(itemId: string) {
    setActionError(null);
    const res = await authedFetch(`/packages/instances/items/${itemId}/redeem`, { method: "POST" });
    await afterInstanceAction(res);
  }

  async function afterInstanceAction(res: Response | null) {
    if (res && !res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Action failed." }));
      setActionError(p.detail || "Action failed.");
    }
    await loadAll();
    if (selectedInstanceId) await loadSelectedInstance(selectedInstanceId);
  }

  const [extendDate, setExtendDate] = useState("");
  const [extendReason, setExtendReason] = useState("");
  async function submitExtend(instanceId: string) {
    if (!extendDate || !extendReason.trim()) {
      setActionError("Pick a new date and give a reason.");
      return;
    }
    const res = await authedFetch(`/packages/instances/${instanceId}/extend`, {
      method: "POST", body: JSON.stringify({ newValidityEnd: extendDate, reason: extendReason }),
    });
    setExtendDate(""); setExtendReason("");
    await afterInstanceAction(res);
  }

  const [refundPreview, setRefundPreview] = useState<{ paid: number; usedListValue: number; refundAmount: number; amountOwed: number } | null>(null);
  const [refundReason, setRefundReason] = useState("");
  async function loadRefundPreview(instanceId: string) {
    const res = await authedFetch(`/packages/instances/${instanceId}/refund-preview`);
    if (res?.ok) setRefundPreview(await res.json());
  }
  async function submitRefundRequest(instanceId: string) {
    if (!refundReason.trim()) {
      setActionError("Give a refund reason.");
      return;
    }
    const res = await authedFetch(`/packages/instances/${instanceId}/refund`, {
      method: "POST", body: JSON.stringify({ reason: refundReason }),
    });
    setRefundReason(""); setRefundPreview(null);
    await afterInstanceAction(res);
    if (res?.ok) {
      setSelectedInstanceId(null);
      setSelectedInstance(null);
      setTab("refunds");
    }
  }

  async function approveRefund(id: string) {
    setActionError(null);
    const res = await authedFetch(`/packages/refunds/${id}/approve`, { method: "POST" });
    await afterInstanceAction(res);
  }

  async function sendReminder(instanceId: string) {
    const res = await authedFetch(`/packages/instances/${instanceId}/send-reminder`, { method: "POST" });
    await afterInstanceAction(res);
  }

  async function saveSettings() {
    const res = await authedFetch("/packages/settings", { method: "PATCH", body: JSON.stringify({ priceFloorPercent: Number(floorInput) || 0 }) });
    await afterInstanceAction(res);
  }

  if (loading) return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to Treatment Packages.</div></main>;
  if (error) return <main className={styles.page}><div className={styles.errorState}>{error}</div></main>;

  const visiblePackages = catalogueFilter === "all" ? packages : packages.filter((p) => p.status === catalogueFilter);

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Treatment Packages</h1>
          <p className={styles.subtitle}>Bundled sessions and combination programmes, sold once, redeemed over time.</p>
        </div>
        {mode === "list" && !selectedInstanceId && tab === "packages" && (
          <div className={styles.headerActions}>
            <button className={styles.secondaryBtn} onClick={openSellForm}>Sell package</button>
            <button className={styles.wizardBtn} onClick={openNewForm}>New package</button>
          </div>
        )}
        {(mode !== "list" || selectedInstanceId) && (
          <button className={styles.backBtn} onClick={() => { setMode("list"); setSelectedInstanceId(null); setSelectedInstance(null); setRefundPreview(null); }}>← Back</button>
        )}
      </div>

      {actionError && <div className={styles.bannerWarn}>{actionError}</div>}

      {selectedInstanceId && selectedInstance ? (
        <InstanceDetail
          instance={selectedInstance}
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
              <input className={styles.input} value={formName} onChange={(e) => setFormName(e.target.value)} required />
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
              <input className={styles.input} type="number" step="0.01" value={formPrice} onChange={(e) => setFormPrice(e.target.value)} />
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
              <input className={styles.input} type="number" value={formValidityDays} onChange={(e) => setFormValidityDays(e.target.value)} />
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
              <input className={styles.input} type="number" value={formGraceDays} onChange={(e) => setFormGraceDays(e.target.value)} />
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

          {formError && <div className={styles.errorState}>{formError}</div>}
          <div className={styles.actions}>
            <button type="button" className={styles.btn} onClick={() => setMode("list")}>Cancel</button>
            <button type="submit" className={styles.btnPrimary} disabled={saving}>{saving ? "Saving…" : editingId ? "Save changes" : "Save as draft"}</button>
          </div>
        </form>
      ) : mode === "sell" ? (
        <form className={styles.card} onSubmit={submitSale}>
          <h2 className={styles.cardTitle}>Sell a package</h2>
          <div className={styles.field}>
            <label className={styles.label}>Patient</label>
            {sellPatient ? (
              <div className={styles.itemLine}>
                <span>{sellPatient.name} · {sellPatient.phone}</span>
                <button type="button" className={styles.btnSmall} onClick={() => setSellPatient(null)}>Change</button>
              </div>
            ) : (
              <>
                <input className={styles.input} placeholder="Search patient by name or phone…" value={sellQuery} onChange={(e) => onSellQueryChange(e.target.value)} />
                {sellResults.map((p) => (
                  <button type="button" key={p.id} className={styles.btn} style={{ display: "block", width: "100%", textAlign: "left", marginTop: "4px" }}
                    onClick={() => { setSellPatient(p); setSellResults([]); }}>{p.name} · {p.phone} · {p.mrn}</button>
                ))}
              </>
            )}
          </div>

          <div className={styles.field}>
            <label className={styles.label}>Package</label>
            <select className={styles.select} value={sellPackageId} onChange={(e) => setSellPackageId(e.target.value)}>
              <option value="">Select an on-sale package…</option>
              {packages.filter((p) => p.status === "on_sale").map((p) => (
                <option key={p.id} value={p.id}>{p.name} — {money(p.price)} ({p.validityDays}d)</option>
              ))}
            </select>
          </div>

          {sellPackage && (
            <>
              {sellPackage.doctorLeaveWarning && <div className={styles.bannerWarn}>{sellPackage.doctorLeaveWarning}</div>}
              <div className={styles.card} style={{ margin: "0 0 16px" }}>
                <div className={styles.itemLine}><span>List value</span><span>{money(sellPackage.listValue)}</span></div>
                <div className={styles.itemLine}><span>Package price</span><span>{money(sellPackage.price)}</span></div>
                <div className={styles.itemLine}><span>Patient saves</span><span>{money(sellPackage.saveAmount)} ({sellPackage.savePercent}%)</span></div>
                {sellPackage.items.map((i) => (
                  <div key={i.id} className={styles.itemLine}><span>{i.name} × {i.quantity}</span><span>{money(i.listValue)}</span></div>
                ))}
              </div>
            </>
          )}

          <div className={styles.field}>
            <label className={styles.label}>Payment method</label>
            <div style={{ display: "flex", gap: "8px" }}>
              {[["cash", "Cash"], ["card", "Card"], ["upi", "UPI"], ["other", "Bank transfer"]].map(([v, label]) => (
                <button type="button" key={v} className={sellMethod === v ? styles.btnPrimary : styles.btn} onClick={() => setSellMethod(v)}>{label}</button>
              ))}
            </div>
          </div>

          {sellError && <div className={styles.errorState}>{sellError}</div>}
          <div className={styles.actions}>
            <button type="button" className={styles.btn} onClick={() => setMode("list")}>Cancel</button>
            <button type="submit" className={styles.btnPrimary} disabled={selling || !sellPatient || !sellPackageId}>{selling ? "Selling…" : "Sell package"}</button>
          </div>
        </form>
      ) : (
        <>
          <div className={styles.tabs}>
            {TABS.map((t) => (
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
                      <div className={styles.cardActions}>
                        <button className={styles.btnSmall} onClick={() => openEditForm(p)}>Edit</button>
                        {p.status === "on_sale"
                          ? <button className={styles.btnSmall} onClick={() => deactivate(p.id)}>Deactivate</button>
                          : <button className={styles.btnSmall} onClick={() => activate(p.id)}>{p.belowFloor ? "Fix the price first" : "Activate"}</button>}
                      </div>
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
                <thead><tr><th>Patient</th><th>Item</th><th>Package</th><th>Entitlement</th><th>Value</th><th></th></tr></thead>
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
                        <button className={styles.btnSmall} onClick={() => book(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Book</button>{" "}
                        <button className={styles.btnSmall} onClick={() => redeem(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Redeem</button>{" "}
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
              <table className={styles.table}>
                <thead><tr><th>Patient</th><th>Package</th><th>Remaining</th><th>Expires</th><th>Value left</th><th>Contact</th><th></th></tr></thead>
                <tbody>
                  {expiring.map((e) => (
                    <tr key={e.instanceId}>
                      <td>{e.patientName}</td>
                      <td>{e.packageName}</td>
                      <td>{e.quantityTotal - e.quantityConsumed}/{e.quantityTotal}</td>
                      <td>{e.expiresOn}</td>
                      <td>{money(e.valueLeft)}</td>
                      <td><span className={`${styles.pill} ${e.reminderSentForTier ? styles.pillDone : styles.pillWarn}`}>{e.reminderSentForTier ? "Sent" : `${e.alertTier}-day reminder due`}</span></td>
                      <td><button className={styles.btnSmall} onClick={() => sendReminder(e.instanceId)} disabled={e.reminderSentForTier}>Send reminder</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {expiring.length === 0 && <div className={styles.empty}>Nothing expiring within 30 days.</div>}
            </div>
          )}

          {tab === "refunds" && (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Patient</th><th>Package</th><th>Reason</th><th>Refund</th><th>Owed</th><th>Status</th><th></th></tr></thead>
                <tbody>
                  {refunds.map((r) => (
                    <tr key={r.id}>
                      <td>{r.patientName}</td>
                      <td>{r.packageName}</td>
                      <td>{r.reason}</td>
                      <td>{money(r.refundAmount)}</td>
                      <td>{money(r.amountOwed)}</td>
                      <td><span className={`${styles.pill} ${STATUS_PILL[r.status]}`}>{r.status}{r.creditNoteNumber ? ` · ${r.creditNoteNumber}` : ""}</span></td>
                      <td>{r.status === "pending" && <button className={styles.btnSmall} onClick={() => approveRefund(r.id)}>Approve</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {refunds.length === 0 && <div className={styles.empty}>No refund requests.</div>}
            </div>
          )}

          {tab === "liability" && liability && (
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

          {tab === "settings" && (
            <div className={styles.card}>
              <h2 className={styles.cardTitle}>Pricing rules</h2>
              <div className={styles.field}>
                <label className={styles.label}>Price floor (% of list value — never silently overridable)</label>
                <input className={styles.input} type="number" step="0.1" value={floorInput} onChange={(e) => setFloorInput(e.target.value)} style={{ maxWidth: "160px" }} />
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
          <tr><th>Patient</th><th>Package</th><th>Progress</th>{showInvoice && <th>Invoice</th>}<th>Expires</th><th>Status</th><th></th></tr>
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
  instance, onBook, onRedeem, extendDate, setExtendDate, extendReason, setExtendReason, onExtend,
  refundPreview, onLoadRefundPreview, refundReason, setRefundReason, onRequestRefund,
}: {
  instance: InstanceT;
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
          <thead><tr><th>Item</th><th>Progress</th><th>Allocated</th><th></th></tr></thead>
          <tbody>
            {instance.items.map((item) => (
              <tr key={item.id}>
                <td>{item.name}</td>
                <td>{item.quantityConsumed}/{item.quantityTotal}</td>
                <td>{money(item.allocatedPrice)}</td>
                <td>
                  <button className={styles.btnSmall} onClick={() => onBook(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Book next session</button>{" "}
                  <button className={styles.btnSmall} onClick={() => onRedeem(item.id)} disabled={item.quantityConsumed >= item.quantityTotal}>Redeem after completed service</button>
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
            <div>{e.eventType.replace(/_/g, " ")}{e.delta != null && e.delta !== 0 ? ` (${e.delta})` : ""}</div>
            <div className={styles.ledgerNote}>{e.note} · {e.actorName}</div>
          </div>
          <div className={styles.muted}>{new Date(e.createdAt).toLocaleString()}</div>
        </div>
      ))}

      <div className={styles.row} style={{ marginTop: "24px" }}>
        <div className={styles.card} style={{ margin: 0 }}>
          <h2 className={styles.cardTitle}>Extend (manager approval required)</h2>
          <div className={styles.field}>
            <label className={styles.label}>New expiry date</label>
            <input className={styles.input} type="date" value={extendDate} onChange={(e) => setExtendDate(e.target.value)} />
          </div>
          <div className={styles.field}>
            <label className={styles.label}>Reason</label>
            <input className={styles.input} value={extendReason} onChange={(e) => setExtendReason(e.target.value)} />
          </div>
          <button className={styles.btn} onClick={onExtend}>Extend</button>
        </div>

        <div className={styles.card} style={{ margin: 0 }}>
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
        </div>
      </div>
    </div>
  );
}
