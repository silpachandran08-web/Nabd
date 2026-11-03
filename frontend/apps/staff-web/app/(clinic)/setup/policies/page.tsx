"use client";

import styles from "../setup.module.css";
import PrintPaperPanel from "../../../components/setup/PrintPaperPanel";
import { can } from "../../../lib/permissions";
import { SetupTabBody, useSetupTab, type Policy } from "../setupTab";

export default function PoliciesTab() {
  const tab = useSetupTab<{ policies: Policy[] }>({ policies: "/setup/policies" }, { policies: [] });
  const { policies } = tab.data;
  const updatePolicy = async (p: Policy) => {
    const value = prompt(`New value for ${p.policyKey}${p.policyKey === "session.idle_lock_minutes" ? " (minutes, 2-30)" : ""}`, p.value);
    if (value === null) return;
    if (await tab.patch(`/setup/policies/${p.policyKey}`, { value })) tab.loadAll();
  };
  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Simple policy settings</h2>
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr><th scope="col">Policy</th><th scope="col">Value</th><th scope="col">Version</th><th scope="col"></th></tr>
            </thead>
            <tbody>
              {policies.map((p) => (
                <tr key={p.id}>
                  <td>{p.policyKey.replace(/_/g, " ")}</td>
                  <td>{p.value}</td>
                  <td>{p.version}</td>
                  <td><button className={styles.smallBtn} onClick={() => updatePolicy(p)}>Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <PrintPaperPanel policies={policies} canEdit={can("setup:edit")} onSave={async (k, v) => { const ok = await tab.patch(`/setup/policies/${k}`, { value: v }); if (ok) tab.loadAll(); return ok; }} />
    </SetupTabBody>
  );
}
