"use client";

import { useTranslations } from "next-intl";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Icon from "../ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { useHasMounted } from "../../lib/useHasMounted";
import { age, type Allergy, type AllergyStatus, type PatientDetail, type Prescription, type PrescriptionAllergySnapshot } from "./shared";
import { PrintSheet } from "@nabd/ui";
import { usePrintPaper } from "../../lib/usePrintPaper";
import r from "./rxPrint.module.css";
import { responseFetch } from "../../lib/responseFetch";

// DOC-N1: printable SIGNED prescription. Data comes from GET /clinical/prescriptions/{queueEntryId},
// GET /clinical/prescriptions/{queueEntryId}/print-header (clinic + prescriber of record + registration number),
// GET /patients/{id}. DOC-R3: the allergy statement printed is the one captured AT SIGNING (rx.allergySnapshot).
// Only a prescription signed before that snapshot existed (null snapshot) falls back to the current register
// (/clinical/patients/{id}/allergies[/status]), and the printout then says the status at signing was not recorded.
// Nothing is altered: medicine, dose, frequency, duration and instructions are printed exactly as signed.
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type PrintHeader = {
  prescriptionId: string; signedAt: string | null; clinicName: string; clinicRegion: string; clinicPhone: string | null;
  prescriberId: string | null; prescriberName: string | null; professionalRegistrationNo: string | null;
  genericFirst?: boolean; // CLN-021: print the generic (salt) first, the brand in brackets only if one was chosen
  qrPayload?: string | null; qrSvg?: string | null; // PHL-013: the signed Rx QR the pharmacy scans (server-rendered SVG)
};
type RxFull = Prescription & { patientId: string };
type AllergyView =
  | { kind: "atSigning"; snapshot: PrescriptionAllergySnapshot }
  | { kind: "currentRegister"; allergies: Allergy[]; allergyStatus: AllergyStatus };
type Loaded = { rx: RxFull; header: PrintHeader; patient: PatientDetail; allergy: AllergyView };
type State = { kind: "loading" } | { kind: "error"; message: string } | { kind: "draft" } | { kind: "ok"; data: Loaded };

class LoadError extends Error {}

// DOC-R3: a snapshot that is present but not in the expected shape must block printing, never print as "none".
const SNAPSHOT_STATES = ["recorded", "none_documented", "not_assessed"];
function validSnapshot(x: unknown): x is PrescriptionAllergySnapshot {
  if (!x || typeof x !== "object") return false;
  const o = x as Record<string, unknown>;
  if (typeof o.state !== "string" || !SNAPSHOT_STATES.includes(o.state) || !Array.isArray(o.allergies)) return false;
  // "recorded" needs at least one entry; "none_documented" and "not_assessed" must list none.
  if ((o.state === "recorded") !== (o.allergies.length > 0)) return false;
  const text = (v: unknown) => typeof v === "string" && v.trim() !== "";
  const entriesOk = o.allergies.every((a) => {
    if (!a || typeof a !== "object") return false;
    const e = a as Record<string, unknown>;
    return text(e.substance) && typeof e.severity === "string" && (e.reaction == null || typeof e.reaction === "string");
  });
  if (!entriesOk) return false;
  // "No known allergies" is a documented statement: who and when must be there (the server always sets both).
  if (o.state === "none_documented") {
    if (!text(o.documentedBy) || !text(o.documentedAt) || Number.isNaN(Date.parse(o.documentedAt as string))) return false;
    if (o.documentedByName != null && typeof o.documentedByName !== "string") return false;
  }
  return true;
}

export default function RxPrint({ queueEntryId }: { queueEntryId: string }) {
  const tx = useTranslations("consult.rxPrint");
  const g = useTranslations("glossary");
  const router = useRouter();
  const mounted = useHasMounted();
  const [state, setState] = useState<State>({ kind: "loading" });
  const [tick, setTick] = useState(0);
  const allowed = mounted && can("clinical:view");
  const paper = usePrintPaper("rx"); // FEF-046: clinic default, no prompt

  const get = useCallback(async (path: string): Promise<Response> => {
    const res = await responseFetch(path);
    if (!res) throw new LoadError("signed out"); // 401: the api client has sent the user to sign-in
    return res;
  }, []);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (cancelled) return;
      setState({ kind: "loading" });
      try {
        const rxRes = await get(`/clinical/prescriptions/${queueEntryId}`);
        if (rxRes.status === 404) throw new LoadError(tx("thereIsNoPrescriptionFor"));
        if (!rxRes.ok) throw new LoadError(tx("couldnTLoadThePrescription"));
        const rx: RxFull = await rxRes.json();
        if (rx.status !== "signed") {
          if (!cancelled) setState({ kind: "draft" });
          return;
        }
        // A safe print needs every part: a missing allergy statement must never print as an empty one.
        // DOC-R3: the register is read only for a legacy prescription that has no snapshot from signing.
        // undefined = the server predates the field; null = legacy signed row; anything else must be a valid snapshot.
        const snapshot = rx.allergySnapshot ?? null;
        if (snapshot !== null && !validSnapshot(snapshot)) {
          throw new LoadError(tx("theAllergyStatementSavedAt"));
        }
        const [hRes, pRes, aRes, sRes] = await Promise.all([
          get(`/clinical/prescriptions/${queueEntryId}/print-header`),
          get(`/patients/${rx.patientId}`),
          snapshot ? Promise.resolve(null) : get(`/clinical/patients/${rx.patientId}/allergies`),
          snapshot ? Promise.resolve(null) : get(`/clinical/patients/${rx.patientId}/allergies/status`),
        ]);
        if (hRes.status === 409) {
          if (!cancelled) setState({ kind: "draft" });
          return;
        }
        if (!hRes.ok) throw new LoadError(tx("couldnTLoadTheClinic"));
        if (!pRes.ok) throw new LoadError(tx("couldnTLoadThePatient"));
        let allergy: AllergyView;
        if (snapshot) {
          allergy = { kind: "atSigning", snapshot };
        } else {
          if (!aRes || !sRes || !aRes.ok || !sRes.ok) throw new LoadError(tx("couldnTConfirmTheAllergy"));
          allergy = { kind: "currentRegister", allergies: await aRes.json(), allergyStatus: await sRes.json() };
        }
        const data: Loaded = { rx, header: await hRes.json(), patient: await pRes.json(), allergy };
        if (!cancelled) setState({ kind: "ok", data });
      } catch (e) {
        if (cancelled || (e instanceof LoadError && e.message === "signed out")) return;
        setState({ kind: "error", message: e instanceof LoadError ? e.message : tx("couldnTReachTheServer") });
      }
    });
    return () => { cancelled = true; };
  }, [allowed, get, queueEntryId, tick]);

  if (!mounted) return null;
  if (!allowed) {
    return <main className={r.page}><EmptyState icon="shield" title={tx("yourRoleDoesnTHave")} /></main>;
  }
  if (state.kind === "loading") return <main className={r.page}><Skeleton rows={6} label={tx("loadingPrescription")} /></main>;
  if (state.kind === "error") return <main className={r.page}><ErrorState message={state.message} onRetry={() => setTick((t) => t + 1)} /></main>;
  if (state.kind === "draft") {
    return (
      <main className={r.page}>
        <EmptyState title={tx("thisPrescriptionIsnTSigned")} hint={tx("onlyASignedPrescriptionCan")}
          action={<button type="button" className={r.btn} onClick={() => router.back()}>{tx("backToConsultation")}</button>} />
      </main>
    );
  }

  const { rx, header, patient, allergy } = state.data;
  // One shape for both sources, so the three statements (recorded / no known allergies / not asked) print identically.
  const statement = allergy.kind === "atSigning"
    ? { state: allergy.snapshot.state, entries: allergy.snapshot.allergies, documentedByName: allergy.snapshot.documentedByName, documentedAt: allergy.snapshot.documentedAt }
    : { state: allergy.allergyStatus.state, entries: allergy.allergies.filter((a) => a.active), documentedByName: allergy.allergyStatus.documentedByName, documentedAt: allergy.allergyStatus.documentedAt };
  const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }) : "—");
  const signedDay = rx.signedAt ? new Date(rx.signedAt).toLocaleDateString(undefined, { dateStyle: "medium" }) : "—";
  const printedOn = new Date().toLocaleDateString(undefined, { dateStyle: "medium" });

  return (
    <main className={r.page}>
      <div className={r.toolbar}>
        <h1 className={r.toolbarTitle}>{tx("printPrescription")}</h1>
        <div className={r.toolbarActions}>
          <button type="button" className={r.btn} onClick={() => router.back()}>{tx("back")}</button>
          <button type="button" className={`${r.btn} ${r.btnPrimary}`} onClick={() => window.print()}><Icon name="receipt" size={14} /> {" "}{tx("print")}</button>
        </div>
      </div>
      {!header.professionalRegistrationNo && (
        <div className={r.notice} role="status">
          <Icon name="alert" size={16} />
          <span>{tx("registrationNumberNotSetFor")}{" "}{header.prescriberName ?? tx("thePrescriber")}{tx("itWillBeLeftOff")}</span>
        </div>
      )}
      <div className={`${r.notice} ${r.noticeInfo}`} role="note">
        <Icon name="info" size={16} />
        <span>{tx("medicinesArePrintedExactlyAs")}</span>
      </div>

      <PrintSheet paper={paper} label={g("rx.prescription")} bare>
      <article className={r.sheet} aria-label={g("rx.prescription")}>
        <header className={r.letterhead}>
          <div>
            <h2 className={r.clinicName}><bdi>{header.clinicName}</bdi></h2>
            {header.clinicPhone && <div className={r.muted}>{header.clinicPhone}</div>}
          </div>
          <div className={r.prescriber}>
            <div className={r.prescriberName}><bdi>{header.prescriberName ?? tx("prescriber")}</bdi></div>
            {header.professionalRegistrationNo && <div>{tx("regNo", { professionalRegistrationNo: header.professionalRegistrationNo })}</div>}
          </div>
        </header>

        <div className={r.metaRow}>
          <div className={r.metaItem}><span className={r.metaLabel}>{tx("patient")}</span><span>{patient.name}</span></div>
          <div className={r.metaItem}><span className={r.metaLabel}>{tx("mrn")}</span><span>{patient.mrn}</span></div>
          <div className={r.metaItem}><span className={r.metaLabel}>{tx("ageSex")}</span><span>{patient.dob ? `${age(patient.dob)} y` : "—"} · {patient.gender}</span></div>
          <div className={r.metaItem}><span className={r.metaLabel}>{tx("date")}</span><span>{signedDay}</span></div>
        </div>

        <section className={r.allergyBox} aria-label={g("allergy.plural")}>
          {allergy.kind === "atSigning" ? (
            <h3 className={r.sectionTitle}>{tx("allergiesAtSigning")}{when(rx.signedAt)})</h3>
          ) : (
            <>
              <p className={r.muted} role="note">{tx("allergyStatusAtSigningNot", { signedDay })}</p>
              <h3 className={r.sectionTitle}>{tx("allergiesAsRecordedInThe", { printedOn })}</h3>
            </>
          )}
          {statement.entries.length > 0 ? (
            <ul className={r.allergyList}>
              {statement.entries.map((a, i) => (
                <li key={`${a.substance}-${i}`}>{a.substance}{a.severity ? ` (${a.severity})` : ""}{a.reaction ? ` · ${a.reaction}` : ""}</li>
              ))}
            </ul>
          ) : statement.state === "none_documented" ? (
            <div>{g("allergy.nkaDocumented")}{statement.documentedByName ? ` by ${statement.documentedByName}` : ""}{statement.documentedAt ? ` on ${when(statement.documentedAt)}` : ""}.</div>
          ) : (
            <div>{tx("noAllergyInformationRecordedAllergies")}</div>
          )}
        </section>

        <section aria-label={g("rx.medicines")}>
          <h3 className={r.sectionTitle}>{g("rx.medicines")}</h3>
          <ol className={r.meds}>
            {rx.items.map((m, i) => (
              <li key={m.id ?? i} className={r.med}>
                <span className={r.medNo}>{i + 1}.</span>
                <div>
                  {header.genericFirst
                    ? <div className={r.medName} data-testid="med-line">{m.brandName ? `${m.drugName} (${m.brandName})` : m.drugName}</div>
                    : <>
                        <div className={r.medName}>{m.drugName}</div>
                        {m.brandName && <div className={r.medInstructions}><span className={r.muted}>{tx("brand")}{" "}</span>{m.brandName}</div>}
                      </>}
                  <div className={r.medFields}>
                    {m.route && <span className={r.medField}><span className={r.muted}>{g("rx.route")}</span>{m.route}</span>}
                    {m.dosage && <span className={r.medField}><span className={r.muted}>{g("rx.dose")}</span>{m.dosage}</span>}
                    {m.sigPattern && m.sigPattern !== m.frequency && <span className={r.medField}><span className={r.muted}>{tx("pattern")}</span>{m.sigPattern}</span>}
                    {m.frequency && <span className={r.medField}><span className={r.muted}>{g("rx.frequency")}</span>{m.frequency}</span>}
                    {m.duration && <span className={r.medField}><span className={r.muted}>{g("rx.duration")}</span>{m.duration}</span>}
                    {m.quantity != null && <span className={r.medField}><span className={r.muted}>{g("rx.quantity")}</span>{m.quantity}{m.quantityUnit ? ` ${m.quantityUnit}` : ""}</span>}
                  </div>
                  {m.instructions && <div className={r.medInstructions}><span className={r.muted}>{g("rx.instructions")}{" "}</span>{m.instructions}</div>}
                </div>
              </li>
            ))}
          </ol>
        </section>

        <footer className={r.signature}>
          <div>
            <div className={r.metaLabel}>{tx("signedElectronicallyBy")}</div>
            <div className={r.prescriberName}><bdi>{header.prescriberName ?? tx("prescriber")}</bdi></div>
            {header.professionalRegistrationNo && <div>{tx("regNo", { professionalRegistrationNo: header.professionalRegistrationNo })}</div>}
          </div>
          <div>
            <div className={r.metaLabel}>{tx("signedAt")}</div>
            <div>{when(rx.signedAt)}</div>
          </div>
          {rx.safetyDictionaryVersion && (
            <div>
              <div className={r.metaLabel}>{g("rx.safetyChecks")}</div>
              <div>{tx("nabdSafetyDictionary", { safetyDictionaryVersion: rx.safetyDictionaryVersion })}</div>
            </div>
          )}
          {header.qrSvg && (
            <figure className={r.qr} data-testid="rx-qr">
              <img className={r.qrImg} alt={tx("qrAlt")} src={`data:image/svg+xml;utf8,${encodeURIComponent(header.qrSvg)}`} />
              <figcaption className={r.metaLabel}>{tx("qrCaption")}</figcaption>
            </figure>
          )}
        </footer>
      </article>
      </PrintSheet>
    </main>
  );
}
