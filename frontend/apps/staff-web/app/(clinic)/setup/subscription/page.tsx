"use client";

import styles from "../setup.module.css";
import { seatMeter } from "../../../lib/entitlements";
import { SetupTabBody, useSetupTab } from "../setupTab";

type Subscription = { plan: string | null; status: string; activePatientsUsed: number; activePatientsLimit: number; whatsappMessagesUsed: number; whatsappMessagesLimit: number; branchesUsed: number; branchesLimit: number; modules?: { code: string; enabled: boolean }[]; killedFeatures?: string[]; seatsUsed?: number; seatsLimit?: number | null };

export default function SubscriptionTab() {
  const tab = useSetupTab<{ subscription: Subscription | null }>({ subscription: "/setup/subscription" }, { subscription: null });
  const { subscription } = tab.data;
  return (
    <SetupTabBody tab={tab}>
      {subscription && (
        <div className={styles.card}>
          <h2 className={styles.cardTitle}>Plan & billing</h2>
          <p><strong>Plan:</strong> {subscription.plan ?? "No plan assigned yet"}</p>
          <p><strong>Status:</strong> <span className={`${styles.pill} ${styles.pillActive}`}>{subscription.status}</span></p>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th scope="col">Metric</th><th scope="col">Used</th><th scope="col">Limit</th></tr>
              </thead>
              <tbody>
                <tr><td>Active patients</td><td>{subscription.activePatientsUsed}</td><td>{subscription.activePatientsLimit}</td></tr>
                <tr><td>WhatsApp messages</td><td>{subscription.whatsappMessagesUsed}</td><td>{subscription.whatsappMessagesLimit}</td></tr>
                <tr><td>Branches</td><td>{subscription.branchesUsed}</td><td>{subscription.branchesLimit}</td></tr>
              </tbody>
            </table>
          </div>
          {subscription.seatsUsed !== undefined && (
            <p data-testid="seat-meter"><strong>Staff seats:</strong> {seatMeter(subscription.seatsUsed, subscription.seatsLimit ?? null).label}</p>
          )}
          {subscription.modules && (
            <>
              <h3 className={styles.cardTitle}>Modules</h3>
              <ul data-testid="module-list">
                {subscription.modules.map((m) => (
                  <li key={m.code}>{m.code.replace(/_/g, " ")}: {subscription.killedFeatures?.includes(m.code) ? "switched off by Nabd" : m.enabled ? "included" : "not in your plan"}</li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </SetupTabBody>
  );
}
