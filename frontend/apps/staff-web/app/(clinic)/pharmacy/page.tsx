"use client";

import PharmacyPanel from "../../components/setup/PharmacyPanel";
import AccessGate from "../../components/ui/AccessGate";
import setupStyles from "../setup/setup.module.css";

// The pharmacist's own home (PHA-002): the Pharmacy panel on its own route, fed only by /pharmacy/*,
// so a pharmacy-only role never needs Setup access to reach the dispensing queue. The nav entry
// (ClinicNav, pharmacy:view) and the landing rule (lib/session.ts) point here; the Setup tab
// renders the same panel for setup:view holders who also hold pharmacy:view.
export default function PharmacyPage() {
  return (
    <AccessGate permission="pharmacy:view" surface="Pharmacy">
      <PharmacyPageInner />
    </AccessGate>
  );
}

function PharmacyPageInner() {
  return (
    <main className={setupStyles.page}>
      <h1 className={setupStyles.title}>Pharmacy</h1>
      <p className={setupStyles.subtitle}>Dispensing queue, item master and stock for this clinic.</p>
      <PharmacyPanel />
    </main>
  );
}
