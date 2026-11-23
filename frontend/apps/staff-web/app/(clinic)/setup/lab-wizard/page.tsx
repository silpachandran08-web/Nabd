"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import styles from "../setup.module.css";
import w from "./labWizard.module.css";
import AccessGate from "../../../components/ui/AccessGate";
import BusinessDetailsStep, { type BusinessProfile } from "../../../components/setup/BusinessDetailsStep";
import { ImportDialog } from "../../../components/lab/LabCatalogue";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/StateViews";
import { api, reasonText } from "../../../lib/api";
import { useEntitlementsStatus } from "../../../lib/entitlements";
import { can } from "../../../lib/permissions";
import {
  PRICE_FILE_MAX_CHARS, labWizardGate, labWizardSteps, labelTestHtml, letterheadError, letterheadPreview, priceImportSummary, priceTemplateCsv, requiredLeft, setupBody,
  type LabSetup, type PriceImport, type SetupBody, type WizardStep,
} from "./labWizardModel";

// PHL-056: the six-step lab setup. The lab's record is GET / PUT /lab/setup, the business details are the clinic profile, the starter
// catalogue is the PHL-041 import and prices come from the lab's own price list (dry run first). Each step can be left and resumed.
type StaffRow = { id: string; name: string; status: string };
type TestPage = { items: { code: string; name: string }[]; nextCursor: string | null };

export default function LabWizardPage() {
  return (
    <AccessGate permission="setup:view" surface="Lab setup">
      <main className={styles.page}>
        <h1 className={styles.title}>Lab setup</h1>
        <p className={styles.subtitle}>Six steps from sign-up to your first sample label.</p>
        <Gate />
      </main>
    </AccessGate>
  );
}

/** Nothing below is mounted (so nothing is requested) until the entitlements answer says this clinic has the lab module. */
function Gate() {
  const { ent, settled, retry } = useEntitlementsStatus();
  const gate = labWizardGate(ent, settled, can("lab:view"));
  if (gate === "wait") return <Skeleton rows={6} label="Loading lab setup" />;
  if (gate === "unverified") return <ErrorState message="Could not check your plan, so the lab setup is not shown. Try again." onRetry={retry} />;
  if (gate === "blocked") return <EmptyState icon="shield" title="You do not have access to Lab setup." hint="It needs the lab module on your plan and a role that can see the lab." />;
  return <Wizard />;
}

function Wizard() {
  const [setup, setSetup] = useState<LabSetup | null>(null);
  const [profile, setProfile] = useState<BusinessProfile | null>(null);
  const [staff, setStaff] = useState<StaffRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, p] = await Promise.all([api.get<LabSetup>("/lab/setup"), api.get<BusinessProfile>("/setup/profile")]);
      setSetup(s); setProfile(p); setError(null);
      if (can("staff:view")) api.get<{ data: StaffRow[] }>("/staff?limit=50").then((r) => setStaff(r.data.filter((x) => x.status === "active"))).catch(() => setStaff([]));
    } catch (e) { setError(reasonText(e, "Could not load the lab setup.")); }
    setLoaded(true); // never goes back: a refresh keeps the page, it does not swap it for a skeleton
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const save = useCallback(async (edits: Partial<SetupBody>, failed: string) => {
    if (!setup) return;
    setError(null);
    try { setSetup(await api.put<LabSetup>("/lab/setup", setupBody(setup, edits))); }
    catch (e) { setError(reasonText(e, failed)); }
  }, [setup]);

  if (!loaded) return <Skeleton rows={6} label="Loading lab setup" />;
  if (error && !setup) return <ErrorState message={error} onRetry={() => { void load(); }} />;
  if (!setup || !profile) return null;

  const steps = labWizardSteps(setup, profile);
  const left = requiredLeft(steps);
  const canEdit = can("setup:edit") && can("lab:catalogue");
  return (
    <>
      <p aria-live="polite">{left === 0 ? "Every step is done." : `${left} step${left === 1 ? "" : "s"} to go.`}</p>
      {error && <div className={styles.errorState} role="alert">{error}</div>}
      {!canEdit && <p className={styles.hint}>You can see the setup. Changing it needs the owner or the lab manager.</p>}
      <div>
        {steps.map((step, i) => (
          <section key={step.key} className={styles.card} aria-label={step.title}>
            <h2 className={styles.cardTitle}>{i + 1}. {step.title} <StepPill step={step} /></h2>
            {step.key === "business" && (canEdit
              ? <BusinessDetailsStep profile={profile} canEdit onSaved={load} onError={setError} />
              : <p className={styles.empty}>{profile.name}{profile.taxId ? `, ${profile.taxIdType === "VAT" ? "VAT no." : "GSTIN"} ${profile.taxId}` : ""}</p>)}
            {step.key === "accreditation" && (canEdit
              ? <Accreditation setup={setup} save={save} />
              : <p className={styles.empty}>{setup.accreditationNo ? `NABL accreditation no. ${setup.accreditationNo}` : "No accreditation number entered."}</p>)}
            {step.key === "pathologist" && (canEdit
              ? <Pathologist setup={setup} staff={staff} save={save} />
              : <p className={styles.empty}>{setup.pathologistName ?? "No pathologist chosen yet."}</p>)}
            {step.key === "letterhead" && <LetterheadStep setup={setup} profile={profile} canEdit={canEdit} save={save} />}
            {step.key === "catalogue" && <Catalogue setup={setup} canEdit={canEdit} onChanged={load} onError={setError} />}
            {step.key === "printer" && <Printer lab={profile.name} done={step.done} canEdit={canEdit} onSaved={load} onError={setError} />}
          </section>
        ))}
      </div>
      {left === 0 && <p><Link href="/lab/catalog" className={styles.btnPrimary}>Open the test catalogue</Link></p>}
    </>
  );
}

function StepPill({ step }: { step: WizardStep }) {
  if (step.done) return <span className={`${styles.pill} ${styles.pillDone}`}>Done</span>;
  return <span className={`${styles.pill} ${styles.pillPending}`}>{step.optional ? "Optional" : "To do"}</span>;
}

type Save = (edits: Partial<SetupBody>, failed: string) => Promise<void>;

function Accreditation({ setup, save }: { setup: LabSetup; save: Save }) {
  const [no, setNo] = useState(setup.accreditationNo ?? "");
  const [busy, setBusy] = useState(false);
  const submit = async () => { setBusy(true); await save({ accreditationNo: no }, "Could not save the accreditation number."); setBusy(false); };
  return (
    <form onSubmit={(e) => { e.preventDefault(); void submit(); }}>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="lw-nabl">NABL accreditation number (leave empty if the lab is not accredited)</label>
        <input id="lw-nabl" className={styles.input} value={no} maxLength={40} onChange={(e) => setNo(e.target.value)} />
      </div>
      <button type="submit" className={`${styles.btnPrimary} ${w.target}`} disabled={busy}>{busy ? "Saving" : "Save"}</button>
    </form>
  );
}

function Pathologist({ setup, staff, save }: { setup: LabSetup; staff: StaffRow[]; save: Save }) {
  const [who, setWho] = useState(setup.pathologistStaffId ?? "");
  const [reg, setReg] = useState(setup.pathologistRegistrationNo ?? "");
  const [busy, setBusy] = useState(false);
  const submit = async () => { setBusy(true); await save({ pathologistStaffId: who, pathologistRegistrationNo: reg }, "Could not save the pathologist."); setBusy(false); };
  // the saved pathologist stays selectable even when the staff list could not be read
  const options = setup.pathologistStaffId && setup.pathologistName && !staff.some((s) => s.id === setup.pathologistStaffId)
    ? [{ id: setup.pathologistStaffId, name: setup.pathologistName, status: "active" }, ...staff] : staff;
  return (
    <form onSubmit={(e) => { e.preventDefault(); void submit(); }}>
      {options.length === 0 && <p className={styles.hint}>Invite the pathologist under Staff &amp; Access first, then choose them here.</p>}
      <div className={styles.field}>
        <label className={styles.label} htmlFor="lw-path">Pathologist who signs the reports</label>
        <select id="lw-path" className={styles.input} value={who} onChange={(e) => setWho(e.target.value)}>
          <option value="">Choose a staff member</option>
          {options.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      </div>
      <div className={styles.field}><label className={styles.label} htmlFor="lw-reg">Medical council registration number</label><input id="lw-reg" className={styles.input} value={reg} maxLength={80} onChange={(e) => setReg(e.target.value)} /></div>
      <button type="submit" className={`${styles.btnPrimary} ${w.target}`} disabled={busy || !who || !reg.trim()}>{busy ? "Saving" : "Save"}</button>
    </form>
  );
}

function LetterheadStep({ setup, profile, canEdit, save }: { setup: LabSetup; profile: BusinessProfile; canEdit: boolean; save: Save }) {
  const [text, setText] = useState(setup.letterheadLines ?? "");
  const [busy, setBusy] = useState(false);
  const problem = letterheadError(text);
  const head = letterheadPreview(profile, setup, canEdit ? text : undefined);
  const submit = async () => { setBusy(true); await save({ letterheadLines: text }, "Could not save the letterhead."); setBusy(false); };
  return (
    <>
      {canEdit && (
        <form onSubmit={(e) => { e.preventDefault(); void submit(); }}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="lw-head">Lines under the lab&apos;s name (address, phone, email, timings)</label>
            <textarea id="lw-head" className={styles.textarea} rows={4} value={text} onChange={(e) => setText(e.target.value)} aria-describedby={problem ? "lw-head-err" : undefined} />
            {problem && <p id="lw-head-err" className={styles.errorState} role="alert">{problem}</p>}
          </div>
          <button type="submit" className={`${styles.btnPrimary} ${w.target}`} disabled={busy || !!problem || !text.trim()}>{busy ? "Saving" : "Save letterhead"}</button>
        </form>
      )}
      <p className={styles.hint}>Preview: this is how the top of a report and an order sheet will read.</p>
      <div className={w.letterhead} aria-label="Letterhead preview">
        <p className={w.letterheadName}>{head.name}</p>
        {head.lines.length === 0 && <p className={styles.hint}>No address lines yet.</p>}
        {head.lines.map((l, i) => <p key={i}>{l}</p>)}
        {head.accreditation && <p>{head.accreditation}</p>}
        {head.tax && <p>{head.tax}</p>}
        {head.pathologist && <p className={w.letterheadFoot}>{head.pathologist}</p>}
      </div>
    </>
  );
}

function Catalogue({ setup, canEdit, onChanged, onError }: { setup: LabSetup; canEdit: boolean; onChanged: () => Promise<void>; onError: (m: string | null) => void }) {
  const [importing, setImporting] = useState(false);
  const [csv, setCsv] = useState<string | null>(null);
  const [diff, setDiff] = useState<PriceImport | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const template = async () => {
    onError(null);
    try {
      const tests: TestPage["items"] = [];
      let cursor: string | null = null;
      do {
        const page: TestPage = await api.get<TestPage>(`/lab/tests?limit=200${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
        tests.push(...page.items);
        cursor = page.nextCursor;
      } while (cursor);
      const a = document.createElement("a");
      a.href = URL.createObjectURL(new Blob([priceTemplateCsv(tests)], { type: "text/csv" }));
      a.download = "lab-price-list.csv";
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
    } catch (e) { onError(reasonText(e, "Could not build the price list file.")); }
  };
  const run = async (text: string, dryRun: boolean) => {
    setBusy(true); onError(null);
    try {
      const r = await api.post<PriceImport>("/lab/catalogue/price-imports", { csv: text, dryRun });
      if (dryRun) { setCsv(text); setDiff(r); setDone(null); }
      else { setCsv(null); setDiff(null); setDone(`${r.updated} test${r.updated === 1 ? " was" : "s were"} priced.`); await onChanged(); }
    } catch (e) { onError(reasonText(e, dryRun ? "Could not read the price list." : "Could not apply the price list.")); }
    setBusy(false);
  };
  const choose = async (file: File | undefined) => {
    if (!file) return;
    const text = await file.text();
    if (text.length > PRICE_FILE_MAX_CHARS) { onError("This file is too large for a price list. Keep it to 5,000 lines of code and price."); return; }
    await run(text, true);
  };

  return (
    <>
      <p>{setup.catalogueTests === 0 ? "The catalogue is empty." : `${setup.catalogueTests} test${setup.catalogueTests === 1 ? "" : "s"} in the catalogue, ${setup.activeTests} with a price.`}</p>
      {!canEdit && <p className={styles.empty}>Ask the owner or the lab manager to load the catalogue and the prices.</p>}
      {canEdit && (
        <>
          <div className={w.actions}>
            <button type="button" className={`${styles.btn} ${w.target}`} onClick={() => setImporting(true)}>Add the starter catalogue</button>
            {setup.catalogueTests > 0 && <button type="button" className={`${styles.btn} ${w.target}`} onClick={() => { void template(); }}>Download the price list to fill in</button>}
          </div>
          {setup.catalogueTests > 0 && (
            <div className={styles.field}>
              <label className={styles.label} htmlFor="lw-prices">Your price list (CSV: test code, price)</label>
              <input id="lw-prices" className={w.target} type="file" accept=".csv,text/csv" disabled={busy} onChange={(e) => { void choose(e.target.files?.[0]); e.target.value = ""; }} />
            </div>
          )}
          {diff && csv != null && (
            <div role="status">
              {priceImportSummary(diff).map((s) => <p key={s}>{s}</p>)}
              {diff.problems.length > 0 && <ul className={w.problems}>{diff.problems.slice(0, 8).map((p) => <li key={p.line}>{p.message}</li>)}</ul>}
              <div className={w.actions}>
                {diff.toPrice.length > 0 && <button type="button" className={`${styles.btnPrimary} ${w.target}`} disabled={busy} onClick={() => { void run(csv, false); }}>{busy ? "Applying" : `Set ${diff.toPrice.length} price${diff.toPrice.length === 1 ? "" : "s"}`}</button>}
                <button type="button" className={`${styles.btn} ${w.target}`} disabled={busy} onClick={() => { setCsv(null); setDiff(null); }}>Cancel</button>
              </div>
            </div>
          )}
          {done && <p role="status">{done}</p>}
        </>
      )}
      {importing && <ImportDialog onClose={() => setImporting(false)} onDone={() => { setImporting(false); void onChanged(); }} />}
    </>
  );
}

function Printer({ lab, done, canEdit, onSaved, onError }: { lab: string; done: boolean; canEdit: boolean; onSaved: () => Promise<void>; onError: (m: string | null) => void }) {
  const [printed, setPrinted] = useState(false);
  const [busy, setBusy] = useState(false);
  const print = () => {
    const f = document.createElement("iframe");
    f.style.display = "none";
    f.srcdoc = labelTestHtml(lab);
    f.onload = () => { f.contentWindow?.print(); setPrinted(true); setTimeout(() => f.remove(), 60_000); };
    document.body.appendChild(f);
  };
  const confirm = async () => {
    setBusy(true); onError(null);
    try { await api.post("/lab/setup/printer-test", {}); await onSaved(); }
    catch (e) { onError(reasonText(e, "Could not record the printer test.")); }
    setBusy(false);
  };
  if (!canEdit) return <p className={styles.empty}>{done ? "The label printer was tested." : "Ask the owner or the lab manager to test the label printer."}</p>;
  return (
    <div className={w.actions}>
      <button type="button" className={`${styles.btn} ${w.target}`} onClick={print}>Print a test label</button>
      {(printed || done) && <button type="button" className={`${styles.btnPrimary} ${w.target}`} onClick={() => { void confirm(); }} disabled={busy}>{done ? "Test again, it printed" : "It printed"}</button>}
    </div>
  );
}
