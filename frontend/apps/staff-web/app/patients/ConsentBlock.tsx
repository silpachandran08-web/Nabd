"use client";

import styles from "./patients.module.css";
import { NO_CONSENT, type Toggles } from "../lib/consentCapture";

/**
 * IDC-004: the three itemised consent toggles under the registration form (processing / WhatsApp / marketing).
 * Keyboard: C toggles the block. Optional purposes default off; marketing is not offered for a child (IDC-008).
 * TODO(needs-legal-review): the wording next to each toggle is a placeholder; the notice text itself comes from the clinic's privacy notice.
 */
export default function ConsentBlock({ value, onChange, minor, disabled, noticeVersion }: {
  value: Toggles; onChange: (t: Toggles) => void; minor: boolean; disabled?: boolean; noticeVersion?: string | null;
}) {
  const set = (k: keyof Toggles, v: boolean) => onChange(k === "processing" && !v ? { ...NO_CONSENT } : { ...value, [k]: v });
  return (
    <fieldset className={styles.consentBlock} aria-describedby="consent-note">
      <legend className={styles.fieldLabel}>Consent{noticeVersion ? ` · notice v${noticeVersion}` : ""}</legend>
      <label className={styles.consentRow}>
        <input type="checkbox" checked={value.processing} disabled={disabled} onChange={(e) => set("processing", e.target.checked)} />
        <span>Keep my health record to look after me{minor ? " (given by the guardian)" : ""}</span>
      </label>
      <label className={styles.consentRow}>
        <input type="checkbox" checked={value.messaging} disabled={disabled || !value.processing} onChange={(e) => set("messaging", e.target.checked)} />
        <span>Send messages about my care on WhatsApp</span>
      </label>
      {!minor && (
        <label className={styles.consentRow}>
          <input type="checkbox" checked={value.marketing} disabled={disabled || !value.processing} onChange={(e) => set("marketing", e.target.checked)} />
          <span>Send me offers and news</span>
        </label>
      )}
      <p id="consent-note" className={styles.dialogSub}>
        {value.processing ? "Optional choices are off unless the patient says yes." : "Without the first choice the patient can only be treated in an emergency, and no message is sent."}
      </p>
    </fieldset>
  );
}
