"use client";

// FEF-028: "Register walk-in". All form state lives here (one reducer), so typing re-renders this dialog only,
// never the board behind it.
import { useReducer, useRef, useState } from "react";
import { age } from "@nabd/domain";
import type { PatientPage, QueueEntryResponse } from "@nabd/api-client/generated";
import type { CreatedPatient, DuplicateCandidate } from "@nabd/api-client/types";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { clinicToday } from "../../../lib/clinicTime";
import { api, ApiError, errorText, intentKeys } from "../../../lib/api";
import { CheckInIntakeFields, type CheckInResult } from "../CheckInDialog";
import DuplicateReview from "../duplicate-review/DuplicateReview";
import type { Grants, Staff } from "../types";
import GuardianSearch from "./GuardianSearch";
import { initialWalkIn, walkInReducer } from "./walkInReducer";
import { checkInBody, registerBody, walkInProblem } from "./walkInSchema";

export default function WalkInDialog({ staff, grants, region, onClose, onCheckedIn }: {
  staff: Staff[]; grants: Grants; region?: string; onClose: () => void; onCheckedIn: (r: CheckInResult) => void;
}) {
  const [s, dispatch] = useReducer(walkInReducer, initialWalkIn);
  const [submitting, setSubmitting] = useState(false);
  const keys = useRef(intentKeys()); // Idempotency-Key per register / check-in intent, reused on a user retry of the same request
  // A patient created by a check-in whose second call failed: a retry must reuse it, not re-register.
  // shortcut: editing any new-patient field after "registered, check-in failed" re-registers on retry, so a corrected DOB is never dropped; the duplicate check then surfaces the earlier record. A PATCH of the changed fields would avoid the second record.
  const registeredRef = useRef<{ id: string; name: string } | null>(null);
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const minor = !!s.dob && age(s.dob, clinicToday()) < 18;
  const edited = () => { registeredRef.current = null; };

  function onQuery(value: string) {
    edited();
    dispatch({ type: "query", value });
    if (searchTimer.current) clearTimeout(searchTimer.current);
    if (value.trim().length < 3) { // OPS-009: the API answers 400 QUERY_TOO_SHORT below 3 characters
      dispatch({ type: "results", results: [] });
      return;
    }
    searchTimer.current = setTimeout(async () => {
      const page = await api.get<PatientPage>(`/patients?q=${encodeURIComponent(value.trim())}`).catch(() => null);
      dispatch({ type: "results", results: page?.data ?? [] });
    }, 150);
  }

  // confirmedNotDuplicate=true is only sent after the receptionist reviewed the match list and chose "Different
  // person"; the server then files the record under duplicate review.
  async function registerAndCheckIn(confirmedNotDuplicate: boolean) {
    dispatch({ type: "error", message: null });
    setSubmitting(true);
    try {
      let patientId = s.selected?.id ?? registeredRef.current?.id ?? null;
      let patientName = s.selected?.name ?? registeredRef.current?.name ?? "";
      const problem = walkInProblem(s, !!patientId, minor);
      if (problem) { dispatch({ type: "error", message: problem }); return; }
      if (!patientId) {
        const regBody = registerBody(s, minor, confirmedNotDuplicate);
        try {
          patientId = (await api.post<CreatedPatient>("/patients", regBody, { idempotencyKey: keys.current.get("register", regBody) })).id;
          keys.current.done("register");
        } catch (e) {
          if (e instanceof ApiError && e.status === 409) {
            const candidates = (e.problem.candidates as DuplicateCandidate[] | undefined) ?? [];
            dispatch({ type: "duplicates", candidates, message: candidates.length > 0
              ? "Possible existing records found. Review the matches below — use an existing patient, or confirm this is a different person."
              : "A similar patient already exists — search for them above instead of registering again." });
            return;
          }
          dispatch({ type: "error", message: errorText(e, "Couldn't register patient."), fieldErrs: e instanceof ApiError && e.errors.length > 0 ? e.fieldErrors() : undefined });
          return;
        }
        patientName = s.name;
        registeredRef.current = { id: patientId as string, name: s.name };
      }
      const ciBody = checkInBody(s, patientId as string);
      let entry: QueueEntryResponse;
      try {
        entry = await api.post<QueueEntryResponse>("/queue/check-in", ciBody, { idempotencyKey: keys.current.get("checkin", ciBody) });
      } catch (e) {
        dispatch({ type: "error", message: errorText(e, "Couldn't check in.") });
        return;
      }
      keys.current.done("checkin");
      onCheckedIn({ tokenNumber: entry.tokenNumber ?? 0, patientName, doctorId: s.doctorId, doctorName: staff.find((x) => x.id === s.doctorId)?.name ?? "", entryId: entry.id ?? "" });
    } finally {
      setSubmitting(false);
    }
  }

  function onField(f: "name" | "phone" | "dob" | "gender", value: string) { edited(); dispatch({ type: "field", field: f, value }); }
  const err = (f: string) => s.fieldErrs[f];
  const inv = (f: string) => ({ "aria-invalid": err(f) ? true : undefined, "aria-describedby": err(f) ? `new${f[0].toUpperCase()}${f.slice(1)}-err` : undefined });
  const fieldError = (f: string) => err(f) && <div id={`new${f[0].toUpperCase()}${f.slice(1)}-err`} className={styles.formError}>{err(f)}</div>;

  return (
    <Dialog size="drawer" title="Register walk-in" onClose={onClose}>
      <form onSubmit={(e) => { e.preventDefault(); void registerAndCheckIn(false); }}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="patientSearch">Patient</label>
          <input id="patientSearch" className={styles.input} placeholder="Search by name or phone…" value={s.query} onChange={(e) => onQuery(e.target.value)} disabled={!!s.selected} />
          {s.selected && (
            <div className={styles.selectedPatient}>
              <span>{s.selected.name} · {s.selected.phone}</span>
              <button type="button" className={styles.linkBtn} onClick={() => dispatch({ type: "clearSelected" })}>Change</button>
            </div>
          )}
          {!s.selected && s.results.length > 0 && (
            <div className={styles.searchResults}>
              {s.results.map((p) => (
                <button type="button" key={p.id} className={styles.searchResultRow} onClick={() => dispatch({ type: "select", patient: p })}>{p.name} · {p.phone} · {p.mrn}{p.matchedOn && <span style={{ opacity: 0.7 }}> · matched on {p.matchedOn}</span>}</button>
              ))}
            </div>
          )}
          {grants.patientsCreate && !s.selected && s.query.trim().length >= 3 && s.results.length === 0 && !s.newMode && (
            <button type="button" className={styles.linkBtn} style={{ marginTop: "8px" }} onClick={() => dispatch({ type: "newMode" })}>No match — register a new patient</button>
          )}
        </div>

        {s.newMode && !s.selected && (
          <>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newName">Name</label>
              <input id="newName" className={styles.input} value={s.name} onChange={(e) => onField("name", e.target.value)} {...inv("name")} />
              {fieldError("name")}
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newPhone">Phone</label>
              <input id="newPhone" className={styles.input} inputMode="tel" value={s.phone} onChange={(e) => onField("phone", e.target.value)} {...inv("phone")} />
              {fieldError("phone")}
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newDob">Date of birth</label>
              <input id="newDob" type="date" className={styles.input} value={s.dob} max={new Date().toLocaleDateString("en-CA")} onChange={(e) => onField("dob", e.target.value)} {...inv("dob")} />
              {fieldError("dob")}
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newGender">Gender</label>
              <select id="newGender" className={styles.select} value={s.gender} onChange={(e) => onField("gender", e.target.value)} {...inv("gender")}>
                <option value="male">Male</option>
                <option value="female">Female</option>
                <option value="other">Other</option>
              </select>
              {fieldError("gender")}
            </div>
            {minor && (
              <GuardianSearch query={s.guardianQuery} guardian={s.guardian} consent={s.guardianConsent} channel={s.guardianChannel}
                onQuery={(v) => dispatch({ type: "guardianQuery", value: v })}
                onPick={(g) => { edited(); dispatch({ type: "guardianPick", patient: g }); }}
                onClear={() => { edited(); dispatch({ type: "guardianClear" }); }}
                onConsent={(v) => dispatch({ type: "guardianConsent", value: v })}
                onChannel={(v) => dispatch({ type: "guardianChannel", value: v })} />
            )}
          </>
        )}

        <div className={styles.field}>
          <label className={styles.label} htmlFor="doctor">Doctor</label>
          <select id="doctor" className={styles.select} value={s.doctorId} onChange={(e) => dispatch({ type: "doctor", value: e.target.value })}>
            <option value="">Select a doctor…</option>
            {staff.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>
        </div>

        <CheckInIntakeFields complaint={s.complaint} onComplaint={(v) => dispatch({ type: "complaint", value: v })}
          paymentMode={s.paymentMode} onPaymentMode={(v) => dispatch({ type: "payment", value: v })} region={region} />

        {s.error && <div className={styles.formError} role="alert">{s.error}</div>}

        <DuplicateReview candidates={s.candidates} submitting={submitting}
          onUse={(c) => dispatch({ type: "useCandidate", patient: { id: c.patientId, name: c.name, phone: c.phone, mrn: "" } })}
          onDifferent={() => void registerAndCheckIn(true)} />

        <div className={styles.modalActions}>
          <button type="button" className={styles.cancelBtn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.submitBtn} disabled={submitting}>{submitting ? "Checking in…" : "Check in"}</button>
        </div>
      </form>
    </Dialog>
  );
}
