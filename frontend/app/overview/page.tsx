"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import styles from "./overview.module.css";
import { getIdentity, getPermissions } from "../lib/session";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

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
type Licence = { id: string; licenceType: string; holderName: string | null; number: string; issuingBody: string | null; expiryDate: string };
type Subscription = { plan: string; status: string };

const METHOD_LABELS: Record<string, string> = { cash: "Cash", card: "Card", upi: "Digital / UPI", other: "Other" };
const METHOD_COLORS: Record<string, string> = { cash: "#5fd4d0", card: "#6aa8ff", upi: "#4ccf8f", other: "#b08cff" };
const LICENCE_TYPE_LABELS: Record<string, string> = { facility: "Facility", clinician: "Clinician" }; // LicenceWriteRequest's two types
const LICENCE_WARNING_DAYS = 30; // same window SetupRepository.computeLicenceStatus uses

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts[1]?.[0] ?? "")).toUpperCase();
}

/** "2026-09-26" → a local calendar date (new Date("2026-09-26") would be UTC midnight, a day early west of UTC). */
function localDate(iso: string): Date {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

function daysBetween(from: Date, to: Date): number {
  return Math.round((to.getTime() - from.getTime()) / 86_400_000);
}

function signedInLabel(signedInAt: string, now: number): string {
  const minutes = Math.max(0, Math.floor((now - new Date(signedInAt).getTime()) / 60000));
  if (minutes < 5) return "Now";
  if (minutes < 60) return `${minutes} min ago`;
  return `${Math.floor(minutes / 60)}h ago`;
}

export default function OverviewPage() {
  const router = useRouter();
  const [data, setData] = useState<Overview | null>(null);
  const [licences, setLicences] = useState<Licence[] | null>(null);
  const [subscription, setSubscription] = useState<Subscription | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [money, setMoney] = useState<Intl.NumberFormat | null>(null);
  const [now, setNow] = useState(0);

  const authedFetch = useCallback(
    async (path: string) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${token}` } });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(async () => {
    setError(null);
    try {
      // Licences and plan live under Setup; only asked for when this role can read them.
      const canSetup = getPermissions().includes("setup:view");
      const [ovRes, licRes, subRes] = await Promise.all([
        authedFetch("/reports/overview"),
        canSetup ? authedFetch("/setup/licences") : Promise.resolve(null),
        canSetup ? authedFetch("/setup/subscription") : Promise.resolve(null),
      ]);
      if (!ovRes) return;
      if (ovRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!ovRes.ok) {
        setError("Couldn't load today's overview. Try again.");
        return;
      }
      setData(await ovRes.json());
      if (licRes?.ok) setLicences(await licRes.json());
      if (subRes?.ok) setSubscription(await subRes.json());
      const region = getIdentity()?.tenantRegion;
      setMoney(new Intl.NumberFormat(region === "KSA" ? "en-SA" : "en-IN", {
        style: "currency", currency: region === "KSA" ? "SAR" : "INR", maximumFractionDigits: 0,
      }));
      setNow(Date.now());
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    }
  }, [authedFetch]);

  useEffect(() => {
    // Deferred to a microtask so the effect body never calls setState synchronously
    // (react-hooks/set-state-in-effect) — same as the setup and departments pages.
    void Promise.resolve().then(load);
  }, [load]);

  if (forbidden) {
    return <main className={styles.page}><p className={styles.state}>You don&apos;t have access to the clinic overview.</p></main>;
  }
  if (error) {
    return <main className={styles.page}><div className={styles.error} role="alert">{error}</div></main>;
  }
  if (!data || !money) {
    return <main className={styles.page}><p className={styles.state}>Loading…</p></main>;
  }

  const today = localDate(data.date);
  const splitTotal = data.paymentSplit.reduce((sum, p) => sum + Number(p.amount), 0);
  const licenceAlerts = (licences ?? [])
    .map((l) => ({ ...l, daysLeft: daysBetween(today, localDate(l.expiryDate)) }))
    .filter((l) => l.daysLeft <= LICENCE_WARNING_DAYS)
    .sort((a, b) => a.daysLeft - b.daysLeft);
  // Same rule as Billing & Day Close: only consultations with no bill block the close.
  const blocked = !data.dayClose.closed && data.dayClose.unbilledConsultations > 0;
  const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Today at a glance</h1>
      <p className={styles.subtitle}>
        {today.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric" })}
        {" · "}{plural(data.visits.total, "visit", "visits")}
        {" · "}{data.activeStaff.length} staff active
      </p>

      <div className={styles.kpis}>
        <div className={styles.card}>
          <div className={styles.cardLabel}>Visits today</div>
          <div className={styles.kpiValue}>{data.visits.total}</div>
          <div className={styles.kpiSub}>{data.visits.completed} completed · {data.visits.inFlow} in flow</div>
        </div>
        <div className={styles.card}>
          <div className={styles.cardLabel}>Collections</div>
          <div className={styles.kpiValue}>{money.format(Number(data.collections.collected))}</div>
          <div className={styles.kpiSub}>{plural(data.collections.invoices, "invoice", "invoices")} · {data.collections.pendingInvoices} pending</div>
        </div>
        <div className={styles.card}>
          <div className={styles.cardLabel}>Checkout pending</div>
          <div className={styles.kpiValue}>{data.checkout.pending}</div>
          <div className={styles.kpiSub}>{money.format(Number(data.checkout.outstanding))} outstanding</div>
        </div>
        <div className={styles.card}>
          <div className={styles.cardLabel}>Package sales</div>
          <div className={styles.kpiValue}>{data.packages.soldToday}</div>
          <div className={styles.kpiSub}>{plural(data.packages.sessionsOwed, "session", "sessions")} owed</div>
        </div>
      </div>

      <div className={styles.columns}>
        <div className={styles.column}>
          <section className={styles.card} aria-label="Payment split">
            <div className={styles.cardLabel}>Payment split</div>
            {splitTotal === 0 ? (
              <p className={styles.empty}>No payments recorded yet today.</p>
            ) : (
              <>
                <div className={styles.splitBar}>
                  {data.paymentSplit.map((p) => (
                    <div key={p.method} style={{ width: `${(Number(p.amount) / splitTotal) * 100}%`, background: METHOD_COLORS[p.method] ?? METHOD_COLORS.other }} />
                  ))}
                </div>
                {data.paymentSplit.map((p) => (
                  <div key={p.method} className={styles.splitRow}>
                    <span className={styles.dot} style={{ background: METHOD_COLORS[p.method] ?? METHOD_COLORS.other }} />
                    <span>{METHOD_LABELS[p.method] ?? p.method}</span>
                    <span className={styles.amount}>{money.format(Number(p.amount))}</span>
                    <span className={styles.pct}>{Math.round((Number(p.amount) / splitTotal) * 100)}%</span>
                  </div>
                ))}
              </>
            )}
          </section>

          {licences !== null && (
            <section className={styles.card} aria-label="Compliance and licences">
              <div className={styles.cardLabel}>Compliance &amp; licences</div>
              <div className={styles.alerts}>
                {licences.length === 0 && (
                  <div className={`${styles.alert} ${styles.alertInfo}`}>
                    <div className={styles.alertTitle}>No licences recorded yet</div>
                    <div className={styles.alertSub}>
                      Add your clinic&apos;s licences under <Link className={styles.inlineLink} href="/setup?tab=licences">Compliance &amp; Audit</Link> to get renewal reminders here.
                    </div>
                  </div>
                )}
                {licences.length > 0 && licenceAlerts.length === 0 && (
                  <div className={`${styles.alert} ${styles.alertOk}`}>
                    <div className={styles.alertTitle}>All {plural(licences.length, "licence", "licences")} current</div>
                    <div className={styles.alertSub}>Nothing expires in the next {LICENCE_WARNING_DAYS} days.</div>
                  </div>
                )}
                {licenceAlerts.map((l) => (
                  <div key={l.id} className={`${styles.alert} ${l.daysLeft < 0 ? styles.alertDanger : styles.alertWarn}`}>
                    <div className={styles.alertTitle}>
                      {LICENCE_TYPE_LABELS[l.licenceType] ?? l.licenceType} licence{" "}
                      {l.daysLeft < 0 ? `expired ${plural(-l.daysLeft, "day", "days")} ago`
                        : l.daysLeft === 0 ? "expires today" : `renews in ${plural(l.daysLeft, "day", "days")}`}
                    </div>
                    <div className={styles.alertSub}>
                      {[l.number, l.holderName, l.issuingBody].filter(Boolean).join(" · ")}
                    </div>
                  </div>
                ))}
              </div>
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
            <Link className={styles.primaryLink} href="/billing">{data.dayClose.closed ? "View day close" : "Go to day close"}</Link>
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
            <span className={styles.chevron} aria-hidden="true">›</span>
          </Link>
          <Link className={styles.quickLink} href="/setup">
            <div>
              <div className={styles.quickTitle}>Clinic Setup</div>
              <div className={styles.quickSub}>Profile, charges, departments, holidays</div>
            </div>
            <span className={styles.chevron} aria-hidden="true">›</span>
          </Link>
          <Link className={styles.quickLink} href="/setup?tab=subscription">
            <div>
              <div className={styles.quickTitle}>Plan &amp; Modules</div>
              <div className={styles.quickSub}>{subscription ? `${subscription.plan} plan · ${subscription.status}` : "Your plan and usage"}</div>
            </div>
            <span className={styles.chevron} aria-hidden="true">›</span>
          </Link>
        </div>
      </div>
    </main>
  );
}
