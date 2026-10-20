"use client";

import { useCallback, useEffect, useState } from "react";
import { can } from "../../lib/permissions";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import { adminStatus, hasConsent, procStatusLabel, ROUTES, type AdminOrder, type ChargeHead, type ProcOrder } from "./doctorOrders";
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
        <div className={s.row} style={{ marginBlock: "var(--nb-space-12)" }}>
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
        <ul className={s.list} aria-label="Procedure orders" style={{ marginBlockStart: "var(--nb-space-12)" }}>
          {procs!.map((o) => (
            <li key={o.id} style={{ display: "flex", flexDirection: "column", alignItems: "flex-start", gap: "var(--nb-space-4)" }}>
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
      {rowError && <div className={`${s.notice} ${s.noticeDanger}`} role="alert" style={{ marginBlockStart: "var(--nb-space-8)" }}><Icon name="alert" size={16} /><span>{rowError}</span></div>}

      <ul className={s.list} aria-label="Not available yet" style={{ marginBlockStart: "var(--nb-space-12)" }}>
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

async function problemText(res: Response, fallback: string): Promise<string> {
  const p: Problem = await res.json().catch(() => ({ title: "", detail: "" }));
  return p.detail || fallback;
}

function AdminForm({ authedFetch, queueEntryId, onClose, onCreated }: {
  authedFetch: AuthedFetch; queueEntryId: string; onClose: () => void; onCreated: (o: AdminOrder) => void;
}) {
  const [f, setF] = useState({ drugName: "", dose: "", route: "", site: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ok = f.drugName.trim() && f.dose.trim();

  async function submit() {
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      const body = { queueEntryId, drugName: f.drugName.trim(), dose: f.dose.trim(), ...(f.route ? { route: f.route } : {}), ...(f.site.trim() ? { site: f.site.trim() } : {}) };
      const res = await authedFetch("/nursing/administration-orders", { method: "POST", body: JSON.stringify(body) });
      if (!res) return;
      if (!res.ok) { setError(await problemText(res, "The order was not placed. Try again.")); return; }
      onCreated((await res.json()) as AdminOrder);
    } catch {
      setError("Couldn't reach the server. The order was not placed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title="Order vaccine or injection" onClose={onClose}>
      <div className={s.stack}>
        <div className={s.field}>
          <label htmlFor="doDrug">Item (as you want it given)</label>
          <input id="doDrug" className={s.input} value={f.drugName} onChange={(e) => setF({ ...f, drugName: e.target.value })} autoComplete="off" autoFocus />
        </div>
        <div className={s.formGrid}>
          <div className={s.field}>
            <label htmlFor="doDose">Dose</label>
            <input id="doDose" className={s.input} value={f.dose} onChange={(e) => setF({ ...f, dose: e.target.value })} autoComplete="off" />
          </div>
          <div className={s.field}>
            <label htmlFor="doRoute">Route</label>
            <select id="doRoute" className={s.input} value={f.route} onChange={(e) => setF({ ...f, route: e.target.value })}>
              <option value="">Not specified</option>
              {ROUTES.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
          </div>
          <div className={s.field}>
            <label htmlFor="doSite">Site</label>
            <input id="doSite" className={s.input} value={f.site} onChange={(e) => setF({ ...f, site: e.target.value })} autoComplete="off" />
          </div>
        </div>
        <p className={s.muted}>Placing the order does not record that it was given. The nurse records that, and a difference from this order is flagged.</p>
        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>Cancel</button>
        <button type="button" className={s.btnPrimary} onClick={submit} disabled={busy || !ok}>{busy ? "Placing…" : "Place order"}</button>
      </div>
    </Dialog>
  );
}

function ProcForm({ authedFetch, queueEntryId, onClose, onCreated }: {
  authedFetch: AuthedFetch; queueEntryId: string; onClose: () => void; onCreated: (o: ProcOrder) => void;
}) {
  const maySetup = can("setup:view");
  const [charges, setCharges] = useState<ChargeHead[] | null>(maySetup ? null : []);
  const [chargesError, setChargesError] = useState(false);
  const [f, setF] = useState({ chargeCode: "", prepNotes: "", consentNote: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!maySetup) return;
    let cancelled = false;
    void Promise.resolve().then(async () => {
      try {
        const res = await authedFetch("/setup/charges");
        if (cancelled || !res) return;
        if (!res.ok) { setChargesError(true); setCharges([]); return; }
        setCharges(((await res.json()) as ChargeHead[]).filter((c) => c.active));
      } catch {
        if (!cancelled) { setChargesError(true); setCharges([]); }
      }
    });
    return () => { cancelled = true; };
  }, [authedFetch, maySetup]);

  async function submit() {
    if (!f.chargeCode.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const body = { queueEntryId, chargeCode: f.chargeCode.trim(), ...(f.prepNotes.trim() ? { prepNotes: f.prepNotes.trim() } : {}), ...(f.consentNote.trim() ? { consentNote: f.consentNote.trim() } : {}) };
      const res = await authedFetch("/nursing/procedure-orders", { method: "POST", body: JSON.stringify(body) });
      if (!res) return;
      if (!res.ok) { setError(await problemText(res, "The order was not placed. Try again.")); return; }
      onCreated((await res.json()) as ProcOrder);
    } catch {
      setError("Couldn't reach the server. The order was not placed.");
    } finally {
      setBusy(false);
    }
  }

  const picker = charges && charges.length > 0;
  return (
    <Dialog title="Order procedure" onClose={onClose}>
      <div className={s.stack}>
        <div className={s.field}>
          <label htmlFor="doCharge">Procedure (charge code from the clinic catalogue)</label>
          {charges === null ? <Skeleton rows={1} label="Loading procedures" /> : picker ? (
            <select id="doCharge" className={s.input} value={f.chargeCode} onChange={(e) => setF({ ...f, chargeCode: e.target.value })}>
              <option value="">Choose a procedure</option>
              {charges.map((c) => <option key={c.code} value={c.code}>{c.name} ({c.code})</option>)}
            </select>
          ) : (
            <input id="doCharge" className={s.input} value={f.chargeCode} onChange={(e) => setF({ ...f, chargeCode: e.target.value })} autoComplete="off" />
          )}
          {!picker && charges !== null && <span className={s.muted}>{chargesError ? "Couldn't load the catalogue; type the exact charge code." : "Type the exact charge code from Clinic Setup → Charges."}</span>}
        </div>
        <div className={s.field}>
          <label htmlFor="doPrep">Preparation notes for the nurse (optional)</label>
          <textarea id="doPrep" className={s.textarea} value={f.prepNotes} onChange={(e) => setF({ ...f, prepNotes: e.target.value })} />
        </div>
        <div className={s.field}>
          <label htmlFor="doConsent">Consent note (optional, in your own words)</label>
          <textarea id="doConsent" className={s.textarea} value={f.consentNote} onChange={(e) => setF({ ...f, consentNote: e.target.value })} />
        </div>
        <div className={`${s.notice} ${s.noticeInfo}`}><Icon name="info" size={16} /><span>Consent must be recorded before this procedure can be marked ready or completed.</span></div>
        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>Cancel</button>
        <button type="button" className={s.btnPrimary} onClick={submit} disabled={busy || !f.chargeCode.trim()}>{busy ? "Placing…" : "Place order"}</button>
      </div>
    </Dialog>
  );
}
