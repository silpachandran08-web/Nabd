"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import styles from "../../(clinic)/nursing/nursing.module.css";
import type { QueueEntry } from "@nabd/domain";

// NUR-B2. Record-only vaccine log. There is NO immunisation schedule anywhere in the product, so
// there is no "due list": nothing here computes, suggests or implies what a patient is due for.
// The vaccine name is free text typed by the nurse.

export type VaccineRecord = {
  id: string; patientId: string; patientName: string; queueEntryId?: string | null; vaccineName: string; dose: string;
  route: string; site?: string | null; lotNumber?: string | null; expiryDate?: string | null; notes?: string | null;
  recordedByName: string; administeredAt: string;
};
export type VaccineVisit = { patientId: string; queueEntryId: string; patientName: string } & Pick<QueueEntry, "tokenNumber">;
type Fetcher = (path: string, init?: RequestInit) => Promise<Response | null>;

const ROUTES = ["IM", "SC", "oral", "IV", "infusion", "topical"] as const;
const hhmm = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

export function RecordVaccineDialog({ authedFetch, visits, onClose, onRecorded }: {
  authedFetch: Fetcher; visits: VaccineVisit[]; onClose: () => void; onRecorded: () => void;
}) {
  const [visitId, setVisitId] = useState("");
  const [name, setName] = useState("");
  const [dose, setDose] = useState("");
  const [route, setRoute] = useState<string>("IM");
  const [site, setSite] = useState("");
  const [lot, setLot] = useState("");
  const [expiry, setExpiry] = useState("");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const visit = visits.find((v) => v.queueEntryId === visitId);
  const valid = !!visit && name.trim() !== "" && dose.trim() !== "";

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!visit || !valid || busy) return;
    setBusy(true);
    setErr(null);
    try {
      const res = await authedFetch("/nursing/vaccine-records", {
        method: "POST",
        body: JSON.stringify({
          patientId: visit.patientId, queueEntryId: visit.queueEntryId, vaccineName: name.trim(), dose: dose.trim(), route,
          site: site.trim() || null, lotNumber: lot.trim() || null, expiryDate: expiry || null, notes: notes.trim() || null,
        }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: { detail?: string } = await res.json().catch(() => ({}));
        setErr(p.detail || "Couldn't save this vaccine record.");
        return;
      }
      onRecorded();
    } catch {
      setErr("Couldn't reach the server. Nothing was saved. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title="Record vaccine" onClose={() => { if (!busy) onClose(); }}>
      <form onSubmit={submit}>
        <p className={styles.muted}>Records a vaccine that has already been given. Type the details as on the vial and the order.</p>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="vxPatient">Patient (today&apos;s visits)</label>
          <select id="vxPatient" className={styles.select} value={visitId} onChange={(e) => setVisitId(e.target.value)}>
            <option value="">Select a patient…</option>
            {visits.map((v) => <option key={v.queueEntryId} value={v.queueEntryId}>{v.patientName} · T-{v.tokenNumber}</option>)}
          </select>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="vxName">Vaccine</label>
          <input id="vxName" className={styles.input} value={name} maxLength={200} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className={styles.fieldGrid}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="vxDose">Dose given</label>
            <input id="vxDose" className={styles.input} value={dose} maxLength={100} onChange={(e) => setDose(e.target.value)} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="vxRoute">Route</label>
            <select id="vxRoute" className={styles.select} value={route} onChange={(e) => setRoute(e.target.value)}>
              {ROUTES.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="vxSite">Site (optional)</label>
            <input id="vxSite" className={styles.input} value={site} maxLength={100} onChange={(e) => setSite(e.target.value)} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="vxLot">Lot number (optional)</label>
            <input id="vxLot" className={styles.input} value={lot} maxLength={100} onChange={(e) => setLot(e.target.value)} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="vxExpiry">Expiry date (optional)</label>
            <input id="vxExpiry" type="date" className={styles.input} value={expiry} onChange={(e) => setExpiry(e.target.value)} />
          </div>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="vxNotes">Notes (optional)</label>
          <input id="vxNotes" className={styles.input} value={notes} maxLength={1000} onChange={(e) => setNotes(e.target.value)} />
        </div>
        {err && <div className={styles.formError} role="alert">{err}</div>}
        <div className={styles.modalActions}>
          <button type="button" className={styles.cancelBtn} onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className={styles.submitBtn} disabled={!valid || busy}>{busy ? "Saving…" : "Save record"}</button>
        </div>
      </form>
    </Dialog>
  );
}

export default function VaccinePanel({ authedFetch, reloadKey }: { authedFetch: Fetcher; reloadKey: number }) {
  const [rows, setRows] = useState<VaccineRecord[] | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    try {
      const res = await authedFetch("/nursing/vaccine-records/today");
      if (!res) return;
      if (!res.ok) { setFailed(true); return; }
      setRows(await res.json());
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, [authedFetch]);

  useEffect(() => { void Promise.resolve().then(load); }, [load, reloadKey]);

  return (
    <div className={styles.layout}>
      <div className={styles.card}>
        {rows === null && !failed && <Skeleton rows={4} label="Loading vaccines recorded today" />}
        {rows === null && failed && <ErrorState message="Couldn't load today's vaccine records." onRetry={() => { setFailed(false); void load(); }} />}
        {rows !== null && failed && (
          <div className={styles.banner} role="alert"><Icon name="alert" size={16} />Couldn&apos;t refresh. Showing the last loaded records.
            <button type="button" className={styles.smallBtn} onClick={() => void load()}>Retry</button></div>
        )}
        {rows !== null && rows.length === 0 && <EmptyState icon="syringe" title="No vaccines recorded today" hint="Vaccines you record appear here." />}
        {rows !== null && rows.length > 0 && (
          <table className={styles.table}>
            <thead><tr><th scope="col">Time</th><th scope="col">Patient</th><th scope="col">Vaccine</th><th scope="col">Dose · route</th><th scope="col">Lot</th><th scope="col">Recorded by</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td data-label="Time">{hhmm(r.administeredAt)}</td>
                  <td data-label="Patient"><span className={styles.patientName}>{r.patientName}</span></td>
                  <td data-label="Vaccine">{r.vaccineName}</td>
                  <td data-label="Dose · route">{[r.dose, r.route, r.site].filter(Boolean).join(" · ")}</td>
                  <td data-label="Lot">{r.lotNumber || "—"}{r.expiryDate ? ` (exp ${r.expiryDate})` : ""}</td>
                  <td data-label="Recorded by">{r.recordedByName}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <div className={styles.card}>
        <div className={styles.sideTitle}><Icon name="syringe" size={14} />Vaccines due</div>
        <div className={styles.sideState}>Not available yet. No immunisation schedule is set up, so nothing is shown as due.</div>
      </div>
    </div>
  );
}
