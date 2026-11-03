"use client";

import styles from "../setup.module.css";
import { SetupTabBody, useSetupTab } from "../setupTab";

type ConsentContact = { name: string; email: string; phone: string | null };

export default function ConsentTab() {
  const tab = useSetupTab<{ consent: ConsentContact }>({ consent: "/setup/consent-contact" }, { consent: { name: "", email: "", phone: "" } });
  const { consent } = tab.data;
  const setConsent = (c: ConsentContact) => tab.setData({ consent: c });
  const saveConsent = async (e: React.FormEvent) => {
    e.preventDefault();
    if (await tab.patch("/setup/consent-contact", consent)) tab.loadAll();
  };
  return (
    <SetupTabBody tab={tab}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Consent & privacy notice contact</h2>
        <form onSubmit={saveConsent}>
          <div className={styles.row}>
            <div className={styles.field}>
              <label className={styles.label}>Name</label>
              <input className={styles.input} value={consent.name} onChange={(e) => setConsent({ ...consent, name: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Email</label>
              <input className={styles.input} type="email" value={consent.email} onChange={(e) => setConsent({ ...consent, email: e.target.value })} />
            </div>
          </div>
          <div className={styles.field}>
            <label className={styles.label}>Phone</label>
            <input className={styles.input} value={consent.phone ?? ""} onChange={(e) => setConsent({ ...consent, phone: e.target.value })} />
          </div>
          <div className={styles.actions}>
            <button className={styles.btnPrimary} type="submit">Save contact</button>
          </div>
        </form>
      </div>
    </SetupTabBody>
  );
}
