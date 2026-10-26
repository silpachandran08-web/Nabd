"use client";

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
        setRowError(p.detail || "Couldn't mark the procedure ready.");
        return;
      }
      replaceProc((await res.json()) as ProcOrder);
    } catch {
      setRowError("Couldn't reach the server. The procedure was not changed.");
    } finally {
      setReadyBusy(null);
    }
  }
  const replaceProc = (n: ProcOrder) => setProcs((cur) => (cur ?? []).map((x) => (x.id === n.id ? n : x)));

  const loading = mayList && (admin === null || procs === null) && !loadError;
  const empty = (admin?.length ?? 0) + (procs?.length ?? 0) === 0;

  return (
    <section className={s.card} aria-label="Doctor orders, nurse records">
      <h3 className={s.cardTitle}>
        Doctor orders, nurse records
        <StatusPill tone="info" icon="syringe" label="You order · nurse records" />
      </h3>
      <p className={s.muted}>The nurse cannot change what you order. Outcomes are recorded by nursing.</p>

      {mayCreate && (
        <div className={`${s.row} ${s.myM}`}>
          <button type="button" className={s.btn} onClick={() => setForm("admin")}><Icon name="syringe" size={14} /> Vaccine or injection</button>
          <button type="button" className={s.btn} onClick={() => setForm("proc")}><Icon name="plus" size={14} /> Procedure</button>
        </div>
      )}

      {loading && <Skeleton rows={2} label="Loading orders" />}
      {loadError && (
        <div className={`${s.notice} ${s.noticeDanger}`} role="alert">
          <Icon name="alert" size={16} />
          <span>Couldn&apos;t load this visit&apos;s orders. <button type="button" className={s.linkBtn} onClick={() => void load()}>Retry</button></span>
        </div>
      )}
      {!mayList && <p className={s.muted}>Your role can place orders but not list them, so only orders placed in this session appear below.</p>}
      {!loading && !loadError && empty && <p className={s.muted}>No orders for this visit.</p>}

      {(admin?.length ?? 0) > 0 && (
        <ul className={s.list} aria-label="Vaccine and injection orders">
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
        <ul className={`${s.list} ${s.mtM}`} aria-label="Procedure orders">
          {procs!.map((o) => (
            <li key={o.id} className={s.orderRow}>
              <div className={s.listRow}>
                <strong>{o.chargeName}</strong>
                <StatusPill tone={o.status === "completed" ? "ok" : o.status === "cancelled" ? "neutral" : o.status === "prepped" ? "info" : "warn"} label={procStatusLabel(o.status)} />
              </div>
              {o.status !== "cancelled" && (hasConsent(o)
                ? <StatusPill tone="ok" label={`Consent recorded${o.consentSignedName ? ` · ${o.consentSignedName}` : ""}`} />
                : <StatusPill tone="danger" label="Consent required before performance" />)}
              {hasConsent(o) && o.consentRecordedByName && <span className={s.muted}>Recorded by {o.consentRecordedByName}</span>}
              {o.status === "ordered" && !hasConsent(o) && (
                mayEdit
                  ? <div><button type="button" className={s.btn} onClick={() => setConsentFor(o)}>Record consent</button></div>
                  : <span className={s.muted}>Consent is recorded by nursing before the procedure can be marked ready.</span>
              )}
              {o.status === "ordered" && hasConsent(o) && mayEdit && (
                <div><button type="button" className={s.btn} disabled={readyBusy === o.id} onClick={() => void markReady(o)}>{readyBusy === o.id ? "Saving…" : "Mark ready"}</button></div>
              )}
            </li>
          ))}
        </ul>
      )}
      {rowError && <div className={`${s.notice} ${s.noticeDanger} ${s.mtS}`} role="alert"><Icon name="alert" size={16} /><span>{rowError}</span></div>}

      <ul className={`${s.list} ${s.mtM}`} aria-label="Not available yet">
        <li className={s.listRow}><strong>External diagnostic request</strong><StatusPill tone="neutral" icon="clock" label="Not available yet" /></li>
        <li className={s.listRow}><strong>Clinical photo consent</strong><StatusPill tone="neutral" icon="clock" label="Not available yet" /></li>
        <li className={s.listRow}><strong>Package consent</strong><StatusPill tone="neutral" icon="clock" label="Not available yet" /></li>
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
