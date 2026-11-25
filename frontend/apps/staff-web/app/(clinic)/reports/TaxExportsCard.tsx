"use client";

import { taxRegime } from "@nabd/domain";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { ApiError, api } from "../../lib/api";
import { clinicToday } from "../../lib/clinicTime";
import { can } from "../../lib/permissions";
import { key as queryKey } from "../../lib/query";
import { getIdentity, isOwner } from "../../lib/session";
import { useClinicTimeZone } from "../../lib/useClinicTimeZone";
import { useHasMounted } from "../../lib/useHasMounted";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { decodeBase64 } from "./libraryModel";
import { TAX_FORMATS, canRetry, chip, defaultPeriod, exportPath, periodProblem, type EinvoiceItem, type Gstr1Answer, type TaxFormat } from "./taxExportModel";
import s from "./library.module.css";
import x from "./taxExports.module.css";

// FIN-011: the owner's tax exports card (GSTR-1 friendly file for one finished month) and, when e-invoicing is switched on, the
// IRN status of the latest B2B bills with a retry. Absent without reports:export / for a clinic outside GST. The server repeats
// every gate (Owner role, PERIOD_OPEN, GST market).
type Settings = { irpEnabled: boolean; adapterAvailable: boolean };
type EinvoiceList = { irpEnabled: boolean; items: EinvoiceItem[] };
type Run = { state: "idle" } | { state: "busy" } | { state: "done"; answer: Gstr1Answer } | { state: "error"; message: string };

export default function TaxExportsCard() {
  const mounted = useHasMounted(); // grants live in the browser: wait for hydration before deciding what is absent
  if (!mounted || !can("reports:export") || taxRegime(getIdentity()?.tenantRegion).label !== "GST") return null;
  return <Card />;
}

function Card() {
  const t = useTranslations("shell.taxExports");
  const tz = useClinicTimeZone();
  const today = clinicToday(tz);
  const [period, setPeriod] = useState(() => defaultPeriod(today));
  const [format, setFormat] = useState<TaxFormat>("xlsx");
  const [run, setRun] = useState<Run>({ state: "idle" });
  const problem = periodProblem(period, today);

  async function download() {
    setRun({ state: "busy" });
    try {
      const r = await api.get<Gstr1Answer>(exportPath(period, format));
      const url = URL.createObjectURL(new Blob([decodeBase64(r.contentBase64)], { type: r.contentType }));
      const a = document.createElement("a");
      a.href = url;
      a.download = r.fileName;
      a.click();
      URL.revokeObjectURL(url);
      setRun({ state: "done", answer: r });
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0;
      setRun({ state: "error", message: t(status === 422 ? "periodOpen" : status === 403 ? "ownerOnly" : "failed") });
    }
  }

  return (
    <section className={s.card} aria-label={t("title")}>
      <div>
        <p className={s.eyebrow}>{t("title")}</p>
        <h2 className={s.h2}>{t("gstr1")}</h2>
        <p className={s.note}>{t("hint")}</p>
      </div>
      <div className={x.fields}>
        <label className={s.field}>{t("period")}
          <input type="month" className={s.date} value={period} max={defaultPeriod(today)} onChange={(e) => { setRun({ state: "idle" }); setPeriod(e.target.value); }} />
        </label>
        <label className={s.field}>{t("format")}
          <select className={s.date} value={format} onChange={(e) => setFormat(e.target.value as TaxFormat)}>
            {TAX_FORMATS.map((f) => <option key={f} value={f}>{t(`format_${f}`)}</option>)}
          </select>
        </label>
        <button type="button" className={s.btn} disabled={run.state === "busy" || problem !== null} onClick={() => void download()}>{t("download")}</button>
      </div>
      {problem && <p className={s.problem} role="alert">{t(problem === "open" ? "periodOpen" : "periodInvalid")}</p>}
      {run.state === "busy" && <p className={s.note} role="status">{t("preparing")}</p>}
      {run.state === "error" && <p className={s.problem} role="alert">{run.message}</p>}
      {run.state === "done" && (run.answer.reconciled
        ? <p className={s.note} role="status">{t("doneOk", { invoices: run.answer.invoices, notes: run.answer.creditNotes })}</p>
        : <p className={s.problem} role="alert">{t("doneNotReconciled")}</p>)}
      <p className={s.note}>{t("taxCheck")}</p>
      <Einvoices />
    </section>
  );
}

// The IRN chip list: shown only once the clinic has switched e-invoicing on (the list answers irpEnabled).
function Einvoices() {
  const t = useTranslations("shell.taxExports");
  const qc = useQueryClient();
  const listKey = queryKey("einvoices");
  const canView = can("billing:view");
  const q = useQuery({ queryKey: listKey, queryFn: ({ signal }) => api.get<EinvoiceList>("/billing/einvoices", { signal }), enabled: canView, staleTime: 15_000 });
  const settings = useQuery({ queryKey: queryKey("einvoice-settings"), queryFn: ({ signal }) => api.get<Settings>("/billing/einvoice/settings", { signal }), enabled: canView, staleTime: 60_000 });
  const retry = useMutation({
    mutationFn: (id: string) => api.post(`/billing/invoices/${encodeURIComponent(id)}/einvoice/retry`, {}),
    onSettled: () => void qc.invalidateQueries({ queryKey: listKey }),
  });
  const flip = useMutation({
    mutationFn: (irpEnabled: boolean) => api.put(`/billing/einvoice/settings`, { irpEnabled }),
    onSettled: () => void Promise.all([qc.invalidateQueries({ queryKey: listKey }), qc.invalidateQueries({ queryKey: queryKey("einvoice-settings") })]),
  });
  if (!canView) return null;
  const st = settings.data;
  // the owner's switch is offered only where an IRP adapter is deployed (the server answers 409 IRP_NOT_CONFIGURED otherwise)
  const canSwitch = isOwner() && can("billing:approve") && !!st && (st.adapterAvailable || st.irpEnabled);
  if (!q.data?.irpEnabled && !canSwitch && !q.isError) return null;

  return (
    <div className={s.card} aria-label={t("einvoiceTitle")}>
      <div className={s.deliv}>
        <p className={s.eyebrow}>{t("einvoiceTitle")}</p>
        {canSwitch && <button type="button" className={s.btn} disabled={flip.isPending} onClick={() => flip.mutate(!st.irpEnabled)}>{t(st.irpEnabled ? "switchOff" : "switchOn")}</button>}
      </div>
      {flip.isError && <p className={s.problem} role="alert">{t("switchFailed")}</p>}
      {q.isPending && <Skeleton rows={2} label={t("loading")} />}
      {q.isError && <ErrorState message={t("loadFailed")} onRetry={() => void q.refetch()} retryLabel={t("retry")} />}
      {q.data && q.data.irpEnabled && q.data.items.length === 0 && <EmptyState title={t("none")} />}
      {q.data && q.data.items.length > 0 && (
        <div className={s.tableWrap} role="region" aria-label={t("einvoiceTitle")} tabIndex={0}>
          <table className={s.table}>
            <thead><tr><th scope="col">{t("colBill")}</th><th scope="col">{t("colStatus")}</th><th scope="col">{t("colIrn")}</th>{can("billing:edit") && <th scope="col" />}</tr></thead>
            <tbody>
              {q.data.items.map((i) => {
                const c = chip(i.status);
                return (
                  <tr key={i.invoiceId}>
                    <td>{i.invoiceNumber}</td>
                    <td><span className={c.tone === "bad" ? x.bad : c.tone === "ok" ? x.ok : x.irnChip}>{t(c.key)}</span>{i.lastError && <span className={s.note}> {i.lastError}</span>}</td>
                    <td className={x.mono}>{i.irn ?? "—"}</td>
                    {can("billing:edit") && <td>{canRetry(i.status) && <button type="button" className={s.btn} disabled={retry.isPending} onClick={() => retry.mutate(i.invoiceId)}>{t("retry")}</button>}</td>}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {retry.isError && <p className={s.problem} role="alert">{t("retryFailed")}</p>}
    </div>
  );
}
