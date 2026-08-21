"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./wizard.module.css";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type Problem = { title: string; detail: string };
type ChecklistItem = { step: string; status: "pending" | "skipped" | "done" };
type Profile = { name: string; region: string; timezone: string; taxIdType: string | null; taxId: string | null; whatsappNumber: string | null; specialties: string[] };

const STEPS = [
  { key: "welcome", title: "Welcome" },
  { key: "profile", title: "Clinic profile" },
  { key: "tax", title: "Tax & IDs" },
  { key: "doctors", title: "Doctors" },
  { key: "schedule", title: "Schedule" },
  { key: "charges", title: "Price master" },
  { key: "pharmacy", title: "Pharmacy" },
  { key: "whatsapp", title: "WhatsApp" },
  { key: "go_live", title: "Go live" },
];

export default function SetupWizardPage() {
  const router = useRouter();
  const [stepIndex, setStepIndex] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [checklist, setChecklist] = useState<ChecklistItem[]>([]);
  const [profile, setProfile] = useState<Profile>({ name: "", region: "IN", timezone: "UTC", taxIdType: null, taxId: null, whatsappNumber: null, specialties: [] });

  const authedFetch = useCallback(async (path: string, init?: RequestInit) => {
    const token = localStorage.getItem("nabd_access_token");
    if (!token) {
      router.replace("/login");
      return null;
    }
    const res = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    });
    if (res.status === 401) {
      localStorage.removeItem("nabd_access_token");
      router.replace("/login");
      return null;
    }
    return res;
  }, [router]);

  useEffect(() => {
    async function init() {
      const res = await authedFetch("/setup/checklist");
      if (!res) return;
      if (res.ok) {
        const data: ChecklistItem[] = await res.json();
        setChecklist(data);
        const firstPending = STEPS.findIndex((s) => data.find((c) => c.step === s.key)?.status === "pending");
        setStepIndex(firstPending === -1 ? STEPS.length - 1 : firstPending);
      }
      const profileRes = await authedFetch("/setup/profile");
      if (profileRes?.ok) setProfile(await profileRes.json());
      setLoading(false);
    }
    init();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const post = async (path: string, body: unknown) => {
    const res = await authedFetch(path, { method: "POST", body: JSON.stringify(body) });
    if (!res) return false;
    if (!res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Request failed" }));
      setError(p.detail || "Request failed");
      return false;
    }
    return true;
  };

  const patch = async (path: string, body: unknown) => {
    const res = await authedFetch(path, { method: "PATCH", body: JSON.stringify(body) });
    if (!res) return false;
    if (!res.ok) {
      const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Request failed" }));
      setError(p.detail || "Request failed");
      return false;
    }
    return true;
  };

  const mark = async (status: "done" | "skipped") => {
    const step = STEPS[stepIndex].key;
    if (await post(`/setup/checklist/${step}/${status === "done" ? "complete" : "skip"}`, { status })) {
      setChecklist((prev) => prev.map((p) => (p.step === step ? { ...p, status } : p)));
    }
  };

  const saveProfile = async () => {
    if (await patch("/setup/profile", profile)) {
      await mark("done");
      next();
    }
  };

  const next = () => {
    if (stepIndex < STEPS.length - 1) setStepIndex(stepIndex + 1);
  };

  const prev = () => {
    if (stepIndex > 0) setStepIndex(stepIndex - 1);
  };

  const finish = () => {
    router.push("/setup");
  };

  const currentStep = STEPS[stepIndex].key;
  const currentStatus = checklist.find((c) => c.step === currentStep)?.status ?? "pending";

  const renderStep = () => {
    switch (currentStep) {
      case "welcome":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Welcome to Nabd</h2>
            <p className={styles.text}>This wizard covers everything needed to go live — usually under 60 minutes. You can skip any step and resume later from Clinic Setup.</p>
          </div>
        );
      case "profile":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Clinic profile</h2>
            <div className={styles.field}>
              <label className={styles.label}>Clinic name</label>
              <input className={styles.input} value={profile.name} onChange={(e) => setProfile({ ...profile, name: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Timezone</label>
              <input className={styles.input} value={profile.timezone} onChange={(e) => setProfile({ ...profile, timezone: e.target.value })} />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Specialties (comma separated)</label>
              <input className={styles.input} value={profile.specialties.join(", ")} onChange={(e) => setProfile({ ...profile, specialties: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) })} />
            </div>
          </div>
        );
      case "tax":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Tax & business IDs</h2>
            <div className={styles.field}>
              <label className={styles.label}>Tax ID type</label>
              <select className={styles.select} value={profile.taxIdType ?? ""} onChange={(e) => setProfile({ ...profile, taxIdType: e.target.value || null })}>
                <option value="">—</option>
                <option value="GSTIN">GSTIN (India)</option>
                <option value="VAT">VAT (KSA)</option>
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Tax ID</label>
              <input className={styles.input} value={profile.taxId ?? ""} onChange={(e) => setProfile({ ...profile, taxId: e.target.value })} />
            </div>
          </div>
        );
      case "doctors":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Doctors</h2>
            <p className={styles.text}>Invite doctors from Staff & Access. Once added, their licence numbers can be recorded in the Licences tab.</p>
          </div>
        );
      case "schedule":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Schedules</h2>
            <p className={styles.text}>Set clinic-wide holidays and doctor working hours from the Clinic Setup hub. Changes reach the front desk within one minute.</p>
          </div>
        );
      case "charges":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Price master</h2>
            <p className={styles.text}>Configure service codes, base amounts and tax codes from the Charges tab. The price master can be pre-filled by specialty.</p>
          </div>
        );
      case "pharmacy":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Pharmacy mode</h2>
            <p className={styles.text}>Choose the pharmacy operating mode: None / Dispense-at-desk / Full pharmacy. This can be changed later from Clinic Setup.</p>
          </div>
        );
      case "whatsapp":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>WhatsApp connect</h2>
            <p className={styles.text}>WhatsApp Business onboarding is handled as a managed service. Add the clinic&apos;s WhatsApp number in the profile step and submit for verification.</p>
            <div className={styles.field}>
              <label className={styles.label}>WhatsApp number</label>
              <input className={styles.input} value={profile.whatsappNumber ?? ""} onChange={(e) => setProfile({ ...profile, whatsappNumber: e.target.value })} />
            </div>
          </div>
        );
      case "go_live":
        const blockers = [];
        if (checklist.find((c) => c.step === "doctors")?.status !== "done") blockers.push("No doctor added");
        if (checklist.find((c) => c.step === "schedule")?.status !== "done") blockers.push("No schedule set");
        if (checklist.find((c) => c.step === "whatsapp")?.status !== "done") blockers.push("WhatsApp not connected");
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
      default:
        return null;
    }
  };

  if (loading) return <main className={styles.page}><div className={styles.card}>Loading…</div></main>;

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Clinic setup wizard</h1>
        <p className={styles.subtitle}>Step {stepIndex + 1} of {STEPS.length}: {STEPS[stepIndex].title}</p>
      </div>

      <div className={styles.steps}>
        {STEPS.map((s, i) => {
          const status = checklist.find((c) => c.step === s.key)?.status ?? "pending";
          const className = status === "done" ? styles.stepDone : status === "skipped" ? styles.stepSkipped : styles.step;
          return (
            <div key={s.key} className={className} onClick={() => setStepIndex(i)} role="button" tabIndex={0}>
              {s.title}
            </div>
          );
        })}
      </div>

      {error && <div className={styles.card} style={{ color: "var(--nb-danger-500)" }}>{error}</div>}

      {renderStep()}

      <div className={styles.actions}>
        <button className={styles.btn} onClick={prev} disabled={stepIndex === 0}>Back</button>
        <div style={{ display: "flex", gap: "12px" }}>
          {currentStep !== "go_live" && (
            <button className={styles.btn} onClick={() => mark("skipped")}>Skip</button>
          )}
          {currentStep === "profile" || currentStep === "tax" ? (
            <button className={styles.btnPrimary} onClick={saveProfile}>Save & continue</button>
          ) : currentStep === "go_live" ? (
            <button className={styles.btnPrimary} onClick={finish}>Finish</button>
          ) : (
            <button className={styles.btnPrimary} onClick={async () => { await mark("done"); next(); }}>Continue</button>
          )}
        </div>
      </div>
    </main>
  );
}
