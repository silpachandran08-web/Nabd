"use client";

import { useState } from "react";
import styles from "../setup.module.css";
import { SetupTabBody, useSetupTab, type StaffSummary } from "../setupTab";

type Shift = { id: string; staffId: string; staffName: string | null; patternJson: string; effectiveFrom: string; effectiveTo: string | null };

export default function ShiftsTab() {
  const tab = useSetupTab<{ shifts: Shift[]; staff: StaffSummary[] }>({ shifts: "/setup/shifts", staff: "/setup/staff" }, { shifts: [], staff: [] });
  const { shifts, staff } = tab.data;
  const [newShift, setNewShift] = useState<Partial<Shift>>({ patternJson: "{}" });
  const addShift = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newShift.staffId || !newShift.patternJson || !newShift.effectiveFrom) return;
    if (await tab.post("/setup/shifts", newShift)) {
      setNewShift({ patternJson: "{}" });
      tab.loadAll();
    }
  };
  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Shift timings</h2>
        <form onSubmit={addShift}>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Staff</label>
              <select className={styles.select} value={newShift.staffId ?? ""} onChange={(e) => setNewShift({ ...newShift, staffId: e.target.value })}>
                <option value="">—</option>
                {staff.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.roleName})</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Effective from</label>
              <input className={styles.input} type="date" value={newShift.effectiveFrom ?? ""} onChange={(e) => setNewShift({ ...newShift, effectiveFrom: e.target.value })} />
            </div>
          </div>
          <div className={styles.field}>
            <label className={styles.label}>Pattern JSON</label>
            <textarea className={styles.textarea} value={newShift.patternJson} onChange={(e) => setNewShift({ ...newShift, patternJson: e.target.value })} />
          </div>
          <div className={styles.actions}>
            <button className={styles.btnPrimary} type="submit">Add shift</button>
          </div>
        </form>
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr><th scope="col">Staff</th><th scope="col">Pattern</th><th scope="col">From</th><th scope="col">To</th></tr>
            </thead>
            <tbody>
              {shifts.map((s) => (
                <tr key={s.id}>
                  <td>{s.staffName ?? s.staffId}</td>
                  <td><code>{s.patternJson}</code></td>
                  <td>{s.effectiveFrom}</td>
                  <td>{s.effectiveTo ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </SetupTabBody>
  );
}
