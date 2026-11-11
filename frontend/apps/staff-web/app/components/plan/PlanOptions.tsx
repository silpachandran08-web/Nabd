"use client";

import Link from "next/link";
import { EmptyState, Skeleton } from "../ui/StateViews";
import Icon from "../ui/Icon";
import { useEntitlements } from "../../lib/entitlements";
import { usePermissions } from "../../lib/usePermissions";
import { ownerUpgradeOptions } from "../../shell/navRegistry";
import styles from "./planOptions.module.css";

const MODULE_NAME: Record<string, string> = {
  queue_eta: "Queue wait estimates", whatsapp_booking: "WhatsApp booking", emr_erx: "Clinical records and e-prescribing", billing: "Billing",
  pharmacy_external: "External pharmacy", pharmacy_inhouse: "In-house pharmacy", lab: "Laboratory", packages: "Treatment packages",
  branded_site: "Branded booking site", hr: "HR and payroll", insurance: "Insurance", ipd: "Inpatient care", patient_app: "Patient app", ai_safety: "AI safety checks",
};

/** FEF-058: the owner's Plan & Modules screen. A module the plan lacks hides its nav item and appears here as an upgrade option instead. */
export default function PlanOptions() {
  const { ready, permissions } = usePermissions();
  const ent = useEntitlements();
  if (!ready) return <main><Skeleton rows={3} label="Loading plan" /></main>;
  const options = ownerUpgradeOptions(permissions, ent);
  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Plan &amp; Modules</h1>
      <p className={styles.sub}>What your plan includes. Areas that need a module your plan does not have are not shown in the menu; they are listed here instead.</p>
      {!ent ? (
        <EmptyState icon="info" title="Plan details are not available right now." hint="Your menu shows everything until they load. Try again in a moment." />
      ) : options.length === 0 ? (
        <EmptyState icon="check" title="Every area is included in your plan." />
      ) : (
        <section aria-labelledby="upgrade-h">
          <h2 id="upgrade-h" className={styles.h2}>Upgrade options</h2>
          <ul className={styles.list}>
            {options.map(({ item, modules }) => (
              <li key={item.id} className={styles.row} data-testid={`upgrade-${item.id}`}>
                <Icon name={item.icon} size={18} />
                <span className={styles.text}>
                  <strong>{item.label}</strong>
                  <span className={styles.muted}>Needs: {modules.map((m) => MODULE_NAME[m] ?? m).join(" or ")}</span>
                </span>
              </li>
            ))}
          </ul>
          <p className={styles.muted}>To add a module, change your plan below.</p>
        </section>
      )}
      <Link href="/setup?tab=subscription" className={styles.link}>View the plan catalogue</Link>
    </main>
  );
}
