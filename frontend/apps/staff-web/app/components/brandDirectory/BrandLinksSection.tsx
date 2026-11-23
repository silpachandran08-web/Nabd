"use client";

// PLT-024: the patient drawer's 'Other branches' block: linked badges, the patient's opt-out, and (built-in Owner role only)
// removing a link. The server checks every one of these again; a role that may not act never sees the control.
import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { api, ApiError } from "../../lib/api";
import { isOwner } from "../../lib/session";
import Dialog from "../ui/Dialog";
import styles from "./brandDirectory.module.css";
import type { BranchLink, PatientLinks } from "./brandDirectory";

export default function BrandLinksSection({ patientId, canEdit }: { patientId: string; canEdit: boolean }) {
  const t = useTranslations("shell.brandDirectory.links");
  const [state, setState] = useState<PatientLinks | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [failed, setFailed] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [removing, setRemoving] = useState<BranchLink | null>(null);
  const [busy, setBusy] = useState(false);
  const owner = canEdit && isOwner();

  const load = useCallback(async () => {
    setFailed(false);
    try {
      setState(await api.get<PatientLinks>(`/patients/${patientId}/brand-links`));
    } catch { setFailed(true); }
  }, [patientId]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useEffect(() => {
    api.get<{ enabled: boolean }>("/patients/brand-directory/status").then((r) => setEnabled(r.enabled)).catch(() => setEnabled(false));
  }, []);

  async function setOptOut(optOut: boolean) {
    setProblem(null);
    try {
      await api.put(`/patients/${patientId}/brand-directory-opt-out`, { optOut });
      await load();
    } catch { setProblem(t("optOutFailed")); }
  }

  async function remove(link: BranchLink) {
    setBusy(true);
    setProblem(null);
    try {
      await api.delete(`/patients/${patientId}/brand-links/${link.id}`);
      setRemoving(null);
      await load();
    } catch (e) {
      setRemoving(null);
      setProblem(e instanceof ApiError && e.status === 403 ? e.detail : t("unlinkFailed"));
    } finally { setBusy(false); }
  }

  if (failed) return <div className={styles.error} role="alert">{t("loadFailed")}</div>;
  // Nothing to show for a clinic that does not share and a patient who was never linked: the block is absent.
  if (!state || (!enabled && state.links.length === 0)) return null;

  return (
    <section className={styles.panel} aria-label={t("label")}>
      <h3 className={styles.h2}>{t("label")}</h3>
      {state.links.length === 0 ? <div className={styles.muted}>{t("none")}</div> : (
        <ul className={styles.list}>
          {state.links.map((l) => (
            <li key={l.id}>
              <span className={styles.badge}>{t("badge", { branch: l.branchLabel })}</span>
              {owner && <button type="button" className={styles.btnGhost} aria-label={t("unlinkLabel", { branch: l.branchLabel })} onClick={() => setRemoving(l)}>{t("unlink")}</button>}
            </li>
          ))}
        </ul>
      )}
      {canEdit && enabled && (
        <label className={styles.check}>
          <input type="checkbox" checked={state.optedOut} onChange={(e) => void setOptOut(e.target.checked)} />
          <span>{t("optOut")}<br /><span className={styles.muted}>{t("optOutHint")}</span></span>
        </label>
      )}
      {problem && <div className={styles.error} role="alert">{problem}</div>}
      {removing && (
        <Dialog title={t("unlink")} onClose={() => { if (!busy) setRemoving(null); }}>
          <p>{t("unlinkConfirm", { branch: removing.branchLabel })}</p>
          <div className={styles.actions}>
            <button type="button" className={styles.btnGhost} disabled={busy} onClick={() => setRemoving(null)}>{t("unlinkNo")}</button>
            <button type="button" className={styles.btn} disabled={busy} onClick={() => void remove(removing)}>{t("unlinkYes")}</button>
          </div>
        </Dialog>
      )}
    </section>
  );
}
