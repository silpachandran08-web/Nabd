"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./wizard.module.css";
import TimezoneSelect from "../TimezoneSelect";
import AccessGate from "../../components/ui/AccessGate";
import { api, ApiError, reasonText } from "../../lib/api";
import DpaPanel from "../../components/setup/DpaPanel";

// ADM-007: nine steps; progress and drafts live on the server, so a phone and a laptop show the same wizard.
type ChecklistItem = { step: string; status: "pending" | "current" | "skipped" | "done"; draft?: Record<string, unknown>; version?: number };
type Profile = { name: string; region: string; timezone: string; taxIdType: string | null; taxId: string | null; whatsappNumber: string | null; specialties: string[] };

const STEPS = [
  { key: "profile", title: "Clinic profile", minutes: 3 },
  { key: "preset", title: "Clinic type", minutes: 3 },
  { key: "doctors", title: "Doctors", minutes: 12 },
  { key: "prices", title: "Prices", minutes: 10 },
  { key: "whatsapp", title: "WhatsApp", minutes: 5 },
  { key: "tax", title: "Tax", minutes: 3 },
  { key: "dpa", title: "DPA", minutes: 3 },
  { key: "import", title: "Import", minutes: 10 },
  { key: "go_live", title: "Go live", minutes: 2 },
];

/** The form fields a step keeps as its draft (a slice of the clinic profile). Steps that only link to another screen keep none. */
function draftOf(step: string, p: Profile): Record<string, unknown> | null {
  if (step === "profile") return { name: p.name, timezone: p.timezone };
  if (step === "preset") return { specialties: p.specialties };
  if (step === "tax") return { taxIdType: p.taxIdType, taxId: p.taxId };
  if (step === "whatsapp") return { whatsappNumber: p.whatsappNumber };
  return null;
}

export default function SetupWizardPage() {
  return (
    <AccessGate permission="setup:view" surface="Clinic Setup">
      <SetupWizard />
    </AccessGate>
  );
}

function SetupWizard() {
  const router = useRouter();
  const [stepIndex, setStepIndex] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [checklist, setChecklist] = useState<ChecklistItem[]>([]);
  const [profile, setProfile] = useState<Profile>({ name: "", region: "IN", timezone: "UTC", taxIdType: null, taxId: null, whatsappNumber: null, specialties: [] });
  const saved = useRef<Record<string, string>>({}); // step -> JSON of the draft last read or written, to skip no-op writes

  useEffect(() => {
    async function init() {
      // A failed read leaves the wizard on its defaults; each step's own save reports errors.
      let prof: Profile | null = null;
      try { prof = await api.get<Profile>("/setup/profile"); } catch { /* defaults */ }
      try {
        const data = await api.get<ChecklistItem[]>("/setup/checklist");
        setChecklist(data);
        const cur = STEPS.findIndex((s) => data.find((c) => c.step === s.key)?.status === "current");
        setStepIndex(cur === -1 ? STEPS.length - 1 : cur);
        // a draft saved on another device wins over what the profile says, until the step is saved
        let merged = prof;
        for (const c of data) {
          if (c.draft && Object.keys(c.draft).length > 0) { saved.current[c.step] = JSON.stringify(c.draft); merged = { ...(merged ?? profileDefaults), ...c.draft } as Profile; }
        }
        prof = merged;
      } catch { /* defaults */ }
      if (prof) setProfile(prof);
      setLoading(false);
    }
    // Deferred to a microtask so the effect body itself never calls setState synchronously (react-hooks/set-state-in-effect).
    void Promise.resolve().then(init);
  }, []);

  // A failed save keeps every field as typed; the error shows and the same button retries.
  const send = async (method: "POST" | "PATCH" | "PUT", path: string, body: unknown) => {
    try {
      const res = await api.request(method, path, { body });
      setError(null);
      return res;
    } catch (e) {
      setError(reasonText(e, "Request failed"));
      return null;
    }
  };

  const version = (step: string) => checklist.find((c) => c.step === step)?.version ?? 0;
  // Keeps what the owner typed on this step on the server (optimistic version); another device saving first answers 409.
  const saveDraft = async (step: string) => {
    const d = draftOf(step, profile);
    if (!d || JSON.stringify(d) === saved.current[step]) return;
    try {
      const r = await api.request<{ version: number }>("PUT", `/setup/checklist/${step}/draft`, { body: { draft: d, version: version(step) } });
      saved.current[step] = JSON.stringify(d);
      setChecklist((prev) => prev.map((p) => (p.step === step ? { ...p, version: r.version } : p)));
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setError("This step was changed on another device. Reload to see the latest.");
    }
  };

  const goTo = async (i: number) => {
    await saveDraft(STEPS[stepIndex].key);
    setStepIndex(Math.min(Math.max(i, 0), STEPS.length - 1));
  };

  const mark = async (status: "done" | "skipped") => {
    const step = STEPS[stepIndex].key;
    const ok = await send("POST", `/setup/checklist/${step}/${status === "done" ? "complete" : "skip"}`, {});
    if (ok === null) return false;
    // the next pending step becomes the current one
    setChecklist((prev) => {
      const upd = prev.map((p) => (p.step === step ? { ...p, status } : p));
      const first = STEPS.find((s) => upd.find((c) => c.step === s.key)?.status !== "done" && upd.find((c) => c.step === s.key)?.status !== "skipped")?.key;
      return upd.map((p) => (p.status === "current" || (p.step === first && p.status === "pending") ? { ...p, status: p.step === first ? "current" : "pending" } : p));
    });
    return true;
  };

  const saveProfile = async () => {
    if ((await send("PATCH", "/setup/profile", profile)) !== null && (await mark("done"))) await goTo(stepIndex + 1);
  };
  const markAndNext = async (status: "done" | "skipped") => {
    await saveDraft(STEPS[stepIndex].key);
    if (await mark(status)) setStepIndex((i) => Math.min(i + 1, STEPS.length - 1));
  };

  const currentStep = STEPS[stepIndex].key;
  const statusOf = (k: string) => checklist.find((c) => c.step === k)?.status ?? "pending";
  const minutesLeft = STEPS.filter((s) => !["done", "skipped"].includes(statusOf(s.key))).reduce((n, s) => n + s.minutes, 0);

  const renderStep = () => {
    switch (currentStep) {
      case "profile":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Clinic profile</h2>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="w-name">Clinic name</label>
              <input id="w-name" className={styles.input} value={profile.name} onChange={(e) => setProfile({ ...profile, name: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="clinic-timezone">Timezone</label>
              <TimezoneSelect id="clinic-timezone" className={styles.select} value={profile.timezone} onChange={(timezone) => setProfile({ ...profile, timezone })} />
            </div>
            <p className={styles.text}>Licence numbers are recorded per doctor and clinic in the Licences tab of Clinic Setup.</p>
          </div>
        );
      case "preset":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Clinic type</h2>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="w-spec">Specialties (comma separated)</label>
              <input id="w-spec" className={styles.input} value={profile.specialties.join(", ")} onChange={(e) => setProfile({ ...profile, specialties: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) })} />
            </div>
            <p className={styles.text}>Departments such as Dental or Dermatology, and whether each needs the vitals step, are set on their own screen.</p>
            <button className={styles.btn} onClick={() => router.push("/setup/departments")}>Open Departments</button>
          </div>
        );
      case "doctors":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Doctors and timings</h2>
            <p className={styles.text}>Invite doctors from Staff & Access. Their weekly hours are set under Shifts and holidays in Clinic Setup; changes reach the front desk within one minute.</p>
            <button className={styles.btn} onClick={() => router.push("/staff")}>Open Staff & Access</button>
            <button className={styles.btn} onClick={() => router.push("/setup?tab=shifts")}>Open Shifts</button>
          </div>
        );
      case "prices":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Prices</h2>
            <p className={styles.text}>Add service codes, base amounts and tax codes in the Charges tab of Clinic Setup.</p>
            <button className={styles.btn} onClick={() => router.push("/setup?tab=charges")}>Open Charges</button>
          </div>
        );
      case "whatsapp":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>WhatsApp number</h2>
            <p className={styles.text}>WhatsApp Business onboarding is a managed service. Enter the clinic&apos;s WhatsApp number and submit it for verification.</p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="w-wa">WhatsApp number</label>
              <input id="w-wa" className={styles.input} value={profile.whatsappNumber ?? ""} onChange={(e) => setProfile({ ...profile, whatsappNumber: e.target.value })} />
            </div>
          </div>
        );
      case "tax":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Tax registration</h2>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="w-taxtype">Tax ID type</label>
              <select id="w-taxtype" className={styles.select} value={profile.taxIdType ?? ""} onChange={(e) => setProfile({ ...profile, taxIdType: e.target.value || null })}>
                <option value="">—</option>
                <option value="GSTIN">GSTIN (India)</option>
                <option value="VAT">VAT (KSA)</option>
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="w-taxid">Tax ID</label>
              <input id="w-taxid" className={styles.input} value={profile.taxId ?? ""} onChange={(e) => setProfile({ ...profile, taxId: e.target.value })} />
            </div>
          </div>
        );
      case "dpa":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Data processing agreement</h2>
            <p className={styles.text}>This step cannot be skipped: the clinic goes live once the agreement is signed.</p>
            <DpaPanel />
          </div>
        );
      case "import":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Import your patients</h2>
            <p className={styles.text}>Bring patient records from a spreadsheet in Import / Export. You can skip this and import later.</p>
            <button className={styles.btn} onClick={() => router.push("/setup?tab=data")}>Open Import / Export</button>
          </div>
        );
      case "go_live": {
        const blockers: string[] = [];
        if (statusOf("doctors") !== "done") blockers.push("No doctor added");
        if (statusOf("whatsapp") !== "done") blockers.push("WhatsApp not connected");
        if (statusOf("dpa") !== "done") blockers.push("Data processing agreement not signed");
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Go-live check</h2>
            {blockers.length === 0 ? (
              <div className={styles.success}>
                <div className={styles.successIcon}>✅</div>
                <p className={styles.text}>You&apos;re ready — you can book and bill a real patient now.</p>
              </div>
            ) : (
              <>
                <p className={styles.text}>The following blockers must be cleared before going live:</p>
                <ul className={styles.text}>
                  {blockers.map((b) => <li key={b}>{b}</li>)}
                </ul>
              </>
            )}
          </div>
        );
      }
      default:
        return null;
    }
  };

  if (loading) return <main className={styles.page}><div className={styles.card}>Loading…</div></main>;

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Clinic setup wizard</h1>
        <p className={styles.subtitle}>Step {stepIndex + 1} of {STEPS.length}: {STEPS[stepIndex].title}{minutesLeft > 0 ? ` · about ${minutesLeft} minutes left` : ""}</p>
      </div>

      <div className={styles.steps}>
        {STEPS.map((s, i) => {
          const status = statusOf(s.key);
          const className = status === "done" ? styles.stepDone : status === "skipped" ? styles.stepSkipped : styles.step;
          return (
            <button key={s.key} type="button" className={className} data-status={status} onClick={() => void goTo(i)} aria-current={i === stepIndex ? "step" : undefined}>
              {s.title}
            </button>
          );
        })}
      </div>

      {error && <div role="alert" className={styles.card} style={{ color: "var(--nb-danger-500)" }}>{error}</div>}

      {renderStep()}

      <div className={styles.actions}>
        <button className={styles.btn} onClick={() => void goTo(stepIndex - 1)} disabled={stepIndex === 0}>Back</button>
        <div style={{ display: "flex", gap: "12px" }}>
          {currentStep !== "go_live" && currentStep !== "dpa" && (
            <button className={styles.btn} onClick={() => void markAndNext("skipped")}>Skip</button>
          )}
          {currentStep === "profile" || currentStep === "tax" ? (
            <button className={styles.btnPrimary} onClick={saveProfile}>Save & continue</button>
          ) : currentStep === "go_live" ? (
            <button className={styles.btnPrimary} onClick={() => router.push("/setup")}>Finish</button>
          ) : (
            <button className={styles.btnPrimary} onClick={() => void markAndNext("done")}>{currentStep === "dpa" ? "Mark done" : "Continue"}</button>
          )}
        </div>
      </div>
    </main>
  );
}

const profileDefaults: Profile = { name: "", region: "IN", timezone: "UTC", taxIdType: null, taxId: null, whatsappNumber: null, specialties: [] };
