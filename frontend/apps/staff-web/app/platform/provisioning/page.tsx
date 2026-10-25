"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./provisioning.module.css";
import InviteLink from "./InviteLink";
import Dialog from "../../components/ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import RoleDenied from "../../components/platform/RoleDenied";
import { JobStatusPill } from "../../components/platform/statusPills";
import { isForbidden, platformApi, platformError, useOperatorAccess } from "../platformApi";

// Matches POST/GET /v1/platform/provisioning-jobs, POST .../{id}/advance, POST .../{id}/approve
// (ProvisioningController) — NB-258/259/260/261. Six fixed steps, run one at a time via advance().
//
// The wireframe (Platform Console v29) draws "Tenant Provisioning" as six tabs. Only tab 1 has a
// real API behind it — tabs 2/4/6 are reference panels in the wireframe itself (no API calls there
// either, just local click-state), and tab 5 (WhatsApp/Meta onboarding) needs E17's messaging
// infrastructure, which doesn't exist in this app. Those stay reference-only here too, honestly
// labeled, rather than faking a live tree/roster/onboarding flow this app can't actually run.
type Step = {
  stepName: string; stepOrder: number; status: string;
  startedAt: string | null; completedAt: string | null; errorDetail: string | null;
};
// GET /v1/regions (OPS-061): the picker and its consequences come from the regions table, not from this file.
type RegionRow = {
  code: string; name: string; currency: string; taxRegime: string; standardTaxRate: number | null;
  regulator: string; defaultTimezone: string;
};
type Job = {
  id: string; tenantSlug: string; tenantName: string; region: string; status: string; path: string;
  approvedAt: string | null; createdTenantId: string | null; steps: Step[];
  // Reveal-once (NB-353): only non-null on the single advance() response right after verify_invite_owner runs.
  ownerInviteToken: string | null;
  // Reveal-once (NB-354): the owner's top-level account invite — null if they already activated
  // it on an earlier clinic (see OwnerService.invite()'s own-pin check).
  ownerAccountInviteToken: string | null;
};

const STEP_LABELS: Record<string, string> = {
  create_tenant: "Create tenant",
  migrate_schema: "Migrate schema",
  seed_masters: "Seed masters",
  provision_whatsapp: "Provision WhatsApp",
  verify_invite_owner: "Verify & invite owner",
  go_live: "Go live",
};

const emptyForm = {
  tenantSlug: "", tenantName: "", region: "IN", ownerEmail: "", ownerName: "", ownerMobile: "", brandName: "", path: "self_serve",
};

const TABS = [
  "New tenant", "Owner · Brand · Clinic", "Lifecycle", "Platform roles", "WhatsApp numbers", "Secrets & integrations",
] as const;

const LIFECYCLE_STATES = [
  { key: "trialing", label: "Trialing", sub: "14 days · synthetic data · shared WhatsApp number", detail: "What ends it: conversion to a paid plan, or day 14 → archived." },
  { key: "active", label: "Active", sub: "paid plan + signed DPA · own WhatsApp number", detail: "What ends it: failed payment → grace, or owner cancellation → export then archive." },
  { key: "overdue", label: "Payment overdue (grace)", sub: "14 days · everything works · banner to the owner only", detail: "What ends it: payment → active, or day 15 → suspended." },
  { key: "suspended", label: "Suspended — read-only", sub: "no booking, no billing · history readable · bot points patients to the phone", detail: "What the patient is told: \"The clinic is temporarily offline — please call 080xxxxxxx\" — and the clinic is shown the exact words its patients are receiving." },
  { key: "archived", label: "Archived", sub: "hidden from the fleet · restorable for 90 days", detail: "What remains: data intact for 90 days, no login, no messages, no invoice." },
  { key: "export", label: "Export requested", sub: "full package, OTP-gated, 7-day link", detail: "What is exported: patients, visits, invoices, prescriptions, consents, attachments — CSV + PDF, OTP-gated link." },
  { key: "purged", label: "Purged", sub: "manual, only after the export download is confirmed — never on a timer", detail: "The gate: requires a confirmed export download + written owner approval + super-admin approval. Never automatic." },
];

const PLATFORM_ROLES = [
  { label: "Super admin", sub: "everything, plus discount approvals above the cap and purge approvals" },
  { label: "Implementation", sub: "provisions tenants, configures per-tenant integrations, hands over" },
  { label: "Support engineer", sub: "tenant data only with clinic consent, 60 minutes, fully logged" },
  { label: "Billing only", sub: "plans, invoices, discounts up to the cap — no tenant data" },
  { label: "SRE", sub: "installation-wide secrets, migrations, performance — never clinical records" },
  { label: "Read-only commercial", sub: "fleet revenue and conversion, aggregate only" },
  { label: "Compliance / DPO", sub: "consent registry, DSR, breach ops, RoPA — no configuration changes" },
];

const WA_MODEL = [
  { label: "Trial", value: "Nabd's shared number", sub: "sends and receives immediately · clinic identity carried in the message body" },
  { label: "Paid", value: "The clinic's own number", sub: "the clinic buys it, we configure it · approved display name shown to the patient" },
  { label: "Aggregator", value: "Nabd's unified number", sub: "permanent for aggregator patients — routed invisibly into the clinic inbox" },
];

const META_STEPS = [
  "Clinic hands over Meta login — for the setup session only",
  "We complete business verification — trade licence, address, website",
  "Display-name approval — must match the brand's trade name",
  "Number registration & migration — the clinic's number goes live",
  "Clinic changes its password — mandatory, we prompt for it and log the confirmation",
];

const SECRETS = [
  { label: "Installation Meta app keys", owner: "SRE", sub: "shared across the fleet · rotated every 90 days" },
  { label: "Region KMS keys", owner: "SRE", sub: "region-scoped · never cross a border" },
  { label: "Tenant payment gateway", owner: "Implementation", sub: "the clinic's own account · entered by the clinic or by us in white-glove" },
  { label: "Insurance / NPHIES connector", owner: "Implementation", sub: "per tenant · payer portal or NPHIES credentials depending on region" },
  { label: "SMS fallback provider", owner: "SRE", sub: "installation level · DLT registration for India" },
  { label: "Tenant export signing key", owner: "Compliance", sub: "signs the OTP-gated export links" },
];

export default function ProvisioningPage() {
  const access = useOperatorAccess();
  const [tab, setTab] = useState(0);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [showNewTenant, setShowNewTenant] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [busyJobId, setBusyJobId] = useState<string | null>(null);
  const [jobActionError, setJobActionError] = useState<Record<string, string>>({});
  const [ownerInviteTokens, setOwnerInviteTokens] = useState<Record<string, string>>({});
  const [ownerAccountInviteTokens, setOwnerAccountInviteTokens] = useState<Record<string, string>>({});
  const [lifecycleSel, setLifecycleSel] = useState(LIFECYCLE_STATES[1].key);

  const [regions, setRegions] = useState<RegionRow[]>([]);

  useEffect(() => {
    void platformApi.get<{ items?: RegionRow[] }>("/platform/regions").then((r) => setRegions(r.items ?? []))
      .catch(() => { /* the form still works: the server validates the code */ });
  }, []);

  const load = useCallback(async (showSkeleton = false) => {
    if (showSkeleton) setLoading(true);
    setError(null);
    try {
      const data = await platformApi.get<Job[]>("/platform/provisioning-jobs");
      setJobs(data.sort((a, b) => (a.id < b.id ? 1 : -1)));
    } catch (e) {
      if (isForbidden(e)) setForbidden(true);
      else setError(platformError(e, "Couldn't load provisioning jobs. Try again."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(() => load());
  }, [load]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setSaving(true);
    try {
      await platformApi.post("/platform/provisioning-jobs", form);
      setForm(emptyForm);
      setShowNewTenant(false);
      load();
    } catch (e) {
      setFormError(platformError(e, "Couldn't create the job."));
    } finally {
      setSaving(false);
    }
  }

  async function approve(jobId: string) {
    setBusyJobId(jobId);
    setJobActionError((prev) => ({ ...prev, [jobId]: "" }));
    try {
      await platformApi.post(`/platform/provisioning-jobs/${jobId}/approve`);
      await load();
    } catch (e) {
      // A failure must always say why (it once failed silently, leaving the button just reset).
      setJobActionError((prev) => ({ ...prev, [jobId]: platformError(e, "Couldn't approve the job. Try again.") }));
    } finally {
      setBusyJobId(null);
    }
  }

  // One advance() call runs exactly one step (SSA-01: resumable and inspectable at every step) —
  // looping here just saves an operator from clicking six times for the ordinary happy path.
  async function runToCompletion(jobId: string) {
    setBusyJobId(jobId);
    setJobActionError((prev) => ({ ...prev, [jobId]: "" }));
    try {
      const stepCount = Object.keys(STEP_LABELS).length;
      for (let i = 0; i < stepCount; i++) {
        const updated = await platformApi.post<Job>(`/platform/provisioning-jobs/${jobId}/advance`);
        setJobs((prev) => prev.map((j) => (j.id === jobId ? updated : j)));
        if (updated.ownerInviteToken) {
          // captured separately: the very next advance() call in this loop overwrites the job's
          // own ownerInviteToken field with null, so this is the only chance to keep it around.
          setOwnerInviteTokens((prev) => ({ ...prev, [jobId]: updated.ownerInviteToken! }));
        }
        if (updated.ownerAccountInviteToken) {
          // Same reveal-once capture, for the separate top-level owner-account invite (NB-354) —
          // null here whenever this owner already activated their account on an earlier clinic.
          setOwnerAccountInviteTokens((prev) => ({ ...prev, [jobId]: updated.ownerAccountInviteToken! }));
        }
        if (!["queued", "running"].includes(updated.status)) break;
      }
    } catch (e) {
      setJobActionError((prev) => ({ ...prev, [jobId]: platformError(e, "Couldn't advance the job. Try again.") }));
    } finally {
      setBusyJobId(null);
    }
  }

  const lifecycleDetail = LIFECYCLE_STATES.find((s) => s.key === lifecycleSel) ?? LIFECYCLE_STATES[1];

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Tenant Provisioning</h1>
          <p className={styles.subtitle}>Six-step job queue — create, migrate, seed, WhatsApp, verify &amp; invite, go live.</p>
        </div>
        <span className={styles.frd}>SSA-01 · region locks irreversibly on commit</span>
      </div>

      {!forbidden && (
        <div className={styles.tabStrip}>
          {TABS.map((t, i) => (
            <button key={t} type="button" className={i === tab ? styles.tabActive : styles.tab} onClick={() => setTab(i)}>{t}</button>
          ))}
        </div>
      )}

      {loading ? (
        <Skeleton rows={5} label="Loading provisioning jobs" />
      ) : forbidden ? (
        <RoleDenied
          surface="Tenant provisioning"
          body="SSA-02 · Only Super admin or Implementation can create a tenant. The element is absent, not disabled — no create button exists anywhere for this role."
        />
      ) : error ? (
        <ErrorState message={error} onRetry={() => load(true)} />
      ) : (
        <>
          {tab === 0 && (
            <>
              <div className={styles.tabHeadRow}>
                <span className={styles.jobMeta}>{jobs.length} provisioning job{jobs.length === 1 ? "" : "s"}</span>
                {access.canWrite && (
                  <button type="button" className={styles.submit} onClick={() => setShowNewTenant(true)}>+ New tenant</button>
                )}
              </div>

              {access.canWrite && showNewTenant && (
                <Dialog title="Provision a new tenant" onClose={() => setShowNewTenant(false)}>
                  <form className={styles.dialogForm} onSubmit={submit}>
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor="path">Path</label>
                      <div className={styles.segmented}>
                        <button type="button" className={form.path === "self_serve" ? styles.segActive : styles.seg}
                          onClick={() => setForm({ ...form, path: "self_serve" })}>Self-serve</button>
                        <button type="button" className={form.path === "enterprise" ? styles.segActive : styles.seg}
                          onClick={() => setForm({ ...form, path: "enterprise" })}>Enterprise (assisted)</button>
                      </div>
                    </div>
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor="tenantSlug">URL identifier</label>
                      <input id="tenantSlug" className={styles.input} placeholder="shifa-family-clinic" value={form.tenantSlug}
                        onChange={(e) => setForm({ ...form, tenantSlug: e.target.value })} required />
                    </div>
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor="tenantName">Tenant name</label>
                      <input id="tenantName" className={styles.input} placeholder="Shifa Family Clinic" value={form.tenantName}
                        onChange={(e) => setForm({ ...form, tenantName: e.target.value })} required />
                    </div>
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor="brandName">Trade name (patient-facing)</label>
                      <input id="brandName" className={styles.input} value={form.brandName}
                        onChange={(e) => setForm({ ...form, brandName: e.target.value })} required />
                    </div>
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor="region">Country &amp; data region</label>
                      <select id="region" className={styles.select} value={form.region} onChange={(e) => setForm({ ...form, region: e.target.value })}>
                        {regions.length === 0 && <option value={form.region}>{form.region}</option>}
                        {regions.map((r) => <option key={r.code} value={r.code}>{r.name}</option>)}
                      </select>
                      {regions.filter((r) => r.code === form.region).map((r) => (
                        <p key={r.code} className={styles.formNote} data-testid="region-consequences">
                          {r.currency}, {r.taxRegime === "NONE" ? "no VAT" : r.standardTaxRate == null ? r.taxRegime : `${r.taxRegime} ${r.standardTaxRate}%`},
                          {" "}{r.defaultTimezone}, regulator {r.regulator}. Irreversible after go-live: the region cannot be changed later.
                        </p>
                      ))}
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
                      <label className={styles.label} htmlFor="ownerMobile">Owner mobile</label>
                      <input id="ownerMobile" type="tel" className={styles.input} placeholder="+91 98765 43210" value={form.ownerMobile}
                        onChange={(e) => setForm({ ...form, ownerMobile: e.target.value })} required />
                    </div>
                    {formError && <div className={styles.formError} role="alert">{formError}</div>}
                    <div className={styles.formNote}>
                      Once provisioning reaches &quot;Verify &amp; invite owner&quot;, this creates the owner&apos;s login and shows a one-time invite link here to relay by hand — Tax ID, doctor count, specialties and plan selection still aren&apos;t part of the provisioning job; they&apos;re set afterwards in Clinic Setup.
                    </div>
                    <div className={styles.modalActions}>
                      <button type="button" className={styles.cancelBtn} onClick={() => setShowNewTenant(false)}>Cancel</button>
                      <button type="submit" className={styles.submit} disabled={saving}>{saving ? "Provisioning…" : "Provision tenant"}</button>
                    </div>
                  </form>
                </Dialog>
              )}

              <div className={styles.jobList}>
                {jobs.length === 0 ? (
                  <EmptyState
                    icon="building"
                    title="No provisioning jobs yet"
                    hint={access.canWrite ? "Use “+ New tenant” to provision the first clinic." : undefined}
                  />
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
                            <JobStatusPill status={job.status} />
                            {access.canWrite && gated && (
                              <button className={styles.actionBtn} disabled={busyJobId === job.id} onClick={() => approve(job.id)}>
                                {busyJobId === job.id ? "Approving…" : "Approve"}
                              </button>
                            )}
                            {access.canWrite && !gated && !finished && (
                              <button className={styles.actionBtn} disabled={busyJobId === job.id} onClick={() => runToCompletion(job.id)}>
                                {busyJobId === job.id ? "Running…" : job.status === "failed" ? "Retry" : "Run provisioning"}
                              </button>
                            )}
                          </div>
                        </div>
                        {jobActionError[job.id] && <div className={styles.formError} role="alert">{jobActionError[job.id]}</div>}
                        {ownerInviteTokens[job.id] && (
                          <InviteLink label="Clinic staff invite link" path="/accept-invite/" token={ownerInviteTokens[job.id]} />
                        )}
                        {ownerAccountInviteTokens[job.id] && (
                          <InviteLink
                            label="Owner account invite link — lets this owner log into every clinic they own from one place"
                            path="/owner/accept-invite/"
                            emailed
                            token={ownerAccountInviteTokens[job.id]}
                          />
                        )}
                        <div className={styles.steps}>
                          {job.steps.map((s) => (
                            <div key={s.stepName} className={styles.step}>
                              <JobStatusPill status={s.status} />
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

          {tab === 1 && (
            <div className={styles.refCard}>
              <span className={styles.frd}>SSA-01 · the brand carries visible identity, the clinic carries operations</span>
              <div className={styles.cNoteInfo}>
                Resolution rule: if a patient sees it, it belongs to the brand; if it differs branch to branch, it belongs to the clinic. No field exists at both levels.
              </div>
              <div className={styles.refText}>
                The one exception: a clinic may override hours and branch address inside a brand template — a declared override, never a second template.
              </div>
              <div className={styles.cNoteWarn}>Not built yet: a live Owner → Brand → Clinic tree view for the fleet. The rule above is what the data model (owners → brands → tenants) already follows.</div>
            </div>
          )}

          {tab === 2 && (
            <div className={styles.refCard}>
              <span className={styles.frd}>SSA-01 / DPO-04 · seven states, every transition audited</span>
              <div className={styles.refList}>
                {LIFECYCLE_STATES.map((s) => (
                  <button key={s.key} type="button" className={s.key === lifecycleSel ? styles.refRowActive : styles.refRow} onClick={() => setLifecycleSel(s.key)}>
                    <div className={styles.refRowLabel}>{s.label}</div>
                    <div className={styles.refRowSub}>{s.sub}</div>
                  </button>
                ))}
              </div>
              <div className={styles.cNoteInfo}>{lifecycleDetail.detail}</div>
            </div>
          )}

          {tab === 3 && (
            <div className={styles.refCard}>
              <span className={styles.frd}>SSA-02 · seven SaaS-side roles — no single super-admin does everything</span>
              <div className={styles.refList}>
                {PLATFORM_ROLES.map((r) => (
                  <div key={r.label} className={styles.refRowStatic}>
                    <div className={styles.refRowLabel}>{r.label}</div>
                    <div className={styles.refRowSub}>{r.sub}</div>
                  </div>
                ))}
              </div>
              <div className={styles.cNoteWarn}>SSA-08 · Sales may discount to 15%; beyond that opens a super-admin approval request with a written reason. No discount applies before approval.</div>
              <div className={styles.cNoteInfo}>SSA-04 · A support engineer never sees clinical fields — the same &quot;overlay: support&quot; model the clinic already sees.</div>
            </div>
          )}

          {tab === 4 && (
            <div className={styles.refCard}>
              <span className={styles.frd}>SSA-03 / CAD-07 · shared on trial, own number on paid — and we complete Meta verification on the clinic&apos;s behalf</span>
              <div className={styles.refList}>
                {WA_MODEL.map((w) => (
                  <div key={w.label} className={styles.refRowStatic}>
                    <div className={styles.refRowLabel}>{w.label} — {w.value}</div>
                    <div className={styles.refRowSub}>{w.sub}</div>
                  </div>
                ))}
              </div>
              <span className={styles.frd}>SSA-03 · Meta verification, white-glove — SCS-04</span>
              <div className={styles.refList}>
                {META_STEPS.map((m) => <div key={m} className={styles.refRowStatic}><div className={styles.refRowSub}>{m}</div></div>)}
              </div>
              <div className={styles.cNoteWarn}>Not built yet: this whole tab needs E17&apos;s WhatsApp/Meta messaging infrastructure, which doesn&apos;t exist in this app. Shown here as the design reference only.</div>
            </div>
          )}

          {tab === 5 && (
            <div className={styles.refCard}>
              <span className={styles.frd}>SSA-01 / SYS-14 · installation secrets are separate from tenant integrations — two different roles</span>
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th scope="col">Secret</th><th scope="col">Owner</th><th scope="col">Note</th></tr></thead>
                  <tbody>
                    {SECRETS.map((s) => (
                      <tr key={s.label}><td>{s.label}</td><td>{s.owner}</td><td className={styles.muted}>{s.sub}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className={styles.cNoteWarn}>Reference only — no actual secret values are ever shown or managed here. Real secrets live in Render/KMS, not this app&apos;s database.</div>
            </div>
          )}
        </>
      )}
    </main>
  );
}
