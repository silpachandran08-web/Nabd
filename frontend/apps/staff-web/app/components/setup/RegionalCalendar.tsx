"use client";

import { useCallback, useEffect, useState } from "react";
import { api, errorText } from "../../lib/api";
import styles from "../../(clinic)/setup/setup.module.css";

type Hours = { from: string; to: string };
export type CalHoliday = { id: string; holidayDate: string; name: string; recurring: boolean; effect?: string; hours?: Hours[] | null; source?: string | null; bookingsToMove?: number | null };
type Template = { code: string; name: string; year: number; source: string };
type Quiet = { enabled: boolean; from: string; to: string };

// ADM-028: state template picker, closed / short / open per holiday with the bookings that must move, quiet hours.
// Controls render only for setup:edit; the server checks it again.
export default function RegionalCalendar({ holidays, canEdit, onChanged }: { holidays: CalHoliday[]; canEdit: boolean; onChanged: () => void }) {
  const [templates, setTemplates] = useState<Template[] | null>(null);
  const [pick, setPick] = useState("");
  const [quiet, setQuiet] = useState<Quiet | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [short, setShort] = useState<Record<string, Hours>>({});

  const load = useCallback(async () => {
    try {
      const [t, q] = await Promise.all([api.get<Template[]>("/setup/calendar/templates?market=IN"), api.get<Quiet>("/setup/calendar/quiet-hours")]);
      setTemplates(t);
      setQuiet(q);
      setPick((p) => p || t[0]?.code || "");
      setError(null);
    } catch (e) {
      setError(errorText(e, "Couldn't load the regional calendar."));
    }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const run = async (fn: () => Promise<void>, fallback: string) => {
    setError(null);
    setMsg(null);
    try { await fn(); } catch (e) { setError(errorText(e, fallback)); }
  };
  const chosen = templates?.find((t) => t.code === pick);
  const apply = () => chosen && run(async () => {
    const r = await api.post<{ added: number; skipped: number }>(`/setup/calendar/templates/${chosen.code}/apply`, { year: chosen.year });
    setMsg(`${r.added} added, ${r.skipped} already there.`);
    onChanged();
  }, "Couldn't apply the template.");
  const setEffect = (h: CalHoliday, effect: string) => run(async () => {
    const hours = effect === "short" ? [short[h.id] ?? h.hours?.[0] ?? { from: "09:00", to: "13:00" }] : undefined;
    await api.patch(`/setup/holidays/${h.id}`, { effect, hours });
    onChanged();
  }, "Couldn't save that holiday.");
  const saveQuiet = (q: Quiet) => run(async () => { setQuiet(await api.put<Quiet>("/setup/calendar/quiet-hours", q)); }, "Couldn't save quiet hours.");

  return (
    <section aria-label="Regional calendar">
      <h3 className={styles.cardTitle}>Regional calendar (India)</h3>
      {error && <div role="alert" className={styles.errorState}>{error}</div>}
      {msg && <div role="status">{msg}</div>}
      {templates && templates.length === 0 && <p>No state calendars are available yet. Add holidays by hand below.</p>}
      {canEdit && templates && templates.length > 0 && (
        <div className={styles.row}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="calTemplate">State calendar</label>
            <select id="calTemplate" className={styles.input} value={pick} onChange={(e) => setPick(e.target.value)}>
              {templates.map((t) => <option key={`${t.code}-${t.year}`} value={t.code}>{t.name} {t.year}</option>)}
            </select>
          </div>
          <div className={styles.actions}><button type="button" className={styles.btnPrimary} onClick={() => void apply()}>Apply calendar</button></div>
        </div>
      )}
      <p>On a closed day, slots are not offered and booked patients are moved to the next slot. Short hours keep only the window you set. Open keeps normal slots.</p>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead><tr><th scope="col">Date</th><th scope="col">Holiday</th><th scope="col">List</th><th scope="col">Clinic</th><th scope="col">To move</th></tr></thead>
          <tbody>
            {holidays.length === 0 && <tr><td colSpan={5}>No holidays yet.</td></tr>}
            {holidays.map((h) => (
              <tr key={h.id}>
                <td>{h.holidayDate}</td>
                <td>{h.name}</td>
                <td>{h.source ?? "Added by hand"}</td>
                <td>
                  {canEdit ? (
                    <>
                      <select aria-label={`Effect for ${h.name}`} className={styles.input} value={h.effect ?? "closed"} onChange={(e) => void setEffect(h, e.target.value)}>
                        <option value="closed">Closed</option><option value="short">Short hours</option><option value="open">Open</option>
                      </select>
                      {h.effect === "short" && (
                        <span>
                          <input aria-label="From" type="time" value={(short[h.id] ?? h.hours?.[0])?.from ?? "09:00"} onChange={(e) => setShort({ ...short, [h.id]: { from: e.target.value, to: (short[h.id] ?? h.hours?.[0])?.to ?? "13:00" } })} />
                          <input aria-label="To" type="time" value={(short[h.id] ?? h.hours?.[0])?.to ?? "13:00"} onChange={(e) => setShort({ ...short, [h.id]: { from: (short[h.id] ?? h.hours?.[0])?.from ?? "09:00", to: e.target.value } })} />
                          <button type="button" className={styles.smallBtn} onClick={() => void setEffect(h, "short")}>Save hours</button>
                        </span>
                      )}
                    </>
                  ) : (h.effect === "short" ? `Short ${h.hours?.[0]?.from ?? ""}-${h.hours?.[0]?.to ?? ""}` : h.effect === "open" ? "Open" : "Closed")}
                </td>
                <td>{h.bookingsToMove ? `${h.bookingsToMove} to move` : "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {quiet && (
        <div className={styles.field}>
          <label className={styles.label}>
            <input type="checkbox" disabled={!canEdit} checked={quiet.enabled} onChange={(e) => void saveQuiet({ ...quiet, enabled: e.target.checked })} /> Quiet hours for patient messages ({quiet.from} to {quiet.to}). Queue updates and OTPs still go out.
          </label>
        </div>
      )}
    </section>
  );
}
