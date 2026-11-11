"use client";
// ACC-035: the reception competency check (ten desk tasks, docs/training/reception-train-the-trainer.md) and the 2-minute video
// slots of the top five tasks, in the Help panel. The trainee ticks a task after doing it once with the trainer watching; the
// tenth tick records the pass at Core. Video links are external content: a missing one says so instead of linking anywhere.
import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import styles from "./shell.module.css";
import { COMPETENCY_TASKS, DESK_VIDEO_TASKS, DESK_VIDEOS, taskKey, tasksDone, VIDEO_LANGS, type CompetencyTask } from "./help";
import { useOnboarding } from "./useOnboarding";

export default function CompetencyChecklist() {
  const t = useTranslations("help");
  const locale = useLocale();
  const { progress, loading, failed, retry, complete } = useOnboarding();
  const [saving, setSaving] = useState<CompetencyTask | null>(null);
  const [saveFailed, setSaveFailed] = useState(false);

  async function tick(task: CompetencyTask) {
    setSaving(task);
    setSaveFailed(false);
    try { await complete(taskKey(task)); } catch { setSaveFailed(true); void retry(); } finally { setSaving(null); }
  }

  return (
    <section className={styles.competency} data-testid="competency-check" aria-labelledby="competency-title">
      <h3 id="competency-title" className={styles.competencyTitle}>{t("competency.title")}</h3>
      {loading ? <p className={styles.empty}>{t("competency.loading")}</p>
        : failed || !progress ? (
          <p className={styles.empty} role="alert">{t("competency.error")} <button type="button" className={styles.chip} onClick={() => void retry()}>{t("competency.retry")}</button></p>
        ) : (
          <>
            <p className={styles.empty}>{t("competency.hint")}</p>
            <p aria-live="polite" data-testid="competency-progress">
              {progress.competencyPassedAt
                ? t("competency.passed", { date: new Date(progress.competencyPassedAt).toLocaleDateString(locale) })
                : t("competency.progress", { done: tasksDone(progress), total: COMPETENCY_TASKS.length })}
            </p>
            <ul className={styles.competencyList}>
              {COMPETENCY_TASKS.map((task) => {
                const done = progress.completed.includes(taskKey(task));
                return (
                  <li key={task}>
                    <label className={styles.competencyRow}>
                      <input type="checkbox" checked={done} disabled={done || saving !== null} onChange={() => void tick(task)} />
                      <span>{t(`competency.tasks.${task}`)}</span>
                    </label>
                  </li>
                );
              })}
            </ul>
            {saveFailed && <p className={styles.empty} role="alert">{t("competency.saveFailed")}</p>}
          </>
        )}
      <h3 className={styles.competencyTitle}>{t("videos.title")}</h3>
      <ul className={styles.competencyList} data-testid="desk-videos">
        {DESK_VIDEO_TASKS.map((task) => (
          <li key={task} className={styles.videoRow}>
            <span>{t(`competency.tasks.${task}`)}</span>
            {VIDEO_LANGS.map((lang) => {
              const url = DESK_VIDEOS[task][lang];
              return url
                ? <a key={lang} href={url} target="_blank" rel="noreferrer" className={styles.helpLink}>{t("videos.watch", { lang: t(`videos.lang.${lang}`) })}</a>
                : <span key={lang} className={styles.empty}>{t("videos.missing", { lang: t(`videos.lang.${lang}`) })}</span>;
            })}
          </li>
        ))}
      </ul>
    </section>
  );
}
