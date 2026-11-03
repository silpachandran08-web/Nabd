"use client";

// FEF-032: the new / edit package form. It mounts each time it opens, so New starts empty and Edit starts from the package.
import { useState } from "react";
import styles from "./packages.module.css";
import { api, ApiError } from "../../lib/api";
import { ErrorSummary, FieldError, useFormErrors } from "../../lib/formErrors";
import { EMPTY_ITEM, money, type ItemDraft, type PackageT, type StaffOption } from "./packagesModel";

type Draft = {
  name: string; type: string; speciality: string; description: string; price: string; taxInclusive: boolean; validityDays: string;
  validityStarts: string; graceDays: string; refundNote: string; doctorIds: string[]; items: ItemDraft[];
};
const EMPTY_DRAFT: Draft = {
  name: "", type: "combination", speciality: "", description: "", price: "0", taxInclusive: false, validityDays: "90",
  validityStarts: "purchase_date", graceDays: "7", refundNote: "", doctorIds: [], items: [{ ...EMPTY_ITEM }],
};
const draftOf = (p: PackageT): Draft => ({
  name: p.name, type: p.packageType, speciality: p.speciality ?? "", description: p.description ?? "", price: String(p.price), taxInclusive: p.taxInclusive,
  validityDays: String(p.validityDays), validityStarts: p.validityStarts, graceDays: String(p.graceDays), refundNote: p.refundNote ?? "", doctorIds: p.eligibleDoctorIds,
  items: p.items.map((i) => ({ itemType: i.itemType, name: i.name, quantity: String(i.quantity), unitListPrice: String(i.unitListPrice), taxRatePercent: String(i.taxRatePercent) })),
});

export default function PackageForm({ editing, staff, onCancel, onSaved }: {
  editing: PackageT | null; staff: StaffOption[]; onCancel: () => void; onSaved: () => Promise<unknown>;
}) {
  const [d, setD] = useState<Draft>(() => (editing ? draftOf(editing) : { ...EMPTY_DRAFT, items: [{ ...EMPTY_ITEM }] }));
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setD((prev) => ({ ...prev, [k]: v }));
  const setItems = (fn: (prev: ItemDraft[]) => ItemDraft[]) => setD((prev) => ({ ...prev, items: fn(prev.items) }));
  const setItem = (idx: number, patch: Partial<ItemDraft>) => setItems((prev) => prev.map((it, i) => (i === idx ? { ...it, ...patch } : it)));
  const ffe = useFormErrors("pkf", { name: "Package name", price: "Price", validityDays: "Validity", graceDays: "Grace days", packageType: "Package type", speciality: "Speciality", description: "Description", refundNote: "Refund note", eligibleDoctorIds: "Doctors" });
  const [saving, setSaving] = useState(false);

  const formListValue = d.items.reduce((sum, i) => sum + (Number(i.unitListPrice) || 0) * (Number(i.quantity) || 0), 0);
  const formSave = Math.max(0, formListValue - (Number(d.price) || 0));

  async function submitForm(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    ffe.clear();
    const items = d.items.filter((i) => i.name.trim().length > 0);
    if (items.length === 0) {
      ffe.set("Add at least one included item.");
      return;
    }
    setSaving(true);
    try {
      const body = {
        name: d.name, packageType: d.type, speciality: d.speciality || null, description: d.description || null,
        price: Number(d.price) || 0, taxInclusive: d.taxInclusive, validityDays: Number(d.validityDays) || 1,
        validityStarts: d.validityStarts, graceDays: Number(d.graceDays) || 0, refundNote: d.refundNote || null,
        eligibleDoctorIds: d.doctorIds,
        items: items.map((i) => ({ itemType: i.itemType, name: i.name, quantity: Number(i.quantity) || 1, unitListPrice: Number(i.unitListPrice) || 0, taxRatePercent: Number(i.taxRatePercent) || 0 })),
      };
      await (editing ? api.patch(`/packages/${editing.id}`, body) : api.post("/packages", body));
      await onSaved();
    } catch (err) {
      if (err instanceof ApiError && !(err.status === 400 && err.errors.length)) ffe.set(err.detail || "Couldn't save the package.");
      else ffe.fail(err, "Couldn't save the package.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className={styles.card} onSubmit={submitForm}>
      <h2 className={styles.cardTitle}>{editing ? "Edit package" : "New package"}</h2>
      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label}>Package name</label>
          <input className={styles.input} value={d.name} {...ffe.attrs("name")} onChange={(e) => { ffe.clearField("name"); set("name", e.target.value); }} required />
          <FieldError fe={ffe} name="name" className={styles.muted} />
        </div>
        <div className={styles.field}>
          <label className={styles.label}>Package type</label>
          <select className={styles.select} value={d.type} onChange={(e) => set("type", e.target.value)}>
            <option value="combination">Combination package</option>
            <option value="session">Session package</option>
          </select>
        </div>
      </div>
      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label}>Speciality</label>
          <input className={styles.input} value={d.speciality} onChange={(e) => set("speciality", e.target.value)} />
        </div>
        <div className={styles.field}>
          <label className={styles.label}>Patient-facing description</label>
          <input className={styles.input} value={d.description} onChange={(e) => set("description", e.target.value)} />
        </div>
      </div>

      <h2 className={`${styles.cardTitle} ${styles.cardTitleSpaced}`}>Included items</h2>
      {d.items.map((item, idx) => (
        <div key={idx} className={`${styles.row3} ${styles.itemRow}`}>
          <div className={`${styles.field} ${styles.fieldFlush}`}>
            <label className={styles.label}>Name</label>
            <input className={styles.input} value={item.name} onChange={(e) => setItem(idx, { name: e.target.value })} />
          </div>
          <div className={`${styles.field} ${styles.fieldFlush}`}>
            <label className={styles.label}>Type</label>
            <select className={styles.select} value={item.itemType} onChange={(e) => setItem(idx, { itemType: e.target.value })}>
              <option value="service_session">Service session</option>
              <option value="procedure">Procedure</option>
              <option value="consultation">Consultation</option>
              <option value="take_home_product">Take-home product</option>
            </select>
          </div>
          <div className={styles.itemNumbers}>
            <div className={`${styles.field} ${styles.fieldFlush} ${styles.fieldGrow}`}>
              <label className={styles.label}>Qty</label>
              <input className={styles.input} type="number" min="1" value={item.quantity} onChange={(e) => setItem(idx, { quantity: e.target.value })} />
            </div>
            <div className={`${styles.field} ${styles.fieldFlush} ${styles.fieldGrow}`}>
              <label className={styles.label}>List price</label>
              <input className={styles.input} type="number" step="0.01" value={item.unitListPrice} onChange={(e) => setItem(idx, { unitListPrice: e.target.value })} />
            </div>
            <div className={`${styles.field} ${styles.fieldFlush} ${styles.fieldGrow}`}>
              <label className={styles.label}>Tax %</label>
              <input className={styles.input} type="number" step="0.01" value={item.taxRatePercent} onChange={(e) => setItem(idx, { taxRatePercent: e.target.value })} />
            </div>
            <button type="button" className={styles.btnSmall} onClick={() => setItems((prev) => prev.filter((_, i) => i !== idx))}>Remove</button>
          </div>
        </div>
      ))}
      <button type="button" className={styles.btn} onClick={() => setItems((prev) => [...prev, { ...EMPTY_ITEM }])}>+ Add an item</button>

      <h2 className={`${styles.cardTitle} ${styles.cardTitleSpaced}`}>Pricing & savings</h2>
      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label}>Package price ({money(formListValue)} list value)</label>
          <input className={styles.input} type="number" step="0.01" value={d.price} {...ffe.attrs("price")} onChange={(e) => { ffe.clearField("price"); set("price", e.target.value); }} />
          <FieldError fe={ffe} name="price" className={styles.muted} />
        </div>
        <div className={styles.field}>
          <label className={styles.label}>Patient saves</label>
          <input className={styles.input} value={money(formSave)} disabled />
        </div>
      </div>
      <label className={styles.label}><input type="checkbox" checked={d.taxInclusive} onChange={(e) => set("taxInclusive", e.target.checked)} /> Price is tax inclusive</label>

      <h2 className={`${styles.cardTitle} ${styles.cardTitleSpaced}`}>Validity & eligibility</h2>
      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label}>Validity period (days)</label>
          <input className={styles.input} type="number" value={d.validityDays} {...ffe.attrs("validityDays")} onChange={(e) => { ffe.clearField("validityDays"); set("validityDays", e.target.value); }} />
          <FieldError fe={ffe} name="validityDays" className={styles.muted} />
        </div>
        <div className={styles.field}>
          <label className={styles.label}>Validity starts</label>
          <select className={styles.select} value={d.validityStarts} onChange={(e) => set("validityStarts", e.target.value)}>
            <option value="purchase_date">Purchase date</option>
            <option value="first_session">First session</option>
          </select>
        </div>
      </div>
      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label}>Grace period (days)</label>
          <input className={styles.input} type="number" value={d.graceDays} {...ffe.attrs("graceDays")} onChange={(e) => { ffe.clearField("graceDays"); set("graceDays", e.target.value); }} />
          <FieldError fe={ffe} name="graceDays" className={styles.muted} />
        </div>
        <div className={styles.field}>
          <label className={styles.label}>Allowed doctors (blank = all)</label>
          <div className={styles.doctorList}>
            {staff.map((s) => (
              <label key={s.id} className={styles.muted}>
                <input type="checkbox" checked={d.doctorIds.includes(s.id)}
                  onChange={(e) => setD((prev) => ({ ...prev, doctorIds: e.target.checked ? [...prev.doctorIds, s.id] : prev.doctorIds.filter((id) => id !== s.id) }))} /> {s.name}
              </label>
            ))}
          </div>
        </div>
      </div>

      <h2 className={`${styles.cardTitle} ${styles.cardTitleSpaced}`}>Payment & refunds</h2>
      <div className={styles.field}>
        <label className={styles.label}>Refund note (shown to staff on the refund screen)</label>
        <textarea className={styles.textarea} value={d.refundNote} onChange={(e) => set("refundNote", e.target.value)} />
      </div>
      <p className={styles.muted}>Full payment only in this build — scheduled instalments and pay-per-session are a later phase.</p>

      <ErrorSummary fe={ffe} className={styles.errorState} />
      <div className={styles.actions}>
        <button type="button" className={styles.btn} onClick={onCancel}>Cancel</button>
        <button type="submit" className={styles.btnPrimary} disabled={saving}>{saving ? "Saving…" : editing ? "Save changes" : "Save as draft"}</button>
      </div>
    </form>
  );
}
