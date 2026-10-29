"use client";

import { useTranslations } from "next-intl";
import { useCallback, useEffect, useState } from "react";
import { can } from "../../lib/permissions";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import { adminStatus, hasConsent, procStatusLabel, ROUTES, type AdminOrder, type ChargeHead, type ProcOrder } from "./doctorOrders";
import { AdminForm, ProcForm } from "./orders/OrderForms";
import ProcedureConsent from "./ProcedureConsent";
import type { AuthedFetch, Problem } from "./shared";
import s from "./consultParts.module.css";

// DOC-039 / DOC-040: "Doctor orders, nurse records". The doctor creates the order through the same
// endpoints the nursing worklist reads; the nurse records the outcome there. Permissions follow
// NursingController: create = nursing:create, list = nursing:view, consent/ready = nursing:edit.
// No permission, no control (absent, not disabled).
export default function DoctorOrders({ authedFetch, queueEntryId }: { authedFetch: AuthedFetch; queueEntryId: string }) {
  const tx = useTranslations("consult.doctorOrders");
  const mayCreate = can("nursing:create");
  const mayList = can("nursing:view");
  const mayEdit = can("nursing:edit");
  const [admin, setAdmin] = useState<AdminOrder[] | null>(null);
  const [procs, setProcs] = useState<ProcOrder[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [form, setForm] = useState<"admin" | "proc" | null>(null);
  const [consentFor, setConsentFor] = useState<ProcOrder | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);
  const [readyBusy, setReadyBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoadError(false);
    try {
      const [a, p] = await Promise.all([authedFetch("/nursing/administration-orders/today"), authedFetch("/nursing/procedure-orders/today")]);
      if (!a || !p) return;
      if (!a.ok || !p.ok) { setLoadError(true); return; }
      setAdmin(((await a.json()) as AdminOrder[]).filter((o) => o.queueEntryId === queueEntryId));
      setProcs(((await p.json()) as ProcOrder[]).filter((o) => o.queueEntryId === queueEntryId));
    } catch {
      setLoadError(true);
    }
  }, [authedFetch, queueEntryId]);

  useEffect(() => {
    // The worklists are clinic-wide, so a different visit starts from a clean slate.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setAdmin(mayList ? null : []);
    setProcs(mayList ? null : []);
    setRowError(null);
    if (mayList) void load();
  }, [load, mayList]);

  if (!mayCreate && !mayList) return null;

  async function markReady(o: ProcOrder) {
    setRowError(null);
    setReadyBusy(o.id);
    try {
      const res = await authedFetch(`/nursing/procedure-orders/${o.id}/status`, { method: "PATCH", body: JSON.stringify({ status: "prepped" }) });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "" }));
        setRowError(p.detail || tx("couldnTMarkTheProcedure"));
        return;
      }
      replaceProc((await res.json()) as ProcOrder);
    } catch {
      setRowError(tx("couldnTReachTheServer"));
    } finally {
      setReadyBusy(null);
    }
  }
  const replaceProc = (n: ProcOrder) => setProcs((cur) => (cur ?? []).map((x) => (x.id === n.id ? n : x)));

  const loading = mayList && (admin === null || procs === null) && !loadError;
  const empty = (admin?.length ?? 0) + (procs?.length ?? 0) === 0;

  return (
    <section className={s.card} aria-label={tx("doctorOrdersNurseRecords")}>
      <h3 className={s.cardTitle}>
        {tx("doctorOrdersNurseRecords")}
        <StatusPill tone="info" icon="syringe" label={tx("youOrderNurseRecords")} />
      </h3>
      <p className={s.muted}>{tx("theNurseCannotChangeWhat")}</p>

      {mayCreate && (
        <div className={`${s.row} ${s.myM}`}>
          <button type="button" className={s.btn} onClick={() => setForm("admin")}><Icon name="syringe" size={14} /> {" "}{tx("vaccineOrInjection")}</button>
          <button type="button" className={s.btn} onClick={() => setForm("proc")}><Icon name="plus" size={14} /> {" "}{tx("procedure")}</button>
        </div>
      )}

      {loading && <Skeleton rows={2} label={tx("loadingOrders")} />}
      {loadError && (
        <div className={`${s.notice} ${s.noticeDanger}`} role="alert">
          <Icon name="alert" size={16} />
          <span>{tx("couldnTLoadThisVisit")}{" "}<button type="button" className={s.linkBtn} onClick={() => void load()}>{tx("retry")}</button></span>
        </div>
      )}
      {!mayList && <p className={s.muted}>{tx("yourRoleCanPlaceOrders")}</p>}
      {!loading && !loadError && empty && <p className={s.muted}>{tx("noOrdersForThisVisit")}</p>}

      {(admin?.length ?? 0) > 0 && (
        <ul className={s.list} aria-label={tx("vaccineAndInjectionOrders")}>
          {admin!.map((o) => {
            const st = adminStatus(o);
            return (
              <li key={o.id} className={s.listRow}>
                <strong>{o.drugName}</strong>
                <span className={s.muted}>{[o.dose, o.route, o.site].filter(Boolean).join(" · ")}</span>
                <StatusPill tone={st.tone} label={st.label} />
              </li>
            );
          })}
        </ul>
      )}

      {(procs?.length ?? 0) > 0 && (
        <ul className={`${s.list} ${s.mtM}`} aria-label={tx("procedureOrders")}>
          {procs!.map((o) => (
            <li key={o.id} className={s.orderRow}>
              <div className={s.listRow}>
                <strong>{o.chargeName}</strong>
                <StatusPill tone={o.status === "completed" ? "ok" : o.status === "cancelled" ? "neutral" : o.status === "prepped" ? "info" : "warn"} label={procStatusLabel(o.status)} />
              </div>
              {o.status !== "cancelled" && (hasConsent(o)
                ? <StatusPill tone="ok" label={o.consentSignedName ? tx("consentRecordedBy", { name: o.consentSignedName }) : tx("consentRecorded")} />
                : <StatusPill tone="danger" label={tx("consentRequiredBeforePerformance")} />)}
              {hasConsent(o) && o.consentRecordedByName && <span className={s.muted}>{tx("recordedBy", { consentRecordedByName: o.consentRecordedByName })}</span>}
              {o.status === "ordered" && !hasConsent(o) && (
                mayEdit
                  ? <div><button type="button" className={s.btn} onClick={() => setConsentFor(o)}>{tx("recordConsent")}</button></div>
                  : <span className={s.muted}>{tx("consentIsRecordedByNursing")}</span>
              )}
              {o.status === "ordered" && hasConsent(o) && mayEdit && (
                <div><button type="button" className={s.btn} disabled={readyBusy === o.id} onClick={() => void markReady(o)}>{readyBusy === o.id ? tx("saving") : tx("markReady")}</button></div>
              )}
            </li>
          ))}
        </ul>
      )}
      {rowError && <div className={`${s.notice} ${s.noticeDanger} ${s.mtS}`} role="alert"><Icon name="alert" size={16} /><span>{rowError}</span></div>}

      <ul className={`${s.list} ${s.mtM}`} aria-label={tx("notAvailableYet")}>
        <li className={s.listRow}><strong>{tx("externalDiagnosticRequest")}</strong><StatusPill tone="neutral" icon="clock" label={tx("notAvailableYet")} /></li>
        <li className={s.listRow}><strong>{tx("clinicalPhotoConsent")}</strong><StatusPill tone="neutral" icon="clock" label={tx("notAvailableYet")} /></li>
        <li className={s.listRow}><strong>{tx("packageConsent")}</strong><StatusPill tone="neutral" icon="clock" label={tx("notAvailableYet")} /></li>
      </ul>

      {form === "admin" && (
        <AdminForm authedFetch={authedFetch} queueEntryId={queueEntryId} onClose={() => setForm(null)}
          onCreated={(o) => { setForm(null); setAdmin((c) => [...(c ?? []), o]); if (mayList) void load(); }} />
      )}
      {form === "proc" && (
        <ProcForm authedFetch={authedFetch} queueEntryId={queueEntryId} onClose={() => setForm(null)}
          onCreated={(o) => { setForm(null); setProcs((c) => [...(c ?? []), o]); if (mayList) void load(); }} />
      )}
      {consentFor && (
        <ProcedureConsent authedFetch={authedFetch} order={consentFor} onClose={() => setConsentFor(null)}
          onRecorded={(o) => { setConsentFor(null); replaceProc(o); }} />
      )}
    </section>
  );
}
