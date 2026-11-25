"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { ApiError, api } from "../../lib/api";
import { useEntitlementsStatus } from "../../lib/entitlements";
import { can } from "../../lib/permissions";
import { getter, key } from "../../lib/query";
import { coverDraft, insuranceErrorKey, insuranceOff, type Coverage } from "../../lib/insurance";
import styles from "./insurance.module.css";

/** FIN-036: who pays for this visit, chosen at checkout before the bill is made. A policy that cannot cover the visit date shows its
 * warning at once and has no Save (the server refuses it too). Once the visit is billed the cover is shown, not editable.
 * Absent without insurance:view, for a clinic without the insurance module and on a 403. */
export default function CoverageBar({ visitId }: { visitId: string }) {
  const t = useTranslations("common.insurance");
  const canEdit = can("insurance:edit");
  const { ent, settled } = useEntitlementsStatus();
  const off = insuranceOff(ent);
  const qc = useQueryClient();
  const qk = key("visit-coverage", { id: visitId });
  const q = useQuery({ queryKey: qk, queryFn: getter<Coverage>(`/insurance/visits/${visitId}/coverage`), enabled: settled && !off });
  const [mode, setMode] = useState<"self_pay" | "insured" | null>(null);
  const [policyId, setPolicyId] = useState<string | null>(null);
  const [ref, setRef] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  if (!settled || off) return null;
  if (q.error instanceof ApiError && q.error.status === 403) return null;
  if (!q.data) {
    return q.isError
      ? <ErrorState message={t("loadError")} onRetry={() => void q.refetch()} />
      : <Skeleton rows={1} label={t("cover.title")} />;
  }
  const c = q.data;
  const curMode = mode ?? c.mode;
  const curPolicy = policyId ?? c.policyId ?? "";
  const curRef = ref ?? c.eligibilityRef ?? "";
  const { warn, dirty, canSave } = coverDraft(c, curMode, curPolicy, curRef);

  async function save() {
    if (busy || !canSave) return;
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const body = curMode === "insured" ? { mode: "insured", policyId: curPolicy, ...(curRef.trim() ? { eligibilityRef: curRef.trim() } : {}) } : { mode: "self_pay" };
      qc.setQueryData(qk, await api.put<Coverage>(`/insurance/visits/${visitId}/coverage`, body));
      setMode(null); setPolicyId(null); setRef(null);
      setSaved(true);
    } catch (e) {
      setError(t(`errors.${insuranceErrorKey(e)}`));
      void q.refetch(); // show what is really saved
    } finally {
      setBusy(false);
    }
  }

  const summary = c.mode === "insured"
    ? t("cover.summary", { mode: t("cover.insured"), payer: c.payerName ?? "", percent: c.copayPercent ?? 0 })
    : t("cover.summarySelf");

  if (c.locked || !canEdit) {
    return (
      <section className={styles.cover} aria-label={t("cover.title")} data-testid="coverage-bar">
        <div className={styles.title}>{t("cover.title")}</div>
        <div>{summary}</div>
        {c.locked && <div className={styles.muted}>{t("cover.locked")}</div>}
      </section>
    );
  }

  return (
    <section className={styles.cover} aria-label={t("cover.title")} aria-busy={busy} data-testid="coverage-bar">
      <div className={styles.title}>{t("cover.title")}</div>
      <div className={styles.row} role="radiogroup" aria-label={t("cover.title")}>
        <label className={styles.radio}>
          <input type="radio" name={`cover-${visitId}`} checked={curMode === "self_pay"} disabled={busy} onChange={() => setMode("self_pay")} />
          {t("cover.selfPay")}
        </label>
        <label className={styles.radio}>
          <input type="radio" name={`cover-${visitId}`} checked={curMode === "insured"} disabled={busy} onChange={() => setMode("insured")} />
          {t("cover.insured")}
        </label>
      </div>
      {curMode === "insured" && (c.policies.length === 0 ? (
        <div className={styles.warn} role="status">{t("cover.noPolicies")}</div>
      ) : (
        <div className={styles.row}>
          <label>
            <span className={styles.muted}>{t("cover.policy")} </span>
            <select className={styles.select} value={curPolicy} disabled={busy} onChange={(e) => { setPolicyId(e.target.value); setSaved(false); }}>
              <option value="">{t("cover.pickPolicy")}</option>
              {c.policies.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.planName
                    ? t("cover.option", { payer: p.payerName, plan: p.planName, member: p.memberIdMasked, to: p.validTo })
                    : t("cover.optionNoPlan", { payer: p.payerName, member: p.memberIdMasked, to: p.validTo })}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className={styles.muted}>{t("cover.eligibilityRef")} </span>
            <input className={styles.text} value={curRef} maxLength={80} disabled={busy} onChange={(e) => setRef(e.target.value)} />
          </label>
        </div>
      ))}
      {warn && <div className={styles.warn} role="alert" data-testid="policy-warning">{t(`warnings.${warn}`)}</div>}
      {canSave && <div><button type="button" className={styles.btn} disabled={busy} onClick={() => void save()}>{busy ? t("cover.saving") : t("cover.save")}</button></div>}
      <div role="status" className={styles.muted}>{saved && !dirty ? `${t("cover.saved")} ${summary}` : ""}</div>
      {error && <div className={styles.err} role="alert">{error}</div>}
    </section>
  );
}
