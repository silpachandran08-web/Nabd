import styles from "../arrivals.module.css";
import { TABS, type ArrivalsTab } from "./boardLogic";

export default function TabBar({ tab, counts, onSelect }: { tab: ArrivalsTab; counts: Record<string, number>; onSelect: (t: ArrivalsTab) => void }) {
  return (
    <div className={styles.tabs} role="group" aria-label="Filter arrivals by status">
      {TABS.map((t) => (
        <button type="button" key={t.key} className={tab === t.key ? styles.tabActive : styles.tab} aria-pressed={tab === t.key} onClick={() => onSelect(t.key)}>
          {t.label}
          {counts[t.key] !== undefined && <span className={styles.chip}>{counts[t.key]}</span>}
        </button>
      ))}
    </div>
  );
}
