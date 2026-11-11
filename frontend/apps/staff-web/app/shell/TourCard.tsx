"use client";
// FEF-062: first-run tour per role (reception, nurse, doctor, owner): five text steps in a non-modal card, so it never blocks
// work. Runs once per person and role (ACC-035: completion stored at Core, GET/PUT /v1/me/onboarding), is deferred while a consult
// is open, can be skipped (Escape or the button) and replayed from Help or the user menu (the "nabd:tour" event). Mounted in
// AppShell so it survives route changes. While the progress is loading or failed to load, no tour starts (never twice by mistake).
import { useEffect, useRef, useState } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import styles from "./shell.module.css";
import { consultActive, isTourRole, TOUR_STEPS, tourDone, tourDue, tourKey } from "./help";
import { useShellNav } from "./useShellNav";
import { useOnboarding } from "./useOnboarding";

export const TOUR_EVENT = "nabd:tour";

export default function TourCard() {
  const t = useTranslations("help");
  const pathname = usePathname();
  const search = useSearchParams();
  const { role } = useShellNav();
  const [step, setStep] = useState(0); // 1..TOUR_STEPS while running, 0 when not
  const [replay, setReplay] = useState(false);
  const headRef = useRef<HTMLHeadingElement>(null);
  const consult = consultActive(pathname, search);
  const { progress, complete } = useOnboarding(isTourRole(role));

  useEffect(() => {
    const on = () => setReplay(true);
    document.addEventListener(TOUR_EVENT, on);
    return () => document.removeEventListener(TOUR_EVENT, on);
  }, []);

  // Start when due (first sign-in for this role, no consult open) or when a replay was asked for.
  useEffect(() => {
    if (step > 0 || !isTourRole(role) || consult) return;
    if (replay || (progress && tourDue({ role, done: tourDone(progress, role), consult }))) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setStep(1);
      setReplay(false);
    }
  }, [role, consult, replay, step, progress]);

  useEffect(() => { if (step > 0) headRef.current?.focus(); }, [step]);

  if (step === 0 || !isTourRole(role)) return null;
  // a failed save only means the tour may show again on the next sign-in; it never blocks work
  const finish = () => { if (!tourDone(progress, role)) complete(tourKey(role)).catch(() => {}); setStep(0); };
  const key = `tours.${role}.s${step}`;
  return (
    <aside className={styles.tour} aria-label={t("tour.label")} data-testid="tour-card" data-step={step}
      onKeyDown={(e) => { if (e.key === "Escape") finish(); }}>
      <p className={styles.tourStep}>{t("tour.step", { n: step, total: TOUR_STEPS })}</p>
      <h2 ref={headRef} tabIndex={-1} className={styles.tourTitle}>{t(`${key}.title`)}</h2>
      <p className={styles.tourBody} aria-live="polite">{t(`${key}.body`)}</p>
      <div className={styles.tourActions}>
        <button type="button" className={styles.chip} onClick={finish}>{t("tour.skip")}</button>
        {step > 1 && <button type="button" className={styles.chip} onClick={() => setStep(step - 1)}>{t("tour.back")}</button>}
        {step < TOUR_STEPS
          ? <button type="button" className={`${styles.chip} ${styles.tourPrimary}`} onClick={() => setStep(step + 1)}>{t("tour.next")}</button>
          : <button type="button" className={`${styles.chip} ${styles.tourPrimary}`} onClick={finish}>{t("tour.done")}</button>}
      </div>
    </aside>
  );
}
