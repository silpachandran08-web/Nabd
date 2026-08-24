"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./plans.module.css";

// Matches GET/POST/PATCH /v1/platform/plans (PlanController).
type Plan = {
  id: string;
  code: string;
  name: string;
  monthlyPriceCents: number;
  currency: string;
  seatLimit: number;
  active: boolean;
};
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

const emptyForm = { code: "", name: "", monthlyPrice: "", currency: "INR", seatLimit: "", active: true };

export default function PlansPage() {
  const router = useRouter();
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

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
      const body = { code: form.code, name: form.name, monthlyPriceCents, currency: form.currency, seatLimit, active: form.active };
      const res = editingId
        ? await authedFetch(`/platform/plans/${editingId}`, { method: "PATCH", body: JSON.stringify(body) })
        : await authedFetch("/platform/plans", { method: "POST", body: JSON.stringify(body) });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't save plan." }));
        setFormError(p.detail || "Couldn't save plan.");
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

      {!forbidden && (
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
            </select>
          </div>
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

      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : forbidden ? (
          <div className={styles.state}>Your role doesn&apos;t have access to pricing & packaging.</div>
        ) : error ? (
          <div className={styles.errorState}>{error}</div>
        ) : plans.length === 0 ? (
          <div className={styles.state}>No plans yet.</div>
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th>Monthly price</th>
                  <th>Seat limit</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {plans.map((p) => (
                  <tr key={p.id}>
                    <td>{p.code}</td>
                    <td>{p.name}</td>
                    <td>{(p.monthlyPriceCents / 100).toFixed(2)} {p.currency}</td>
                    <td>{p.seatLimit}</td>
                    <td><span className={`${styles.pill} ${p.active ? styles.pillActive : styles.pillInactive}`}>{p.active ? "active" : "inactive"}</span></td>
                    <td><button className={styles.actionBtn} onClick={() => startEdit(p)}>Edit</button></td>
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
