"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../components/ui/Dialog";
import StatusPill from "../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import styles from "./schedule.module.css";

// GET /v1/waitlist?doctorId= (WaitlistController, queue:view) -> WaitlistEntryResponse.
type WaitlistEntry = {
  id: string; doctorId: string; patientId: string; patientName: string; joinedAt: string; status: string;
  offeredSlotStart: string | null; offerExpiresAt: string | null; bookedAppointmentId: string | null;
};

/**
 * Waitlist overlay (wireframe "waitlist"): lists who is waiting for a slot and lets reception
 * remove an entry (DELETE /v1/waitlist/{id}, queue:edit). The list endpoint is per doctor, so one
 * request goes out per doctor on the day. Adding entries and offering a freed slot
 * (POST /waitlist, POST /waitlist/{id}/accept) are not offered here yet.
 */
export default function WaitlistPanel({ doctors, authedFetch, onClose }: {
  doctors: { id: string; name: string }[];
  authedFetch: (path: string, init?: RequestInit) => Promise<Response | null>;
  onClose: () => void;
}) {
  const [entries, setEntries] = useState<WaitlistEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null); // entry awaiting confirmation
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const results = await Promise.all(doctors.map((d) => authedFetch(`/waitlist?doctorId=${encodeURIComponent(d.id)}`)));
      if (results.some((r) => !r)) return;
      if (results.some((r) => !r!.ok)) {
        setError("Couldn't load the waitlist.");
        return;
      }
      const lists = await Promise.all(results.map((r) => r!.json() as Promise<WaitlistEntry[]>));
      setEntries(lists.flat().sort((a, b) => a.joinedAt.localeCompare(b.joinedAt)));
      setError(null);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    }
  }, [authedFetch, doctors]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function remove(id: string) {
    setBusy(true);
    setActionError(null);
    try {
      const res = await authedFetch(`/waitlist/${id}`, { method: "DELETE" });
      if (!res) return;
      if (!res.ok) {
        setActionError("Couldn't remove this entry. Try again.");
        return;
      }
      setRemoving(null);
      await load();
    } catch {
      setActionError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title="Waitlist" size="drawer" onClose={onClose}>
      {error ? <ErrorState message={error} onRetry={() => { setError(null); void load(); }} />
        : !entries ? <Skeleton rows={4} label="Loading waitlist" />
        : entries.length === 0 ? <EmptyState title="Nobody is on the waitlist" hint="Patients waiting for an earlier slot appear here." icon="list" />
        : (
          <ul className={styles.waitList}>
            {entries.map((w) => {
              const doctor = doctors.find((d) => d.id === w.doctorId)?.name;
              return (
                <li key={w.id} className={styles.waitItem}>
                  <div className={styles.waitWho}>
                    <span className={styles.slotName}>{w.patientName}</span>
                    <span className={styles.resultSub}>
                      {[doctor, `joined ${w.joinedAt.slice(0, 10)}`, w.offeredSlotStart && `offered ${w.offeredSlotStart.slice(0, 16).replace("T", " ")}`].filter(Boolean).join(" · ")}
                    </span>
                    <StatusPill tone="warn" label={w.status.replace(/_/g, " ")} />
                  </div>
                  {removing === w.id ? (
                    <div className={styles.waitConfirm}>
                      <button type="button" className={styles.btn} onClick={() => setRemoving(null)}>Keep</button>
                      <button type="button" className={styles.btnDanger} disabled={busy} onClick={() => void remove(w.id)}>
                        {busy ? "Removing…" : "Remove"}
                      </button>
                    </div>
                  ) : (
                    <button type="button" className={styles.btn} onClick={() => { setActionError(null); setRemoving(w.id); }}>Remove</button>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      {actionError && <div className={styles.error} role="alert">{actionError}</div>}
    </Dialog>
  );
}
