"use client";

// ADM-032: the licence register sorted by days left, with the reminder thresholds already sent, the "hidden from booking" state of a
// lapsed clinician and, for the owner (setup:edit + a fresh authenticator code), a time-boxed booking override with a reason.
import { useState } from "react";
import { daysLeft, remindersText } from "@nabd/domain";
import styles from "../../(clinic)/setup/setup.module.css";
import { can } from "../../lib/permissions";
import { ApiError, api } from "../../lib/api";
import { requestStepUpToken } from "../../lib/stepUp";

export type LicenceRow = {
  id: string; licenceType: string; holderId: string | null; holderName: string | null; number: string; issuingBody: string | null;
  expiryDate: string; region: string; status: string; bookable?: boolean | null; remindersSent?: number[] | null; nextReminder?: number | null;
  overrideUntil?: string | null;
};

const dayOf = (iso: string) => iso.slice(0, 10);

export default function LicenceTable({ licences, today, onChanged }: { licences: LicenceRow[]; today: string; onChanged: () => void }) {
  const [open, setOpen] = useState<string | null>(null);
  const [days, setDays] = useState("7");
  const [reason, setReason] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [fail, setFail] = useState<string | null>(null);
  const mayOverride = can("setup:edit");
  const rows = [...licences].sort((a, b) => a.expiryDate.localeCompare(b.expiryDate));

  async function apply(l: LicenceRow) {
    setBusy(true); setFail(null);
    const su = await requestStepUpToken(code);
    if ("error" in su) { setFail(su.error); setBusy(false); return; }
    const until = new Date(Date.parse(`${today}T00:00:00Z`) + Number(days) * 86_400_000).toISOString().slice(0, 10);
    try {
      await api.post(`/setup/licences/${l.id}/override`, { until, reason: reason.trim() }, { headers: { "X-Step-Up-Token": su.token } });
      setOpen(null); setReason(""); setCode(""); onChanged();
    } catch (e) {
      setFail(!(e instanceof ApiError) ? "Could not reach the server. Check your connection."
        : e.status === 422 ? "An override can last at most 30 days." : e.status === 403 ? "Only the owner can apply an override." : "Could not save the override.");
    }
    setBusy(false);
  }

  if (rows.length === 0) return <p>No licences registered yet.</p>;
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead>
          <tr><th scope="col">Type</th><th scope="col">Holder</th><th scope="col">Number</th><th scope="col">Issuing body</th><th scope="col">Expiry</th><th scope="col">Days left</th><th scope="col">Reminders</th><th scope="col">Status</th></tr>
        </thead>
        <tbody>
          {rows.map((l) => {
            const left = daysLeft(l.expiryDate, today);
            const overridden = !!l.overrideUntil && dayOf(l.overrideUntil) >= today;
            return (
              <tr key={l.id}>
                <td>{l.licenceType}</td>
                <td>{l.holderName ?? "—"}</td>
                <td>{l.number}</td>
                <td>{l.issuingBody ?? "—"}</td>
                <td>{l.expiryDate}</td>
                <td>{left < 0 ? `${-left} days ago` : left}</td>
                <td>{remindersText(l.remindersSent) || "—"}</td>
                <td>
                  <span className={`${styles.pill} ${l.status === "valid" ? styles.pillValid : l.status === "expiring_soon" ? styles.pillExpiring : styles.pillExpired}`}>{l.status}</span>
                  {l.licenceType === "clinician" && l.bookable === false && <span className={`${styles.pill} ${styles.pillExpired}`} data-testid="hidden-from-booking"> Hidden from booking</span>}
                  {overridden && <span className={`${styles.pill} ${styles.pillExpiring}`} data-testid="override-active"> Booking allowed until {dayOf(l.overrideUntil!)}</span>}
                  {mayOverride && l.licenceType === "clinician" && l.holderId && l.status === "expired" && !overridden && (
                    open === l.id ? (
                      <div className={styles.row}>
                        <label className={styles.label} htmlFor={`ov-days-${l.id}`}>Days (1–30)</label>
                        <input id={`ov-days-${l.id}`} className={styles.input} type="number" min={1} max={30} value={days} onChange={(e) => setDays(e.target.value)} style={{ minBlockSize: 44 }} />
                        <label className={styles.label} htmlFor={`ov-reason-${l.id}`}>Reason</label>
                        <input id={`ov-reason-${l.id}`} className={styles.input} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} style={{ minBlockSize: 44 }} />
                        <input className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} aria-label="Authenticator code" placeholder="6-digit code"
                          value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} style={{ minBlockSize: 44 }} />
                        <button type="button" className={styles.btnPrimary} style={{ minBlockSize: 44 }} onClick={() => void apply(l)}
                          disabled={busy || code.length !== 6 || reason.trim().length < 3 || !(Number(days) >= 1 && Number(days) <= 30)}>Allow booking</button>
                        <button type="button" className={styles.btn} style={{ minBlockSize: 44 }} onClick={() => { setOpen(null); setFail(null); }}>Cancel</button>
                        {fail && <div className={styles.errorState} role="alert">{fail}</div>}
                      </div>
                    ) : (
                      <button type="button" className={styles.btn} style={{ minBlockSize: 44 }} onClick={() => { setOpen(l.id); setFail(null); }}>Allow booking while renewing</button>
                    )
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
