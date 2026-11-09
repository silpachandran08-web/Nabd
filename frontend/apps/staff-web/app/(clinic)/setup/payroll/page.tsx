"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "../setup.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/StateViews";
import { api, ApiError, reasonText } from "../../../lib/api";
import { useSetupState } from "../setupStore";

type PayrollRow = { staffId: string; staffName: string; role: string; days: number; hours: number; salary: number; notes: string };

const localMonth = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`; }; // local month, not UTC

function csvCell(v: string | number): string {
  let t = String(v ?? "");
  if (typeof v === "string" && /^[=+\-@\t\r]/.test(t)) t = `'${t}`; // neutralise spreadsheet formula injection
  return /[",\n\r]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t;
}

export default function PayrollTab() {
  const [payroll, setPayroll] = useState<PayrollRow[]>([]);
  const [payrollMonth, setPayrollMonth] = useSetupState("payrollMonth", localMonth());
  const [payrollError, setPayrollError] = useState<string | null>(null);
  const [payrollLoading, setPayrollLoading] = useState(false);

  const loadPayroll = useCallback(async (month: string) => {
    setPayrollLoading(true);
    setPayrollError(null);
    try {
      setPayroll(await api.get<PayrollRow[]>(`/setup/payroll-export?month=${encodeURIComponent(month)}`));
    } catch (e) {
      setPayrollError(e instanceof ApiError && e.status < 500 ? e.detail || "Couldn't load the payroll export." : reasonText(e, ""));
    } finally {
      setPayrollLoading(false);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(() => loadPayroll(payrollMonth));
  }, [payrollMonth, loadPayroll]);

  const exportPayrollCsv = () => {
    const head = ["Staff", "Role", "Days", "Hours", "Salary", "Notes"];
    const lines = [head, ...payroll.map((r) => [r.staffName, r.role, r.days, r.hours, r.salary, r.notes])]
      .map((row) => row.map(csvCell).join(","));
    const url = URL.createObjectURL(new Blob([lines.join("\r\n")], { type: "text/csv;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = `payroll-${payrollMonth}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Simple payroll export</h2>
      <div className={styles.row}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="payroll-month">Month</label>
          <input id="payroll-month" className={styles.input} type="month" value={payrollMonth} onChange={(e) => e.target.value && setPayrollMonth(e.target.value)} />
        </div>
        <div className={styles.field}>
          <span className={styles.label}>&nbsp;</span>
          {payroll.length > 0 && !payrollError && <button type="button" className={styles.btnPrimary} onClick={exportPayrollCsv}>Export CSV</button>}
        </div>
      </div>
      <p className={`${styles.subtitle} ${styles.payrollNote}`}>
        Statutory contributions (PF/ESI for India, GOSI for KSA) are not calculated here — apply them in your payroll system after export.
      </p>
      {payrollLoading ? (
        <Skeleton rows={4} label="Loading payroll" />
      ) : payrollError ? (
        <ErrorState message={payrollError} onRetry={() => loadPayroll(payrollMonth)} />
      ) : payroll.length === 0 ? (
        <EmptyState title="No payroll rows for this month" hint="Pick another month, or add shifts so hours can be summed." icon="list" />
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr><th scope="col">Staff</th><th scope="col">Role</th><th scope="col">Days</th><th scope="col">Hours</th><th scope="col">Salary</th><th scope="col">Notes</th></tr>
            </thead>
            <tbody>
              {payroll.map((r) => (
                <tr key={r.staffId}>
                  <td>{r.staffName}</td>
                  <td>{r.role}</td>
                  <td>{r.days}</td>
                  <td>{r.hours}</td>
                  <td>{r.salary}</td>
                  <td>{r.notes}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
