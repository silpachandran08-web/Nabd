"use client";

// PLT-024: Compliance & audit: lookups made from this clinic and lookups of its patients by sibling branches (last 30 days).
import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { api } from "../../lib/api";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import styles from "./brandDirectory.module.css";
import { logKey, type LogItem } from "./brandDirectory";

export default function DirectoryLog() {
  const t = useTranslations("shell.brandDirectory.log");
  const [items, setItems] = useState<LogItem[] | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    setFailed(false);
    try { setItems((await api.get<{ items: LogItem[] }>("/patients/brand-directory/lookups")).items); } catch { setFailed(true); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  return (
    <section className={styles.panel} aria-labelledby="bd-log-h">
      <h2 id="bd-log-h" className={styles.h2}>{t("title")}</h2>
      <p className={styles.muted}>{t("intro")}</p>
      {failed ? <ErrorState message={t("loadFailed")} onRetry={() => void load()} />
        : !items ? <Skeleton rows={3} label={t("loading")} />
        : items.length === 0 ? <EmptyState icon="shield" title={t("empty")} hint={t("emptyHint")} />
        : (
          <ul className={styles.list}>
            {items.map((i) => (
              <li key={i.id}>
                <span>{t(logKey(i), { staff: i.staffName ?? t("unknownStaff"), branch: i.branchLabel ?? t("unknownBranch"), patient: i.patientName ?? t("unknownPatient") })}</span>
                <span className={styles.muted}>{new Date(i.at).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        )}
    </section>
  );
}
