"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import styles from "./setup.module.css";
import WhatsAppTemplates from "./WhatsAppTemplates";
import TimezoneSelect from "./TimezoneSelect";
import PharmacyPanel from "../components/setup/PharmacyPanel";
import Icon from "../components/ui/Icon";
import AccessGate from "../components/ui/AccessGate";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { can } from "../lib/permissions";
import { getIdentity, regionLabel } from "../lib/session";
import { apiFetch, goLogin } from "../lib/apiClient";
import type { Problem } from "@nabd/api-client/types";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";


type ChecklistItem = { step: string; status: "pending" | "skipped" | "done"; skippedAt: string | null; doneAt: string | null };
type Profile = { id: string; name: string; region: string; timezone: string; taxId: string | null; taxIdType: string | null; whatsappNumber: string | null; specialties: string[]; status: string; setupCompletedAt: string | null };
type Charge = { id: string; code: string; name: string; category: string; baseAmount: number; followUpAmount: number | null; emergencyAmount: number | null; taxCode: string | null; taxRatePercent: number; doctorOverride: boolean; active: boolean; effectiveFrom: string; effectiveTo: string | null; displayOrder: number };
type Policy = { id: string; policyKey: string; value: string; version: number };
type ConsentContact = { name: string; email: string; phone: string | null };
type Holiday = { id: string; holidayDate: string; name: string; recurring: boolean };
type Shift = { id: string; staffId: string; staffName: string | null; patternJson: string; effectiveFrom: string; effectiveTo: string | null };
type PayrollRow = { staffId: string; staffName: string; role: string; days: number; hours: number; salary: number; notes: string };
type Subscription = { plan: string | null; status: string; activePatientsUsed: number; activePatientsLimit: number; whatsappMessagesUsed: number; whatsappMessagesLimit: number; branchesUsed: number; branchesLimit: number };
type ImportJob = { id: string; importType: string; fileName: string; status: string; resultUrl: string | null; errorMessage: string | null; createdAt: string | null };
type ExportJob = { id: string; exportType: string; status: string; resultUrl: string | null; errorMessage: string | null; createdAt: string | null };
type Licence = { id: string; licenceType: string; holderId: string | null; holderName: string | null; number: string; issuingBody: string | null; expiryDate: string; region: string; status: string };
type StaffSummary = { id: string; name: string; roleName: string };

const TABS = [
  { key: "checklist", label: "Checklist" },
  { key: "profile", label: "Profile" },
  { key: "departments", label: "Departments" },
  { key: "charges", label: "Charges" },
  { key: "policies", label: "Policies" },
  { key: "consent", label: "Consent" },
  { key: "whatsapp", label: "WhatsApp" },
  { key: "holidays", label: "Holidays" },
  { key: "shifts", label: "Shifts" },
  { key: "payroll", label: "Payroll" },
  { key: "subscription", label: "Subscription" },
  { key: "data", label: "Import / Export" },
  { key: "licences", label: "Licences" },
  { key: "pharmacy", label: "Pharmacy" },
];

const isSetupTab = (t: string | null): t is string => TABS.some((x) => x.key === t);

// Each tab loads only its own data, so one tab's missing grant or failing endpoint can't blank the others.
const TAB_DATA: Record<string, string[]> = {
  checklist: ["checklist"], profile: ["profile"], charges: ["charges"], policies: ["policies"], consent: ["consent"],
  holidays: ["holidays"], shifts: ["shifts", "staff"], subscription: ["subscription"], data: ["importJobs", "exportJobs"],
  licences: ["licences"], payroll: [],
};

function csvCell(v: string | number): string {
  let t = String(v ?? "");
  if (typeof v === "string" && /^[=+\-@\t\r]/.test(t)) t = `'${t}`; // neutralise spreadsheet formula injection
  return /[",\n\r]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t;
}

// ?tab= deep links (the owner sidebar's Compliance & Audit and Plan & Modules open the Licences and
// Subscription tabs) read useSearchParams, which needs a Suspense boundary for static prerender.
export default function SetupPage() {
  return (
    <AccessGate permission="setup:view" surface="Clinic Setup">
      <Suspense fallback={null}>
        <SetupPageInner />
      </Suspense>
    </AccessGate>
  );
}

function SetupPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get("tab");
  // The URL is the tab's only state: sidebar links and tab clicks both just change ?tab=, so the
  // sidebar's active item always matches what's on screen.
  const tab = isSetupTab(tabParam) ? tabParam : "checklist";
  function selectTab(key: string) {
    router.replace(key === "checklist" ? "/setup" : `/setup?tab=${key}`, { scroll: false });
  }
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [checklist, setChecklist] = useState<ChecklistItem[]>([]);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [charges, setCharges] = useState<Charge[]>([]);
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [consent, setConsent] = useState<ConsentContact>({ name: "", email: "", phone: "" });
  const [holidays, setHolidays] = useState<Holiday[]>([]);
  const [shifts, setShifts] = useState<Shift[]>([]);
  const [payroll, setPayroll] = useState<PayrollRow[]>([]);
  const [subscription, setSubscription] = useState<Subscription | null>(null);
  const [importJobs, setImportJobs] = useState<ImportJob[]>([]);
  const [exportJobs, setExportJobs] = useState<ExportJob[]>([]);
  const [licences, setLicences] = useState<Licence[]>([]);
  const [staff, setStaff] = useState<StaffSummary[]>([]);
  const [canPharmacy, setCanPharmacy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [payrollMonth, setPayrollMonth] = useState(() => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`; }) // local month, not UTC;
  const [payrollError, setPayrollError] = useState<string | null>(null);
  const [payrollLoading, setPayrollLoading] = useState(false);
  const [specialtyDraft, setSpecialtyDraft] = useState("");
  // Profile edits need setup:edit (PATCH /setup/profile); without it the profile is shown read-only.
  const [canEditProfile, setCanEditProfile] = useState(false);
  const [profileSaving, setProfileSaving] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);

  const authedFetch = useCallback(async (path: string, init?: RequestInit) => {
    const token = localStorage.getItem("nabd_access_token");
    if (!token) {
      goLogin();
      return null;
    }
    const res = await apiFetch(path, {
      ...init,
      headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    });
    if (res.status === 401) {
      localStorage.removeItem("nabd_access_token");
      goLogin();
      return null;
    }
    return res;
  }, [router]);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const fetchJson = async <T,>(path: string): Promise<T | null> => {
        const res = await authedFetch(path);
        if (!res) return null;
        if (!res.ok) {
          const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Request failed" }));
          setError(p.detail || `Failed to load ${path}`);
          return null;
        }
        return res.json();
      };
      const wanted = new Set(TAB_DATA[tab] ?? []);
      const [cl, pr, ch, po, co, ho, sh, su, ij, ej, li, st] = await Promise.all([
        wanted.has("checklist") ? fetchJson<ChecklistItem[]>("/setup/checklist") : null,
        wanted.has("profile") ? fetchJson<Profile>("/setup/profile") : null,
        wanted.has("charges") ? fetchJson<Charge[]>("/setup/charges") : null,
        wanted.has("policies") ? fetchJson<Policy[]>("/setup/policies") : null,
        wanted.has("consent") ? fetchJson<ConsentContact>("/setup/consent-contact") : null,
        wanted.has("holidays") ? fetchJson<Holiday[]>("/setup/holidays") : null,
        wanted.has("shifts") ? fetchJson<Shift[]>("/setup/shifts") : null,
        wanted.has("subscription") ? fetchJson<Subscription>("/setup/subscription") : null,
        wanted.has("importJobs") ? fetchJson<ImportJob[]>("/setup/import-jobs") : null,
        wanted.has("exportJobs") ? fetchJson<ExportJob[]>("/setup/export-jobs") : null,
        wanted.has("licences") ? fetchJson<Licence[]>("/setup/licences") : null,
        wanted.has("staff") ? fetchJson<StaffSummary[]>("/setup/staff") : null,
      ]);
      if (cl) setChecklist(cl);
      if (pr) setProfile(pr);
      if (ch) setCharges(ch);
      if (po) setPolicies(po);
      if (co) setConsent(co);
      if (ho) setHolidays(ho);
      if (sh) setShifts(sh);
      if (su) setSubscription(su);
      if (ij) setImportJobs(ij);
      if (ej) setExportJobs(ej);
      if (li) setLicences(li);
      if (st) setStaff(st);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch, tab]);

  useEffect(() => {
    // Deferred to a microtask so the effect body itself never calls setState
    // synchronously (react-hooks/set-state-in-effect) — loadAll does, immediately.
    void Promise.resolve().then(() => {
      setCanPharmacy(can("pharmacy:view"));
      setCanEditProfile(can("setup:edit"));
      setNotice(null);
      return loadAll();
    });
  }, [loadAll]);

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

  const markStep = async (step: string, status: "skipped" | "done") => {
    if (await post(`/setup/checklist/${step}/${status === "skipped" ? "skip" : "complete"}`, { status })) {
      loadAll();
    }
  };

  // A failed save stays inline (unlike the shared patch(), which swaps the whole tab for an
  // error view) so the owner's edits are not lost.
  const saveProfile = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!profile || profileSaving) return;
    setNotice(null);
    setProfileError(null);
    const body = {
      name: profile.name,
      timezone: profile.timezone,
      taxIdType: profile.taxIdType,
      taxId: profile.taxId,
      whatsappNumber: profile.whatsappNumber,
      specialties: profile.specialties,
    };
    setProfileSaving(true);
    try {
      const res = await authedFetch("/setup/profile", { method: "PATCH", body: JSON.stringify(body) });
      if (!res) return;
      if (!res.ok) {
        const p: Problem | null = await res.json().catch(() => null);
        setProfileError(res.status === 403 ? "Your role can't change the clinic profile." : p?.detail || "Couldn't save the profile. Try again.");
        return;
      }
      setProfile(await res.json());
      setNotice("Profile saved.");
    } catch {
      setProfileError("Couldn't reach the server. Your changes are still here; try again.");
    } finally {
      setProfileSaving(false);
    }
  };

  const addSpecialty = () => {
    const v = specialtyDraft.trim();
    setSpecialtyDraft("");
    if (!profile || !v || profile.specialties.some((x) => x.toLowerCase() === v.toLowerCase())) return;
    setProfile({ ...profile, specialties: [...profile.specialties, v] });
  };

  // effectiveFrom has no visible input below — defaulted to today (matching the column's own
  // CURRENT_DATE default) since addCharge()'s own guard requires it truthy to submit at all.
  const [newCharge, setNewCharge] = useState<Partial<Charge>>({ active: true, displayOrder: 0, effectiveFrom: new Date().toLocaleDateString("en-CA") });
  const addCharge = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCharge.code || !newCharge.name || !newCharge.category || newCharge.baseAmount == null || !newCharge.effectiveFrom) return;
    if (await post("/setup/charges", newCharge)) {
      setNewCharge({ active: true, displayOrder: 0, effectiveFrom: new Date().toLocaleDateString("en-CA") });
      loadAll();
    }
  };

  const toggleCharge = async (c: Charge) => {
    if (await patch(`/setup/charges/${c.id}`, { ...c, active: !c.active })) loadAll();
  };

  const updatePolicy = async (p: Policy) => {
    const value = prompt(`New value for ${p.policyKey}${p.policyKey === "session.idle_lock_minutes" ? " (minutes, 2-30)" : ""}`, p.value);
    if (value === null) return;
    if (await patch(`/setup/policies/${p.policyKey}`, { value })) loadAll();
  };

  const saveConsent = async (e: React.FormEvent) => {
    e.preventDefault();
    if (await patch("/setup/consent-contact", consent)) loadAll();
  };

  const [newHoliday, setNewHoliday] = useState<Partial<Holiday>>({ recurring: false });
  const addHoliday = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newHoliday.holidayDate || !newHoliday.name) return;
    if (await post("/setup/holidays", newHoliday)) {
      setNewHoliday({ recurring: false });
      loadAll();
    }
  };

  const deleteHoliday = async (id: string) => {
    const res = await authedFetch(`/setup/holidays/${id}`, { method: "DELETE" });
    if (res?.ok) loadAll();
  };

  const [newShift, setNewShift] = useState<Partial<Shift>>({ patternJson: "{}" });
  const addShift = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newShift.staffId || !newShift.patternJson || !newShift.effectiveFrom) return;
    if (await post("/setup/shifts", newShift)) {
      setNewShift({ patternJson: "{}" });
      loadAll();
    }
  };

  const loadPayroll = useCallback(async (month: string) => {
    setPayrollLoading(true);
    setPayrollError(null);
    try {
      const res = await authedFetch(`/setup/payroll-export?month=${encodeURIComponent(month)}`);
      if (!res) return;
      if (res.ok) setPayroll(await res.json());
      else {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Request failed" }));
        setPayrollError(p.detail || "Couldn't load the payroll export.");
      }
    } catch {
      setPayrollError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setPayrollLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    if (tab !== "payroll") return;
    void Promise.resolve().then(() => loadPayroll(payrollMonth));
  }, [tab, payrollMonth, loadPayroll]);

  const exportPayrollCsv = () => {
    const head = ["Staff", "Role", "Days", "Hours", "Salary", "Notes"];
    const lines = [head, ...payroll.map((r) => [r.staffName, r.role, r.days, r.hours, r.salary, r.notes])]
      .map((row) => row.map(csvCell).join(","));
    const url = URL.createObjectURL(new Blob([lines.join("\r\n")], { type: "text/csv;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = `payroll-${payrollMonth}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const createImport = async (importType: string) => {
    if (await post("/setup/import-jobs", { importType, fileName: `${importType}-import.csv` })) loadAll();
  };

  const createExport = async (exportType: string) => {
    if (await post("/setup/export-jobs", { exportType })) loadAll();
  };

  // The API only accepts IN or KSA; default to the clinic's own region (fixed at provisioning).
  const licenceRegion = getIdentity()?.tenantRegion === "KSA" ? "KSA" : "IN";
  const [newLicence, setNewLicence] = useState<Partial<Licence>>({ region: licenceRegion, licenceType: "clinician" });
  const addLicence = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newLicence.licenceType || !newLicence.number || !newLicence.expiryDate || !newLicence.region) return;
    if (await post("/setup/licences", newLicence)) {
      setNewLicence({ region: licenceRegion, licenceType: "clinician" });
      loadAll();
    }
  };

  const renderContent = () => {
    if (tab === "pharmacy") return canPharmacy ? <PharmacyPanel authedFetch={authedFetch} /> : null;
    if (loading && (TAB_DATA[tab]?.length ?? 0) > 0) return <div className={styles.card}><Skeleton rows={5} label="Loading" /></div>;
    if (error) return <div className={styles.card}><ErrorState message={error} onRetry={loadAll} /></div>;

    switch (tab) {
      case "checklist":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Setup checklist</h2>
            <div className={styles.checklist}>
              {checklist.map((item) => (
                <div key={item.step} className={styles.checkItem}>
                  <span className={styles.checkLabel}>{item.step.replace(/_/g, " ")}</span>
                  <div className={styles.checkActions}>
                    <span className={`${styles.pill} ${item.status === "done" ? styles.pillDone : item.status === "skipped" ? styles.pillSkipped : styles.pillPending}`}>{item.status}</span>
                    {item.status !== "done" && <button className={styles.smallBtn} onClick={() => markStep(item.step, "done")}>Mark done</button>}
                    {item.status !== "skipped" && <button className={styles.smallBtn} onClick={() => markStep(item.step, "skipped")}>Skip</button>}
                  </div>
                </div>
              ))}
            </div>
          </div>
        );
      case "departments":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Departments</h2>
            <button className={styles.btn} onClick={() => router.push("/setup/departments")}>Open Departments</button>
          </div>
        );
      case "profile":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Clinic profile</h2>
            {profile && (
              <form onSubmit={saveProfile}>
                {notice && <div className={styles.notice} role="status"><Icon name="check" size={14} /> {notice}</div>}
                {profileError && <div className={styles.errorState} role="alert">{profileError}</div>}
                {!canEditProfile && <p className={styles.regionNote}>View only: your role can&apos;t change the clinic profile.</p>}
                <fieldset className={styles.plainFieldset} disabled={!canEditProfile || profileSaving}>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="clinic-name">Clinic name</label>
                  <input id="clinic-name" className={styles.input} value={profile.name} onChange={(e) => setProfile({ ...profile, name: e.target.value })} />
                </div>
                <div className={styles.regionLock} role="group" aria-label="Region">
                  <Icon name="lock" size={14} />
                  <span className={styles.regionNote}>Region is locked irreversibly since provisioning — SYS-14 / NFR-04.</span>
                  <strong>{regionLabel(profile.region)}{profile.region && regionLabel(profile.region) !== profile.region ? ` (${profile.region})` : ""}</strong>
                </div>
                <div className={styles.row}>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="clinic-timezone">Timezone</label>
                    <TimezoneSelect id="clinic-timezone" className={styles.select} value={profile.timezone} onChange={(timezone) => setProfile({ ...profile, timezone })} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="clinic-whatsapp">WhatsApp number</label>
                    <input id="clinic-whatsapp" type="tel" className={styles.input} value={profile.whatsappNumber ?? ""} onChange={(e) => setProfile({ ...profile, whatsappNumber: e.target.value })} />
                  </div>
                </div>
                <div className={styles.row}>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="clinic-tax-type">Tax ID type</label>
                    <select id="clinic-tax-type" className={styles.select} value={profile.taxIdType ?? ""} onChange={(e) => setProfile({ ...profile, taxIdType: e.target.value || null })}>
                      <option value="">—</option>
                      <option value="GSTIN">GSTIN (India)</option>
                      <option value="VAT">VAT (KSA)</option>
                    </select>
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="clinic-tax-id">Tax ID</label>
                    <input id="clinic-tax-id" className={styles.input} value={profile.taxId ?? ""} onChange={(e) => setProfile({ ...profile, taxId: e.target.value })} />
                  </div>
                </div>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor={canEditProfile ? "clinic-specialty" : undefined}>Specialties</label>
                  <div className={styles.chips}>
                    {profile.specialties.length === 0 && <span className={styles.regionNote}>No specialties added yet.</span>}
                    {profile.specialties.map((sp) => (
                      <span key={sp} className={styles.chip}>
                        {sp}
                        {canEditProfile && (
                          <button type="button" className={styles.chipX} aria-label={`Remove ${sp}`} onClick={() => setProfile({ ...profile, specialties: profile.specialties.filter((x) => x !== sp) })}>×</button>
                        )}
                      </span>
                    ))}
                  </div>
                  {canEditProfile && <div className={styles.chipAdd}>
                    <input
                      id="clinic-specialty"
                      className={styles.input}
                      value={specialtyDraft}
                      placeholder="Type a specialty and press Enter"
                      onChange={(e) => setSpecialtyDraft(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addSpecialty(); } }}
                    />
                    <button type="button" className={styles.smallBtn} onClick={addSpecialty}>Add</button>
                  </div>}
                </div>
                </fieldset>
                {canEditProfile && (
                  <div className={styles.actions}>
                    <button className={styles.btnPrimary} type="submit" disabled={profileSaving}>{profileSaving ? "Saving…" : "Save profile"}</button>
                  </div>
                )}
              </form>
            )}
          </div>
        );
      case "charges":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Charge head / price master</h2>
            <form onSubmit={addCharge}>
              <div className={styles.row}>
                <div className={styles.field}>
                  <label className={styles.label}>Code</label>
                  <input className={styles.input} value={newCharge.code ?? ""} onChange={(e) => setNewCharge({ ...newCharge, code: e.target.value })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Name</label>
                  <input className={styles.input} value={newCharge.name ?? ""} onChange={(e) => setNewCharge({ ...newCharge, name: e.target.value })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Category</label>
                  <input className={styles.input} value={newCharge.category ?? ""} onChange={(e) => setNewCharge({ ...newCharge, category: e.target.value })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Base amount</label>
                  <input className={styles.input} type="number" min="0" step="0.01" value={newCharge.baseAmount ?? ""} onChange={(e) => setNewCharge({ ...newCharge, baseAmount: Number(e.target.value) })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Tax rate %</label>
                  <input className={styles.input} type="number" min="0" step="0.01" value={newCharge.taxRatePercent ?? ""} onChange={(e) => setNewCharge({ ...newCharge, taxRatePercent: Number(e.target.value) })} />
                </div>
              </div>
              <div className={styles.actions}>
                <button className={styles.btnPrimary} type="submit">Add charge</button>
              </div>
            </form>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr><th>Code</th><th>Name</th><th>Category</th><th>Base</th><th>Tax %</th><th>Status</th><th></th></tr>
                </thead>
                <tbody>
                  {charges.map((c) => (
                    <tr key={c.id}>
                      <td>{c.code}</td>
                      <td>{c.name}</td>
                      <td>{c.category}</td>
                      <td>{c.baseAmount}</td>
                      <td>{c.taxRatePercent}</td>
                      <td><span className={`${styles.pill} ${c.active ? styles.pillActive : styles.pillInactive}`}>{c.active ? "active" : "inactive"}</span></td>
                      <td><button className={styles.smallBtn} onClick={() => toggleCharge(c)}>{c.active ? "Deactivate" : "Activate"}</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      case "policies":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Simple policy settings</h2>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr><th>Policy</th><th>Value</th><th>Version</th><th></th></tr>
                </thead>
                <tbody>
                  {policies.map((p) => (
                    <tr key={p.id}>
                      <td>{p.policyKey.replace(/_/g, " ")}</td>
                      <td>{p.value}</td>
                      <td>{p.version}</td>
                      <td><button className={styles.smallBtn} onClick={() => updatePolicy(p)}>Edit</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      case "consent":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Consent & privacy notice contact</h2>
            <form onSubmit={saveConsent}>
              <div className={styles.row}>
                <div className={styles.field}>
                  <label className={styles.label}>Name</label>
                  <input className={styles.input} value={consent.name} onChange={(e) => setConsent({ ...consent, name: e.target.value })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Email</label>
                  <input className={styles.input} type="email" value={consent.email} onChange={(e) => setConsent({ ...consent, email: e.target.value })} />
                </div>
              </div>
              <div className={styles.field}>
                <label className={styles.label}>Phone</label>
                <input className={styles.input} value={consent.phone ?? ""} onChange={(e) => setConsent({ ...consent, phone: e.target.value })} />
              </div>
              <div className={styles.actions}>
                <button className={styles.btnPrimary} type="submit">Save contact</button>
              </div>
            </form>
          </div>
        );
      case "holidays":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Clinic holidays</h2>
            <form onSubmit={addHoliday}>
              <div className={styles.row}>
                <div className={styles.field}>
                  <label className={styles.label}>Date</label>
                  <input className={styles.input} type="date" value={newHoliday.holidayDate ?? ""} onChange={(e) => setNewHoliday({ ...newHoliday, holidayDate: e.target.value })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Name</label>
                  <input className={styles.input} value={newHoliday.name ?? ""} onChange={(e) => setNewHoliday({ ...newHoliday, name: e.target.value })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>
                    <input type="checkbox" checked={newHoliday.recurring ?? false} onChange={(e) => setNewHoliday({ ...newHoliday, recurring: e.target.checked })} /> Recurring
                  </label>
                </div>
              </div>
              <div className={styles.actions}>
                <button className={styles.btnPrimary} type="submit">Add holiday</button>
              </div>
            </form>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr><th>Date</th><th>Name</th><th>Recurring</th><th></th></tr>
                </thead>
                <tbody>
                  {holidays.map((h) => (
                    <tr key={h.id}>
                      <td>{h.holidayDate}</td>
                      <td>{h.name}</td>
                      <td>{h.recurring ? "Yes" : "No"}</td>
                      <td><button className={styles.smallBtn} onClick={() => deleteHoliday(h.id)}>Delete</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      case "shifts":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Shift timings</h2>
            <form onSubmit={addShift}>
              <div className={styles.row}>
                <div className={styles.field}>
                  <label className={styles.label}>Staff</label>
                  <select className={styles.select} value={newShift.staffId ?? ""} onChange={(e) => setNewShift({ ...newShift, staffId: e.target.value })}>
                    <option value="">—</option>
                    {staff.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.roleName})</option>)}
                  </select>
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Effective from</label>
                  <input className={styles.input} type="date" value={newShift.effectiveFrom ?? ""} onChange={(e) => setNewShift({ ...newShift, effectiveFrom: e.target.value })} />
                </div>
              </div>
              <div className={styles.field}>
                <label className={styles.label}>Pattern JSON</label>
                <textarea className={styles.textarea} value={newShift.patternJson} onChange={(e) => setNewShift({ ...newShift, patternJson: e.target.value })} />
              </div>
              <div className={styles.actions}>
                <button className={styles.btnPrimary} type="submit">Add shift</button>
              </div>
            </form>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr><th>Staff</th><th>Pattern</th><th>From</th><th>To</th></tr>
                </thead>
                <tbody>
                  {shifts.map((s) => (
                    <tr key={s.id}>
                      <td>{s.staffName ?? s.staffId}</td>
                      <td><code>{s.patternJson}</code></td>
                      <td>{s.effectiveFrom}</td>
                      <td>{s.effectiveTo ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      case "payroll":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Simple payroll export</h2>
            <div className={styles.row}>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="payroll-month">Month</label>
                <input id="payroll-month" className={styles.input} type="month" value={payrollMonth} onChange={(e) => e.target.value && setPayrollMonth(e.target.value)} />
              </div>
              <div className={styles.field}>
                <span className={styles.label}>&nbsp;</span>
                {payroll.length > 0 && !payrollError && <button type="button" className={styles.btnPrimary} onClick={exportPayrollCsv}>Export CSV</button>}
              </div>
            </div>
            <p className={styles.subtitle} style={{ marginBottom: "var(--nb-space-16)" }}>
              Statutory contributions (PF/ESI for India, GOSI for KSA) are not calculated here — apply them in your payroll system after export.
            </p>
            {payrollLoading ? (
              <Skeleton rows={4} label="Loading payroll" />
            ) : payrollError ? (
              <ErrorState message={payrollError} onRetry={() => loadPayroll(payrollMonth)} />
            ) : payroll.length === 0 ? (
              <EmptyState title="No payroll rows for this month" hint="Pick another month, or add shifts so hours can be summed." icon="list" />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead>
                    <tr><th>Staff</th><th>Role</th><th>Days</th><th>Hours</th><th>Salary</th><th>Notes</th></tr>
                  </thead>
                  <tbody>
                    {payroll.map((r) => (
                      <tr key={r.staffId}>
                        <td>{r.staffName}</td>
                        <td>{r.role}</td>
                        <td>{r.days}</td>
                        <td>{r.hours}</td>
                        <td>{r.salary}</td>
                        <td>{r.notes}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        );
      case "subscription":
        return subscription && (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Plan & billing</h2>
            <p><strong>Plan:</strong> {subscription.plan ?? "No plan assigned yet"}</p>
            <p><strong>Status:</strong> <span className={`${styles.pill} ${styles.pillActive}`}>{subscription.status}</span></p>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr><th>Metric</th><th>Used</th><th>Limit</th></tr>
                </thead>
                <tbody>
                  <tr><td>Active patients</td><td>{subscription.activePatientsUsed}</td><td>{subscription.activePatientsLimit}</td></tr>
                  <tr><td>WhatsApp messages</td><td>{subscription.whatsappMessagesUsed}</td><td>{subscription.whatsappMessagesLimit}</td></tr>
                  <tr><td>Branches</td><td>{subscription.branchesUsed}</td><td>{subscription.branchesLimit}</td></tr>
                </tbody>
              </table>
            </div>
          </div>
        );
      case "data":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Self-serve data import & export</h2>
            <div className={styles.row}>
              <div className={styles.field}>
                <label className={styles.label}>Import patients</label>
                <button className={styles.btnPrimary} onClick={() => createImport("patients")}>Start import job</button>
              </div>
              <div className={styles.field}>
                <label className={styles.label}>Export full tenant</label>
                <button className={styles.btnPrimary} onClick={() => createExport("full_tenant")}>Start export job</button>
              </div>
            </div>
            <h3 className={styles.cardTitle}>Recent import jobs</h3>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Type</th><th>File</th><th>Status</th><th>Created</th></tr></thead>
                <tbody>
                  {importJobs.map((j) => (
                    <tr key={j.id}><td>{j.importType}</td><td>{j.fileName}</td><td><span className={`${styles.pill} ${styles.pillPending}`}>{j.status}</span></td><td>{j.createdAt ? new Date(j.createdAt).toLocaleString() : "—"}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
            <h3 className={styles.cardTitle}>Recent export jobs</h3>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th>Type</th><th>Status</th><th>Created</th></tr></thead>
                <tbody>
                  {exportJobs.map((j) => (
                    <tr key={j.id}><td>{j.exportType}</td><td><span className={`${styles.pill} ${styles.pillPending}`}>{j.status}</span></td><td>{j.createdAt ? new Date(j.createdAt).toLocaleString() : "—"}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      case "licences":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Facility & licence registry</h2>
            <form onSubmit={addLicence}>
              <div className={styles.row}>
                <div className={styles.field}>
                  <label className={styles.label}>Type</label>
                  <select className={styles.select} value={newLicence.licenceType} onChange={(e) => setNewLicence({ ...newLicence, licenceType: e.target.value })}>
                    <option value="clinician">Clinician</option>
                    <option value="facility">Facility</option>
                  </select>
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Number</label>
                  <input className={styles.input} value={newLicence.number ?? ""} onChange={(e) => setNewLicence({ ...newLicence, number: e.target.value })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Issuing body</label>
                  <input className={styles.input} value={newLicence.issuingBody ?? ""} onChange={(e) => setNewLicence({ ...newLicence, issuingBody: e.target.value })} />
                </div>
                <div className={styles.field}>
                  <label className={styles.label}>Expiry</label>
                  <input className={styles.input} type="date" value={newLicence.expiryDate ?? ""} onChange={(e) => setNewLicence({ ...newLicence, expiryDate: e.target.value })} />
                </div>
              </div>
              <div className={styles.actions}>
                <button className={styles.btnPrimary} type="submit">Add licence</button>
              </div>
            </form>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr><th>Type</th><th>Holder</th><th>Number</th><th>Issuing body</th><th>Expiry</th><th>Status</th></tr>
                </thead>
                <tbody>
                  {licences.map((l) => (
                    <tr key={l.id}>
                      <td>{l.licenceType}</td>
                      <td>{l.holderName ?? "—"}</td>
                      <td>{l.number}</td>
                      <td>{l.issuingBody ?? "—"}</td>
                      <td>{l.expiryDate}</td>
                      <td><span className={`${styles.pill} ${l.status === "valid" ? styles.pillValid : l.status === "expiring_soon" ? styles.pillExpiring : styles.pillExpired}`}>{l.status}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      case "whatsapp":
        return <WhatsAppTemplates authedFetch={authedFetch} />;
      default:
        return null;
    }
  };

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Clinic Setup</h1>
          <p className={styles.subtitle}>Configure the clinic before going live.</p>
        </div>
        <button className={styles.wizardBtn} onClick={() => router.push("/setup/wizard")}>Open setup wizard</button>
      </div>

      <div className={styles.tabs}>
        {TABS.filter((t) => t.key !== "pharmacy" || canPharmacy).map((t) => (
          <button key={t.key} className={tab === t.key ? styles.tabActive : styles.tab} onClick={() => selectTab(t.key)}>
            {t.label}
          </button>
        ))}
      </div>

      {renderContent()}
    </main>
  );
}
