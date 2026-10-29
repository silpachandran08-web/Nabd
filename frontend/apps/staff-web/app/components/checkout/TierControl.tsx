"use client";

// FIN-016: Normal / Follow-up / Emergency for a Consultation line, with the reason chip ("Follow-up · last visit 4 days ago", "Urgent check-in").
// Choosing a tier is not a price override: it needs no billing.discount; the server still decides every amount.
import styles from "../../(clinic)/checkout/checkout.module.css";
import { formatMoney } from "@nabd/domain";
import { tierChip, tierOptions, type TierLine, type TierName } from "../../lib/pricingTier";

export default function TierControl({ line, picked, currency, onPick }: { line: TierLine; picked: TierName; currency: string; onPick: (t: TierName) => void }) {
  const options = tierOptions(line);
  if (options.length < 2 && !line.reason) return null;
  return (
    <div>
      <div role="radiogroup" aria-label={`Price tier for ${line.name}`} style={{ display: "flex", flexWrap: "wrap", gap: 6, marginBlockStart: 6 }}>
        {options.map((o) => (
          <button key={o.tier} type="button" role="radio" aria-checked={picked === o.tier} className={picked === o.tier ? styles.catTabActive : styles.catTab}
            style={{ minBlockSize: 44 }} onClick={() => onPick(o.tier)}>
            {o.label} · {formatMoney(o.amount, currency)}
          </button>
        ))}
      </div>
      <div className={styles.muted} data-testid="tier-chip" style={{ marginBlockStart: 4 }}>{tierChip(line, picked)}</div>
      {line.tier === "emergency" && <div className={styles.muted}>Confirm the emergency price, or switch to Normal.</div>}
    </div>
  );
}
