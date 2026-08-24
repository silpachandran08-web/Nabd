"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./provisioning.module.css";

// Matches POST/GET /v1/platform/provisioning-jobs, POST .../{id}/advance, POST .../{id}/approve
// (ProvisioningController) — NB-258/259/260/261. Six fixed steps, run one at a time via advance().
type Step = {
  stepName: string; stepOrder: number; status: string;
  startedAt: string | null; completedAt: string | null; errorDetail: string | null;
};
type Job = {
  id: string; tenantSlug: string; tenantName: string; region: string; status: string; path: string;
  approvedAt: string | null; createdTenantId: string | null; steps: Step[];
};
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

const STEP_LABELS: Record<string, string> = {
  create_tenant: "Create tenant",
  migrate_schema: "Migrate schema",
  seed_masters: "Seed masters",
  provision_whatsapp: "Provision WhatsApp",
  verify_invite_owner: "Verify & invite owner",
  go_live: "Go live",
};

const STATUS_CLASS: Record<string, string> = {
  queued: styles.statusQueued, running: styles.statusRunning, done: styles.statusDone,
  failed: styles.statusFailed, rolled_back: styles.statusRolled_back,
};
const STEP_STATUS_CLASS: Record<string, string> = {
  queued: styles.statusStepQueued, running: styles.statusStepRunning, done: styles.statusStepDone,
  failed: styles.statusStepFailed, rolled_back: styles.statusStepRolled_back,
};

const emptyForm = {
  tenantSlug: "", tenantName: "", region: "IN", ownerEmail: "", ownerName: "", brandName: "", path: "self_serve",
};

export default function ProvisioningPage() {
  const router = useRouter();
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [busyJobId, setBusyJobId] = useState<string | null>(null);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_platform_access_token");
      if (!token) {
        router.replace("/platform/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_platform_access_token");
        router.replace("/platform/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await authedFetch("/platform/provisioning-jobs");
      if (!res) return;
      if (res.status === 403) {
        setForbidden(true);
        return;
      }
      if (!res.ok) {
        setError("Couldn't load provisioning jobs. Try again.");
        return;
      }
      const data: Job[] = await res.json();
      setJobs(data.sort((a, b) => (a.id < b.id ? 1 : -1)));
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setSaving(true);
    try {
      const res = await authedFetch("/platform/provisioning-jobs", { method: "POST", body: JSON.stringify(form) });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't create the job." }));
        setFormError(p.detail || "Couldn't create the job.");
        return;
      }
      setForm(emptyForm);
      load();
    } finally {
      setSaving(false);
    }
  }

  async function approve(jobId: string) {
    setBusyJobId(jobId);
    try {
      const res = await authedFetch(`/platform/provisioning-jobs/${jobId}/approve`, { method: "POST" });
      if (res?.ok) await load();
    } finally {
      setBusyJobId(null);
    }
  }

  // One advance() call runs exactly one step (SSA-01: resumable and inspectable at every step) —
  // looping here just saves an operator from clicking six times for the ordinary happy path.
  async function runToCompletion(jobId: string) {
    setBusyJobId(jobId);
    try {
      const stepCount = Object.keys(STEP_LABELS).length;
      for (let i = 0; i < stepCount; i++) {
        const res = await authedFetch(`/platform/provisioning-jobs/${jobId}/advance`, { method: "POST" });
        if (!res?.ok) break;
        const updated: Job = await res.json();
        setJobs((prev) => prev.map((j) => (j.id === jobId ? updated : j)));
        if (!["queued", "running"].includes(updated.status)) break;
      }
    } finally {
      setBusyJobId(null);
    }
  }

  if (loading) {
    return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Tenant Provisioning</h1>
          <p className={styles.subtitle}>Six-step job queue — create, migrate, seed, WhatsApp, verify &amp; invite, go live.</p>
        </div>
        <span className={styles.frd}>SSA-01 · region locks irreversibly on commit</span>
      </div>

      {forbidden ? (
        <div className={styles.state}>Your role doesn&apos;t have access to tenant provisioning.</div>
      ) : error ? (
        <div className={styles.errorState}>{error}</div>
      ) : (
        <>
          <form className={styles.formCard} onSubmit={submit}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="tenantSlug">Tenant slug</label>
              <input id="tenantSlug" className={styles.input} placeholder="shifa-family-clinic" value={form.tenantSlug}
                onChange={(e) => setForm({ ...form, tenantSlug: e.target.value })} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="tenantName">Tenant name</label>
              <input id="tenantName" className={styles.input} placeholder="Shifa Family Clinic" value={form.tenantName}
                onChange={(e) => setForm({ ...form, tenantName: e.target.value })} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="region">Region</label>
              <select id="region" className={styles.select} value={form.region} onChange={(e) => setForm({ ...form, region: e.target.value })}>
                <option value="IN">India</option>
                <option value="KSA">Saudi Arabia</option>
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="brandName">Brand name</label>
              <input id="brandName" className={styles.input} value={form.brandName}
                onChange={(e) => setForm({ ...form, brandName: e.target.value })} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="ownerName">Owner name</label>
              <input id="ownerName" className={styles.input} value={form.ownerName}
                onChange={(e) => setForm({ ...form, ownerName: e.target.value })} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="ownerEmail">Owner email</label>
              <input id="ownerEmail" type="email" className={styles.input} value={form.ownerEmail}
                onChange={(e) => setForm({ ...form, ownerEmail: e.target.value })} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="path">Path</label>
              <select id="path" className={styles.select} value={form.path} onChange={(e) => setForm({ ...form, path: e.target.value })}>
                <option value="self_serve">Self-serve</option>
                <option value="enterprise">Enterprise (needs approval)</option>
              </select>
            </div>
            <button className={styles.submit} type="submit" disabled={saving}>{saving ? "Queuing…" : "Queue provisioning job"}</button>
            {formError && <div className={styles.formError} role="alert">{formError}</div>}
          </form>

          <div className={styles.jobList}>
            {jobs.length === 0 ? (
              <div className={styles.state}>No provisioning jobs yet.</div>
            ) : (
              jobs.map((job) => {
                const gated = job.path === "enterprise" && !job.approvedAt;
                const finished = ["done", "rolled_back"].includes(job.status);
                return (
                  <div key={job.id} className={styles.jobCard}>
                    <div className={styles.jobHead}>
                      <div>
                        <div className={styles.jobTenant}>{job.tenantName} <span className={styles.jobMeta}>({job.tenantSlug})</span></div>
                        <div className={styles.jobMeta}>
                          {job.region} · <span className={styles.pathBadge}>{job.path === "enterprise" ? "Enterprise" : "Self-serve"}</span>
                          {job.createdTenantId && <> · tenant {job.createdTenantId}</>}
                        </div>
                      </div>
                      <div className={styles.jobActions}>
                        <span className={`${styles.pill} ${STATUS_CLASS[job.status] ?? ""}`}>{job.status.replace("_", " ")}</span>
                        {gated && (
                          <button className={styles.actionBtn} disabled={busyJobId === job.id} onClick={() => approve(job.id)}>
                            {busyJobId === job.id ? "Approving…" : "Approve"}
                          </button>
                        )}
                        {!gated && !finished && (
                          <button className={styles.actionBtn} disabled={busyJobId === job.id} onClick={() => runToCompletion(job.id)}>
                            {busyJobId === job.id ? "Running…" : job.status === "failed" ? "Retry" : "Advance"}
                          </button>
                        )}
                      </div>
                    </div>
                    <div className={styles.steps}>
                      {job.steps.map((s) => (
                        <div key={s.stepName} className={styles.step}>
                          <span className={`${styles.stepDot} ${STEP_STATUS_CLASS[s.status] ?? ""}`}>{s.status.replace("_", " ")}</span>
                          <span className={styles.stepLabel}>{STEP_LABELS[s.stepName] ?? s.stepName}</span>
                          {s.errorDetail && <span className={styles.stepError}>{s.errorDetail}</span>}
                        </div>
                      ))}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </>
      )}
    </main>
  );
}
