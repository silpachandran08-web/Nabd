"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./setup.module.css";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type Problem = { title: string; detail: string };

type ChecklistItem = { step: string; status: "pending" | "skipped" | "done"; skippedAt: string | null; doneAt: string | null };
type Profile = { id: string; name: string; region: string; timezone: string; taxId: string | null; taxIdType: string | null; whatsappNumber: string | null; specialties: string[]; status: string; setupCompletedAt: string | null };
type Charge = { id: string; code: string; name: string; category: string; baseAmount: number; followUpAmount: number | null; emergencyAmount: number | null; taxCode: string | null; doctorOverride: boolean; active: boolean; effectiveFrom: string; effectiveTo: string | null; displayOrder: number };
type Policy = { id: string; policyKey: string; value: string; version: number };
type ConsentContact = { name: string; email: string; phone: string | null };
type Holiday = { id: string; holidayDate: string; name: string; recurring: boolean };
type Shift = { id: string; staffId: string; staffName: string | null; patternJson: string; effectiveFrom: string; effectiveTo: string | null };
type PayrollRow = { staffId: string; staffName: string; role: string; days: number; hours: number; salary: number; notes: string };
type Subscription = { plan: string; status: string; activePatientsUsed: number; activePatientsLimit: number; whatsappMessagesUsed: number; whatsappMessagesLimit: number; branchesUsed: number; branchesLimit: number };
type ImportJob = { id: string; importType: string; fileName: string; status: string; resultUrl: string | null; errorMessage: string | null; createdAt: string | null };
type ExportJob = { id: string; exportType: string; status: string; resultUrl: string | null; errorMessage: string | null; createdAt: string | null };
type Licence = { id: string; licenceType: string; holderId: string | null; holderName: string | null; number: string; issuingBody: string | null; expiryDate: string; region: string; status: string };
type StaffSummary = { id: string; name: string; roleName: string };

const TABS = [
  { key: "checklist", label: "Checklist" },
  { key: "profile", label: "Profile" },
  { key: "charges", label: "Charges" },
  { key: "policies", label: "Policies" },
  { key: "consent", label: "Consent" },
  { key: "holidays", label: "Holidays" },
  { key: "shifts", label: "Shifts" },
  { key: "payroll", label: "Payroll" },
  { key: "subscription", label: "Subscription" },
  { key: "data", label: "Import / Export" },
  { key: "licences", label: "Licences" },
];

export default function SetupPage() {
  const router = useRouter();
  const [tab, setTab] = useState("checklist");
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
      const [cl, pr, ch, po, co, ho, sh, su, ij, ej, li, st] = await Promise.all([
        fetchJson<ChecklistItem[]>("/setup/checklist"),
        fetchJson<Profile>("/setup/profile"),
        fetchJson<Charge[]>("/setup/charges"),
        fetchJson<Policy[]>("/setup/policies"),
        fetchJson<ConsentContact>("/setup/consent-contact"),
        fetchJson<Holiday[]>("/setup/holidays"),
        fetchJson<Shift[]>("/setup/shifts"),
        fetchJson<Subscription>("/setup/subscription"),
        fetchJson<ImportJob[]>("/setup/import-jobs"),
        fetchJson<ExportJob[]>("/setup/export-jobs"),
        fetchJson<Licence[]>("/setup/licences"),
        fetchJson<StaffSummary[]>("/setup/staff"),
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
  }, [authedFetch]);

  useEffect(() => {
    loadAll();
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

  const markStep = async (step: string, status: "skipped" | "done") => {
    if (await post(`/setup/checklist/${step}/${status === "skipped" ? "skip" : "complete"}`, { status })) {
      loadAll();
    }
  };

  const saveProfile = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!profile) return;
    const body = {
      name: profile.name,
      timezone: profile.timezone,
      taxIdType: profile.taxIdType,
      taxId: profile.taxId,
      whatsappNumber: profile.whatsappNumber,
      specialties: profile.specialties,
    };
    if (await patch("/setup/profile", body)) loadAll();
  };

  const [newCharge, setNewCharge] = useState<Partial<Charge>>({ active: true, displayOrder: 0 });
  const addCharge = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCharge.code || !newCharge.name || !newCharge.category || newCharge.baseAmount == null || !newCharge.effectiveFrom) return;
    if (await post("/setup/charges", newCharge)) {
      setNewCharge({ active: true, displayOrder: 0 });
      loadAll();
    }
  };

  const toggleCharge = async (c: Charge) => {
    if (await patch(`/setup/charges/${c.id}`, { ...c, active: !c.active })) loadAll();
  };

  const updatePolicy = async (p: Policy) => {
    const value = prompt(`New value for ${p.policyKey}`, p.value);
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

  const loadPayroll = async (month: string) => {
    const res = await authedFetch(`/setup/payroll-export?month=${month}`);
    if (res?.ok) setPayroll(await res.json());
  };

  const createImport = async (importType: string) => {
    if (await post("/setup/import-jobs", { importType, fileName: `${importType}-import.csv` })) loadAll();
  };

  const createExport = async (exportType: string) => {
    if (await post("/setup/export-jobs", { exportType })) loadAll();
  };

  const [newLicence, setNewLicence] = useState<Partial<Licence>>({ region: "IN", licenceType: "clinician" });
  const addLicence = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newLicence.licenceType || !newLicence.number || !newLicence.expiryDate || !newLicence.region) return;
    if (await post("/setup/licences", newLicence)) {
      setNewLicence({ region: "IN", licenceType: "clinician" });
      loadAll();
    }
  };

  const renderContent = () => {
    if (loading) return <div className={styles.state}>Loading…</div>;
    if (error) return <div className={styles.errorState}>{error}</div>;

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
      case "profile":
        return (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Clinic profile</h2>
            {profile && (
              <form onSubmit={saveProfile}>
                <div className={styles.field}>
                  <label className={styles.label}>Clinic name</label>
                  <input className={styles.input} value={profile.name} onChange={(e) => setProfile({ ...profile, name: e.target.value })} />
                </div>
                <div className={styles.row}>
                  <div className={styles.field}>
                    <label className={styles.label}>Timezone</label>
                    <input className={styles.input} value={profile.timezone} onChange={(e) => setProfile({ ...profile, timezone: e.target.value })} />
                  </div>
                  <div className={styles.field}>
                    <label className={styles.label}>WhatsApp number</label>
                    <input className={styles.input} value={profile.whatsappNumber ?? ""} onChange={(e) => setProfile({ ...profile, whatsappNumber: e.target.value })} />
                  </div>
                </div>
                <div className={styles.row}>
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
                <div className={styles.field}>
                  <label className={styles.label}>Specialties (comma separated)</label>
                  <input className={styles.input} value={profile.specialties.join(", ")} onChange={(e) => setProfile({ ...profile, specialties: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) })} />
                </div>
                <div className={styles.actions}>
                  <button className={styles.btnPrimary} type="submit">Save profile</button>
                </div>
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
                  <input className={styles.input} type="number" value={newCharge.baseAmount ?? ""} onChange={(e) => setNewCharge({ ...newCharge, baseAmount: Number(e.target.value) })} />
                </div>
              </div>
              <div className={styles.actions}>
                <button className={styles.btnPrimary} type="submit">Add charge</button>
              </div>
            </form>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr><th>Code</th><th>Name</th><th>Category</th><th>Base</th><th>Status</th><th></th></tr>
                </thead>
                <tbody>
                  {charges.map((c) => (
                    <tr key={c.id}>
                      <td>{c.code}</td>
                      <td>{c.name}</td>
                      <td>{c.category}</td>
                      <td>{c.baseAmount}</td>
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
            <div className={styles.field}>
              <label className={styles.label}>Month</label>
              <input className={styles.input} type="month" defaultValue={new Date().toISOString().slice(0, 7)} onChange={(e) => loadPayroll(e.target.value)} />
            </div>
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
          </div>
        );
      case "subscription":
        return subscription && (
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Plan & billing</h2>
            <p><strong>Plan:</strong> {subscription.plan}</p>
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
      default:
        return null;
    }
  };

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Clinic Setup & Administration</h1>
          <p className={styles.subtitle}>Configure the clinic before going live.</p>
        </div>
        <button className={styles.wizardBtn} onClick={() => router.push("/setup/wizard")}>Open setup wizard</button>
      </div>

      <div className={styles.tabs}>
        {TABS.map((t) => (
          <button key={t.key} className={tab === t.key ? styles.tabActive : styles.tab} onClick={() => setTab(t.key)}>
            {t.label}
          </button>
        ))}
      </div>

      {renderContent()}
    </main>
  );
}
