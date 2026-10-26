"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import type { AppointmentPage } from "@nabd/api-client/generated";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { EmptyState, Skeleton } from "../../ui/StateViews";
import { api } from "../../../lib/api";
import { key } from "../../../lib/query";
import { dateLocale } from "../dateLocale";
import { failText } from "../failText";
import { patientDetailQuery } from "../hooks/useArrivals";
import type { AppointmentOption, Row, Staff } from "../types";

// Today's scheduled appointments not yet checked in, distinct from Register walk-in (always a fresh arrival).
export default function CheckinPicker({ rows, staff, patientsView, onPick, onClose }: {
  rows: Row[]; staff: Staff[]; patientsView: boolean; onPick: (a: AppointmentOption) => void; onClose: () => void;
}) {
  const qc = useQueryClient();
  const t = useTranslations("arrivals");
  const locale = useLocale();
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
          doctorName: staff.find((s) => s.id === a.doctorId)?.name ?? t("dialog.none"), patientName: p?.name ?? t("page.unknownPatient"), startTime: a.startTime ?? "",
        };
      }));
    },
  });
  // ACC-031: type to narrow, Enter takes the first match, the arrows walk the list.
  const [filter, setFilter] = useState("");
  const filterRef = useRef<HTMLInputElement>(null);
  const all = q.isPending ? null : q.data ?? [];
  const needle = filter.trim().toLowerCase();
  const list = all && (needle ? all.filter((a) => `${a.patientName} ${a.doctorName}`.toLowerCase().includes(needle)) : all);
  const loaded = all !== null;
  useEffect(() => { if (loaded) filterRef.current?.focus(); }, [loaded]);
  const error = q.isError ? failText(q.error, t("checkinPicker.fail"), t("page.denied")) : null;

  return (
    <Dialog title={t("checkinPicker.title")} onClose={onClose}>
      {error && <div className={styles.formError} role="alert">{error}</div>}
      {list === null ? (
        <Skeleton rows={3} label={t("checkinPicker.loading")} />
      ) : (
        <>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="checkinFilter">{t("checkinPicker.filter")}</label>
            <input id="checkinFilter" ref={filterRef} className={styles.input} autoComplete="off" value={filter} onChange={(e) => setFilter(e.target.value)}
              onKeyDown={(e) => {
                const rows = e.currentTarget.closest("[role=dialog]")?.querySelectorAll<HTMLButtonElement>("[data-results] button");
                if (e.key === "Enter" && !e.ctrlKey && !e.metaKey && list.length > 0) { e.preventDefault(); rows?.[0]?.click(); }
                else if (e.key === "ArrowDown" && rows?.length) { e.preventDefault(); rows[0].focus(); }
              }} />
          </div>
      {list.length === 0 ? (
        <EmptyState icon="calendar" title={all && all.length > 0 ? t("checkinPicker.noMatch") : t("checkinPicker.empty")} />
      ) : (
        <div className={styles.searchResults} role="presentation" data-results onKeyDown={(e) => {
          if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
          const items = Array.from(e.currentTarget.querySelectorAll<HTMLButtonElement>("button"));
          const i = items.indexOf(document.activeElement as HTMLButtonElement);
          if (i < 0) return;
          e.preventDefault();
          if (e.key === "ArrowUp" && i === 0) document.getElementById("checkinFilter")?.focus(); else items[Math.min(items.length - 1, Math.max(0, i + (e.key === "ArrowDown" ? 1 : -1)))].focus();
        }}>
          {list.map((a) => (
            <div key={a.id} className={styles.appointmentRow}>
              <span>{a.patientName} · {a.doctorName} · {new Date(a.startTime).toLocaleTimeString(dateLocale(locale), { hour: "2-digit", minute: "2-digit" })}</span>
              <button type="button" className={styles.actionBtn} onClick={() => onPick(a)}>{t("page.checkIn")}</button>
            </div>
          ))}
        </div>
      )}
        </>
      )}
      <div className={styles.modalActions}>
        <button type="button" className={styles.cancelBtn} onClick={onClose}>{t("dialog.cancel")}</button>
      </div>
    </Dialog>
  );
}
