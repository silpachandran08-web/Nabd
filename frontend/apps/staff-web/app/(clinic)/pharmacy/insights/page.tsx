"use client";

import StockAlerts from "../../../components/setup/StockAlerts";
import AccessGate from "../../../components/ui/AccessGate";
import setupStyles from "../../setup/setup.module.css";

// PHL-008: near-expiry, low-stock and dead-stock alerts plus the expiry report. pharmacy:view reads; acknowledging needs pharmacy:edit.
export default function PharmacyInsightsPage() {
  return (
    <AccessGate permission="pharmacy:view" surface="Pharmacy insights">
      <main className={setupStyles.page}>
        <h1 className={setupStyles.title}>Stock alerts and expiry</h1>
        <p className={setupStyles.subtitle}>Batches near expiry, items below their reorder level and stock that has not moved, with what to do about each.</p>
        <StockAlerts />
      </main>
    </AccessGate>
  );
}
