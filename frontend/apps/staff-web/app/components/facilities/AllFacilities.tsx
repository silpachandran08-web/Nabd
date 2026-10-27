"use client";

import Icon from "../ui/Icon";
import { EmptyState } from "../ui/StateViews";
import { setFacilityScope, useKnownFacilities } from "../../shell/facilityScope";
import styles from "./allFacilities.module.css";

/** FEF-058: Overview scope "All facilities". Read-only: nothing here writes. Combined figures wait on the rollup reports (T-196). */
export default function AllFacilities() {
  const facilities = useKnownFacilities();
  return (
    <main className={styles.page}>
      <h1 className={styles.title}>All facilities</h1>
      <p className={styles.note} role="status"><Icon name="info" size={16} /> Read-only view across your facilities. Pick one facility in the switcher to work in it.</p>
      {facilities.length === 0 ? (
        <EmptyState icon="building" title="No facility list loaded." hint="Open the facility switcher to load your facilities." />
      ) : (
        <ul className={styles.list} aria-label="Facilities">
          {facilities.map((f) => (
            <li key={f.id} className={styles.row}><Icon name="building" size={16} /><span><bdi>{f.name}</bdi><span className={styles.sub}>{f.brand}</span></span></li>
          ))}
        </ul>
      )}
      <EmptyState icon="chart" title="Combined figures are not available yet." hint="Totals across facilities arrive with the rollup reports." />
      <button type="button" className={styles.back} onClick={() => setFacilityScope("one")}>Back to this facility</button>
    </main>
  );
}
