"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { AppointmentPage } from "@nabd/api-client/generated";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { EmptyState, Skeleton } from "../../ui/StateViews";
import { api } from "../../../lib/api";
import { key } from "../../../lib/query";
import { failText } from "../failText";
import { patientDetailQuery } from "../hooks/useArrivals";
import type { AppointmentOption, Row, Staff } from "../types";

// Today's scheduled appointments not yet checked in, distinct from Register walk-in (always a fresh arrival).
export default function CheckinPicker({ rows, staff, patientsView, onPick, onClose }: {
  rows: Row[]; staff: Staff[]; patientsView: boolean; onPick: (a: AppointmentOption) => void; onClose: () => void;
}) {
  const qc = useQueryClient();
  // Local date, not toISOString() (UTC): before 05:30 in India that would ask for yesterday.
  const today = new Date().toLocaleDateString("en-CA");
  // Re-read on every open (gcTime 0): an appointment checked in elsewhere must not be offered again.
  const q = useQuery({
    queryKey: key("checkin-options", { date: today }), staleTime: 0, gcTime: 0,
    queryFn: async (): Promise<AppointmentOption[]> => {
      const page = (await api.get<AppointmentPage>(`/appointments?date=${today}&limit=100`)).data ?? [];
      const arrived = new Set(rows.map((r) => r.appointmentId).filter(Boolean));
      const pending = page.filter((a) => a.status === "scheduled" && !arrived.has(a.id ?? null));
      // Names from the same patient cache the board fills: a patient already on screen costs no request.
      return Promise.all(pending.map(async (a) => {
        const p = patientsView && a.patientId ? await qc.ensureQueryData(patientDetailQuery(a.patientId)).catch(() => null) : null;
        return {
          id: a.id ?? "", patientId: a.patientId ?? "", doctorId: a.doctorId ?? "",
          doctorName: staff.find((s) => s.id === a.doctorId)?.name ?? "—", patientName: p?.name ?? "Unknown patient", startTime: a.startTime ?? "",
        };
      }));
    },
  });
  const list = q.isPending ? null : q.data ?? [];
  const error = q.isError ? failText(q.error, "Couldn't load today's appointments.") : null;

  return (
    <Dialog title="Check in — today's appointments" onClose={onClose}>
      {error && <div className={styles.formError} role="alert">{error}</div>}
      {list === null ? (
        <Skeleton rows={3} label="Loading today's appointments" />
      ) : list.length === 0 ? (
        <EmptyState icon="calendar" title="No scheduled appointments are waiting to arrive" />
      ) : (
        <div className={styles.searchResults}>
          {list.map((a) => (
            <div key={a.id} className={styles.appointmentRow}>
              <span>{a.patientName} · {a.doctorName} · {new Date(a.startTime).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
              <button type="button" className={styles.actionBtn} onClick={() => onPick(a)}>Check in</button>
            </div>
          ))}
        </div>
      )}
      <div className={styles.modalActions}>
        <button type="button" className={styles.cancelBtn} onClick={onClose}>Cancel</button>
      </div>
    </Dialog>
  );
}
