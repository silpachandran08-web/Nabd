"use client";

// FEF-030: the two order forms of "Doctor orders" (vaccine / injection, procedure), split from DoctorOrders.tsx.
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

export function ProcForm({ authedFetch, queueEntryId, onClose, onCreated }: {
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
