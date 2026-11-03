"use client";

import StockTakeSheet from "../../../components/setup/StockTakeSheet";
import AccessGate from "../../../components/ui/AccessGate";
import setupStyles from "../../setup/setup.module.css";

// PHL-010: stock take by batch. pharmacy:edit holders only; the server refuses External mode.
export default function StockTakePage() {
  return (
    <AccessGate permission="pharmacy:edit" surface="Stock take">
      <main className={setupStyles.page}>
        <h1 className={setupStyles.title}>Stock take</h1>
        <p className={setupStyles.subtitle}>Count the shelf by batch, then post the differences with a reason.</p>
        <StockTakeSheet />
      </main>
    </AccessGate>
  );
}
