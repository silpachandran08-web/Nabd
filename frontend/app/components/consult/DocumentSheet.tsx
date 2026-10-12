import { DOC_FIELDS, DOC_LABEL, obj, registrationNumber, str, type DocBody, type DocType } from "./documents";
import s from "./documentSheet.module.css";

// Clinic wall-clock dates (the region is chosen at provisioning), same map the follow-up panel uses.
const REGION_TZ: Record<string, string> = { IN: "Asia/Kolkata", KSA: "Asia/Riyadh" };

/** Values shown while the document is still a draft; replaced by the issue-time snapshot once issued. */
export type SheetFallback = {
  clinicName: string; patientName: string; mrn?: string; dob?: string; gender?: string; doctorName?: string;
};

function fmtDate(ymdOrIso: string, tz?: string): string {
  if (!ymdOrIso) return "";
  const d = new Date(ymdOrIso.length === 10 ? `${ymdOrIso}T00:00:00Z` : ymdOrIso);
  if (Number.isNaN(d.getTime())) return ymdOrIso;
  return d.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric", timeZone: ymdOrIso.length === 10 ? "UTC" : tz });
}

export default function DocumentSheet({
  type, body, header, issuedAt, fallback,
}: { type: DocType; body: DocBody; header: Record<string, unknown> | null; issuedAt?: string | null; fallback?: SheetFallback }) {
  const issued = header !== null;
  const clinic = obj(header, "clinic");
  const patient = obj(header, "patient");
  const doctor = obj(header, "doctor");
  const visit = obj(header, "visit");
  const tz = REGION_TZ[str(clinic, "region")];

  const clinicName = issued ? str(clinic, "name") : fallback?.clinicName ?? "";
  const patientName = issued ? str(patient, "name") : fallback?.patientName ?? "";
  const mrn = issued ? str(patient, "mrn") : fallback?.mrn ?? "";
  const dob = issued ? str(patient, "dob") : fallback?.dob ?? "";
  const gender = issued ? str(patient, "gender") : fallback?.gender ?? "";
  const doctorName = issued ? str(doctor, "name") : fallback?.doctorName ?? "";
  const reg = issued ? registrationNumber(header) : "";
  const dateText = issued ? fmtDate(issuedAt || str(visit, "date"), tz) : "";

  const filled = DOC_FIELDS[type].filter((f) => (body[f.key] ?? "").trim());

  return (
    <article className={s.sheet} aria-label={`${DOC_LABEL[type]}${issued ? "" : " preview"}`}>
      <header className={s.letterhead}>
        <div>
          {clinicName && <p className={s.clinic}>{clinicName}</p>}
          <h2 className={s.docTitle}>{DOC_LABEL[type]}</h2>
        </div>
        {!issued && <span className={s.marker}>Preview — not issued</span>}
      </header>
      <dl className={s.facts}>
        <div><dt>Patient</dt><dd>{patientName || "—"}</dd></div>
        {mrn && <div><dt>MRN</dt><dd>{mrn}</dd></div>}
        {dob && <div><dt>Date of birth</dt><dd>{fmtDate(dob)}</dd></div>}
        {gender && <div><dt>Gender</dt><dd>{gender}</dd></div>}
        {dateText && <div><dt>Date</dt><dd>{dateText}</dd></div>}
      </dl>
      {filled.length === 0 ? (
        <p className={s.empty}>Nothing entered yet.</p>
      ) : (
        <dl className={s.fields}>
          {filled.map((f) => (
            <div key={f.key}>
              <dt>{f.label}</dt>
              <dd>{f.kind === "date" ? fmtDate(body[f.key]) : body[f.key]}</dd>
            </div>
          ))}
        </dl>
      )}
      <footer className={s.signoff}>
        {doctorName && <span className={s.signName}>{doctorName}</span>}
        {reg && <span className={s.small}>Registration no. {reg}</span>}
        {issued && clinicName && <span className={s.small}>{clinicName}</span>}
      </footer>
    </article>
  );
}
