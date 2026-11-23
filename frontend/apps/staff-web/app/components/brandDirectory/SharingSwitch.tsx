"use client";

// PLT-024: Settings > Privacy: the owner's switch for finding patients across the brand's branches. setup:view reads it;
// only the brand's owner account can change it (the server says so; the button is absent for everyone else).
import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { api, ApiError } from "../../lib/api";
import { ErrorState, Skeleton } from "../ui/StateViews";
import styles from "./brandDirectory.module.css";
import { canTurnOn, profileBody, type Sharing } from "./brandDirectory";

export default function SharingSwitch() {
  const t = useTranslations("shell.brandDirectory.sharing");
  const [s, setS] = useState<Sharing | null>(null);
  const [failed, setFailed] = useState(false);
  const [notice, setNotice] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const load = useCallback(async () => {
    setFailed(false);
    try { setS(await api.get<Sharing>("/settings/privacy/brand-directory")); } catch { setFailed(true); }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function save(on: boolean) {
    setBusy(true);
    setProblem(null);
    try {
      setS(await api.put<Sharing>("/settings/privacy/brand-directory", profileBody(on, notice)));
      setNotice(false);
    } catch (e) {
      setProblem(e instanceof ApiError && e.status < 500 && e.detail ? e.detail : t("saveFailed"));
    } finally { setBusy(false); }
  }

  return (
    <section className={styles.panel} aria-labelledby="bd-sharing-h">
      <h2 id="bd-sharing-h" className={styles.h2}>{t("title")}</h2>
      <p className={styles.muted}>{t("intro")}</p>
      {failed ? <ErrorState message={t("loadFailed")} onRetry={() => void load()} />
        : !s ? <Skeleton rows={2} label={t("loading")} />
        : (
          <>
            <div role="status">{s.profile === "DEMOGRAPHICS" ? t("stateOn") : t("stateOff")}</div>
            <div className={styles.muted}>{t("branches", { count: s.siblingBranches })}</div>
            {!s.canEdit ? <div className={styles.notice}>{t("ownerOnly")}</div> : s.profile === "NONE" ? (
              <>
                <label className={styles.check}>
                  <input type="checkbox" checked={notice} onChange={(e) => setNotice(e.target.checked)} />
                  <span>{t("notice")}</span>
                </label>
                <div className={styles.actions}>
                  <button type="button" className={styles.btn} disabled={busy || !canTurnOn(s, notice)} onClick={() => void save(true)}>{busy ? t("saving") : t("turnOn")}</button>
                </div>
              </>
            ) : (
              <div className={styles.actions}>
                <button type="button" className={styles.btnGhost} disabled={busy} onClick={() => void save(false)}>{busy ? t("saving") : t("turnOff")}</button>
              </div>
            )}
            {problem && <div className={styles.error} role="alert">{problem}</div>}
          </>
        )}
    </section>
  );
}
