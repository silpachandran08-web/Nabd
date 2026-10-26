"use client";

import { formatMoney } from "@nabd/i18n";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import styles from "./overview.module.css";
import { getIdentity, getPermissions, isOwner } from "../../lib/session";
import { can } from "../../lib/permissions";
import Icon from "../../components/ui/Icon";
import StatusPill from "../../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { api, ApiError, reasonText } from "../../lib/api";
import { DpaReacceptBanner } from "../../components/setup/DpaPanel";
import { initials } from "@nabd/domain";
import EtaAccuracyTile from "./EtaAccuracyTile";


// Matches GET /v1/reports/overview (ReportsController.overview → OverviewResponse).
type Overview = {
  date: string;
  visits: { total: number; completed: number; inFlow: number };
  collections: { collected: number; invoices: number; pendingInvoices: number };
  checkout: { pending: number; outstanding: number };
  packages: { soldToday: number; sessionsOwed: number };
  paymentSplit: { method: string; amount: number }[];
  dayClose: { unbilledConsultations: number; pendingCheckouts: number; closed: boolean };
  activeStaff: { staffId: string; name: string; roleName: string; departmentName: string | null; signedInAt: string; inConsult: boolean }[];
  staff: { active: number; suspended: number; invited: number; roles: number };
};
// GET /v1/compliance/health (S4 contract OWN-011); every field tolerated absent.
type HealthItem = { kind?: string; label?: string; status?: string; detail?: string | null; link?: string | null };
type Health = { licences?: { total?: number; expiringSoon?: number; expired?: number }; items?: HealthItem[] };
const HEALTH_TONE: Record<string, string> = { ok: "alertOk", attention: "alertWarn", expired: "alertDanger" };
// Only same-app paths become links; anything else falls back to the licences tab.
const safeLink = (l?: string | null) => (l && l.startsWith("/") && !l.startsWith("//") ? l : "/setup?tab=licences");
type Subscription = { plan: string | null; status: string };
// GET /v1/setup/checklist (same rows the Setup > Checklist tab reads).
type ChecklistItem = { step: string; status: "pending" | "skipped" | "done"; skippedAt: string | null; doneAt: string | null };

const METHOD_LABELS: Record<string, string> = { cash: "Cash", card: "Card", upi: "Digital / UPI", other: "Other" };
// Chart series colours (DESIGN §8.22), never the reserved state colours; each row also carries its label.
const METHOD_COLORS: Record<string, string> = { cash: "var(--ov-series-1)", upi: "var(--ov-series-2)", card: "var(--ov-series-3)", other: "var(--nb-text-tertiary)" };

// Setup tab each checklist step lives on; unknown steps fall back to the Setup home.
function setupHref(step: string): string {
  const k = step.toLowerCase();
  if (k.includes("doctor")) return "/staff"; // the Doctors step is adding clinicians
  const tab = k.includes("profile") || k.includes("tax") ? "profile"
    : k.includes("schedule") || k.includes("shift") ? "shifts"
    : k.includes("price") || k.includes("charge") ? "charges"
    : k.includes("whatsapp") ? "whatsapp"
    : k.includes("consent") ? "consent"
    : k.includes("pharmacy") ? "pharmacy"
    : k.includes("depart") || k === "preset" ? "departments"
    : k === "import" ? "data"
    : k.includes("licen") ? "licences"
    : null;
  return tab ? `/setup?tab=${tab}` : "/setup";
}
const stepLabel = (step: string) => {
  const t = step.replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
};
const hideKey = (tenant: string) => `nabd_setup_card_hidden:${tenant}`;


/** "2026-09-26" → a local calendar date (new Date("2026-09-26") would be UTC midnight, a day early west of UTC). */
function localDate(iso: string): Date {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

function signedInLabel(signedInAt: string, now: number): string {
  const minutes = Math.max(0, Math.floor((now - new Date(signedInAt).getTime()) / 60000));
  if (minutes < 5) return "Now";
  if (minutes < 60) return `${minutes} min ago`;
  return `${Math.floor(minutes / 60)}h ago`;
}

export default function OverviewPage() {
  const [data, setData] = useState<Overview | null>(null);
  const [subscription, setSubscription] = useState<Subscription | null>(null);
  const [checklist, setChecklist] = useState<ChecklistItem[] | null>(null);
  const [health, setHealth] = useState<Health | "loading" | "error" | null>(null);
  const [setupHidden, setSetupHidden] = useState(false);
  const [canSwitchClinic, setCanSwitchClinic] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [currency, setCurrency] = useState<string | null>(null);
  const [now, setNow] = useState(0);

  const load = useCallback(async () => {
    setError(null);
    setForbidden(false);
    try {
      // Licences and plan live under Setup; only asked for when this role can read them.
      const canSetup = getPermissions().includes("setup:view");
      const maybe = <T,>(path: string) => api.get<T>(path).catch(() => null);
      const [subRes, clRes] = await Promise.all([
        canSetup ? maybe<Subscription>("/setup/subscription") : null,
        canSetup ? maybe<ChecklistItem[]>("/setup/checklist") : null,
        api.get<Overview>("/reports/overview").then(setData),
      ]);
      if (canSetup) {
        setHealth("loading");
        api.get<Health>("/compliance/health").then(setHealth).catch(() => setHealth("error"));
      }
      if (subRes) setSubscription(subRes);
      if (clRes) setChecklist(clRes);
      try {
        setSetupHidden(localStorage.getItem(hideKey(getIdentity()?.tenantName ?? "")) === "1");
        // The owner's workspace picker needs the pending owner token from the owner login.
        setCanSwitchClinic(isOwner() && !!sessionStorage.getItem("nabd_owner_pending_token"));
      } catch { /* storage blocked: card simply stays visible */ }
      const region = getIdentity()?.tenantRegion;
      // shortcut: static region -> currency/locale pairs mirror the regions table; read GET /v1/regions once the client caches it
      setCurrency(({ AE: "AED", SA: "SAR", QA: "QAR" } as Record<string, string>)[region ?? ""] ?? "INR");
      setNow(Date.now());
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) setForbidden(true);
      else setError(e instanceof ApiError && e.status < 500 ? "Couldn't load today's overview. Try again." : reasonText(e, ""));
    }
  }, []);

  useEffect(() => {
    // Deferred to a microtask so the effect body never calls setState synchronously
    // (react-hooks/set-state-in-effect) — same as the setup and departments pages.
    void Promise.resolve().then(load);
  }, [load]);

  if (forbidden) {
    return <main className={styles.page}><EmptyState icon="shield" title="You don't have access to the clinic overview." /></main>;
  }
  if (error) {
    return <main className={styles.page}><ErrorState message={error} onRetry={() => void load()} /></main>;
  }
  if (!data || !currency) {
    return (
      <main className={styles.page} aria-busy="true">
        <div className={styles.skelKpis} aria-hidden="true">
          {[0, 1, 2, 3].map((i) => <div key={i} className={styles.skelTile} />)}
        </div>
        <Skeleton rows={5} label="Loading today's overview" />
      </main>
    );
  }

  const today = localDate(data.date);
  const splitTotal = data.paymentSplit.reduce((sum, p) => sum + Number(p.amount), 0);
  // Same rule as Billing & Day Close: only consultations with no bill block the close.
  const blocked = !data.dayClose.closed && data.dayClose.unbilledConsultations > 0;
  const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  const doneSteps = (checklist ?? []).filter((c) => c.status === "done").length;
  const totalSteps = checklist?.length ?? 0;
  const setupPct = totalSteps > 0 ? Math.round((doneSteps / totalSteps) * 100) : 0;
  const showSetup = checklist !== null && totalSteps > 0 && doneSteps < totalSteps && !setupHidden;
  const hideSetup = () => {
    setSetupHidden(true);
    try { localStorage.setItem(hideKey(getIdentity()?.tenantName ?? ""), "1"); } catch { /* per-viewer convenience only */ }
  };

  return (
    <main className={styles.page}>
      {can("setup:view") && <DpaReacceptBanner />}
      <h1 className={styles.title}>Today at a glance</h1>
      <p className={styles.subtitle}>
        {today.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric" })}
        {" · "}{plural(data.visits.total, "visit", "visits")}
        {" · "}{data.activeStaff.length} staff active
      </p>

      {showSetup && checklist && (
        <section className={styles.setupCard} aria-label="Finish setting up your clinic">
          <div className={styles.setupHead}>
            <div>
              <h2 className={styles.setupTitle}>Finish setting up your clinic</h2>
              <p className={styles.setupSub}>{doneSteps} of {totalSteps} · {setupPct}%</p>
            </div>
            <button type="button" className={styles.hideBtn} onClick={hideSetup}>Hide</button>
          </div>
          <div className={styles.progressTrack} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={setupPct} aria-label="Setup progress">
            <div className={styles.progressFill} style={{ width: `${setupPct}%` }} />
          </div>
          <ul className={styles.stepList}>
            {checklist.map((c) => (
              <li key={c.step} className={styles.stepRow}>
                <Link className={styles.stepLink} href={setupHref(c.step)}>{stepLabel(c.step)}</Link>
                {c.status === "done" ? <StatusPill tone="ok" label="Done" />
                  : c.status === "skipped" ? <StatusPill tone="neutral" label="Skipped" icon="list" />
                  : <StatusPill tone="warn" label="To do" />}
              </li>
            ))}
          </ul>
          <p className={styles.stepNote}>Open a step to finish it. Hiding this card only affects this browser.</p>
        </section>
      )}

      <div className={styles.kpis}>
        <div className={styles.card}>
          <div className={styles.cardLabel}>Visits today</div>
          <div className={styles.kpiValue}>{data.visits.total}</div>
          <div className={styles.kpiSub}>{data.visits.completed} completed · {data.visits.inFlow} in flow</div>
        </div>
        <div className={styles.card}>
          <div className={styles.cardLabel}>Collections</div>
          <div className={styles.kpiValue}>{formatMoney(data.collections.collected, currency)}</div>
          <div className={styles.kpiSub}>{plural(data.collections.invoices, "invoice", "invoices")} · {data.collections.pendingInvoices} pending</div>
        </div>
        <div className={styles.card}>
          <div className={styles.cardLabel}>Checkout pending</div>
          <div className={styles.kpiValue}>{data.checkout.pending}</div>
          <div className={styles.kpiSub}>{formatMoney(data.checkout.outstanding, currency)} outstanding</div>
        </div>
        <div className={styles.card}>
          <div className={styles.cardLabel}>Package sales</div>
          <div className={styles.kpiValue}>{data.packages.soldToday}</div>
          <div className={styles.kpiSub}>{plural(data.packages.sessionsOwed, "session", "sessions")} owed</div>
        </div>
      </div>

      <div className={styles.columns}>
        <div className={styles.column}>
          {can("reports:view") && <EtaAccuracyTile />}
          <section className={styles.card} aria-label="Payment split">
            <div className={styles.cardLabel}>Payment split</div>
            {splitTotal === 0 ? (
              <p className={styles.empty}>No payments recorded yet today.</p>
            ) : (
              <>
                <div className={styles.splitBar} aria-hidden="true">
                  {data.paymentSplit.map((p) => (
                    <div key={p.method} style={{ width: `${(Number(p.amount) / splitTotal) * 100}%`, background: METHOD_COLORS[p.method] ?? METHOD_COLORS.other }} />
                  ))}
                </div>
                {data.paymentSplit.map((p) => (
                  <div key={p.method} className={styles.splitRow}>
                    <span className={styles.dot} style={{ background: METHOD_COLORS[p.method] ?? METHOD_COLORS.other }} />
                    <span>{METHOD_LABELS[p.method] ?? p.method}</span>
                    <span className={styles.amount}>{formatMoney(p.amount, currency)}</span>
                    <span className={styles.pct}>{Math.round((Number(p.amount) / splitTotal) * 100)}%</span>
                  </div>
                ))}
              </>
            )}
          </section>

          {health !== null && (
            <section className={styles.card} aria-label="Compliance and licences" aria-busy={health === "loading"}>
              <div className={styles.cardLabel}>Compliance &amp; licences</div>
              {health === "loading" ? (
                <Skeleton rows={2} label="Loading compliance status" />
              ) : health === "error" ? (
                <ErrorState message="Couldn't load compliance status." onRetry={() => void load()} />
              ) : !health.items?.length ? (
                <div className={`${styles.alert} ${styles.alertInfo}`}>
                  <div className={styles.alertTitle}>Nothing to show yet</div>
                  <div className={styles.alertSub}>
                    Add your clinic&apos;s licences under <Link className={styles.inlineLink} href="/setup?tab=licences">Compliance &amp; Audit</Link> to get renewal reminders here.
                  </div>
                </div>
              ) : (
                <div className={styles.alerts}>
                  {health.items.map((it, i) => (
                    <Link key={`${it.kind}-${i}`} href={safeLink(it.link)} className={`${styles.alert} ${styles[HEALTH_TONE[it.status ?? ""] ?? "alertInfo"]} ${styles.alertLink}`}>
                      <div className={styles.alertTitle}>{it.label ?? "Compliance item"}</div>
                      {it.detail && <div className={styles.alertSub}>{it.detail}</div>}
                    </Link>
                  ))}
                </div>
              )}
            </section>
          )}
        </div>

        <div className={styles.column}>
          <section className={blocked ? styles.dayClose : styles.dayCloseClear} aria-label="Day close">
            <div className={blocked ? styles.dayCloseLabel : styles.dayCloseLabelClear}>
              Day close · {data.dayClose.closed ? "closed" : blocked ? "blocked" : "ready"}
            </div>
            <p className={styles.dayCloseText}>
              {data.dayClose.closed
                ? "Today is closed. Billing is locked until the day is unlocked."
                : blocked
                  ? `${plural(data.dayClose.unbilledConsultations, "completed consultation has", "completed consultations have")} no bill. Day close is blocked until each is billed.`
                  : `Nothing blocks the close.${data.dayClose.pendingCheckouts > 0 ? ` ${plural(data.dayClose.pendingCheckouts, "checkout", "checkouts")} still pending.` : ""}`}
            </p>
            {/* LIVE-06: /billing needs billing:view; without it the link is absent, not a dead end. */}
            {can("billing:view") && <Link className={styles.primaryLink} href="/billing">{data.dayClose.closed ? "View day close" : "Go to day close"}</Link>}
          </section>

          <section className={styles.card} aria-label="Staff currently active">
            <div className={styles.cardLabel}>
              Staff currently active
              <span className={styles.countBadge}>{data.activeStaff.length} of {data.staff.active}</span>
            </div>
            {data.activeStaff.length === 0 ? (
              <p className={styles.empty}>Nobody has signed in yet today.</p>
            ) : (
              data.activeStaff.map((s) => (
                <div key={s.staffId} className={styles.staffRow}>
                  <div className={styles.avatar}>{initials(s.name)}</div>
                  <div>
                    <div className={styles.staffName}>{s.name}</div>
                    <div className={styles.staffRole}>{[s.roleName, s.departmentName].filter(Boolean).join(" · ")}</div>
                  </div>
                  <span className={styles.chip}>{s.inConsult ? "In consult" : signedInLabel(s.signedInAt, now)}</span>
                </div>
              ))
            )}
          </section>

          <div className={styles.sectionLabel}>Quick access</div>
          <Link className={styles.quickLink} href="/staff">
            <div>
              <div className={styles.quickTitle}>Staff &amp; Access</div>
              <div className={styles.quickSub}>
                {plural(data.staff.active, "staff", "staff")} · {plural(data.staff.roles, "role", "roles")}
                {data.staff.suspended > 0 && ` · ${data.staff.suspended} suspended`}
                {data.staff.invited > 0 && ` · ${data.staff.invited} invited`}
              </div>
            </div>
            <span className={styles.chevron}><Icon name="chevron" size={18} /></span>
          </Link>
          <Link className={styles.quickLink} href="/setup">
            <div>
              <div className={styles.quickTitle}>Clinic Setup</div>
              <div className={styles.quickSub}>Profile, charges, departments, holidays</div>
            </div>
            <span className={styles.chevron}><Icon name="chevron" size={18} /></span>
          </Link>
          <Link className={styles.quickLink} href="/setup?tab=subscription">
            <div>
              <div className={styles.quickTitle}>Plan &amp; Modules</div>
              <div className={styles.quickSub}>{subscription ? `${subscription.plan ? `${subscription.plan} plan` : "No plan assigned"} · ${subscription.status}` : "Your plan and usage"}</div>
            </div>
            <span className={styles.chevron}><Icon name="chevron" size={18} /></span>
          </Link>
          <Link className={styles.quickLink} href="/account">
            <div>
              <div className={styles.quickTitle}>Account &amp; Security</div>
              <div className={styles.quickSub}>Two-step sign-in, active sessions, emergency access</div>
            </div>
            <span className={styles.chevron}><Icon name="chevron" size={18} /></span>
          </Link>
          {canSwitchClinic && (
            <Link className={styles.quickLink} href="/owner/workspaces">
              <div>
                <div className={styles.quickTitle}>Switch clinic</div>
                <div className={styles.quickSub}>Open another clinic or branch you own</div>
              </div>
              <span className={styles.chevron}><Icon name="chevron" size={18} /></span>
            </Link>
          )}
        </div>
      </div>
    </main>
  );
}
