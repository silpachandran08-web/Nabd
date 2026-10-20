"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Icon from "../ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { useHasMounted } from "../../lib/useHasMounted";
import { age, type Allergy, type AllergyStatus, type PatientDetail, type Prescription, type PrescriptionAllergySnapshot } from "./shared";
import r from "./rxPrint.module.css";
import { apiFetch, goLogin } from "../../lib/apiClient";

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
  const router = useRouter();
  const mounted = useHasMounted();
  const [state, setState] = useState<State>({ kind: "loading" });
  const [tick, setTick] = useState(0);
  const allowed = mounted && can("clinical:view");

  const get = useCallback(async (path: string): Promise<Response> => {
    const token = localStorage.getItem("nabd_access_token");
    if (!token) {
      goLogin();
      throw new LoadError("signed out");
    }
    const res = await apiFetch(path, { headers: { Authorization: `Bearer ${token}` } });
    if (res.status === 401) {
      localStorage.removeItem("nabd_access_token");
      goLogin();
      throw new LoadError("signed out");
    }
    return res;
  }, [router]);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (cancelled) return;
      setState({ kind: "loading" });
      try {
        const rxRes = await get(`/clinical/prescriptions/${queueEntryId}`);
        if (rxRes.status === 404) throw new LoadError("There is no prescription for this visit.");
        if (!rxRes.ok) throw new LoadError("Couldn't load the prescription.");
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
          throw new LoadError("The allergy statement saved at signing couldn't be read, so the prescription can't be printed.");
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
        if (!hRes.ok) throw new LoadError("Couldn't load the clinic and prescriber details.");
        if (!pRes.ok) throw new LoadError("Couldn't load the patient details.");
        let allergy: AllergyView;
        if (snapshot) {
          allergy = { kind: "atSigning", snapshot };
        } else {
          if (!aRes || !sRes || !aRes.ok || !sRes.ok) throw new LoadError("Couldn't confirm the allergy register, so the prescription can't be printed yet.");
          allergy = { kind: "currentRegister", allergies: await aRes.json(), allergyStatus: await sRes.json() };
        }
        const data: Loaded = { rx, header: await hRes.json(), patient: await pRes.json(), allergy };
        if (!cancelled) setState({ kind: "ok", data });
      } catch (e) {
        if (cancelled || (e instanceof LoadError && e.message === "signed out")) return;
        setState({ kind: "error", message: e instanceof LoadError ? e.message : "Couldn't reach the server. Check your connection and retry." });
      }
    });
    return () => { cancelled = true; };
  }, [allowed, get, queueEntryId, tick]);

  if (!mounted) return null;
  if (!allowed) {
    return <main className={r.page}><EmptyState icon="shield" title="Your role doesn't have access to prescriptions." /></main>;
  }
  if (state.kind === "loading") return <main className={r.page}><Skeleton rows={6} label="Loading prescription" /></main>;
  if (state.kind === "error") return <main className={r.page}><ErrorState message={state.message} onRetry={() => setTick((t) => t + 1)} /></main>;
  if (state.kind === "draft") {
    return (
      <main className={r.page}>
        <EmptyState title="This prescription isn't signed yet." hint="Only a signed prescription can be printed. Sign it in the consultation first."
          action={<button type="button" className={r.btn} onClick={() => router.back()}>Back to consultation</button>} />
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
        <h1 className={r.toolbarTitle}>Print prescription</h1>
        <div className={r.toolbarActions}>
          <button type="button" className={r.btn} onClick={() => router.back()}>Back</button>
          <button type="button" className={`${r.btn} ${r.btnPrimary}`} onClick={() => window.print()}><Icon name="receipt" size={14} /> Print</button>
        </div>
      </div>
      {!header.professionalRegistrationNo && (
        <div className={r.notice} role="status">
          <Icon name="alert" size={16} />
          <span>Registration number not set for {header.prescriberName ?? "the prescriber"}. It will be left off the printout. Ask a staff administrator to add it in Staff &amp; Access.</span>
        </div>
      )}
      <div className={`${r.notice} ${r.noticeInfo}`} role="note">
        <Icon name="info" size={16} />
        <span>Medicines are printed exactly as the prescriber typed them. No name is added or substituted.</span>
      </div>

      <article className={r.sheet} aria-label="Prescription">
        <header className={r.letterhead}>
          <div>
            <h2 className={r.clinicName}><bdi>{header.clinicName}</bdi></h2>
            {header.clinicPhone && <div className={r.muted}>{header.clinicPhone}</div>}
          </div>
          <div className={r.prescriber}>
            <div className={r.prescriberName}><bdi>{header.prescriberName ?? "Prescriber"}</bdi></div>
            {header.professionalRegistrationNo && <div>Reg. No. {header.professionalRegistrationNo}</div>}
          </div>
        </header>

        <div className={r.metaRow}>
          <div className={r.metaItem}><span className={r.metaLabel}>Patient</span><span>{patient.name}</span></div>
          <div className={r.metaItem}><span className={r.metaLabel}>MRN</span><span>{patient.mrn}</span></div>
          <div className={r.metaItem}><span className={r.metaLabel}>Age / sex</span><span>{patient.dob ? `${age(patient.dob)} y` : "—"} · {patient.gender}</span></div>
          <div className={r.metaItem}><span className={r.metaLabel}>Date</span><span>{signedDay}</span></div>
        </div>

        <section className={r.allergyBox} aria-label="Allergies">
          {allergy.kind === "atSigning" ? (
            <h3 className={r.sectionTitle}>Allergies at signing ({when(rx.signedAt)})</h3>
          ) : (
            <>
              <p className={r.muted} role="note">Allergy status at signing not recorded (signed {signedDay}, before it was captured with the prescription).</p>
              <h3 className={r.sectionTitle}>Allergies (as recorded in the register, {printedOn})</h3>
            </>
          )}
          {statement.entries.length > 0 ? (
            <ul className={r.allergyList}>
              {statement.entries.map((a, i) => (
                <li key={`${a.substance}-${i}`}>{a.substance}{a.severity ? ` (${a.severity})` : ""}{a.reaction ? ` · ${a.reaction}` : ""}</li>
              ))}
            </ul>
          ) : statement.state === "none_documented" ? (
            <div>No known allergies, documented{statement.documentedByName ? ` by ${statement.documentedByName}` : ""}{statement.documentedAt ? ` on ${when(statement.documentedAt)}` : ""}.</div>
          ) : (
            <div>No allergy information recorded. Allergies have not been asked or documented.</div>
          )}
        </section>

        <section aria-label="Medicines">
          <h3 className={r.sectionTitle}>Medicines</h3>
          <ol className={r.meds}>
            {rx.items.map((m, i) => (
              <li key={m.id ?? i} className={r.med}>
                <span className={r.medNo}>{i + 1}.</span>
                <div>
                  <div className={r.medName}>{m.drugName}</div>
                  {m.brandName && <div className={r.medInstructions}><span className={r.muted}>Brand </span>{m.brandName}</div>}
                  <div className={r.medFields}>
                    {m.route && <span className={r.medField}><span className={r.muted}>Route</span>{m.route}</span>}
                    {m.dosage && <span className={r.medField}><span className={r.muted}>Dose</span>{m.dosage}</span>}
                    {m.sigPattern && m.sigPattern !== m.frequency && <span className={r.medField}><span className={r.muted}>Pattern</span>{m.sigPattern}</span>}
                    {m.frequency && <span className={r.medField}><span className={r.muted}>Frequency</span>{m.frequency}</span>}
                    {m.duration && <span className={r.medField}><span className={r.muted}>Duration</span>{m.duration}</span>}
                    {m.quantity != null && <span className={r.medField}><span className={r.muted}>Quantity</span>{m.quantity}{m.quantityUnit ? ` ${m.quantityUnit}` : ""}</span>}
                  </div>
                  {m.instructions && <div className={r.medInstructions}><span className={r.muted}>Instructions </span>{m.instructions}</div>}
                </div>
              </li>
            ))}
          </ol>
        </section>

        <footer className={r.signature}>
          <div>
            <div className={r.metaLabel}>Signed electronically by</div>
            <div className={r.prescriberName}><bdi>{header.prescriberName ?? "Prescriber"}</bdi></div>
            {header.professionalRegistrationNo && <div>Reg. No. {header.professionalRegistrationNo}</div>}
          </div>
          <div>
            <div className={r.metaLabel}>Signed at</div>
            <div>{when(rx.signedAt)}</div>
          </div>
          {rx.safetyDictionaryVersion && (
            <div>
              <div className={r.metaLabel}>Safety checks</div>
              <div>Nabd safety dictionary {rx.safetyDictionaryVersion}</div>
            </div>
          )}
        </footer>
      </article>
    </main>
  );
}
