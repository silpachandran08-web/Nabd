"use client";

import { useEffect, useState } from "react";
import { DOC_INLINE, PatientApiError, type patientApi, type Resolved } from "../lib/patientApi";
import type { T } from "./util";
import styles from "./linkflow.module.css";

/** The document is fetched with the OTP-bound token and shown from a blob: URL (the file URL itself never goes in the address bar). */
export default function DocumentView({ linkId, token, resolved, api, t, onExpired }: {
  linkId: string; token: string; resolved: Extract<Resolved, { kind: "document" }>; api: ReturnType<typeof patientApi>; t: T; onExpired: () => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let made: string | null = null;
    let cancelled = false;
    api.document(linkId, token).then((blob) => {
      if (cancelled) return;
      made = URL.createObjectURL(blob);
      setUrl(made);
    }).catch((e) => {
      if (cancelled) return;
      if (e instanceof PatientApiError && e.failure === "expired") onExpired(); else setFailed(true);
    });
    return () => { cancelled = true; if (made) URL.revokeObjectURL(made); };
  }, [api, linkId, token, attempt, onExpired]);

  const kind = DOC_INLINE(resolved.contentType);
  return (
    <section className={styles.card}>
      <h1 className={styles.h1}>{t("docTitle")}</h1>
      {failed && (
        <>
          <p className={styles.alert} role="alert">{t("errorHint")}</p>
          <button type="button" className={styles.btn} onClick={() => { setFailed(false); setAttempt((n) => n + 1); }}>{t("retry")}</button>
        </>
      )}
      {!url && !failed && <div className={styles.skel} role="status" aria-label={t("loading")} />}
      {url && kind === "pdf" && <iframe className={styles.frame} src={url} title={t("docPreviewTitle")} />}
      {/* a blob: URL from the authenticated download: next/image cannot optimise it */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {url && kind === "image" && <img className={styles.img} src={url} alt={t("docPreviewTitle")} />}
      {url && kind === "other" && <p className={styles.muted}>{t("docUnsupported")}</p>}
      {url && <a className={styles.btn} href={url} download={resolved.fileName ?? "document"}>{t("docOpen")}</a>}
    </section>
  );
}
