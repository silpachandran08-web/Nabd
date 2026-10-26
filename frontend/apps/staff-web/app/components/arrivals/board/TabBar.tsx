import { useTranslations } from "next-intl";
import styles from "../arrivals.module.css";
import { TABS, type ArrivalsTab } from "./boardLogic";

export default function TabBar({ tab, counts, onSelect }: { tab: ArrivalsTab; counts: Record<string, number>; onSelect: (t: ArrivalsTab) => void }) {
  const t = useTranslations("arrivals.tabs");
  return (
    <div className={styles.tabs} role="group" aria-label={t("label")}>
      {TABS.map((x) => (
        <button type="button" key={x.key} className={tab === x.key ? styles.tabActive : styles.tab} aria-pressed={tab === x.key} onClick={() => onSelect(x.key)}>
          {t(x.key)}
          {counts[x.key] !== undefined && <span className={styles.chip}>{counts[x.key]}</span>}
        </button>
      ))}
    </div>
  );
}
