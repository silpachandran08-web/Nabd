"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import styles from "../setup.module.css";
import AccessGate from "../../../components/ui/AccessGate";
import PatientImport from "../../../components/setup/PatientImport";
import BusinessDetailsStep, { type BusinessProfile } from "../../../components/setup/BusinessDetailsStep";
import { ErrorState, Skeleton } from "../../../components/ui/StateViews";
import { api, reasonText } from "../../../lib/api";
import { can } from "../../../lib/permissions";
import {
  PIC_REGISTRATION_KEY, blockerText, doneCount, licenceLabel, mergeLicences, printerTestHtml, wizardSteps,
  type PharmacySetup, type WizardStep,
} from "./pharmacyWizardModel";

// PHL-032: the five-step pharmacy setup. It only reads and writes existing endpoints (clinic profile, pharmacy settings, the ADM-013 stock
// import) plus GET /pharmacy/setup for the state, so each step can be left and resumed from any device.
type Profile = BusinessProfile;
type Settings = { licenceNumbers: Record<string, string>; pharmacistInChargeStaffId: string | null };
type StaffRow = { id: string; name: string; status: string };

export default function PharmacyWizardPage() {
  return (
    <AccessGate permission="setup:view" surface="Pharmacy setup">
      <main className={styles.page}>
        <h1 className={styles.title}>Pharmacy setup</h1>
        <p className={styles.subtitle}>Five steps from sign-up to your first sale.</p>
        <Wizard />
      </main>
    </AccessGate>
  );
}

function Wizard() {
  const [setup, setSetup] = useState<PharmacySetup | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [staff, setStaff] = useState<StaffRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, p, st] = await Promise.all([api.get<PharmacySetup>("/pharmacy/setup"), api.get<Profile>("/setup/profile"), api.get<Settings>("/pharmacy/settings")]);
      setSetup(s); setProfile(p); setSettings(st); setError(null);
      if (can("staff:view")) api.get<{ data: StaffRow[] }>("/staff?limit=50").then((r) => setStaff(r.data.filter((x) => x.status === "active"))).catch(() => setStaff([]));
    } catch (e) { setError(reasonText(e, "Could not load the pharmacy setup.")); }
    setLoaded(true); // never goes back: a refresh keeps the page, it does not swap it for a skeleton
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  if (!loaded) return <Skeleton rows={5} label="Loading pharmacy setup" />;
  if (error && !setup) return <ErrorState message={error} onRetry={() => { void load(); }} />;
  if (!setup || !profile || !settings) return null;

  const steps = wizardSteps(setup, profile);
  const blocker = blockerText(setup);
  const canEdit = can("setup:edit") && can("pharmacy:edit");
  return (
    <>
      <p aria-live="polite">{doneCount(steps)} of {steps.length} steps done</p>
      {blocker && <div className={styles.errorState} role="alert">{blocker}</div>}
      {error && <div className={styles.errorState} role="alert">{error}</div>}
      <div>
        {steps.map((step, i) => (
          <section key={step.key} className={styles.card} aria-label={step.title}>
            <h2 className={styles.cardTitle}>{i + 1}. {step.title} <StepPill step={step} /></h2>
            {step.key === "business" && <BusinessDetailsStep profile={profile} canEdit={canEdit} onSaved={load} onError={setError} />}
            {step.key === "licence" && <Licences setup={setup} settings={settings} canEdit={canEdit} onSaved={load} onError={setError} />}
            {step.key === "in_charge" && <InCharge settings={settings} staff={staff} canEdit={canEdit} onSaved={load} onError={setError} />}
            {step.key === "printer" && <Printer shop={profile.name} done={step.done} canEdit={canEdit} onSaved={load} onError={setError} />}
            {step.key === "stock" && (
              <>
                {canEdit ? <PatientImport startWith={{ importType: "stock", source: "csv" }} /> : <p className={styles.empty}>Ask the owner to import the opening stock.</p>}
                <p>{setup.stockedItems} item{setup.stockedItems === 1 ? "" : "s"} in stock. <button type="button" className={styles.btn} onClick={() => { void load(); }}>Check again</button></p>
              </>
            )}
          </section>
        ))}
      </div>
      {!setup.invoicingBlocked && <p><Link href="/pharmacy/pos" className={styles.btnPrimary}>Make your first sale</Link></p>}
    </>
  );
}

function StepPill({ step }: { step: WizardStep }) {
  if (step.done) return <span className={`${styles.pill} ${styles.pillDone}`}>Done</span>;
  if (step.blocker) return <span className={`${styles.pill} ${styles.pillExpired}`}>Blocker: no invoices until done</span>;
  return <span className={`${styles.pill} ${styles.pillPending}`}>To do</span>;
}

type StepProps = { canEdit: boolean; onSaved: () => Promise<void>; onError: (m: string | null) => void };

function Licences({ setup, settings, canEdit, onSaved, onError }: StepProps & { setup: PharmacySetup; settings: Settings }) {
  const [vals, setVals] = useState<Record<string, string>>(() => Object.fromEntries(setup.requiredLicenceKeys.map((k) => [k, settings.licenceNumbers[k] ?? ""])));
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true); onError(null);
    try { await api.patch("/pharmacy/settings", { licenceNumbers: mergeLicences(settings.licenceNumbers, vals) }); await onSaved(); }
    catch (e) { onError(reasonText(e, "Could not save the licence numbers.")); }
    setBusy(false);
  };
  return (
    <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
      {setup.requiredLicenceKeys.map((k) => (
        <div key={k} className={styles.field}>
          <label className={styles.label} htmlFor={`pw-${k}`}>{licenceLabel(k)}</label>
          <input id={`pw-${k}`} className={styles.input} value={vals[k] ?? ""} maxLength={80} onChange={(e) => setVals({ ...vals, [k]: e.target.value })} disabled={!canEdit} />
        </div>
      ))}
      {canEdit && <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving" : "Save licence numbers"}</button>}
    </form>
  );
}

function InCharge({ settings, staff, canEdit, onSaved, onError }: StepProps & { settings: Settings; staff: StaffRow[] }) {
  const [who, setWho] = useState(settings.pharmacistInChargeStaffId ?? "");
  const [reg, setReg] = useState(settings.licenceNumbers[PIC_REGISTRATION_KEY] ?? "");
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true); onError(null);
    try { await api.patch("/pharmacy/settings", { pharmacistInChargeStaffId: who, licenceNumbers: mergeLicences(settings.licenceNumbers, { [PIC_REGISTRATION_KEY]: reg }) }); await onSaved(); }
    catch (e) { onError(reasonText(e, "Could not save the pharmacist in charge.")); }
    setBusy(false);
  };
  return (
    <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="pw-pic">Pharmacist in charge</label>
        <select id="pw-pic" className={styles.input} value={who} onChange={(e) => setWho(e.target.value)} disabled={!canEdit || staff.length === 0}>
          <option value="">Choose a staff member</option>
          {staff.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      </div>
      <div className={styles.field}><label className={styles.label} htmlFor="pw-reg">Pharmacist registration number</label><input id="pw-reg" className={styles.input} value={reg} maxLength={80} onChange={(e) => setReg(e.target.value)} disabled={!canEdit} /></div>
      {canEdit && <button type="submit" className={styles.btnPrimary} disabled={busy || !who || !reg.trim()}>{busy ? "Saving" : "Save"}</button>}
    </form>
  );
}

function Printer({ shop, done, canEdit, onSaved, onError }: StepProps & { shop: string; done: boolean }) {
  const [printed, setPrinted] = useState(false);
  const [busy, setBusy] = useState(false);
  const print = () => {
    const f = document.createElement("iframe");
    f.style.display = "none";
    f.srcdoc = printerTestHtml(shop, new Date());
    f.onload = () => { f.contentWindow?.print(); setPrinted(true); setTimeout(() => f.remove(), 60_000); };
    document.body.appendChild(f);
  };
  const confirm = async () => {
    setBusy(true); onError(null);
    try { await api.post("/pharmacy/setup/printer-test", {}); await onSaved(); }
    catch (e) { onError(reasonText(e, "Could not record the printer test.")); }
    setBusy(false);
  };
  if (!canEdit) return <p className={styles.empty}>{done ? "The printer was tested." : "Ask the owner to test the receipt printer."}</p>;
  return (
    <div className={styles.row}>
      <button type="button" className={styles.btn} onClick={print}>Print test page</button>
      {(printed || done) && <button type="button" className={styles.btnPrimary} onClick={() => { void confirm(); }} disabled={busy}>{done ? "Test again, it printed" : "It printed"}</button>}
    </div>
  );
}
