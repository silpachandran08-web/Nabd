"use client";

import styles from "./packages.module.css";
import { STATUS_PILL, type InstanceT } from "./packagesModel";

export default function InstanceTable({ instances, onOpen, showInvoice }: { instances: InstanceT[]; onOpen: (id: string) => void; showInvoice: boolean }) {
  if (instances.length === 0) return <div className={styles.empty}>Nothing here yet.</div>;
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
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
                  <div className={styles.progressWrap}>
                    {/* FEF-032: a native bar (was a div sized by an inline width); the count beside it is what is read out */}
                    <progress className={styles.progressBar} value={total ? consumed : 0} max={total || 1} aria-hidden="true" />
                    <span className={styles.muted}>{consumed}/{total}</span>
                  </div>
                </td>
                {showInvoice && <td>{inst.invoiceNumber}</td>}
                <td>{inst.validityEnd ?? "not started"}</td>
                <td><span className={`${styles.pill} ${STATUS_PILL[inst.status] ?? styles.pillInactive}`}>{inst.status}</span></td>
                <td><button className={styles.btnSmall} onClick={() => onOpen(inst.id)}>Open</button></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
