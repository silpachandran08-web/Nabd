"use client";

// FIN-015 per-doctor consultation fees + follow-up window, FIN-016 price change log (filters + CSV). Editing needs setup:edit and is absent without it.
import { useCallback, useEffect, useState } from "react";
import styles from "../../(clinic)/setup/setup.module.css";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { api, reasonText } from "../../lib/api";
import { windowError } from "../../lib/pricingTier";

type Charge = { id: string; name: string; category: string };
type Policy = { policyKey: string; value: string };
type Fee = { doctorId: string; doctorName: string; amount: number | null };
type Doctor = { id: string; name: string };
type Change = { id: string; chargeName: string; field: string; oldValue: string | null; newValue: string | null; actor: string; at: string };
type Page = { items: Change[]; nextCursor: string | null };

const FIELDS: [string, string][] = [["", "Any field"], ["base_amount", "Base price"], ["follow_up_amount", "Follow-up price"], ["emergency_amount", "Emergency price"], ["doctor_fee", "Doctor fee"], ["tax_rate", "Tax rate"], ["active", "Active"]];

function FollowUpWindow({ policies }: { policies: Policy[] }) {
  const [current, setCurrent] = useState(policies.find((p) => p.policyKey === "follow_up_window_days")?.value ?? "7");
  const [v, setV] = useState(current);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const err = windowError(v);
  async function save() {
    if (err) return;
    setBusy(true); setMsg(null);
    try { await api.put("/setup/policies/follow_up_window_days", { value: Number(v) }); setCurrent(String(Number(v))); setMsg("Saved."); }
    catch (e) { setMsg(reasonText(e, "Could not save the window.")); }
    setBusy(false);
  }
  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Follow-up window</h2>
      <p className={styles.hint}>A patient seen by the same doctor within this many days of a full-price visit gets the follow-up price.</p>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="fu-window">Days (1 to 90)</label>
        <input id="fu-window" className={styles.input} inputMode="numeric" value={v} readOnly={!can("setup:edit")} onChange={(e) => setV(e.target.value)} aria-invalid={!!err} />
        {err && <div role="alert" className={styles.hint}>{err}</div>}
      </div>
      {can("setup:edit") && <div className={styles.actions}><button className={styles.btnPrimary} style={{ minBlockSize: 44 }} onClick={() => void save()} disabled={busy || !!err || v === current}>Save window</button></div>}
      {msg && <div role="status" className={styles.hint}>{msg}</div>}
    </div>
  );
}

function DoctorFees({ charges }: { charges: Charge[] }) {
  const heads = charges.filter((c) => c.category === "Consultation");
  const [chargeId, setChargeId] = useState(heads[0]?.id ?? "");
  const [fees, setFees] = useState<Record<string, string> | null>(null);
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const edit = can("setup:edit");

  async function load(id: string) {
    if (!id) return;
    setFees(null); setError(null); setMsg(null);
    try {
      const [f, roster] = await Promise.all([api.get<{ fees: Fee[] }>(`/setup/charges/${id}/doctor-fees`), api.get<Doctor[]>("/staff/roster")]);
      setDoctors(roster); setFees(Object.fromEntries(f.fees.map((x) => [x.doctorId, x.amount == null ? "" : String(x.amount)])));
    } catch (e) { setError(reasonText(e, "Could not load the doctor fees.")); }
  }
  // eslint-disable-next-line react-hooks/set-state-in-effect -- load on charge change; state is set after the awaited fetch
  useEffect(() => { void load(chargeId); }, [chargeId]);

  if (heads.length === 0) return <div className={styles.card}><h2 className={styles.cardTitle}>Doctor fees</h2><p className={styles.hint}>Add a Consultation charge to set per-doctor fees.</p></div>;

  async function save() {
    if (!fees) return;
    const bad = Object.values(fees).some((a) => a !== "" && !/^\d{1,10}(\.\d{1,2})?$/.test(a));
    if (bad) { setMsg("Use 0 or more with up to two decimals, or leave blank for the base price."); return; }
    try {
      await api.put(`/setup/charges/${chargeId}/doctor-fees`, { fees: Object.entries(fees).map(([doctorId, a]) => ({ doctorId, amount: a === "" ? null : Number(a) })) });
      setMsg("Saved."); void load(chargeId);
    } catch (e) { setMsg(reasonText(e, "Could not save the fees.")); }
  }

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Doctor fees</h2>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="df-charge">Consultation charge</label>
        <select id="df-charge" className={styles.select} value={chargeId} onChange={(e) => setChargeId(e.target.value)}>{heads.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select>
      </div>
      {error ? <ErrorState message={error} onRetry={() => void load(chargeId)} /> : !fees ? <Skeleton rows={3} label="Loading doctor fees" /> : doctors.length === 0 ? <p className={styles.hint}>No doctors on the roster yet.</p> : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead><tr><th scope="col">Doctor</th><th scope="col">Fee (blank = base price)</th></tr></thead>
            <tbody>{doctors.map((d) => (
              <tr key={d.id}><td>{d.name}</td><td>
                <input className={styles.input} aria-label={`Fee for ${d.name}`} inputMode="decimal" readOnly={!edit} value={fees[d.id] ?? ""} onChange={(e) => setFees({ ...fees, [d.id]: e.target.value })} />
              </td></tr>
            ))}</tbody>
          </table>
        </div>
      )}
      {edit && fees && <div className={styles.actions}><button className={styles.btnPrimary} style={{ minBlockSize: 44 }} onClick={() => void save()}>Save fees</button></div>}
      {msg && <div role="status" className={styles.hint}>{msg}</div>}
    </div>
  );
}

function PriceChangeLog({ charges }: { charges: Charge[] }) {
  const [q, setQ] = useState({ chargeId: "", field: "", from: "", to: "" });
  const [rows, setRows] = useState<Change[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const qs = (cursor?: string | null) => { const p = new URLSearchParams(); for (const [k, v] of Object.entries(q)) if (v) p.set(k, v); if (cursor) p.set("cursor", cursor); return p.toString(); };

  const load = useCallback(async (cursor?: string | null) => {
    if (!cursor) setRows(null);
    setError(null);
    try {
      const p = await api.get<Page>(`/setup/charges/changes?${qs(cursor)}`);
      setRows((prev) => (cursor ? [...(prev ?? []), ...p.items] : p.items)); setNext(p.nextCursor);
    } catch (e) { setError(reasonText(e, "Could not load the price changes.")); }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- qs reads q; reload is explicit via Apply
  }, [q]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load
  useEffect(() => { void load(); }, []);

  async function exportCsv() {
    try {
      const csv = await api.get<string>(`/setup/charges/changes/export?${qs()}`, { as: "text" });
      const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
      const a = document.createElement("a"); a.href = url; a.download = "price-changes.csv"; a.click(); URL.revokeObjectURL(url);
    } catch (e) { setError(reasonText(e, "Could not export the price changes.")); }
  }

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Price change log</h2>
      <div className={styles.row}>
        <div className={styles.field}><label className={styles.label} htmlFor="pc-charge">Charge</label>
          <select id="pc-charge" className={styles.select} value={q.chargeId} onChange={(e) => setQ({ ...q, chargeId: e.target.value })}><option value="">All charges</option>{charges.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></div>
        <div className={styles.field}><label className={styles.label} htmlFor="pc-field">Field</label>
          <select id="pc-field" className={styles.select} value={q.field} onChange={(e) => setQ({ ...q, field: e.target.value })}>{FIELDS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></div>
        <div className={styles.field}><label className={styles.label} htmlFor="pc-from">From</label><input id="pc-from" type="date" className={styles.input} value={q.from} onChange={(e) => setQ({ ...q, from: e.target.value })} /></div>
        <div className={styles.field}><label className={styles.label} htmlFor="pc-to">To</label><input id="pc-to" type="date" className={styles.input} value={q.to} onChange={(e) => setQ({ ...q, to: e.target.value })} /></div>
      </div>
      <div className={styles.actions}>
        <button className={styles.btn} style={{ minBlockSize: 44 }} onClick={() => void load()}>Apply filters</button>
        <button className={styles.btn} style={{ minBlockSize: 44 }} onClick={() => void exportCsv()}>Export CSV</button>
      </div>
      {error ? <ErrorState message={error} onRetry={() => void load()} /> : !rows ? <Skeleton rows={4} label="Loading price changes" /> : rows.length === 0 ? <p className={styles.hint}>No price changes match.</p> : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead><tr><th scope="col">When</th><th scope="col">Charge</th><th scope="col">Field</th><th scope="col">From</th><th scope="col">To</th><th scope="col">By</th></tr></thead>
            <tbody>{rows.map((r) => <tr key={r.id}><td>{new Date(r.at).toLocaleString()}</td><td>{r.chargeName}</td><td>{r.field.replace(/_/g, " ")}</td><td>{r.oldValue ?? "—"}</td><td>{r.newValue ?? "—"}</td><td>{r.actor}</td></tr>)}</tbody>
          </table>
        </div>
      )}
      {next && <div className={styles.actions}><button className={styles.btn} style={{ minBlockSize: 44 }} onClick={() => void load(next)}>Load more</button></div>}
    </div>
  );
}

export default function PricingPanel({ charges, policies }: { charges: Charge[]; policies: Policy[] }) {
  return (<><FollowUpWindow policies={policies} /><DoctorFees charges={charges} /><PriceChangeLog charges={charges} /></>);
}
