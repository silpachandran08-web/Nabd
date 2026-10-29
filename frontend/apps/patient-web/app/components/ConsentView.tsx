"use client";

import { useRef, useState } from "react";
import { labelOf, PatientApiError, type ConsentSummary, type patientApi } from "../lib/patientApi";
import { formatWhen, newKey, type T } from "./util";
import styles from "./linkflow.module.css";

type Outcome = "approve" | "decline" | "changed" | null;

/** Requester, data, range, purpose and validity in the patient's words; Approve/Decline go out with the OTP-bound token and the hash of what was read. */
export default function ConsentView({ summary, token, api, t, messages, locale, onExpired, onReload }: {
  summary: ConsentSummary; token: string; api: ReturnType<typeof patientApi>; t: T; messages: Record<string, string>; locale: string; onExpired: () => void; onReload: () => void;
}) {
  const [outcome, setOutcome] = useState<Outcome>(summary.decided ?? null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const keys = useRef<Record<string, string>>({}); // one Idempotency-Key per decision, reused on retry so a double tap is one decision

  async function decide(decision: "approve" | "decline") {
    setBusy(true);
    setError(false);
    keys.current[decision] ??= newKey();
    try {
      await api.decide(summary.consentId, token, decision, summary.artefactHash, keys.current[decision]);
      setOutcome(decision);
    } catch (e) {
      const f = e instanceof PatientApiError ? e.failure : "network";
      if (f === "expired") onExpired();
      else if (f === "changed") setOutcome("changed");
      else setError(true);
    } finally { setBusy(false); }
  }

  return (
    <section className={styles.card}>
      <h1 className={styles.h1}>{t("consentTitle")}</h1>
      <dl className={styles.dl}>
        <div><dt>{t("consentRequester")}</dt><dd>{summary.requester}</dd></div>
        <div>
          <dt>{t("consentData")}</dt>
          <dd><ul className={styles.chips}>{summary.categories.map((c) => <li key={c}>{labelOf("cat", c, messages)}</li>)}</ul></dd>
        </div>
        <div><dt>{t("consentRange")}</dt><dd>{formatWhen(summary.from, locale)} {t("to")} {formatWhen(summary.to, locale)}</dd></div>
        <div><dt>{t("consentPurpose")}</dt><dd>{labelOf("purpose", summary.purpose, messages)}</dd></div>
        <div><dt>{t("consentValid")}</dt><dd>{formatWhen(summary.validUntil, locale)}</dd></div>
      </dl>
      {outcome === "approve" && <p className={styles.ok} role="status">{t("consentApproved")}</p>}
      {outcome === "decline" && <p className={styles.ok} role="status">{t("consentDeclined")}</p>}
      {outcome === "changed" && (
        <div className={styles.warn} role="alert">
          <strong>{t("consentChangedTitle")}</strong>
          <span>{t("consentChangedHint")}</span>
          <button type="button" className={styles.btn} onClick={onReload}>{t("consentReload")}</button>
        </div>
      )}
      {error && <p className={styles.alert} role="alert">{t("errorHint")}</p>}
      {outcome === null && (
        <div className={styles.actions}>
          <button type="button" className={styles.btn} disabled={busy} onClick={() => void decide("approve")}>{t("approve")}</button>
          <button type="button" className={styles.btnGhost} disabled={busy} onClick={() => void decide("decline")}>{t("decline")}</button>
        </div>
      )}
    </section>
  );
}
