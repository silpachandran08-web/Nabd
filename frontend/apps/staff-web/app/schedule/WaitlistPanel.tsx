"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../components/ui/Dialog";
import StatusPill, { type PillTone } from "../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { clinicTime } from "./NewAppointmentDialog";
import styles from "./schedule.module.css";

// WaitlistController (/v1/waitlist): GET ?doctorId= (queue:view), POST {doctorId, patientId}
// (queue:create), POST /{id}/accept (queue:edit), DELETE /{id} (queue:edit). Response = WaitlistEntryResponse.
type WaitlistEntry = {
  id: string; doctorId: string; patientId: string; patientName: string; joinedAt: string; status: string;
  offeredSlotStart: string | null; offerExpiresAt: string | null; bookedAppointmentId: string | null;
};
type Patient = { id: string; mrn: string; name: string; phone: string };
type Problem = { title?: string; detail?: string };

const STATUS: Record<string, { tone: PillTone; label: string }> = {
  waiting: { tone: "info", label: "Waiting" },
  offered: { tone: "warn", label: "Slot offered" },
  expired: { tone: "neutral", label: "Offer expired" },
  booked: { tone: "ok", label: "Booked" },
};

function remaining(expiresAt: string, now: number): string {
  const s = Math.floor(Math.max(0, new Date(expiresAt).getTime() - now) / 1000);
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

/**
 * Waitlist overlay (wireframe "waitlist", "Offer the freed slot"). The server runs the offer flow:
 * when an appointment is cancelled, WaitlistService offers the freed slot to the doctor's oldest
 * waiting entry for 15 minutes and re-offers it to the next one when that window lapses (expiry is
 * applied whenever this list is read). Reception sees that order, can add a patient (queue:create),
 * book an open offer once the patient says yes (accept, queue:edit) or remove an entry (queue:edit).
 * There is no endpoint to push an offer by hand or send it on WhatsApp, so no such button exists.
 */
export default function WaitlistPanel({ doctors, timezone, canJoin, authedFetch, onClose, onBooked }: {
  doctors: { id: string; name: string }[];
  timezone: string;
  canJoin: boolean;
  authedFetch: (path: string, init?: RequestInit) => Promise<Response | null>;
  onClose: () => void;
  onBooked: () => void;
}) {
  const [entries, setEntries] = useState<WaitlistEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [doctorFilter, setDoctorFilter] = useState("all");
  const [removing, setRemoving] = useState<string | null>(null);
  const [accepting, setAccepting] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const [adding, setAdding] = useState(false);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Patient[]>([]);
  const [patient, setPatient] = useState<Patient | null>(null);
  const [joinDoctor, setJoinDoctor] = useState(doctors[0]?.id ?? "");

  const load = useCallback(async () => {
    try {
      const res = await Promise.all(doctors.map((d) => authedFetch(`/waitlist?doctorId=${encodeURIComponent(d.id)}`)));
      if (res.some((r) => !r)) return;
      if (res.some((r) => !r!.ok)) {
        setError("Couldn't load the waitlist.");
        return;
      }
      const lists = await Promise.all(res.map((r) => r!.json() as Promise<WaitlistEntry[]>));
      setEntries(lists.flat().sort((a, b) => a.joinedAt.localeCompare(b.joinedAt)));
      setNow(Date.now());
      setError(null);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    }
  }, [authedFetch, doctors]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  // Countdown for open offers; when one lapses, reload so the server's re-offer cascade shows up.
  const hasOffer = !!entries?.some((e) => e.status === "offered");
  useEffect(() => {
    if (!hasOffer) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [hasOffer]);
  const lapsed = !!entries?.some((e) => e.status === "offered" && !!e.offerExpiresAt && new Date(e.offerExpiresAt).getTime() <= now);
  useEffect(() => { if (lapsed) void Promise.resolve().then(load); }, [lapsed, load]);

  // Patient search for the add form, debounced (same endpoint as New appointment).
  useEffect(() => {
    const q = query.trim();
    let cancelled = false;
    const timer = setTimeout(async () => {
      if (q.length < 2) { if (!cancelled) setResults([]); return; }
      const res = await authedFetch(`/patients?q=${encodeURIComponent(q)}&limit=8`);
      if (cancelled || !res?.ok) return;
      const page: { data: Patient[] } = await res.json();
      if (!cancelled) setResults(page.data);
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [query, authedFetch]);

  async function problem(res: Response, fallback: string) {
    const p: Problem | null = await res.json().catch(() => null);
    return p?.detail || fallback;
  }

  async function join(e: React.FormEvent) {
    e.preventDefault();
    if (!patient || !joinDoctor) return;
    setBusy(true);
    setActionError(null);
    try {
      const res = await authedFetch("/waitlist", { method: "POST", body: JSON.stringify({ doctorId: joinDoctor, patientId: patient.id }) });
      if (!res) return;
      if (!res.ok) { setActionError(await problem(res, "Couldn't add to the waitlist.")); return; }
      setNotice(`${patient.name} added to the waitlist.`);
      setAdding(false); setPatient(null); setQuery(""); setResults([]);
      await load();
    } catch {
      setActionError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  async function accept(w: WaitlistEntry) {
    setBusy(true);
    setActionError(null);
    try {
      const res = await authedFetch(`/waitlist/${w.id}/accept`, { method: "POST" });
      if (!res) return;
      setAccepting(null);
      if (!res.ok) {
        setActionError(await problem(res, "Couldn't book this offer."));
        await load();
        return;
      }
      setNotice(`${w.patientName} booked${w.offeredSlotStart ? ` at ${clinicTime(w.offeredSlotStart, timezone)}` : ""}.`);
      await load();
      onBooked();
    } catch {
      setActionError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    setBusy(true);
    setActionError(null);
    try {
      const res = await authedFetch(`/waitlist/${id}`, { method: "DELETE" });
      if (!res) return;
      if (!res.ok) { setActionError(await problem(res, "Couldn't remove this entry. Try again.")); return; }
      setRemoving(null);
      await load();
    } catch {
      setActionError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  const shown = (entries ?? []).filter((w) => doctorFilter === "all" || w.doctorId === doctorFilter);
  // The server offers oldest join first per doctor: that is the priority order.
  const priority = new Map<string, number>();
  for (const d of doctors) {
    let n = 0;
    for (const w of entries ?? []) if (w.doctorId === d.id && (w.status === "waiting" || w.status === "offered")) priority.set(w.id, ++n);
  }
  const openOffers = shown.filter((w) => w.status === "offered").length;
  const onList = shown.filter((w) => w.status === "waiting" || w.status === "offered").length;

  return (
    <Dialog title="Waitlist" size="drawer" onClose={onClose}>
      <p className={styles.dialogSub}>
        A cancelled slot is offered to the longest-waiting patient for that doctor for 15 minutes, then to the next.
        Book it here once the patient confirms.
      </p>

      <div className={styles.toolbar}>
        <select className={styles.input} aria-label="Filter by doctor" value={doctorFilter} onChange={(e) => setDoctorFilter(e.target.value)}>
          <option value="all">All doctors</option>
          {doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
        {canJoin && !adding && (
          <button type="button" className={styles.btnPrimary} onClick={() => { setActionError(null); setNotice(null); setAdding(true); }}>
            + Add to waitlist
          </button>
        )}
      </div>

      {entries && (
        <div className={styles.resultSub} aria-live="polite">
          {onList} on the waitlist · {openOffers} open offer{openOffers === 1 ? "" : "s"}
        </div>
      )}

      {adding && (
        <form className={styles.waitItem} style={{ flexDirection: "column", alignItems: "stretch" }} onSubmit={join}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="wl-patient">Patient</label>
            {patient ? (
              <button type="button" className={styles.resultOn} onClick={() => setPatient(null)}>
                {patient.name}
                <div className={styles.resultSub}>{patient.mrn} · {patient.phone} · change</div>
              </button>
            ) : (
              <>
                <input id="wl-patient" className={styles.input} value={query} autoComplete="off" autoFocus
                  placeholder="Search by name or phone" onChange={(e) => setQuery(e.target.value)} />
                {results.length > 0 && (
                  <div className={styles.results}>
                    {results.map((p) => (
                      <button type="button" key={p.id} className={styles.result} onClick={() => setPatient(p)}>
                        {p.name}
                        <div className={styles.resultSub}>{p.mrn} · {p.phone}</div>
                      </button>
                    ))}
                  </div>
                )}
                {query.trim().length >= 2 && results.length === 0 && (
                  <div className={styles.resultSub}>No matching patients — register them from Live Queue first.</div>
                )}
              </>
            )}
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="wl-doctor">Doctor</label>
            <select id="wl-doctor" className={styles.input} value={joinDoctor} onChange={(e) => setJoinDoctor(e.target.value)}>
              {doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>
          <div className={styles.actions}>
            <button type="button" className={styles.btn} onClick={() => setAdding(false)}>Cancel</button>
            <button type="submit" className={styles.btnPrimary} disabled={busy || !patient || !joinDoctor}>{busy ? "Adding…" : "Add to waitlist"}</button>
          </div>
        </form>
      )}

      {notice && <div className={styles.resultSub} role="status">{notice}</div>}
      {actionError && <div className={styles.error} role="alert">{actionError}</div>}

      {error ? <ErrorState message={error} onRetry={() => { setError(null); void load(); }} />
        : !entries ? <Skeleton rows={4} label="Loading waitlist" />
        : shown.length === 0 ? <EmptyState title="Nobody is on the waitlist" hint="Patients waiting for an earlier slot appear here." icon="list" />
        : (
          <ul className={styles.waitList}>
            {shown.map((w) => {
              const doctor = doctors.find((d) => d.id === w.doctorId)?.name;
              const st = STATUS[w.status] ?? { tone: "neutral" as const, label: w.status.replace(/_/g, " ") };
              const p = priority.get(w.id);
              const offerOpen = w.status === "offered" && !!w.offerExpiresAt && new Date(w.offerExpiresAt).getTime() > now;
              const active = w.status === "waiting" || w.status === "offered";
              const sub = [
                p && `Priority ${p}`,
                doctor,
                `joined ${new Date(w.joinedAt).toLocaleDateString(undefined, { timeZone: timezone })}`,
                w.offeredSlotStart && `slot ${new Date(w.offeredSlotStart).toLocaleDateString(undefined, { timeZone: timezone })} ${clinicTime(w.offeredSlotStart, timezone)}`,
              ].filter(Boolean).join(" · ");
              return (
                <li key={w.id} className={styles.waitItem}>
                  <div className={styles.waitWho}>
                    <span className={styles.slotName}>{w.patientName}</span>
                    <span className={styles.resultSub}>{sub}</span>
                    <span style={{ display: "inline-flex", gap: "var(--nb-space-8)", alignItems: "center" }}>
                      <StatusPill tone={st.tone} label={st.label} />
                      {offerOpen && <span className={styles.resultSub}>{remaining(w.offerExpiresAt!, now)} left</span>}
                    </span>
                  </div>
                  {removing === w.id ? (
                    <div className={styles.waitConfirm}>
                      <button type="button" className={styles.btn} onClick={() => setRemoving(null)}>Keep</button>
                      <button type="button" className={styles.btnDanger} disabled={busy} onClick={() => void remove(w.id)}>
                        {busy ? "Removing…" : "Remove"}
                      </button>
                    </div>
                  ) : accepting === w.id ? (
                    <div className={styles.waitConfirm}>
                      <button type="button" className={styles.btn} onClick={() => setAccepting(null)}>Back</button>
                      <button type="button" className={styles.btnPrimary} disabled={busy} onClick={() => void accept(w)}>
                        {busy ? "Booking…" : "Confirm booking"}
                      </button>
                    </div>
                  ) : active ? (
                    <div className={styles.waitConfirm}>
                      {offerOpen && (
                        <button type="button" className={styles.btnPrimary} onClick={() => { setActionError(null); setRemoving(null); setAccepting(w.id); }}>
                          Book offered slot
                        </button>
                      )}
                      <button type="button" className={styles.btn} onClick={() => { setActionError(null); setAccepting(null); setRemoving(w.id); }}>Remove</button>
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
    </Dialog>
  );
}
