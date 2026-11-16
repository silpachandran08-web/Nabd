"use client";

import { useEffect, useMemo, useState } from "react";
import { patientApi, PatientApiError } from "../lib/patientApi";
import { cacheKeys, cachedKeys, tone, verifyOffline, type OfflineCheck, type RxVerification } from "../lib/rxVerify";
import { formatWhen, makeT } from "./util";
import styles from "./linkflow.module.css";

type Phase =
  | { kind: "loading" }
  | { kind: "online"; v: RxVerification }
  | { kind: "offline"; check: Extract<OfflineCheck, { ok: true }> }
  | { kind: "unknown" }
  | { kind: "tooMany" }
  | { kind: "noKey" };

/** CLN-023: the page a scanned Rx QR opens on any phone. Online: the server's answer (valid, expired, dispensed, revoked) with the
 * doctor, registration number and line count. Offline: the signature checked here against the published keys saved on an earlier
 * visit. Never the patient, the medicines or a diagnosis. */
export default function RxVerifyView({ token, messages, locale }: { token: string | null; messages: Record<string, string>; locale: string }) {
  const t = useMemo(() => makeT(messages), [messages]);
  const api = useMemo(() => patientApi(), []);
  const [phase, setPhase] = useState<Phase>(token ? { kind: "loading" } : { kind: "unknown" });

  useEffect(() => {
    if (!token) return;
    let live = true;
    api.rxVerify(token).then((v) => {
      if (!live) return;
      setPhase({ kind: "online", v });
      api.rxKeys().then(cacheKeys, () => undefined); // saved for the next check with no network
    }).catch(async (e) => {
      if (!live) return;
      const f = e instanceof PatientApiError ? e.failure : "network";
      if (f === "too_many") { setPhase({ kind: "tooMany" }); return; }
      if (f !== "network") { setPhase({ kind: "unknown" }); return; }
      const keys = cachedKeys();
      if (!keys) { setPhase({ kind: "noKey" }); return; }
      const check = await verifyOffline(token, keys);
      if (live) setPhase(check.ok ? { kind: "offline", check } : { kind: "unknown" });
    });
    return () => { live = false; };
  }, [api, token]);

  const day = (iso: string | null) => (iso ? formatWhen(iso, locale) : t("rxNoExpiry"));

  return (
    <main className={styles.page} aria-busy={phase.kind === "loading"}>
      <header className={styles.bar}><p className={styles.clinic}>{t("rxTitle")}</p></header>
      {phase.kind === "loading" && <div className={styles.skel} role="status" aria-label={t("loading")} />}
      {phase.kind === "online" && (
        <section className={styles.card}>
          <p className={styles[tone(phase.v.status)]} role="status" data-status={phase.v.status}>
            {phase.v.status === "valid" ? t("rxValid")
              : phase.v.status === "expired" ? t("rxExpired", { date: day(phase.v.validUntil) })
              : phase.v.status === "dispensed" ? (phase.v.dispensedAt ? t("rxDispensed", { date: formatWhen(phase.v.dispensedAt, locale) }) : t("rxDispensedNoDate"))
              : t("rxRevoked")}
          </p>
          <dl className={styles.dl}>
            <dt>{t("rxDoctor")}</dt><dd><bdi>{phase.v.doctor.name ?? "-"}</bdi></dd>
            {phase.v.doctor.registrationNo && <><dt>{t("rxRegNo")}</dt><dd dir="ltr">{phase.v.doctor.registrationNo}</dd></>}
            {phase.v.doctor.council && <><dt>{t("rxCouncil")}</dt><dd><bdi>{phase.v.doctor.council}</bdi></dd></>}
            <dt>{t("rxIssued")}</dt><dd>{formatWhen(phase.v.issuedAt, locale)}</dd>
            <dt>{t("rxValidUntil")}</dt><dd>{day(phase.v.validUntil)}</dd>
            <dt>{t("rxLines")}</dt><dd>{phase.v.lineCount}</dd>
          </dl>
          <p className={styles.muted}>{t("rxPrivacy")}</p>
        </section>
      )}
      {phase.kind === "offline" && (
        <section className={styles.card}>
          <p className={styles[phase.check.expired ? "warn" : "ok"]} role="status" data-status="offline">
            {phase.check.expired ? t("rxExpired", { date: day(phase.check.validUntil) }) : t("rxOfflineValid")}
          </p>
          <dl className={styles.dl}>
            {phase.check.registrationNo && <><dt>{t("rxRegNo")}</dt><dd dir="ltr">{phase.check.registrationNo}</dd></>}
            <dt>{t("rxIssued")}</dt><dd>{formatWhen(phase.check.issuedAt, locale)}</dd>
            <dt>{t("rxValidUntil")}</dt><dd>{day(phase.check.validUntil)}</dd>
          </dl>
          <p className={styles.muted}>{t("rxOfflineHint")}</p>
          <button type="button" className={styles.btn} onClick={() => window.location.reload()}>{t("retry")}</button>
        </section>
      )}
      {phase.kind === "unknown" && (
        <section className={styles.card} role="alert">
          <h1 className={styles.h1}>{t("rxUnknownTitle")}</h1>
          <p className={styles.muted}>{t("rxUnknownHint")}</p>
        </section>
      )}
      {(phase.kind === "tooMany" || phase.kind === "noKey") && (
        <section className={styles.card} role="alert">
          <h1 className={styles.h1}>{t("errorTitle")}</h1>
          <p className={styles.muted}>{phase.kind === "tooMany" ? t("rxTooMany") : t("rxOfflineNoKey")}</p>
          <button type="button" className={styles.btn} onClick={() => window.location.reload()}>{t("retry")}</button>
        </section>
      )}
    </main>
  );
}
