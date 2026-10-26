"use client";

import { useEffect, useRef, useState } from "react";
import styles from "./patients.module.css";
import Dialog from "../../components/ui/Dialog";
import ConsentBlock from "./ConsentBlock";
import { api } from "../../lib/api";
import { EXPLAINER_LANGUAGES, EXPLAINERS, explainerUsable, type Toggles } from "../../lib/consentCapture";
import { languageLabel } from "../../lib/privacyNotice";
import type { StaffOption } from "@nabd/api-client/types";

/**
 * IDC-007: the full-screen assisted consent the patient can see. Large text + an audio button per language (en, hi, kn, te),
 * the patient answers aloud, the receptionist ticks. Audio is served as a static asset (no PHI). A language whose explainer is
 * not recorded / reviewed yet says so and the receptionist reads the notice aloud instead.
 */
export default function AssistedConsent({ value, onChange, language, onLanguage, minor, witness, onWitness, onDone, onClose }: {
  value: Toggles; onChange: (t: Toggles) => void; language: string; onLanguage: (l: string) => void; minor: boolean;
  witness: string; onWitness: (id: string) => void; onDone: () => void; onClose: () => void;
}) {
  const [staff, setStaff] = useState<StaffOption[]>([]);
  const audio = useRef<HTMLAudioElement>(null);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      const list = await api.get<StaffOption[]>("/staff/roster").catch(() => []);
      if (!cancelled) setStaff(list);
    });
    return () => { cancelled = true; };
  }, []);

  const explainer = EXPLAINERS[language] ?? null;
  const ready = explainerUsable(explainer);
  return (
    <Dialog title="Assisted consent" size="lg" onClose={onClose}>
      <div className={styles.assistedScreen}>
        <div role="group" aria-label="Language" className={styles.fieldRow}>
          {EXPLAINER_LANGUAGES.map((l) => (
            <button key={l} type="button" className={l === language ? styles.primaryBtn : styles.secondaryBtn} aria-pressed={l === language} onClick={() => onLanguage(l)}>{languageLabel(l)}</button>
          ))}
        </div>
        {explainer && (ready ? (
          <>
            <p lang={language}>{explainer.text}</p>
            {explainer.audioUrl && (
              <>
                {/* the transcript is the large text above, so no caption track is needed */}
                {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
                <audio ref={audio} src={explainer.audioUrl} preload="none" />
                <button type="button" className={styles.secondaryBtn} onClick={() => void audio.current?.play()}>Play audio</button>
              </>
            )}
          </>
        ) : (
          <p role="status">The {languageLabel(language)} explainer is not available yet. Read the privacy notice aloud, then record the answers.</p>
        ))}
        <ConsentBlock value={value} onChange={onChange} minor={minor} noticeVersion={null} />
        {staff.length > 0 && (
          <>
            <label className={styles.fieldLabel} htmlFor="assist-witness">Second witness (if your clinic requires one)</label>
            <select id="assist-witness" className={styles.input} value={witness} onChange={(e) => onWitness(e.target.value)}>
              <option value="">None</option>
              {staff.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </>
        )}
        <div className={styles.dialogActions}>
          <button type="button" className={styles.secondaryBtn} onClick={onClose}>Back</button>
          <button type="button" className={styles.primaryBtn} onClick={onDone}>Use these answers</button>
        </div>
      </div>
    </Dialog>
  );
}
