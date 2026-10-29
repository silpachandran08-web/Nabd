"use client";

import { useRef, useState } from "react";
import { api, errorText, intentKeys } from "../../lib/api";
import styles from "./patients.module.css";

/** ACC-043: the desk registered the patient with the minimum fields and asks the bot to collect the rest (address, language) on WhatsApp.
 * The caller renders this only with patients:edit; the server enforces it too. */
export default function CompleteInChat({ patientId }: { patientId: string }) {
  const [state, setState] = useState<"idle" | "sending" | "sent">("idle");
  const [error, setError] = useState<string | null>(null);
  const keys = useRef(intentKeys()); // one Idempotency-Key per request intent; a retry after a timeout reuses it

  async function send() {
    if (state === "sending") return;
    setState("sending");
    setError(null);
    try {
      await api.post(`/patients/${patientId}/complete-in-chat`, {}, { idempotencyKey: keys.current.get("complete", { patientId }) });
      keys.current.done("complete");
      setState("sent");
    } catch (e) {
      setError(errorText(e, "Couldn't ask the patient to complete in chat."));
      setState("idle");
    }
  }

  return (
    <div className={styles.section}>
      <div className={styles.sectionLabel}>Complete registration</div>
      {state === "sent" ? (
        <div className={styles.muted} role="status">Sent on WhatsApp. The patient&apos;s answers will appear on this record.</div>
      ) : (
        <button type="button" className={styles.secondaryBtn} disabled={state === "sending"} onClick={() => void send()}>
          {state === "sending" ? "Sending…" : "Complete in chat"}
        </button>
      )}
      {error && <div className={styles.errorInline} role="alert">{error}</div>}
    </div>
  );
}
