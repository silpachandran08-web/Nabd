"use client";
// PLT-054: the short data safety module (six parts, five questions) and the signed-in person's own completion state.
// Used in the Help panel (clinic staff, api) and on /platform/training (Nabd operators, platformApi): the caller passes its
// client, the completions path and a cache key. A pass is posted to Core; a score under the pass mark is never sent.
import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useQuery } from "@tanstack/react-query";
import type { ApiClient } from "@nabd/api-client";
import styles from "./shell.module.css";
import {
  PASS_MARK, QUIZ_OPTIONS, SECTION_POINTS, TRAINING_MODULE, TRAINING_QUIZ, TRAINING_SECTIONS, asTrainingStatus, quizComplete, quizPassed, quizScore,
  type Answers, type TrainingStatus,
} from "./training";

const QUIZ_STEP = TRAINING_SECTIONS.length;

export default function TrainingModule({ client, path, queryKey }: { client: ApiClient; path: string; queryKey: readonly unknown[] }) {
  const t = useTranslations("help.training");
  const locale = useLocale();
  const status = useQuery({
    queryKey,
    queryFn: async ({ signal }): Promise<TrainingStatus> => {
      const s = asTrainingStatus(await client.get<unknown>(`${path}/me`, { signal }));
      if (!s) throw new Error("unexpected training status");
      return s;
    },
    staleTime: Infinity, refetchOnWindowFocus: false,
  });
  const [step, setStep] = useState<number | null>(null); // null: closed; 0..5: a part; QUIZ_STEP: the questions
  const [answers, setAnswers] = useState<Answers>({});
  const [score, setScore] = useState<number | null>(null); // the marked attempt
  const [saving, setSaving] = useState(false);
  const [saveFailed, setSaveFailed] = useState(false);
  const day = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString(locale) : "");

  function open() { setAnswers({}); setScore(null); setSaveFailed(false); setStep(0); }

  async function submit() {
    const marked = quizScore(answers);
    setSaveFailed(false);
    if (!quizPassed(marked)) { setScore(marked); return; }
    setSaving(true);
    try {
      await client.post(path, { moduleId: TRAINING_MODULE, score: marked });
      setScore(marked);
      void status.refetch();
    } catch { setSaveFailed(true); } finally { setSaving(false); }
  }

  const s = status.data;
  return (
    <section className={styles.competency} data-testid="training-module" aria-labelledby="training-title">
      <h3 id="training-title" className={styles.competencyTitle}>{t("title")}</h3>
      {status.isPending ? <p className={styles.empty}>{t("loading")}</p>
        : status.isError || !s ? (
          <p className={styles.empty} role="alert">{t("error")} <button type="button" className={styles.chip} onClick={() => void status.refetch()}>{t("retry")}</button></p>
        ) : step === null ? (
          <>
            <p aria-live="polite" data-testid="training-status" data-state={s.state}>
              {s.state === "current" ? t("status.current", { date: day(s.completedAt), due: day(s.dueAt) })
                : s.state === "overdue" ? t("status.overdue")
                : s.dueAt ? t("status.dueBy", { due: day(s.dueAt) }) : t("status.due")}
            </p>
            <p className={styles.empty}>{t("intro", { pass: PASS_MARK })}</p>
            <button type="button" className={styles.chip} onClick={open}>{s.state === "current" ? t("retake") : t("start")}</button>
          </>
        ) : step < QUIZ_STEP ? (
          <>
            <p className={styles.empty}>{t("part", { n: step + 1, total: QUIZ_STEP })}</p>
            <h4 className={styles.competencyTitle}>{t(`sections.${TRAINING_SECTIONS[step]}.title`)}</h4>
            <ul className={styles.trainingPoints}>
              {SECTION_POINTS.filter((p) => t.has(`sections.${TRAINING_SECTIONS[step]}.${p}`)).map((p) => <li key={p}>{t(`sections.${TRAINING_SECTIONS[step]}.${p}`)}</li>)}
            </ul>
            <div className={styles.trainingActions}>
              <button type="button" className={styles.chip} onClick={() => setStep(step === 0 ? null : step - 1)}>{step === 0 ? t("close") : t("back")}</button>
              <button type="button" className={styles.chip} onClick={() => setStep(step + 1)}>{step + 1 === QUIZ_STEP ? t("toQuiz") : t("next")}</button>
            </div>
          </>
        ) : score !== null && quizPassed(score) ? (
          <>
            <p role="status" data-testid="training-result">{t("result.passed", { score })}</p>
            <button type="button" className={styles.chip} onClick={() => setStep(null)}>{t("close")}</button>
          </>
        ) : (
          <>
            <h4 className={styles.competencyTitle}>{t("quiz.title")}</h4>
            {TRAINING_QUIZ.map((q) => (
              <fieldset key={q.id} className={styles.trainingQuestion}>
                <legend>{t(`quiz.${q.id}.q`)}</legend>
                {QUIZ_OPTIONS.map((o) => (
                  <label key={o} className={styles.competencyRow}>
                    <input type="radio" name={`training-${q.id}`} checked={answers[q.id] === o} disabled={saving}
                      onChange={() => { setScore(null); setAnswers((a) => ({ ...a, [q.id]: o })); }} />
                    <span>{t(`quiz.${q.id}.${o}`)}</span>
                  </label>
                ))}
              </fieldset>
            ))}
            {score !== null && <p role="alert" data-testid="training-result">{t("result.failed", { score, pass: PASS_MARK })}</p>}
            {saveFailed && <p role="alert">{t("result.saveFailed")}</p>}
            {!quizComplete(answers) && <p className={styles.empty}>{t("quiz.unanswered")}</p>}
            <div className={styles.trainingActions}>
              <button type="button" className={styles.chip} onClick={() => setStep(0)} disabled={saving}>{t("back")}</button>
              {quizComplete(answers) && <button type="button" className={styles.chip} onClick={() => void submit()} disabled={saving}>{t("submit")}</button>}
            </div>
          </>
        )}
    </section>
  );
}
