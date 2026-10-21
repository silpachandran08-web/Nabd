"use client";

import { useQuery } from "@tanstack/react-query";
import type { PatientPage, PatientResponse } from "@nabd/api-client/generated";
import styles from "../arrivals.module.css";
import { getter, key, useDebounced } from "../../../lib/query";

// Guardian search for a minor's new record (same /patients?q= call the registry's dialog uses).
export default function GuardianSearch({ query, guardian, consent, channel, onQuery, onPick, onClear, onConsent, onChannel }: {
  query: string; guardian: PatientResponse | null; consent: boolean; channel: string;
  onQuery: (v: string) => void; onPick: (g: PatientResponse) => void; onClear: () => void; onConsent: (v: boolean) => void; onChannel: (v: string) => void;
}) {
  const term = useDebounced(query.trim(), 250);
  const q = useQuery({
    queryKey: key("patients", { q: term, limit: 6 }),
    queryFn: getter<PatientPage>(`/patients?q=${encodeURIComponent(term)}&limit=6`),
    enabled: term.length >= 2,
  });
  const results = term.length >= 2 ? q.data?.data ?? [] : [];
  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor="guardianSearch">Guardian (required for under-18s)</label>
      {guardian ? (
        <div className={styles.selectedPatient}>
          <span>{guardian.name} · {guardian.phone}</span>
          <button type="button" className={styles.linkBtn} onClick={onClear}>Change</button>
        </div>
      ) : (
        <>
          <input id="guardianSearch" className={styles.input} value={query} placeholder="Search the guardian's record by name or phone" onChange={(e) => onQuery(e.target.value)} />
          {results.length > 0 && (
            <div className={styles.searchResults}>
              {results.map((g) => (
                <button type="button" key={g.id} className={styles.searchResultRow} onClick={() => onPick(g)}>{g.name} · {g.phone}</button>
              ))}
            </div>
          )}
        </>
      )}
      {guardian && (
        <>
          {/* CGV-005: staff-confirmed guardian consent (wording constant needs legal review, same as the registry dialog). */}
          <label className={styles.label}>
            <input type="checkbox" checked={consent} onChange={(e) => onConsent(e.target.checked)} /> Guardian has agreed to access on this patient&apos;s behalf
          </label>
          <select aria-label="How consent was given" className={styles.select} value={channel} onChange={(e) => onChannel(e.target.value)}>
            <option value="">How was consent given…</option>
            <option value="in_person">In person</option>
            <option value="phone">Phone</option>
            <option value="written">Written</option>
          </select>
        </>
      )}
    </div>
  );
}
