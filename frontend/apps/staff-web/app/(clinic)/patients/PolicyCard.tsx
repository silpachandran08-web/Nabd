"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { ApiError, api } from "../../lib/api";
import { useEntitlementsStatus } from "../../lib/entitlements";
import { can } from "../../lib/permissions";
import { getter, key } from "../../lib/query";
import {
  EMPTY_FORM, NEW, PAYER_KINDS, RELATIONSHIPS, insuranceErrorKey, insuranceOff, maskId, policyState, toPolicyRequest, validatePolicy,
  type Payer, type Plan, type Policy, type PolicyForm,
} from "../../lib/insurance";
import styles from "./patients.module.css";
import own from "./policyCard.module.css";

/** FIN-036: patient file > Insurance. Policies of the patient (member id and policy number hidden until shown) and, with insurance:edit,
 * a form to add one. Absent for roles without insurance:view (the caller mounts it only then), for a clinic whose plan has no insurance
 * module and for a server answer of 403. No payer data is seeded: the form can add a clinic's own insurer and plan inline. */
export default function PolicyCard({ patientId }: { patientId: string }) {
  const t = useTranslations("common.insurance");
  const canEdit = can("insurance:edit");
  const { ent, settled } = useEntitlementsStatus();
  const off = insuranceOff(ent);
  const qc = useQueryClient();
  const qk = key("patient-policies", { id: patientId });
  const q = useQuery({ queryKey: qk, queryFn: getter<{ items: Policy[] }>(`/insurance/patients/${patientId}/policies`), enabled: settled && !off });
  const [open, setOpen] = useState(false);
  const [shown, setShown] = useState<Set<string>>(new Set());

  if (!settled || off) return null;
  if (q.error instanceof ApiError && q.error.status === 403) return null;
  if (!q.data) {
    return q.isError
      ? <ErrorState message={t("loadError")} onRetry={() => void q.refetch()} />
      : <Skeleton rows={2} label={t("title")} />;
  }
  const toggle = (id: string) => setShown((s) => { const n = new Set(s); if (!n.delete(id)) n.add(id); return n; });
  const items = q.data.items;

  return (
    <section className={styles.section} aria-label={t("title")}>
      <div className={styles.sectionLabel}>{t("title")}</div>
      {items.length === 0 && !open && <EmptyState title={t("empty")} icon="shield" />}
      {items.map((p) => {
        const state = policyState(p);
        const reveal = shown.has(p.id);
        return (
          <div key={p.id} className={own.policy} data-testid="policy-row">
            <div className={own.head}>
              <span>{p.payerName}{p.planName ? ` · ${p.planName}` : ""}</span>
              <span className={`${own.pill} ${state === "active" ? "" : own.pillBad}`}>{t(`states.${state}`)}</span>
            </div>
            <dl className={own.facts}>
              <dt>{t("fields.memberId")}</dt><dd>{reveal ? p.memberId : maskId(p.memberId)}</dd>
              <dt>{t("fields.policyNumber")}</dt><dd>{reveal ? p.policyNumber : maskId(p.policyNumber)}</dd>
              <dt>{t("fields.relationship")}</dt><dd>{t(`relationships.${p.relationship as (typeof RELATIONSHIPS)[number]}`)}</dd>
              <dt>{t("fields.validTo")}</dt><dd>{t("validity", { from: p.validFrom, to: p.validTo })}</dd>
              <dt>{t("fields.plan")}</dt><dd>{p.copayPercent == null ? t("noPlan") : t("copay", { percent: p.copayPercent })}</dd>
            </dl>
            <div>
              <button type="button" className={`${styles.linkBtn} ${own.btn}`} aria-pressed={reveal} onClick={() => toggle(p.id)}>
                {reveal ? t("hide") : t("show")}
              </button>
            </div>
          </div>
        );
      })}
      {canEdit && !open && (
        <button type="button" className={`${styles.secondaryBtn} ${own.btn}`} onClick={() => setOpen(true)}>{t("add")}</button>
      )}
      {canEdit && open && (
        <PolicyForm patientId={patientId} onClose={() => setOpen(false)} onSaved={() => { setOpen(false); void qc.invalidateQueries({ queryKey: qk }); }} />
      )}
    </section>
  );
}

function PolicyForm({ patientId, onClose, onSaved }: { patientId: string; onClose: () => void; onSaved: () => void }) {
  const t = useTranslations("common.insurance");
  const payers = useQuery({ queryKey: key("insurance-payers"), queryFn: getter<{ items: Payer[] }>("/insurance/payers") });
  const [f, setF] = useState<PolicyForm>(EMPTY_FORM);
  const [problems, setProblems] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<PolicyForm>) => setF((x) => ({ ...x, ...patch }));
  const list = payers.data?.items ?? [];
  const plans: Plan[] = list.find((p) => p.id === f.payerId)?.plans ?? [];

  async function submit() {
    if (busy) return;
    const bad = validatePolicy(f);
    setProblems(bad);
    if (bad.length) return;
    setBusy(true);
    setError(null);
    try {
      let payerId = f.payerId;
      if (payerId === NEW) { // created once: a retry after a later failure picks it from the list
        const made = await api.post<Payer>("/insurance/payers", { name: f.newPayerName.trim(), kind: f.newPayerKind });
        payerId = made.id;
        set({ payerId });
        await payers.refetch();
      }
      let planId: string | null = f.planId || null;
      if (planId === NEW) {
        const made = await api.post<Plan>(`/insurance/payers/${payerId}/plans`, { name: f.newPlanName.trim(), copayPercent: Number(f.newPlanCopay) });
        planId = made.id;
        set({ planId });
        await payers.refetch();
      }
      await api.post(`/insurance/patients/${patientId}/policies`, toPolicyRequest(f, payerId, planId));
      onSaved();
    } catch (e) {
      setError(t(`errors.${insuranceErrorKey(e)}`));
    } finally {
      setBusy(false);
    }
  }

  const err = (k: string) => problems.includes(k) && <span className={styles.errorInline} role="alert">{t(`form.errors.${k}`)}</span>;
  return (
    <form onSubmit={(e) => { e.preventDefault(); void submit(); }} aria-busy={busy} noValidate>
      <div className={own.grid}>
        <label className={own.field}>
          <span className={styles.fieldLabel}>{t("fields.payer")}</span>
          <select className={styles.input} value={f.payerId} disabled={busy} onChange={(e) => set({ payerId: e.target.value, planId: "" })}>
            <option value="">{t("form.pickPayer")}</option>
            {list.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            <option value={NEW}>{t("form.newPayer")}</option>
          </select>
          {err("payer")}
        </label>
        {f.payerId === NEW && (
          <>
            <label className={own.field}>
              <span className={styles.fieldLabel}>{t("form.newPayerName")}</span>
              <input className={styles.input} value={f.newPayerName} maxLength={120} disabled={busy} onChange={(e) => set({ newPayerName: e.target.value })} />
              {err("newPayerName")}
            </label>
            <label className={own.field}>
              <span className={styles.fieldLabel}>{t("form.newPayerKind")}</span>
              <select className={styles.input} value={f.newPayerKind} disabled={busy} onChange={(e) => set({ newPayerKind: e.target.value })}>
                {PAYER_KINDS.map((k) => <option key={k} value={k}>{t(`kinds.${k}`)}</option>)}
              </select>
            </label>
          </>
        )}
        {f.payerId && (
          <label className={own.field}>
            <span className={styles.fieldLabel}>{t("fields.plan")}</span>
            <select className={styles.input} value={f.planId} disabled={busy} onChange={(e) => set({ planId: e.target.value })}>
              <option value="">{t("form.noPlan")}</option>
              {plans.map((p) => <option key={p.id} value={p.id}>{`${p.name} (${p.copayPercent}%)`}</option>)}
              <option value={NEW}>{t("form.newPlan")}</option>
            </select>
          </label>
        )}
        {f.planId === NEW && (
          <>
            <label className={own.field}>
              <span className={styles.fieldLabel}>{t("form.newPlanName")}</span>
              <input className={styles.input} value={f.newPlanName} maxLength={120} disabled={busy} onChange={(e) => set({ newPlanName: e.target.value })} />
              {err("newPlanName")}
            </label>
            <label className={own.field}>
              <span className={styles.fieldLabel}>{t("form.newPlanCopay")}</span>
              <input className={styles.input} inputMode="decimal" value={f.newPlanCopay} disabled={busy} onChange={(e) => set({ newPlanCopay: e.target.value })} />
              {err("newPlanCopay")}
            </label>
          </>
        )}
        <label className={own.field}>
          <span className={styles.fieldLabel}>{t("fields.memberId")}</span>
          <input className={styles.input} value={f.memberId} maxLength={64} autoComplete="off" disabled={busy} onChange={(e) => set({ memberId: e.target.value })} />
          {err("memberId")}
        </label>
        <label className={own.field}>
          <span className={styles.fieldLabel}>{t("fields.policyNumber")}</span>
          <input className={styles.input} value={f.policyNumber} maxLength={64} autoComplete="off" disabled={busy} onChange={(e) => set({ policyNumber: e.target.value })} />
          {err("policyNumber")}
        </label>
        <label className={own.field}>
          <span className={styles.fieldLabel}>{t("fields.validFrom")}</span>
          <input className={styles.input} type="date" value={f.validFrom} disabled={busy} onChange={(e) => set({ validFrom: e.target.value })} />
        </label>
        <label className={own.field}>
          <span className={styles.fieldLabel}>{t("fields.validTo")}</span>
          <input className={styles.input} type="date" value={f.validTo} disabled={busy} onChange={(e) => set({ validTo: e.target.value })} />
          {err("dates")}{err("datesOrder")}
        </label>
        <label className={own.field}>
          <span className={styles.fieldLabel}>{t("fields.relationship")}</span>
          <select className={styles.input} value={f.relationship} disabled={busy} onChange={(e) => set({ relationship: e.target.value })}>
            {RELATIONSHIPS.map((r) => <option key={r} value={r}>{t(`relationships.${r}`)}</option>)}
          </select>
        </label>
      </div>
      <div className={own.actions}>
        <button type="submit" className={`${styles.primaryBtn} ${own.btn}`} disabled={busy}>{busy ? t("saving") : t("save")}</button>
        <button type="button" className={`${styles.secondaryBtn} ${own.btn}`} disabled={busy} onClick={onClose}>{t("cancel")}</button>
      </div>
      {payers.isError && <div className={styles.errorInline} role="alert">{t("loadError")}</div>}
      {error && <div className={styles.errorInline} role="alert">{error}</div>}
    </form>
  );
}
