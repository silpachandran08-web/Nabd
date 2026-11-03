"use client";

import { useState } from "react";
import styles from "../setup.module.css";
import RegionalCalendar, { type CalHoliday } from "../../../components/setup/RegionalCalendar";
import { can } from "../../../lib/permissions";
import { SetupTabBody, useSetupTab } from "../setupTab";

type Holiday = CalHoliday;

export default function HolidaysTab() {
  const tab = useSetupTab<{ holidays: Holiday[] }>({ holidays: "/setup/holidays" }, { holidays: [] });
  const { holidays } = tab.data;
  const [newHoliday, setNewHoliday] = useState<Partial<Holiday>>({ recurring: false });
  const addHoliday = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newHoliday.holidayDate || !newHoliday.name) return;
    if (await tab.post("/setup/holidays", newHoliday)) {
      setNewHoliday({ recurring: false });
      tab.loadAll();
    }
  };
  const deleteHoliday = async (id: string) => {
    if (await tab.mutate("DELETE", `/setup/holidays/${id}`)) tab.loadAll();
  };
  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Clinic holidays</h2>
        <RegionalCalendar holidays={holidays} canEdit={can("setup:edit")} onChanged={tab.loadAll} />
        <form onSubmit={addHoliday}>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Date</label>
              <input className={styles.input} type="date" value={newHoliday.holidayDate ?? ""} onChange={(e) => setNewHoliday({ ...newHoliday, holidayDate: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Name</label>
              <input className={styles.input} value={newHoliday.name ?? ""} onChange={(e) => setNewHoliday({ ...newHoliday, name: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>
                <input type="checkbox" checked={newHoliday.recurring ?? false} onChange={(e) => setNewHoliday({ ...newHoliday, recurring: e.target.checked })} /> Recurring
              </label>
            </div>
          </div>
          <div className={styles.actions}>
            <button className={styles.btnPrimary} type="submit">Add holiday</button>
          </div>
        </form>
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr><th scope="col">Date</th><th scope="col">Name</th><th scope="col">Recurring</th><th scope="col"></th></tr>
            </thead>
            <tbody>
              {holidays.map((h) => (
                <tr key={h.id}>
                  <td>{h.holidayDate}</td>
                  <td>{h.name}</td>
                  <td>{h.recurring ? "Yes" : "No"}</td>
                  <td><button className={styles.smallBtn} onClick={() => deleteHoliday(h.id)}>Delete</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </SetupTabBody>
  );
}
