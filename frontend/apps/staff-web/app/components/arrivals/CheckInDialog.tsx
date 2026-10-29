"use client";

import { FieldError, useFormErrors } from "../../lib/formErrors";
import { useFormValidation, useUnsavedGuard } from "../../lib/formValidation";
import { checkInSchema } from "../../lib/formSchemas";
import { useCallback, useEffect, useRef, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import Dialog from "../ui/Dialog";
import { PAYMENT_METHODS, isKsa } from "@nabd/domain";
import { getIdentity } from "../../lib/session";
import { api, errorText, intentKeys } from "../../lib/api";
import type { StaffOption, WaitEstimate } from "@nabd/api-client/types";
import type { QueueEntry } from "@nabd/domain";
import { PrintSheet } from "@nabd/ui";
import { usePrintPaper } from "../../lib/usePrintPaper";
import styles from "./checkin.module.css";
import { dateLocale } from "./dateLocale";
import { readDeskDefault, writeDeskDefault } from "./walk-in/deskDefaults";

// One check-in overlay shared by Arrivals and Patients (REC-041, REC-017): confirm -> POST
// /v1/queue/check-in -> result slip with token, queue position, estimated wait and print.


export type CheckInResult = { tokenNumber: number; patientName: string; doctorId: string; doctorName: string; entryId?: string };

const WAITING = ["checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done"];

/** Token slip shown after any successful check-in. Position and wait are best-effort reads. */
export function CheckInResultDialog({ result, onClose }: { result: CheckInResult; onClose: () => void }) {
  const sl = useTranslations("arrivals.slip");
  const locale = useLocale();
  const [position, setPosition] = useState<number | null | undefined>(undefined);
  const [waitMin, setWaitMin] = useState<number | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      // Best-effort reads: each one that fails leaves its fact as "—".
      const [list, wait] = await Promise.all([
        api.get<QueueEntry[]>("/queue").catch(() => null),
        api.get<WaitEstimate>(`/queue/wait-estimate?doctorId=${encodeURIComponent(result.doctorId)}`).catch(() => null),
      ]);
      if (cancelled) return;
      if (list) {
        const ahead = list
          .filter((e) => e.doctorId === result.doctorId && WAITING.includes(e.status ?? "waiting"))
          .sort((a, b) => Number(!!b.priority) - Number(!!a.priority) || new Date(a.createdAt ?? 0).getTime() - new Date(b.createdAt ?? 0).getTime());
        const idx = ahead.findIndex((e) => (result.entryId ? e.id === result.entryId : e.tokenNumber === result.tokenNumber));
        setPosition(idx >= 0 ? idx + 1 : null);
      } else setPosition(null);
      setWaitMin(typeof wait?.estimatedMinutes === "number" ? wait.estimatedMinutes : null);
    });
    return () => { cancelled = true; };
  }, [result]);

  const paper = usePrintPaper("token_slip"); // FEF-046
  const loading = position === undefined || waitMin === undefined;
  return (
    <Dialog title={sl("title")} onClose={onClose}>
      <PrintSheet paper={paper} label={sl("label")}>
      <div className={styles.result}>
        <span className={styles.srOnly} role="status">{sl("announce", { token: result.tokenNumber, name: result.patientName })}</span>
        <div className={styles.tokenLabel}>{sl("token")}</div>
        <div className={styles.tokenNumber}>#{result.tokenNumber}</div>
        <div className={styles.patient}>{result.patientName}</div>
        <dl className={styles.facts} aria-busy={loading} aria-label={loading ? sl("loading") : undefined}>
          <div><dt>{sl("doctor")}</dt><dd>{result.doctorName || "—"}</dd></div>
          <div><dt>{sl("position")}</dt><dd>{loading ? "…" : position ? `#${position}` : "—"}</dd></div>
          <div><dt>{sl("wait")}</dt><dd>{loading ? "…" : waitMin != null ? sl("waitValue", { n: waitMin }) : "—"}</dd></div>
          <div><dt>{sl("time")}</dt><dd>{new Date().toLocaleTimeString(dateLocale(locale), { hour: "2-digit", minute: "2-digit" })}</dd></div>
        </dl>
        <p className={`${styles.note} ${styles.noPrint}`}>{!loading && (position == null || waitMin == null) ? sl("unavailable") : "\u00a0"}</p>
      </div>
      </PrintSheet>
      <div className={`${styles.actions} ${styles.noPrint}`}>
        <button type="button" className={styles.secondaryBtn} onClick={() => window.print()}>{sl("print")}</button>
        <button type="button" className={styles.primaryBtn} onClick={onClose}>{sl("done")}</button>
      </div>
    </Dialog>
  );
}

/** Visit intake captured at every check-in (CheckInRequest.presentingComplaint / paymentMode), shared by
 * this dialog and the Arrivals walk-in drawer so both send the same fields. */
export function CheckInIntakeFields({ complaint, onComplaint, paymentMode, onPaymentMode, region }: {
  complaint: string; onComplaint: (v: string) => void; paymentMode: string; onPaymentMode: (v: string) => void; region?: string | null;
}) {
  const t = useTranslations("arrivals");
  const label = (m: string) => (m === "upi" ? t(isKsa(region) ? "payment.upiKsa" : "payment.upi") : t.has(`payment.${m}`) ? t(`payment.${m}`) : m);
  return (
    <>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="checkin-payment">{t("checkin.payment")}</label>
        <select id="checkin-payment" className={styles.select} value={paymentMode} onChange={(e) => onPaymentMode(e.target.value)}>
          {PAYMENT_METHODS.map((m) => <option key={m} value={m}>{label(m)}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="checkin-complaint">{t("checkin.complaint")}</label>
        <textarea id="checkin-complaint" className={styles.textarea} value={complaint}
          onChange={(e) => onComplaint(e.target.value)} placeholder={t("checkin.complaintPlaceholder")} />
      </div>
    </>
  );
}

export type CheckInPatient = {
  id: string; name: string; nextAppointmentToday: boolean; nextAppointmentId: string | null;
  nextDoctorId: string | null; nextDoctorName: string | null; nextAppointment: string | null;
};

/**
 * Check a known patient in. With a booking today it checks in against it (appointmentId);
 * otherwise it's a walk-in for the chosen doctor. `onCheckedIn` fires on success (refresh the
 * list); `onDone` fires when the token slip is dismissed (close the dialog).
 */
export function CheckInDialog({ patient, onClose, onDone, onCheckedIn }: {
  patient: CheckInPatient; onClose: () => void;
  onDone: (token: number) => void; onCheckedIn?: (token: number) => void;
}) {
  const t = useTranslations("arrivals");
  const fv = useTranslations("common.formErrors");
  const fe = useFormErrors("checkin");
  const v = useFormValidation(fe, (k) => fv(k));
  const locale = useLocale();
  const booked = !!(patient.nextAppointmentToday && patient.nextAppointmentId && patient.nextDoctorId);
  const [doctors, setDoctors] = useState<StaffOption[] | null>(null);
  const [doctorId, setDoctorId] = useState(patient.nextDoctorId ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState<CheckInResult | null>(null);
  const [complaint, setComplaint] = useState("");
  const [paymentMode, setPaymentMode] = useState(() => { const p = readDeskDefault("payment"); return (PAYMENT_METHODS as readonly string[]).includes(p) ? p : "cash"; });
  const [region] = useState(() => getIdentity()?.tenantRegion);
  const keys = useRef(intentKeys()); // one Idempotency-Key per check-in intent, reused when the same request is re-sent

  useEffect(() => {
    if (booked) return;
    let cancelled = false;
    void Promise.resolve().then(async () => {
      const list = await api.get<StaffOption[]>("/staff/roster").catch(() => []);
      if (cancelled) return;
      setDoctors(list);
      const last = readDeskDefault("doctor"); // ACC-031: the doctor of the last check-in on this desk
      if (last && list.some((d) => d.id === last)) setDoctorId((cur) => cur || last);
    });
    return () => { cancelled = true; };
  }, [booked]);

  useUnsavedGuard(!!complaint);
  const finish = useCallback(() => { if (result) onDone(result.tokenNumber); }, [result, onDone]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!booked && !v.submit(checkInSchema, { doctorId }, e.currentTarget)) return; // FEF-033
    setSaving(true);
    setError(null);
    try {
      const intake = { presentingComplaint: complaint.trim() || null, paymentMode };
      const body = booked
        ? { patientId: patient.id, doctorId: patient.nextDoctorId, appointmentId: patient.nextAppointmentId, ...intake }
        : { patientId: patient.id, doctorId, ...intake };
      const entry = await api.post<QueueEntry>("/queue/check-in", body, { idempotencyKey: keys.current.get("checkin", body) });
      keys.current.done("checkin");
      writeDeskDefault("payment", paymentMode);
      if (!booked) writeDeskDefault("doctor", doctorId);
      const dId = (booked ? patient.nextDoctorId : doctorId) ?? "";
      const dName = booked ? patient.nextDoctorName ?? "" : doctors?.find((d) => d.id === dId)?.name ?? "";
      setResult({ tokenNumber: entry.tokenNumber, patientName: patient.name, doctorId: dId, doctorName: dName, entryId: entry.id });
      onCheckedIn?.(entry.tokenNumber);
    } catch (e) {
      setError(errorText(e, t("checkin.fail")));
    } finally {
      setSaving(false);
    }
  }

  if (result) return <CheckInResultDialog result={result} onClose={finish} />;

  const time = patient.nextAppointment
    ? new Date(patient.nextAppointment).toLocaleTimeString(dateLocale(locale, "en-GB"), { hour: "2-digit", minute: "2-digit" }) : "";
  return (
    <Dialog title={t("checkin.title", { name: patient.name })} onClose={onClose}>
      <form noValidate onSubmit={submit}>
        {booked ? (
          <p className={styles.sub}>{t("checkin.booked", { time, doctor: patient.nextDoctorName ?? "" })}</p>
        ) : (
          <>
            <p className={styles.sub}>{t("checkin.walkInNote")}</p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="checkin-doctor">{t("checkin.doctor")}</label>
              <select id="checkin-doctor" data-shortcut="doctor" aria-keyshortcuts="F6" className={styles.select} value={doctorId} onChange={(e) => { fe.clearField("doctorId"); setDoctorId(e.target.value); }} onBlur={() => v.blur(checkInSchema, "doctorId", { doctorId })} {...fe.attrs("doctorId")}>
                <option value="">{doctors === null ? t("checkin.loading") : t("checkin.chooseDoctor")}</option>
                {(doctors ?? []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
              <FieldError fe={fe} name="doctorId" className={styles.error} />
            </div>
          </>
        )}
        <CheckInIntakeFields complaint={complaint} onComplaint={setComplaint} paymentMode={paymentMode} onPaymentMode={setPaymentMode} region={region} />
        {error && <div className={styles.error} role="alert">{error}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.secondaryBtn} onClick={onClose}>{t("dialog.cancel")}</button>
          <button type="submit" className={styles.primaryBtn} disabled={saving} aria-keyshortcuts="Control+Enter">
            {saving ? t("checkin.submitting") : t("checkin.submit")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
