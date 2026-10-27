"use client";

// FEF-030: the two order forms of "Doctor orders" (vaccine / injection, procedure), split from DoctorOrders.tsx.
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { can } from "../../../lib/permissions";
import Dialog from "../../ui/Dialog";
import Icon from "../../ui/Icon";
import { Skeleton } from "../../ui/StateViews";
import type { AdminOrder, ChargeHead, ProcOrder } from "../doctorOrders";
import { ROUTES } from "../doctorOrders";
import type { AuthedFetch, Problem } from "../shared";
import s from "../consultParts.module.css";

async function problemText(res: Response, fallback: string): Promise<string> {
  const p: Problem = await res.json().catch(() => ({ title: "", detail: "" }));
  return p.detail || fallback;
}

export function AdminForm({ authedFetch, queueEntryId, onClose, onCreated }: {
  authedFetch: AuthedFetch; queueEntryId: string; onClose: () => void; onCreated: (o: AdminOrder) => void;
}) {
  const tx = useTranslations("consult.orderForms");
  const g = useTranslations("glossary");
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
      if (!res.ok) { setError(await problemText(res, tx("theOrderWasNotPlaced"))); return; }
      onCreated((await res.json()) as AdminOrder);
    } catch {
      setError(tx("couldnTReachTheServer"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={tx("orderVaccineOrInjection")} onClose={onClose}>
      <div className={s.stack}>
        <div className={s.field}>
          <label htmlFor="doDrug">{tx("itemAsYouWantIt")}</label>
          <input id="doDrug" className={s.input} value={f.drugName} onChange={(e) => setF({ ...f, drugName: e.target.value })} autoComplete="off" autoFocus />
        </div>
        <div className={s.formGrid}>
          <div className={s.field}>
            <label htmlFor="doDose">{g("rx.dose")}</label>
            <input id="doDose" className={s.input} value={f.dose} onChange={(e) => setF({ ...f, dose: e.target.value })} autoComplete="off" />
          </div>
          <div className={s.field}>
            <label htmlFor="doRoute">{g("rx.route")}</label>
            <select id="doRoute" className={s.input} value={f.route} onChange={(e) => setF({ ...f, route: e.target.value })}>
              <option value="">{tx("notSpecified")}</option>
              {ROUTES.map((r) => <option key={r} value={r}>{r === r.toUpperCase() ? r : g(`rx.routes.${r}`)}</option>)}
            </select>
          </div>
          <div className={s.field}>
            <label htmlFor="doSite">{tx("site")}</label>
            <input id="doSite" className={s.input} value={f.site} onChange={(e) => setF({ ...f, site: e.target.value })} autoComplete="off" />
          </div>
        </div>
        <p className={s.muted}>{tx("placingTheOrderDoesNot")}</p>
        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>{tx("cancel")}</button>
        <button type="button" className={s.btnPrimary} onClick={submit} disabled={busy || !ok}>{busy ? tx("placing") : tx("placeOrder")}</button>
      </div>
    </Dialog>
  );
}

export function ProcForm({ authedFetch, queueEntryId, onClose, onCreated }: {
  authedFetch: AuthedFetch; queueEntryId: string; onClose: () => void; onCreated: (o: ProcOrder) => void;
}) {
  const tx = useTranslations("consult.orderForms");
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
      if (!res.ok) { setError(await problemText(res, tx("theOrderWasNotPlaced"))); return; }
      onCreated((await res.json()) as ProcOrder);
    } catch {
      setError(tx("couldnTReachTheServer"));
    } finally {
      setBusy(false);
    }
  }

  const picker = charges && charges.length > 0;
  return (
    <Dialog title={tx("orderProcedure")} onClose={onClose}>
      <div className={s.stack}>
        <div className={s.field}>
          <label htmlFor="doCharge">{tx("procedureChargeCodeFromThe")}</label>
          {charges === null ? <Skeleton rows={1} label={tx("loadingProcedures")} /> : picker ? (
            <select id="doCharge" className={s.input} value={f.chargeCode} onChange={(e) => setF({ ...f, chargeCode: e.target.value })}>
              <option value="">{tx("chooseAProcedure")}</option>
              {charges.map((c) => <option key={c.code} value={c.code}>{c.name} ({c.code})</option>)}
            </select>
          ) : (
            <input id="doCharge" className={s.input} value={f.chargeCode} onChange={(e) => setF({ ...f, chargeCode: e.target.value })} autoComplete="off" />
          )}
          {!picker && charges !== null && <span className={s.muted}>{chargesError ? tx("couldnTLoadTheCatalogue") : tx("typeTheExactChargeCode")}</span>}
        </div>
        <div className={s.field}>
          <label htmlFor="doPrep">{tx("preparationNotesForTheNurse")}</label>
          <textarea id="doPrep" className={s.textarea} value={f.prepNotes} onChange={(e) => setF({ ...f, prepNotes: e.target.value })} />
        </div>
        <div className={s.field}>
          <label htmlFor="doConsent">{tx("consentNoteOptionalInYour")}</label>
          <textarea id="doConsent" className={s.textarea} value={f.consentNote} onChange={(e) => setF({ ...f, consentNote: e.target.value })} />
        </div>
        <div className={`${s.notice} ${s.noticeInfo}`}><Icon name="info" size={16} /><span>{tx("consentMustBeRecordedBefore")}</span></div>
        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>{tx("cancel")}</button>
        <button type="button" className={s.btnPrimary} onClick={submit} disabled={busy || !f.chargeCode.trim()}>{busy ? tx("placing") : tx("placeOrder")}</button>
      </div>
    </Dialog>
  );
}
