"use client";

// FEF-028: "Register walk-in". All form state lives here (one reducer), so typing re-renders this dialog only,
// never the board behind it. ACC-031: keyboard-first (F2 opens straight on the new-patient form, F3 on the search; Enter in the
// search picks the first match; Ctrl+Enter confirms), minimum dataset in tab order (name, mobile, sex, age or DOB), smart defaults.
import { useEffect, useReducer, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { age } from "@nabd/domain";
import type { PatientPage, QueueEntryResponse } from "@nabd/api-client/generated";
import type { CreatedPatient, DuplicateCandidate } from "@nabd/api-client/types";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { clinicToday } from "../../../lib/clinicTime";
import { api, ApiError, errorText, intentKeys } from "../../../lib/api";
import { getIdentity } from "../../../lib/session";
import { useFormErrors } from "../../../lib/formErrors";
import { useFormValidation, useUnsavedGuard } from "../../../lib/formValidation";
import { checkInSchema, e164, registerSchema } from "../../../lib/formSchemas";
import { CheckInIntakeFields, type CheckInResult } from "../CheckInDialog";
import DuplicateReview from "../duplicate-review/DuplicateReview";
import type { DoctorQueue, Grants, Row, Staff } from "../types";
import { defaultDoctor, defaultPayment } from "./smartDefaults";
import { dobFromAge } from "./ageDob";
import { readDeskDefault, writeDeskDefault } from "./deskDefaults";
import GuardianSearch from "./GuardianSearch";
import HouseholdPicker, { looksLikePhone } from "./HouseholdPicker";
import { initialWalkIn, walkInReducer } from "./walkInReducer";
import { checkInBody, registerBody, walkInProblem } from "./walkInSchema";

const PAYMENTS = ["cash", "card", "upi", "other"];

export default function WalkInDialog({ staff, grants, region, mode = "search", doctorQueues = [], rows = [], initialQuery = "", onClose, onCheckedIn }: {
  staff: Staff[]; grants: Grants; region?: string; mode?: "search" | "new"; doctorQueues?: DoctorQueue[]; rows?: Row[]; initialQuery?: string; onClose: () => void; onCheckedIn: (r: CheckInResult) => void;
}) {
  const t = useTranslations("arrivals");
  const w = useTranslations("arrivals.walkIn");
  const fv = useTranslations("common.formErrors");
  const fe = useFormErrors("walkin");
  const v = useFormValidation(fe, (k) => fv(k));
  const country = getIdentity()?.tenantRegion ?? region;
  const formRef = useRef<HTMLFormElement>(null);
  const [s, dispatch] = useReducer(walkInReducer, undefined, () => {
    const doc = readDeskDefault("doctor");
    const pay = readDeskDefault("payment");
    return { ...initialWalkIn, newMode: mode === "new" && grants.patientsCreate, query: initialQuery,
      doctorId: defaultDoctor(staff, doctorQueues, doc), paymentMode: defaultPayment(rows, PAYMENTS, pay) };
  });
  const [submitting, setSubmitting] = useState(false);
  const [ageText, setAgeText] = useState("");
  const keys = useRef(intentKeys()); // Idempotency-Key per register / check-in intent, reused on a user retry of the same request
  // A patient created by a check-in whose second call failed: a retry must reuse it, not re-register.
  // shortcut: editing any new-patient field after "registered, check-in failed" re-registers on retry, so a corrected DOB is never dropped; the duplicate check then surfaces the earlier record. A PATCH of the changed fields would avoid the second record.
  const registeredRef = useRef<{ id: string; name: string } | null>(null);
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  const doctorRef = useRef<HTMLSelectElement>(null);
  const minor = !!s.dob && age(s.dob, clinicToday()) < 18;
  const edited = () => { registeredRef.current = null; };
  useUnsavedGuard(!!(s.query || s.name || s.phone || s.dob || s.complaint || s.selected));

  // Focus lands where typing starts: the name on F2, the search box otherwise; a picked patient moves it to the doctor.
  useEffect(() => { (s.newMode && !s.selected ? nameRef.current : searchRef.current)?.focus(); }, []); // eslint-disable-line react-hooks/exhaustive-deps -- once, on open
  useEffect(() => { if (s.selected) doctorRef.current?.focus(); }, [s.selected]);
  // FEF-034: wait estimates arrive after the dialog opens; pick the shortest wait once they do, unless the user already chose a doctor.
  const doctorTouched = useRef(false);
  useEffect(() => {
    if (s.doctorId || doctorTouched.current) return;
    const d = defaultDoctor(staff, doctorQueues, readDeskDefault("doctor"));
    if (d) dispatch({ type: "doctor", value: d });
  }, [doctorQueues, staff, s.doctorId]);
  useEffect(() => { if (initialQuery) onQuery(initialQuery); }, []); // eslint-disable-line react-hooks/exhaustive-deps -- once: a patient picked in the command palette

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

  // Enter in the search box picks the first match (name list, else the family list); the arrows walk the list without the mouse.
  function onSearchKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      const first = e.currentTarget.closest("form")?.querySelector<HTMLButtonElement>("[data-results] button");
      if (first) { e.preventDefault(); first.focus(); }
    } else if (e.key === "Enter" && !e.ctrlKey && !e.metaKey) {
      if (s.results.length > 0) { e.preventDefault(); dispatch({ type: "select", patient: s.results[0] }); }
      else {
        const first = e.currentTarget.closest("form")?.querySelector<HTMLButtonElement>("[data-results] button");
        if (first) { e.preventDefault(); first.click(); }
      }
    }
  }
  function onListKey(e: React.KeyboardEvent<HTMLDivElement>) {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    const items = Array.from(e.currentTarget.querySelectorAll<HTMLButtonElement>("button"));
    const i = items.indexOf(document.activeElement as HTMLButtonElement);
    if (i < 0) return;
    e.preventDefault();
    if (e.key === "ArrowUp" && i === 0) searchRef.current?.focus(); else items[Math.min(items.length - 1, Math.max(0, i + (e.key === "ArrowDown" ? 1 : -1)))].focus();
  }

  // confirmedNotDuplicate=true is only sent after the receptionist reviewed the match list and chose "Different
  // person"; the server then files the record under duplicate review.
  async function registerAndCheckIn(confirmedNotDuplicate: boolean) {
    dispatch({ type: "error", message: null });
    setSubmitting(true);
    try {
      let patientId = s.selected?.id ?? registeredRef.current?.id ?? null;
      let patientName = s.selected?.name ?? registeredRef.current?.name ?? "";
      const fields = { name: s.name, phone: s.phone, dob: s.dob, gender: s.gender };
      if (!patientId && !v.submit(registerSchema(country, clinicToday()), fields, formRef.current)) return;
      if (!v.submit(checkInSchema, { doctorId: s.doctorId }, formRef.current)) return;
      const problem = walkInProblem(s, !!patientId, minor);
      if (problem) { dispatch({ type: "error", message: w(problem) }); return; }
      if (!patientId) {
        const regBody = registerBody({ ...s, phone: e164(s.phone, country) }, minor, confirmedNotDuplicate); // FEF-033: stored as E.164
        try {
          patientId = (await api.post<CreatedPatient>("/patients", regBody, { idempotencyKey: keys.current.get("register", regBody) })).id;
          keys.current.done("register");
        } catch (e) {
          if (e instanceof ApiError && e.status === 409) {
            const candidates = (e.problem.candidates as DuplicateCandidate[] | undefined) ?? [];
            dispatch({ type: "duplicates", candidates, message: candidates.length > 0 ? w("dupFound") : w("dupSimilar") });
            return;
          }
          dispatch({ type: "error", message: errorText(e, w("registerFail")) });
          if (e instanceof ApiError && e.errors.length > 0) fe.fail(e, w("registerFail")); // server field errors land on the same fields
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
        dispatch({ type: "error", message: errorText(e, w("checkInFail")) });
        return;
      }
      keys.current.done("checkin");
      writeDeskDefault("doctor", s.doctorId);
      writeDeskDefault("payment", s.paymentMode);
      onCheckedIn({ tokenNumber: entry.tokenNumber ?? 0, patientName, doctorId: s.doctorId, doctorName: staff.find((x) => x.id === s.doctorId)?.name ?? "", entryId: entry.id ?? "" });
    } finally {
      setSubmitting(false);
    }
  }

  function onField(f: "name" | "phone" | "dob" | "gender", value: string) { edited(); dispatch({ type: "field", field: f, value }); }
  function onAge(value: string) {
    const v = value.replace(/\D/g, "").slice(0, 3);
    setAgeText(v);
    if (v) { fe.clearField("dob"); onField("dob", dobFromAge(v, clinicToday())); }
  }
  const err = (f: string) => fe.fields[f];
  const inv = (f: string) => ({ "aria-invalid": err(f) ? true : undefined, "aria-describedby": err(f) ? `new${f[0].toUpperCase()}${f.slice(1)}-err` : undefined });
  const fieldError = (f: string) => err(f) && <div id={`new${f[0].toUpperCase()}${f.slice(1)}-err`} className={styles.formError}>{err(f)}</div>;

  return (
    <Dialog size="drawer" title={w("title")} onClose={onClose}>
      <form ref={formRef} noValidate onSubmit={(e) => { e.preventDefault(); void registerAndCheckIn(false); }}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="patientSearch">{w("patient")}</label>
          <input id="patientSearch" ref={searchRef} className={styles.input} placeholder={w("search")} value={s.query} onChange={(e) => onQuery(e.target.value)} onKeyDown={onSearchKey}
            aria-keyshortcuts="F3" disabled={!!s.selected} />
          {s.selected && (
            <div className={styles.selectedPatient}>
              <span>{s.selected.name} · {s.selected.phone}</span>
              <button type="button" className={styles.linkBtn} onClick={() => dispatch({ type: "clearSelected" })}>{w("change")}</button>
            </div>
          )}
          {!s.selected && looksLikePhone(s.query) && (
            <HouseholdPicker phone={s.query} onPick={(m) => dispatch({ type: "select", patient: { id: m.patientId, name: m.displayName, phone: s.query.trim(), mrn: "" } })} />
          )}
          {!s.selected && s.results.length > 0 && (
            <div className={styles.searchResults} role="presentation" data-results onKeyDown={onListKey}>
              {s.results.map((p) => (
                <button type="button" key={p.id} className={styles.searchResultRow} onClick={() => dispatch({ type: "select", patient: p })}>{p.name} · {p.phone} · {p.mrn}{p.matchedOn && <span style={{ opacity: 0.7 }}> · {w("matchedOn", { field: p.matchedOn })}</span>}</button>
              ))}
            </div>
          )}
          {grants.patientsCreate && !s.selected && s.query.trim().length >= 3 && s.results.length === 0 && !s.newMode && (
            <button type="button" className={styles.linkBtn} style={{ marginTop: "8px" }} onClick={() => dispatch({ type: "newMode" })}>{w("noMatch")}</button>
          )}
        </div>

        {s.newMode && !s.selected && (
          <>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newName">{w("name")}</label>
              <input id="newName" ref={nameRef} className={styles.input} value={s.name} autoComplete="off" onChange={(e) => { fe.clearField("name"); onField("name", e.target.value); }} onBlur={() => v.blur(registerSchema(country, clinicToday()), "name", { name: s.name, phone: s.phone, dob: s.dob, gender: s.gender })} {...inv("name")} />
              {fieldError("name")}
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newPhone">{w("phone")}</label>
              <input id="newPhone" className={styles.input} inputMode="tel" autoComplete="off" value={s.phone} onChange={(e) => { fe.clearField("phone"); onField("phone", e.target.value); }} onBlur={() => v.blur(registerSchema(country, clinicToday()), "phone", { name: s.name, phone: s.phone, dob: s.dob, gender: s.gender })} {...inv("phone")} />
              {fieldError("phone")}
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newGender">{w("gender")}</label>
              <select id="newGender" className={styles.select} value={s.gender} onChange={(e) => onField("gender", e.target.value)} {...inv("gender")}>
                <option value="male">{w("male")}</option>
                <option value="female">{w("female")}</option>
                <option value="other">{w("other")}</option>
              </select>
              {fieldError("gender")}
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newAge">{w("age")}</label>
              <input id="newAge" className={styles.input} inputMode="numeric" autoComplete="off" value={ageText} onChange={(e) => onAge(e.target.value)} aria-describedby="newAgeHint" />
              <div id="newAgeHint" className={styles.muted}>{w("ageHint")}</div>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="newDob">{w("dob")}</label>
              <input id="newDob" type="date" className={styles.input} value={s.dob} max={clinicToday()} onChange={(e) => { fe.clearField("dob"); setAgeText(""); onField("dob", e.target.value); }} onBlur={() => v.blur(registerSchema(country, clinicToday()), "dob", { name: s.name, phone: s.phone, dob: s.dob, gender: s.gender })} {...inv("dob")} />
              {ageText && <div className={styles.muted} data-testid="dob-estimated">{w("dobEstimated")}</div>}
              {fieldError("dob")}
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
          <label className={styles.label} htmlFor="doctor">{w("doctor")}</label>
          <select id="doctor" ref={doctorRef} data-shortcut="doctor" aria-keyshortcuts="F6" className={styles.select} value={s.doctorId} onChange={(e) => { doctorTouched.current = true; fe.clearField("doctorId"); dispatch({ type: "doctor", value: e.target.value }); }} onBlur={() => v.blur(checkInSchema, "doctorId", { doctorId: s.doctorId })} {...inv("doctorId")}>
            <option value="">{w("selectDoctor")}</option>
            {staff.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>
          {fieldError("doctorId")}
        </div>

        <CheckInIntakeFields complaint={s.complaint} onComplaint={(v) => dispatch({ type: "complaint", value: v })}
          paymentMode={s.paymentMode} onPaymentMode={(v) => dispatch({ type: "payment", value: v })} region={region} />

        {s.error && <div className={styles.formError} role="alert">{s.error}</div>}

        <DuplicateReview candidates={s.candidates} submitting={submitting}
          onUse={(c) => dispatch({ type: "useCandidate", patient: { id: c.patientId, name: c.name, phone: c.phone, mrn: "" } })}
          onDifferent={() => void registerAndCheckIn(true)} />

        <div className={styles.modalActions}>
          <button type="button" className={styles.cancelBtn} onClick={onClose}>{t("dialog.cancel")}</button>
          <button type="submit" className={styles.submitBtn} disabled={submitting} aria-keyshortcuts="Control+Enter">{submitting ? w("submitting") : w("submit")}</button>
        </div>
      </form>
    </Dialog>
  );
}
