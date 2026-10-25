"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./plans.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import RoleDenied, { ReadOnlyNote } from "../../components/platform/RoleDenied";
import { PlanStatusPill } from "../../components/platform/statusPills";
import { formatMoney } from "@nabd/domain";
import { problemDetail, useOperatorAccess, usePlatformFetch } from "../platformApi";

// Matches GET/POST/PATCH /v1/platform/plans (PlanController).
type Plan = {
  id: string;
  code: string;
  name: string;
  monthlyPriceCents: number;
  currency: string;
  seatLimit: number;
  active: boolean;
  version?: number;
  pricingBasis?: string;
  branchLimit?: number | null;
  modules?: string[];
};

// ADM-034: the closed module list (server: PlanEntitlements.MODULES).
const MODULES = ["queue_eta", "whatsapp_booking", "emr_erx", "billing", "pharmacy_external", "pharmacy_inhouse", "lab", "packages", "branded_site", "hr", "insurance", "ipd", "patient_app", "ai_safety"];

const emptyForm = { code: "", name: "", monthlyPrice: "", currency: "INR", seatLimit: "", active: true, pricingBasis: "per_clinic", branchLimit: "", modules: [] as string[] };

export default function PlansPage() {
  const access = useOperatorAccess();
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const authedFetch = usePlatformFetch();

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await authedFetch("/platform/plans");
      if (!res) return;
      if (res.status === 403) {
        setForbidden(true);
        return;
      }
      if (!res.ok) {
        setError("Couldn't load plans. Try again.");
        return;
      }
      setPlans(await res.json());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  function startEdit(p: Plan) {
    setEditingId(p.id);
    setForm({
      code: p.code,
      name: p.name,
      monthlyPrice: (p.monthlyPriceCents / 100).toString(),
      currency: p.currency,
      seatLimit: p.seatLimit.toString(),
      active: p.active,
      pricingBasis: p.pricingBasis ?? "per_clinic",
      branchLimit: p.branchLimit?.toString() ?? "",
      modules: p.modules ?? [],
    });
    setFormError(null);
  }

  function cancelEdit() {
    setEditingId(null);
    setForm(emptyForm);
    setFormError(null);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    const monthlyPriceCents = Math.round(parseFloat(form.monthlyPrice) * 100);
    const seatLimit = parseInt(form.seatLimit, 10);
    if (!form.code || !form.name || Number.isNaN(monthlyPriceCents) || monthlyPriceCents < 0 || !Number.isInteger(seatLimit) || seatLimit < 1) {
      setFormError("Fill in every field with a valid value.");
      return;
    }
    setSaving(true);
    try {
      const branches = form.branchLimit ? parseInt(form.branchLimit, 10) : undefined;
      const body = { code: form.code, name: form.name, monthlyPriceCents, currency: form.currency, seatLimit, active: form.active,
        pricingBasis: form.pricingBasis, modules: form.modules, limits: { seats: seatLimit, branches } };
      const res = editingId
        ? await authedFetch(`/platform/plans/${editingId}`, { method: "PATCH", body: JSON.stringify(body) })
        : await authedFetch("/platform/plans", { method: "POST", body: JSON.stringify(body) });
      if (!res) return;
      if (!res.ok) {
        setFormError(await problemDetail(res, "Couldn't save plan."));
        return;
      }
      cancelEdit();
      load();
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Pricing & Packaging</h1>
          <p className={styles.subtitle}>Plan definitions clinics subscribe to.</p>
        </div>
      </div>

      {!forbidden && access.canBill && (
        <form className={styles.formCard} onSubmit={submit}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="code">Code</label>
            <input id="code" className={styles.input} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} required />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="name">Name</label>
            <input id="name" className={styles.input} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="price">Monthly price</label>
            <input id="price" type="number" min="0" step="0.01" className={styles.input} value={form.monthlyPrice}
              onChange={(e) => setForm({ ...form, monthlyPrice: e.target.value })} required />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="currency">Currency</label>
            <select id="currency" className={styles.select} value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
              <option value="INR">INR</option>
              <option value="SAR">SAR</option>
              <option value="AED">AED</option>
              <option value="QAR">QAR</option>
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="basis">Priced</label>
            <select id="basis" className={styles.select} value={form.pricingBasis} onChange={(e) => setForm({ ...form, pricingBasis: e.target.value })}>
              <option value="per_clinic">Per clinic (flat)</option>
              <option value="per_doctor">Per doctor</option>
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="branches">Branch limit</label>
            <input id="branches" type="number" min="1" step="1" className={styles.input} value={form.branchLimit}
              onChange={(e) => setForm({ ...form, branchLimit: e.target.value })} />
          </div>
          <fieldset className={styles.field} data-testid="module-checklist">
            <legend className={styles.label}>Modules included</legend>
            {MODULES.map((m) => (
              <label key={m} className={styles.checkboxField}>
                <input type="checkbox" checked={form.modules.includes(m)}
                  onChange={(e) => setForm({ ...form, modules: e.target.checked ? [...form.modules, m] : form.modules.filter((x) => x !== m) })} /> {m.replace(/_/g, " ")}
              </label>
            ))}
          </fieldset>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="seats">Seat limit</label>
            <input id="seats" type="number" min="1" step="1" className={styles.input} value={form.seatLimit}
              onChange={(e) => setForm({ ...form, seatLimit: e.target.value })} required />
          </div>
          <label className={styles.checkboxField}>
            <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> Active
          </label>
          <button className={styles.submit} type="submit" disabled={saving}>
            {saving ? "Saving…" : editingId ? "Save changes" : "Add plan"}
          </button>
          {editingId && <button type="button" className={styles.cancelBtn} onClick={cancelEdit}>Cancel</button>}
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
        </form>
      )}

      {!forbidden && access.ready && access.readOnly && <ReadOnlyNote />}

      <div className={styles.card}>
        {loading ? (
          <Skeleton rows={5} label="Loading plans" />
        ) : forbidden ? (
          <RoleDenied surface="Pricing & packaging" />
        ) : error ? (
          <ErrorState message={error} onRetry={load} />
        ) : plans.length === 0 ? (
          <EmptyState icon="package" title="No plans yet" hint={access.canBill ? "Add the first plan with the form above." : undefined} />
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th>Monthly price</th>
                  <th>Seat limit</th>
                  <th>Modules</th>
                  <th>Version</th>
                  <th>Status</th>
                  {access.canBill && <th></th>}
                </tr>
              </thead>
              <tbody>
                {plans.map((p) => (
                  <tr key={p.id}>
                    <td>{p.code}</td>
                    <td>{p.name}</td>
                    <td>{formatMoney(p.monthlyPriceCents / 100, p.currency)}</td>
                    <td>{p.seatLimit}</td>
                    <td>{p.modules?.length ?? 0}</td>
                    <td>v{p.version ?? 1}</td>
                    <td><PlanStatusPill active={p.active} /></td>
                    {access.canBill && <td><button className={styles.actionBtn} onClick={() => startEdit(p)}>Edit</button></td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </main>
  );
}
