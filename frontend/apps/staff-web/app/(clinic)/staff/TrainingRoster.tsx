"use client";
// PLT-054: who on the team holds a current pass of the data safety training (GET /training/completions, staff:view like this
// page). The go-live blocker "staff_phi_training" links here; each person takes the module on their own account from Help (F1).
import { useLocale, useTranslations } from "next-intl";
import { useQuery } from "@tanstack/react-query";
import styles from "./staff.module.css";
import { ErrorState, Skeleton } from "../../components/ui/StateViews";
import { getter, key } from "../../lib/query";
import { asTrainingRoster, type TrainingRoster as Roster } from "../../shell/training";

const read = getter<unknown>("/training/completions");

export default function TrainingRoster() {
  const t = useTranslations("help.training.roster");
  const locale = useLocale();
  const q = useQuery({
    queryKey: key("training/completions"),
    queryFn: async (ctx: { signal: AbortSignal }): Promise<Roster> => {
      const r = asTrainingRoster(await read(ctx));
      if (!r) throw new Error("unexpected training roster");
      return r;
    },
    refetchOnWindowFocus: false,
  });
  const day = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString(locale) : "");
  return (
    <section data-testid="training-roster" aria-labelledby="training-roster-title">
      <div className={styles.sectionHeader}>
        <h2 id="training-roster-title" className={styles.sectionTitle}>{t("title")}</h2>
        {q.data && <span className={styles.muted} data-testid="training-roster-summary">{t("summary", { current: q.data.current, total: q.data.total })}</span>}
      </div>
      <p className={styles.muted}>{t("hint")}</p>
      {q.isPending ? <Skeleton rows={3} label={t("loading")} />
        : q.isError || !q.data ? <ErrorState message={t("error")} onRetry={() => void q.refetch()} />
        : q.data.entries.length === 0 ? <p className={styles.muted}>{t("empty")}</p>
        : (
          <div className={styles.card}>
            <div className={styles.tableWrap}>
              <table className={styles.table} aria-label={t("title")}>
                <thead><tr><th scope="col">{t("name")}</th><th scope="col">{t("role")}</th><th scope="col">{t("status")}</th></tr></thead>
                <tbody>
                  {q.data.entries.map((e) => (
                    <tr key={e.id} data-state={e.state}>
                      <td className={styles.staffName}>{e.name}{e.invited && <span className={styles.muted}> ({t("invited")})</span>}</td>
                      <td>{e.role}</td>
                      <td>{t(`state.${e.state}`, { date: day(e.completedAt), due: day(e.dueAt) })}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
    </section>
  );
}
