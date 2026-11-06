"use client";
// FEF-062: first-run tour per role (reception, nurse, doctor, owner): five text steps in a non-modal card, so it never blocks
// work. Runs once per role and browser (help.ts: nabd_tour_done), is deferred while a consult is open, can be skipped (Escape or
// the button) and replayed from the user menu (the "nabd:tour" event). Mounted in AppShell so it survives route changes.
import { useEffect, useRef, useState } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import styles from "./shell.module.css";
import { consultActive, isTourRole, markTourDone, TOUR_STEPS, tourDone, tourDue } from "./help";
import { decodeAccessToken } from "../lib/session";
import { useShellNav } from "./useShellNav";

export const TOUR_EVENT = "nabd:tour";
const staffId = (): string | null => { try { const t = localStorage.getItem("nabd_access_token"); return (t && decodeAccessToken(t)?.sub) || null; } catch { return null; } };

export default function TourCard() {
  const t = useTranslations("help");
  const pathname = usePathname();
  const search = useSearchParams();
  const { role } = useShellNav();
  const [step, setStep] = useState(0); // 1..TOUR_STEPS while running, 0 when not
  const [replay, setReplay] = useState(false);
  const headRef = useRef<HTMLHeadingElement>(null);
  const consult = consultActive(pathname, search);

  useEffect(() => {
    const on = () => setReplay(true);
    document.addEventListener(TOUR_EVENT, on);
    return () => document.removeEventListener(TOUR_EVENT, on);
  }, []);

  // Start when due (first sign-in for this role, no consult open) or when a replay was asked for.
  useEffect(() => {
    if (step > 0 || !isTourRole(role) || consult) return;
    const id = staffId();
    if (!id) return;
    if (replay || tourDue({ role, done: tourDone(localStorage, id, role), consult })) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setStep(1);
      setReplay(false);
    }
  }, [role, consult, replay, step]);

  useEffect(() => { if (step > 0) headRef.current?.focus(); }, [step]);

  if (step === 0 || !isTourRole(role)) return null;
  const finish = () => { const id = staffId(); if (id) markTourDone(localStorage, id, role); setStep(0); };
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
