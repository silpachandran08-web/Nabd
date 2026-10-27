"use client";

import GoodsReceiptGrid from "../../../components/setup/GoodsReceiptGrid";
import AccessGate from "../../../components/ui/AccessGate";
import setupStyles from "../../setup/setup.module.css";

// PHL-007: supplier bill entry (no purchase order). pharmacy:edit holders only; the server refuses External mode.
export default function ReceiptPage() {
  return (
    <AccessGate permission="pharmacy:edit" surface="Supplier bills">
      <main className={setupStyles.page}>
        <h1 className={setupStyles.title}>Supplier bill</h1>
        <p className={setupStyles.subtitle}>Enter the bill as printed: new stock lands on the shelf when you post it.</p>
        <GoodsReceiptGrid />
      </main>
    </AccessGate>
  );
}
