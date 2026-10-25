"use client";

import { useCallback, useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Dialog from "../components/ui/Dialog";
import StatusPill, { type PillTone } from "../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { clinicTime } from "./NewAppointmentDialog";
import styles from "./schedule.module.css";
import { api, ApiError } from "../lib/api";
import { key } from "../lib/query";
import { ErrorSummary, FieldError, failText, useFormErrors } from "../lib/formErrors";
import { usePatientSearch, type PatientHit } from "../lib/usePatientSearch";
import type { Full } from "../lib/gen";
import type { WaitlistEntryResponse } from "@nabd/api-client/generated";

// WaitlistController (/v1/waitlist): GET ?doctorId= (queue:view), POST {doctorId, patientId}
// (queue:create), POST /{id}/accept (queue:edit), DELETE /{id} (queue:edit). Response = WaitlistEntryResponse.
type WaitlistEntry = Full<WaitlistEntryResponse, "offeredSlotStart" | "offerExpiresAt" | "bookedAppointmentId">;
type Patient = PatientHit;

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

/** FEF-017: the offer countdown. It owns the 1 s tick, so only this cell re-renders each second, not the panel.
 * When the offer lapses it says so once (`onLapse`), and the panel reloads to show the server's re-offer. */
export function Countdown({ expiresAt, onLapse, className }: { expiresAt: string; onLapse: () => void; className?: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const lapsed = new Date(expiresAt).getTime() <= now;
  useEffect(() => { if (lapsed) onLapse(); }, [lapsed, onLapse]);
  return <span className={className} data-testid="offer-countdown">{remaining(expiresAt, now)} left</span>;
}

/**
 * Waitlist overlay (wireframe "waitlist", "Offer the freed slot"). The server runs the offer flow:
 * when an appointment is cancelled, WaitlistService offers the freed slot to the doctor's oldest
 * waiting entry for 15 minutes and re-offers it to the next one when that window lapses (expiry is
 * applied whenever this list is read). Reception sees that order, can add a patient (queue:create),
 * book an open offer once the patient says yes (accept, queue:edit) or remove an entry (queue:edit).
 * There is no endpoint to push an offer by hand or send it on WhatsApp, so no such button exists.
 */
export default function WaitlistPanel({ doctors, timezone, canJoin, onClose, onBooked }: {
  doctors: { id: string; name: string }[];
  timezone: string;
  canJoin: boolean;
  onClose: () => void;
  onBooked: () => void;
}) {
  const [doctorFilter, setDoctorFilter] = useState("all");
  const [removing, setRemoving] = useState<string | null>(null);
  const [accepting, setAccepting] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fe = useFormErrors("wl", { doctorId: "Doctor", patientId: "Patient" });
  const [notice, setNotice] = useState<string | null>(null);
  const [lapsedIds, setLapsedIds] = useState<ReadonlySet<string>>(new Set());

  const [adding, setAdding] = useState(false);
  const [query, setQuery] = useState("");
  const { results, empty: noMatch } = usePatientSearch(query, 8);
  const [patient, setPatient] = useState<Patient | null>(null);
  const [joinDoctor, setJoinDoctor] = useState(doctors[0]?.id ?? "");

  const ids = doctors.map((d) => d.id).join(",");
  const listQ = useQuery({
    queryKey: key("waitlist", { doctors: ids }),
    queryFn: async ({ signal }) => (await Promise.all(doctors.map((d) => api.get<WaitlistEntry[]>(`/waitlist?doctorId=${encodeURIComponent(d.id)}`, { signal }))))
      .flat().sort((a, b) => a.joinedAt.localeCompare(b.joinedAt)),
    staleTime: 0,
  });
  const entries = listQ.data ?? null;
  const loadedAt = listQ.dataUpdatedAt;
  const error = listQ.isError ? (listQ.error instanceof ApiError ? "Couldn't load the waitlist." : "Couldn't reach the server. Check your connection and try again.") : null;
  const { refetch } = listQ;
  const load = useCallback(async () => { setLapsedIds(new Set()); await refetch(); }, [refetch]);
  // An offer that ran out: hide its Book button at once and reload so the server's re-offer cascade shows up.
  const lapse = useCallback((id: string) => {
    setLapsedIds((s) => (s.has(id) ? s : new Set(s).add(id)));
    void refetch();
  }, [refetch]);

  async function join(e: React.FormEvent) {
    e.preventDefault();
    if (!patient || !joinDoctor || busy) return;
    setBusy(true);
    fe.clear();
    let added = false;
    try {
      await api.post("/waitlist", { doctorId: joinDoctor, patientId: patient.id });
      added = true;
    } catch (err) {
      fe.fail(err, "Couldn't add to the waitlist.");
    } finally {
      setBusy(false);
    }
    if (!added) return;
    setNotice(`${patient.name} added to the waitlist.`);
    setAdding(false); setPatient(null); setQuery("");
    await load();
  }

  async function accept(w: WaitlistEntry) {
    if (busy) return;
    setBusy(true);
    fe.clear();
    try {
      await api.post(`/waitlist/${w.id}/accept`);
      setAccepting(null);
      setNotice(`${w.patientName} booked${w.offeredSlotStart ? ` at ${clinicTime(w.offeredSlotStart, timezone)}` : ""}.`);
      await load();
      onBooked();
    } catch (err) {
      setAccepting(null);
      fe.set(failText(err, "Couldn't book this offer."));
      await load();
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    if (busy) return;
    setBusy(true);
    fe.clear();
    try {
      await api.delete(`/waitlist/${id}`);
      setRemoving(null);
      await load();
    } catch (err) {
      fe.set(failText(err, "Couldn't remove this entry. Try again."));
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
          <button type="button" className={styles.btnPrimary} onClick={() => { fe.clear(); setNotice(null); setAdding(true); }}>
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
                {noMatch && (
                  <div className={styles.resultSub}>No matching patients — register them from Live Queue first.</div>
                )}
              </>
            )}
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="wl-doctor">Doctor</label>
            <select id="wl-doctor" className={styles.input} value={joinDoctor} {...fe.attrs("doctorId")} onChange={(e) => { fe.clearField("doctorId"); setJoinDoctor(e.target.value); }}>
              {doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
            <FieldError fe={fe} name="doctorId" className={styles.resultSub} />
            <FieldError fe={fe} name="patientId" className={styles.resultSub} />
          </div>
          <div className={styles.actions}>
            <button type="button" className={styles.btn} onClick={() => setAdding(false)}>Cancel</button>
            <button type="submit" className={styles.btnPrimary} disabled={busy || !patient || !joinDoctor}>{busy ? "Adding…" : "Add to waitlist"}</button>
          </div>
        </form>
      )}

      {notice && <div className={styles.resultSub} role="status">{notice}</div>}
      <ErrorSummary fe={fe} className={styles.error} />

      {error ? <ErrorState message={error} onRetry={() => void load()} />
        : !entries ? <Skeleton rows={4} label="Loading waitlist" />
        : shown.length === 0 ? <EmptyState title="Nobody is on the waitlist" hint="Patients waiting for an earlier slot appear here." icon="list" />
        : (
          <ul className={styles.waitList}>
            {shown.map((w) => {
              const doctor = doctors.find((d) => d.id === w.doctorId)?.name;
              const st = STATUS[w.status] ?? { tone: "neutral" as const, label: w.status.replace(/_/g, " ") };
              const p = priority.get(w.id);
              const offerOpen = w.status === "offered" && !!w.offerExpiresAt && new Date(w.offerExpiresAt).getTime() > loadedAt && !lapsedIds.has(w.id);
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
                      {offerOpen && <Countdown expiresAt={w.offerExpiresAt!} className={styles.resultSub} onLapse={() => lapse(w.id)} />}
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
                        <button type="button" className={styles.btnPrimary} onClick={() => { fe.clear(); setRemoving(null); setAccepting(w.id); }}>
                          Book offered slot
                        </button>
                      )}
                      <button type="button" className={styles.btn} onClick={() => { fe.clear(); setAccepting(null); setRemoving(w.id); }}>Remove</button>
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
