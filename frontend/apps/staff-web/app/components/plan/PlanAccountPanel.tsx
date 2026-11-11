"use client";
// ADM-037 owner.plan: current plan, this month's usage, the plans the clinic can move to (module cards with price), a change
// waiting for the renewal date, Nabd invoices, and the change dialog (pro rata charge shown before confirming; a downgrade lists the
// modules that turn read-only, says nothing is deleted and needs the clinic name typed; every change needs a fresh MFA code bound
// to that plan and timing). The change controls are absent for anyone but the owner with setup:edit; the server enforces the same.
import { useState } from "react";
import { useTranslations } from "next-intl";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { formatMoney } from "@nabd/i18n/format";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, reasonText } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { can } from "../../lib/permissions";
import { getIdentity, isOwner } from "../../lib/session";
import { requestStepUpToken } from "../../lib/stepUp";
import { useHasMounted } from "../../lib/useHasMounted";
import { defaultEffective, meterPct, nameMatches, stepUpTarget, type Invoice, type PlanOption, type PlanSummary, type Preview } from "./planAccount";
import styles from "./planAccount.module.css";

const money = (minor: number, currency: string) => formatMoney(minor, currency, { minor: true });

export default function PlanAccountPanel() {
  const t = useTranslations("billing.plan");
  const qc = useQueryClient();
  const mounted = useHasMounted();
  const canChange = mounted && isOwner() && can("setup:edit");
  const sumQ = useQuery({ queryKey: key("plan-summary"), queryFn: getter<PlanSummary>("/setup/subscription") });
  const invQ = useQuery({ queryKey: key("plan-invoices"), queryFn: getter<{ items: Invoice[]; nextCursor: string | null }>("/setup/subscription/invoices") });
  const [target, setTarget] = useState<PlanOption | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (sumQ.isPending) return <Skeleton rows={4} label={t("loading")} />;
  if (sumQ.isError) return <ErrorState message={t("loadFailed")} onRetry={() => void sumQ.refetch()} />;
  const s = sumQ.data;
  if (!s.planCode || !s.currency) return <EmptyState icon="info" title={t("noSubscription")} hint={t("noSubscriptionHint")} />;
  const currency = s.currency;
  const current = s.availablePlans.find((p) => p.code === s.planCode);
  const currentMonthly = s.nextInvoice?.amountMinor ?? current?.monthlyMinor ?? 0;
  const u = s.usage;
  const modName = (m: string) => (t.has(`module.${m}`) ? t(`module.${m}`) : m);

  return (
    <section className={styles.panel} aria-labelledby="plan-account-h">
      <h2 id="plan-account-h" className={styles.h2}>{t("title")}</h2>
      {notice && <div className={styles.notice} role="status">{notice}</div>}

      <div className={styles.card}>
        <div className={styles.row}><span>{t("currentPlan")}</span><strong>{current?.name ?? s.plan ?? s.planCode}</strong></div>
        <div className={styles.row}><span>{t("renewal")}</span><span>{s.renewalDate ?? "—"}</span></div>
        <div className={styles.row}><span>{t("nextInvoice")}</span><span>{s.nextInvoice ? money(s.nextInvoice.amountMinor, s.nextInvoice.currency) : "—"}</span></div>
        <div className={styles.row}><span>{t("paymentMethod")}</span><span className={styles.muted}>{s.paymentMethod ?? t("paymentMethodExternal")}</span></div>
      </div>

      {s.pendingChange && (
        <div className={styles.notice} role="status" data-testid="pending-change">
          {t("pending", { plan: s.availablePlans.find((p) => p.code === s.pendingChange!.toPlanCode)?.name ?? s.pendingChange.toPlanCode, date: s.pendingChange.effectiveAt.slice(0, 10) })}
        </div>
      )}

      {u && (
        <div className={styles.card} aria-label={t("usage")}>
          <h3 className={styles.h3}>{t("usage")}</h3>
          <Meter label={t("seats")} used={u.seatsUsed} limit={u.seatsLimit} unlimited={t("unlimited")} />
          <Meter label={t("branches")} used={u.branchesUsed} limit={u.branchesLimit} unlimited={t("unlimited")} />
          <div className={styles.row}><span>{t("whatsapp")}</span><span>{u.whatsappUtilityMessages}</span></div>
          <div className={styles.row}><span>{t("whatsappCost")}</span>
            <span className={u.whatsappPassThroughMinor == null ? styles.muted : undefined}>{u.whatsappPassThroughMinor == null ? t("notConnected") : money(u.whatsappPassThroughMinor, currency)}</span></div>
          <div className={styles.row}><span>{t("aiChecks")}</span><span className={u.aiChecks == null ? styles.muted : undefined}>{u.aiChecks ?? t("notMetered")}</span></div>
        </div>
      )}

      <h3 className={styles.h3}>{t("plans")}</h3>
      {s.availablePlans.length === 0 ? <EmptyState icon="info" title={t("noPlans")} /> : (
        <ul className={styles.plans}>
          {s.availablePlans.map((p) => {
            const isCurrent = p.code === s.planCode;
            return (
              <li key={p.code} className={isCurrent ? styles.planCurrent : styles.plan} data-testid={`plan-${p.code}`}>
                <div className={styles.row}><strong>{p.name}</strong><span>{t("perMonth", { amount: money(p.monthlyMinor, currency) })}</span></div>
                <ul className={styles.modules} aria-label={t("modulesOf", { plan: p.name })}>
                  {p.modules.map((m) => <li key={m}>{modName(m)}</li>)}
                </ul>
                {isCurrent ? <span className={styles.tag}>{t("current")}</span>
                  : canChange && <button type="button" className={styles.btn} onClick={() => { setNotice(null); setTarget(p); }}>
                    {p.monthlyMinor > currentMonthly ? t("upgradeTo", { plan: p.name }) : t("downgradeTo", { plan: p.name })}
                  </button>}
              </li>
            );
          })}
        </ul>
      )}

      <h3 className={styles.h3}>{t("invoices")}</h3>
      {invQ.isPending ? <Skeleton rows={2} label={t("loadingInvoices")} />
        : invQ.isError ? <ErrorState message={t("invoicesFailed")} onRetry={() => void invQ.refetch()} />
        : invQ.data.items.length === 0 ? <EmptyState icon="receipt" title={t("noInvoices")} />
        : (
          <ul className={styles.invoices}>
            {invQ.data.items.map((i) => (
              <li key={i.id} className={styles.row} data-testid="nabd-invoice">
                <span><strong>{i.number}</strong><span className={styles.muted}> · {t(`kind.${i.kind === "proration" ? "proration" : "renewal"}`)} · {i.periodStart} – {i.periodEnd}</span></span>
                <span>{money(i.amountMinor, i.currency)} · {t(`status.${["due", "paid", "void"].includes(i.status) ? i.status : "due"}`)}</span>
                {i.pdfUrl ? <a className={styles.link} href={i.pdfUrl}>{t("downloadPdf")}</a> : <span className={styles.muted}>{t("pdfPending")}</span>}
              </li>
            ))}
          </ul>
        )}

      {target && (
        <ChangeDialog target={target} currentMonthly={currentMonthly} modName={modName} onClose={() => setTarget(null)}
          onDone={(msg) => { setTarget(null); setNotice(msg); void qc.invalidateQueries({ queryKey: key("plan-summary") }); void qc.invalidateQueries({ queryKey: key("plan-invoices") }); }} />
      )}
    </section>
  );
}

function Meter({ label, used, limit, unlimited }: { label: string; used: number; limit: number | null; unlimited: string }) {
  const pct = meterPct(used, limit);
  return (
    <div className={styles.meter}>
      <div className={styles.row}><span>{label}</span><span>{used} / {limit ?? unlimited}</span></div>
      {pct !== null && <div className={styles.bar} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={limit ?? 0} aria-valuenow={used}>
        <span className={pct >= 100 ? styles.fillFull : styles.fill} style={{ inlineSize: `${pct}%` }} /></div>}
    </div>
  );
}

function ChangeDialog({ target, currentMonthly, modName, onClose, onDone }: {
  target: PlanOption; currentMonthly: number; modName: (m: string) => string; onClose: () => void; onDone: (notice: string) => void;
}) {
  const t = useTranslations("billing.plan");
  const [effective, setEffective] = useState<"now" | "renewal">(defaultEffective(currentMonthly, target));
  const pq = useQuery({
    queryKey: key("plan-preview", { code: target.code, effective }),
    queryFn: getter<Preview>(`/setup/subscription/change-preview?targetPlanCode=${encodeURIComponent(target.code)}&effective=${effective}`),
  });
  const clinic = getIdentity()?.tenantName ?? "";
  const [typed, setTyped] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const p = pq.data;
  const down = p?.direction === "downgrade";
  const ready = !!p && !p.branchesExceedTarget && (!down || nameMatches(typed, clinic)) && /^\d{6}$/.test(code);

  async function confirm() {
    if (!p || busy) return;
    setBusy(true);
    setError(null);
    const su = await requestStepUpToken(code, stepUpTarget(target.code, effective));
    if ("error" in su) { setError(su.error); setBusy(false); return; }
    try {
      const r = await api.post<{ status: string; effectiveAt: string }>("/setup/subscription/change",
        { targetPlanCode: target.code, effective, ...(down ? { confirmClinicName: typed } : {}) }, { headers: { "X-Step-Up-Token": su.token } });
      onDone(r.status === "applied" ? t("doneNow", { plan: target.name }) : t("doneScheduled", { plan: target.name, date: r.effectiveAt.slice(0, 10) }));
    } catch (e) {
      setError(reasonText(e, t("changeFailed")));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={down ? t("downgradeTo", { plan: target.name }) : t("upgradeTo", { plan: target.name })} onClose={onClose}>
      {pq.isPending ? <Skeleton rows={3} label={t("loadingPreview")} />
        : pq.isError || !p ? <ErrorState message={reasonText(pq.error, t("previewFailed"))} onRetry={() => void pq.refetch()} />
        : (
          <div className={styles.dialog}>
            {down && (
              <fieldset className={styles.fieldset}>
                <legend>{t("when")}</legend>
                <label className={styles.radio}><input type="radio" name="plan-effective" checked={effective === "renewal"} onChange={() => setEffective("renewal")} />{t("atRenewal")}</label>
                <label className={styles.radio}><input type="radio" name="plan-effective" checked={effective === "now"} onChange={() => setEffective("now")} />{t("now")}</label>
              </fieldset>
            )}
            <p data-testid="proration">
              {p.prorationMinor > 0 ? t("chargeNow", { amount: money(p.prorationMinor, p.currency) })
                : p.prorationMinor < 0 ? t("creditNow", { amount: money(-p.prorationMinor, p.currency) })
                : effective === "renewal" ? t("fromRenewal", { date: p.effectiveAt.slice(0, 10) }) : t("noCharge")}
            </p>
            {p.branchesExceedTarget && <div className={styles.error} role="alert">{t("branchesExceed")}</div>}
            {down && (
              <div className={styles.warn} role="note">
                {p.modulesTurningReadOnly.length > 0 ? (
                  <>
                    <strong>{t("readOnlyTitle")}</strong>
                    <ul data-testid="read-only-modules">{p.modulesTurningReadOnly.map((m) => <li key={m}>{modName(m)}</li>)}</ul>
                  </>
                ) : <span>{t("noModulesLost")}</span>}
                <span>{t("noDataDeleted")}</span>
              </div>
            )}
            {down && (
              <label className={styles.field}>
                <span>{t("typeName", { name: clinic })}</span>
                <input className={styles.input} value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
              </label>
            )}
            <label className={styles.field}>
              <span>{t("mfaCode")}</span>
              <input className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} />
            </label>
            {error && <div className={styles.error} role="alert">{error}</div>}
            <div className={styles.actions}>
              <button type="button" className={styles.btnGhost} onClick={onClose}>{t("cancel")}</button>
              <button type="button" className={styles.btn} disabled={!ready || busy} onClick={() => void confirm()}>{busy ? t("changing") : t("confirm")}</button>
            </div>
          </div>
        )}
    </Dialog>
  );
}
