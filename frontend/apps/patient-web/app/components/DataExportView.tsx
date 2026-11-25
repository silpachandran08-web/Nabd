"use client";

import { useEffect, useState } from "react";
import type { DataExportFile } from "../lib/patientApi";
import type { T } from "./util";
import styles from "./linkflow.module.css";

/** PLT-047: the data package decoded in the browser into a blob: URL (nothing is fetched again; the package is not kept anywhere). */
export default function DataExportView({ file, t }: { file: DataExportFile; t: T }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let made: string | null = null;
    let cancelled = false;
    void Promise.resolve().then(() => {
      if (cancelled) return;
      try {
        const bytes = Uint8Array.from(atob(file.contentBase64), (c) => c.charCodeAt(0));
        made = URL.createObjectURL(new Blob([bytes], { type: "application/zip" }));
        setUrl(made);
      } catch { setFailed(true); }
    });
    return () => { cancelled = true; if (made) URL.revokeObjectURL(made); };
  }, [file]);

  return (
    <section className={styles.card}>
      <h1 className={styles.h1}>{t("dxTitle")}</h1>
      <p className={styles.muted}>{t("dxHint")}</p>
      {failed && <p className={styles.alert} role="alert">{t("errorHint")}</p>}
      {!url && !failed && <div className={styles.skel} role="status" aria-label={t("loading")} />}
      {url && <a className={styles.btn} href={url} download={file.fileName}>{t("dxDownload")}</a>}
      <p className={styles.muted}>{t("dxPrivate")}</p>
    </section>
  );
}
