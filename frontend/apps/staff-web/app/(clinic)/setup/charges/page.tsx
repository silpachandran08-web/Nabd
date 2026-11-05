"use client";

import styles from "../setup.module.css";
import PricingPanel from "../../../components/setup/PricingPanel";
import { useSetupState } from "../setupStore";
import { SetupTabBody, useSetupTab, type Charge, type Policy } from "../setupTab";

const freshCharge = (): Partial<Charge> => ({ active: true, displayOrder: 0, effectiveFrom: new Date().toLocaleDateString("en-CA") });

export default function ChargesTab() {
  const tab = useSetupTab<{ charges: Charge[]; policies: Policy[] }>({ charges: "/setup/charges", policies: "/setup/policies" }, { charges: [], policies: [] });
  const { charges, policies } = tab.data;
  // effectiveFrom has no visible input below — defaulted to today (matching the column's own
  // CURRENT_DATE default) since addCharge()'s own guard requires it truthy to submit at all.
  const [newCharge, setNewCharge] = useSetupState<Partial<Charge>>("newCharge", freshCharge());
  const addCharge = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCharge.code || !newCharge.name || !newCharge.category || newCharge.baseAmount == null || !newCharge.effectiveFrom) return;
    if (await tab.post("/setup/charges", newCharge)) {
      setNewCharge(freshCharge());
      tab.loadAll();
    }
  };

  const toggleCharge = async (c: Charge) => {
    if (await tab.patch(`/setup/charges/${c.id}`, { ...c, active: !c.active })) tab.loadAll();
  };

  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Charge head / price master</h2>
        <form onSubmit={addCharge}>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Code</label>
              <input className={styles.input} value={newCharge.code ?? ""} onChange={(e) => setNewCharge({ ...newCharge, code: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Name</label>
              <input className={styles.input} value={newCharge.name ?? ""} onChange={(e) => setNewCharge({ ...newCharge, name: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Category</label>
              <input className={styles.input} value={newCharge.category ?? ""} onChange={(e) => setNewCharge({ ...newCharge, category: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Base amount</label>
              <input className={styles.input} type="number" min="0" step="0.01" value={newCharge.baseAmount ?? ""} onChange={(e) => setNewCharge({ ...newCharge, baseAmount: Number(e.target.value) })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Tax rate %</label>
              <input className={styles.input} type="number" min="0" step="0.01" value={newCharge.taxRatePercent ?? ""} onChange={(e) => setNewCharge({ ...newCharge, taxRatePercent: Number(e.target.value) })} />
            </div>
          </div>
          <div className={styles.actions}>
            <button className={styles.btnPrimary} type="submit">Add charge</button>
          </div>
        </form>
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr><th scope="col">Code</th><th scope="col">Name</th><th scope="col">Category</th><th scope="col">Base</th><th scope="col">Tax %</th><th scope="col">Status</th><th scope="col"></th></tr>
            </thead>
            <tbody>
              {charges.map((c) => (
                <tr key={c.id}>
                  <td>{c.code}</td>
                  <td>{c.name}</td>
                  <td>{c.category}</td>
                  <td>{c.baseAmount}</td>
                  <td>{c.taxRatePercent}</td>
                  <td><span className={`${styles.pill} ${c.active ? styles.pillActive : styles.pillInactive}`}>{c.active ? "active" : "inactive"}</span></td>
                  <td><button className={styles.smallBtn} onClick={() => toggleCharge(c)}>{c.active ? "Deactivate" : "Activate"}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <PricingPanel charges={charges} policies={policies} />
    </SetupTabBody>
  );
}
