"use client";
// PLT-022 owner.setup Branches: the owner's branches, an "Add branch" wizard (details, what to copy from which branch, MFA
// confirmation bound to the branch code) and live progress of the provisioning job with retry from the step that stopped.
// The region is never asked for: a branch is always created in the owner's region. Add and Retry are absent for anyone but
// the owner with setup:edit; the server enforces the same and also checks the plan's branch limit (402 with the upgrade link).
import { useState } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { ApiError, api, reasonText } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { can } from "../../lib/permissions";
import { isOwner } from "../../lib/session";
import { requestStepUpToken } from "../../lib/stepUp";
import { useHasMounted } from "../../lib/useHasMounted";
import { SECTIONS, atLimit, codeOk, draftOk, moving, progress, stepUpTarget, type Branch, type BranchJob, type BranchList, type Draft, type Section } from "./branches";
import styles from "../plan/planAccount.module.css";

// the same zones the profile tab offers (setup/TimezoneSelect); a feature folder may not import another feature
const ZONES = [
  { value: "Asia/Kolkata", label: "India (Asia/Kolkata, UTC+5:30)" },
  { value: "Asia/Riyadh", label: "Saudi Arabia (Asia/Riyadh, UTC+3)" },
  { value: "UTC", label: "UTC" },
];
const STEP_KEYS = ["create_tenant", "seed_masters", "copy_config", "provision_whatsapp", "go_live"];

export default function BranchesPanel() {
  const t = useTranslations("billing.branches");
  const qc = useQueryClient();
  const mounted = useHasMounted();
  const canAdd = mounted && isOwner() && can("setup:edit");
  // live progress: poll only while a job is moving; a refetch never swaps the page for a skeleton
  const q = useQuery({ queryKey: key("owner-branches"), queryFn: getter<BranchList>("/owner/branches"),
    refetchInterval: (query) => (moving(query.state.data?.jobs ?? []) ? 2000 : false) });
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [retryError, setRetryError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState<string | null>(null);
  const reload = () => qc.invalidateQueries({ queryKey: key("owner-branches") });

  if (q.isPending) return <Skeleton rows={3} label={t("loading")} />;
  if (q.isError) return <ErrorState message={reasonText(q.error, t("loadFailed"))} onRetry={() => void q.refetch()} />;
  const list = q.data;
  const stepName = (s: string) => (STEP_KEYS.includes(s) ? t(`step.${s}`) : s);

  async function retry(job: BranchJob) {
    setRetrying(job.provisioningJobId);
    setRetryError(null);
    try {
      await api.post(`/owner/branches/jobs/${job.provisioningJobId}/retry`, {});
      await reload();
    } catch (e) {
      setRetryError(reasonText(e, t("retryFailed")));
    } finally {
      setRetrying(null);
    }
  }

  return (
    <section className={styles.panel} aria-labelledby="branches-h">
      <h2 id="branches-h" className={styles.h2}>{t("title")}</h2>
      {notice && <div className={styles.notice} role="status">{notice}</div>}

      {list.jobs.map((job) => {
        const p = progress(job);
        return (
          <div key={job.provisioningJobId} className={styles.card} data-testid="branch-job" aria-live="polite">
            <div className={styles.row}><strong>{job.name} ({job.branchCode})</strong>
              <span>{job.failedStep ? t("stoppedAt", { step: stepName(job.failedStep) }) : t("progress", { done: p.done, total: p.total })}</span></div>
            <div className={styles.bar} role="progressbar" aria-label={t("progressLabel", { name: job.name })} aria-valuemin={0} aria-valuemax={p.total} aria-valuenow={p.done}>
              <span className={job.failedStep ? styles.fillFull : styles.fill} style={{ inlineSize: `${p.total ? Math.round((p.done / p.total) * 100) : 0}%` }} /></div>
            <ol className={styles.modules}>
              {job.steps.map((s) => <li key={s.stepName}>{stepName(s.stepName)}: {t(`stepStatus.${["queued", "running", "done", "failed"].includes(s.status) ? s.status : "queued"}`)}</li>)}
            </ol>
            {job.failedStep && <div className={styles.error} role="alert">{t("failedHint", { step: stepName(job.failedStep) })}</div>}
            {job.failedStep && canAdd && <button type="button" className={styles.btn} disabled={retrying === job.provisioningJobId} onClick={() => void retry(job)}>
              {retrying === job.provisioningJobId ? t("retrying") : t("retry")}</button>}
          </div>
        );
      })}
      {retryError && <div className={styles.error} role="alert">{retryError}</div>}

      {list.items.length === 0 ? <EmptyState icon="info" title={t("empty")} /> : (
        <ul className={styles.invoices}>
          {list.items.map((b) => (
            <li key={b.tenantId} className={styles.row} data-testid="branch">
              <span><strong>{b.name}</strong>{b.branchCode && <span className={styles.muted}> · {b.branchCode}</span>}</span>
              <span className={styles.muted}>{b.region} · {b.timezone} · {t.has(`status.${b.status}`) ? t(`status.${b.status}`) : b.status}</span>
            </li>
          ))}
        </ul>
      )}

      {canAdd && (atLimit(list)
        ? <div className={styles.warn} role="note" data-testid="plan-limit">{t("planLimit", { limit: list.branchLimit ?? 0 })} <Link className={styles.link} href="/plan">{t("upgrade")}</Link></div>
        : <div className={styles.actions}><button type="button" className={styles.btn} onClick={() => { setNotice(null); setAdding(true); }}>{t("add")}</button></div>)}

      {adding && <AddBranchWizard branches={list.items} onClose={() => setAdding(false)}
        onDone={(name) => { setAdding(false); setNotice(t("started", { name })); void reload(); }} />}
    </section>
  );
}

function AddBranchWizard({ branches, onClose, onDone }: { branches: Branch[]; onClose: () => void; onDone: (name: string) => void }) {
  const t = useTranslations("billing.branches");
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [d, setD] = useState<Draft>({ name: "", branchCode: "", address: "", timezone: branches[0]?.timezone ?? "", copyFromTenantId: branches[0]?.tenantId ?? "", copy: [...SECTIONS] });
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [upgradeUrl, setUpgradeUrl] = useState<string | null>(null);
  const set = (patch: Partial<Draft>) => setD((cur) => ({ ...cur, ...patch }));
  const toggle = (s: Section) => set({ copy: d.copy.includes(s) ? d.copy.filter((x) => x !== s) : [...d.copy, s] });
  const source = branches.find((b) => b.tenantId === d.copyFromTenantId);
  const detailsOk = d.name.trim().length > 0 && d.address.trim().length > 0 && d.timezone.length > 0 && codeOk(d.branchCode);

  async function confirm() {
    if (busy || !draftOk(d)) return;
    setBusy(true);
    setError(null);
    setUpgradeUrl(null);
    const su = await requestStepUpToken(code, stepUpTarget(d.branchCode));
    if ("error" in su) { setError(su.error); setBusy(false); return; }
    try {
      await api.post<{ provisioningJobId: string; branchTenantId: string }>("/owner/branches",
        { ...d, name: d.name.trim(), address: d.address.trim() }, { headers: { "X-Step-Up-Token": su.token } });
      onDone(d.name.trim());
    } catch (e) {
      // 402 PLAN_LIMIT carries the upgrade link
      if (e instanceof ApiError && e.status === 402) setUpgradeUrl(typeof e.problem.upgradeUrl === "string" ? e.problem.upgradeUrl : "/plan");
      setError(reasonText(e, t("addFailed")));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={t("wizardTitle", { step, total: 3 })} onClose={onClose} size="drawer">
      <div className={styles.dialog}>
        {step === 1 && (
          <>
            <label className={styles.field}><span>{t("name")}</span>
              <input className={styles.input} value={d.name} maxLength={120} onChange={(e) => set({ name: e.target.value })} autoComplete="off" /></label>
            <label className={styles.field}><span>{t("code")}</span>
              <input className={styles.input} value={d.branchCode} maxLength={20} onChange={(e) => set({ branchCode: e.target.value.trim() })} autoComplete="off" aria-describedby="branch-code-hint" />
              <span id="branch-code-hint" className={styles.muted}>{t("codeHint")}</span></label>
            <label className={styles.field}><span>{t("address")}</span>
              <textarea className={styles.input} rows={3} value={d.address} maxLength={500} onChange={(e) => set({ address: e.target.value })} /></label>
            <label className={styles.field} htmlFor="branch-tz"><span>{t("timezone")}</span>
              <select id="branch-tz" className={styles.input} value={d.timezone} onChange={(e) => set({ timezone: e.target.value })}>
                {ZONES.map((z) => <option key={z.value} value={z.value}>{z.label}</option>)}
              </select></label>
            <p className={styles.muted} data-testid="region-pinned">{t("regionPinned", { region: source?.region ?? branches[0]?.region ?? "" })}</p>
          </>
        )}
        {step === 2 && (
          <>
            <label className={styles.field}><span>{t("copyFrom")}</span>
              <select className={styles.input} value={d.copyFromTenantId} onChange={(e) => set({ copyFromTenantId: e.target.value })}>
                {branches.map((b) => <option key={b.tenantId} value={b.tenantId}>{b.name}</option>)}
              </select></label>
            <fieldset className={styles.fieldset}>
              <legend>{t("copyWhat")}</legend>
              {SECTIONS.map((s) => (
                <label key={s} className={styles.radio}><input type="checkbox" checked={d.copy.includes(s)} onChange={() => toggle(s)} />{t(`section.${s}`)}</label>
              ))}
            </fieldset>
            <div className={styles.warn} role="note">{t("neverCopied")}</div>
          </>
        )}
        {step === 3 && (
          <>
            <div className={styles.card} data-testid="branch-summary">
              <div className={styles.row}><span>{t("name")}</span><strong>{d.name.trim()} ({d.branchCode})</strong></div>
              <div className={styles.row}><span>{t("timezone")}</span><span>{d.timezone}</span></div>
              <div className={styles.row}><span>{t("copyFrom")}</span><span>{source?.name ?? ""}</span></div>
              <div className={styles.row}><span>{t("copyWhat")}</span><span>{d.copy.length ? SECTIONS.filter((s) => d.copy.includes(s)).map((s) => t(`section.${s}`)).join(", ") : t("copyNothing")}</span></div>
            </div>
            <label className={styles.field}><span>{t("mfaCode")}</span>
              <input className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} /></label>
          </>
        )}
        {error && <div className={styles.error} role="alert">{error} {upgradeUrl && <Link className={styles.link} href={upgradeUrl}>{t("upgrade")}</Link>}</div>}
        <div className={styles.actions}>
          {step === 1 ? <button type="button" className={styles.btnGhost} onClick={onClose}>{t("cancel")}</button>
            : <button type="button" className={styles.btnGhost} onClick={() => setStep(step === 3 ? 2 : 1)}>{t("back")}</button>}
          {step === 1 && <button type="button" className={styles.btn} disabled={!detailsOk} onClick={() => setStep(2)}>{t("next")}</button>}
          {step === 2 && <button type="button" className={styles.btn} disabled={!draftOk(d)} onClick={() => setStep(3)}>{t("next")}</button>}
          {step === 3 && <button type="button" className={styles.btn} disabled={busy || !/^\d{6}$/.test(code)} onClick={() => void confirm()}>{busy ? t("adding") : t("confirm")}</button>}
        </div>
      </div>
    </Dialog>
  );
}
