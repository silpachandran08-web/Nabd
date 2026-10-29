"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./compliance.module.css";
import AccessGate from "../../components/ui/AccessGate";
import StatusPill from "../../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/StateViews";
import { can } from "../../lib/permissions";
import { api, ApiError } from "../../lib/api";

// PLT-057. Matches GET /v1/support-access/requests and POST /v1/support-access/requests/{id}/decision.
type Req = {
  id: string; operatorName: string; reason: string; scope: "config_only" | "redacted_patient_view"; minutes: number;
  status: "requested" | "active" | "declined" | "expired" | "revoked"; requestedAt: string; expiresAt: string;
  windowEndsAt: string | null; declineNote: string | null;
};

const SCOPE: Record<Req["scope"], string> = {
  config_only: "Clinic settings only. No patient records.",
  redacted_patient_view: "Limited patient view: initials, masked phone, birth year. No clinical information.",
};
const TONE = { requested: "warn", active: "ok", declined: "danger", expired: "neutral", revoked: "danger" } as const;
const LABEL = { requested: "Waiting for you", active: "Session open", declined: "Declined", expired: "Expired", revoked: "Ended by support" } as const;

function Inner() {
  const [items, setItems] = useState<Req[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [note, setNote] = useState<Record<string, string>>({});
  const mayDecide = can("compliance:approve");

  const load = useCallback(async () => {
    setError(null);
    try {
      setItems((await api.get<{ items: Req[] }>("/support-access/requests")).items);
    } catch {
      setError("Couldn't load support access requests. Try again.");
    }
  }, []);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  async function decide(id: string, decision: "approve" | "decline") {
    setActing(id);
    setActionError(null);
    try {
      await api.post(`/support-access/requests/${id}/decision`, { decision, note: decision === "decline" ? note[id] || undefined : undefined });
      await load();
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0;
      setActionError(status === 409 ? "This request was already answered." : status === 410 ? "This request expired before you answered." : status ? "Couldn't save your answer. Try again." : "Couldn't reach the server. Check your connection and try again.");
      await load();
    } finally {
      setActing(null);
    }
  }

  const pending = (items ?? []).filter((r) => r.status === "requested");
  const past = (items ?? []).filter((r) => r.status !== "requested");

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Compliance &amp; audit</h1>
      <p className={styles.subtitle}>Nabd support looks into your clinic only after you say yes. Every step is written to your audit log.</p>
      {actionError && <div className={styles.error} role="alert">{actionError}</div>}
      <section className={styles.card} aria-labelledby="sa-h">
        <h2 id="sa-h" className={styles.h2}>Support access requests</h2>
        {items === null && !error ? <Skeleton rows={3} label="Loading requests" />
          : error ? <ErrorState message={error} onRetry={load} />
          : items!.length === 0 ? <EmptyState icon="shield" title="No support access requests" hint="When Nabd support asks to look into your clinic you will see it here and on WhatsApp." />
          : (
            <>
              {pending.map((r) => (
                <article key={r.id} className={styles.req} data-testid="pending-request">
                  <div><strong>{r.operatorName}</strong> asks for access for up to <strong>{r.minutes} minutes</strong> after you approve.</div>
                  <div className={styles.muted}>Reason: {r.reason}</div>
                  <div className={styles.muted}>{SCOPE[r.scope]}</div>
                  <div className={styles.muted}>Answer by {new Date(r.expiresAt).toLocaleString()}; after that it expires.</div>
                  {mayDecide && (
                    <div className={styles.actions}>
                      <button className={styles.primary} disabled={acting === r.id} onClick={() => decide(r.id, "approve")}>Approve</button>
                      <input aria-label="Reason for declining (optional)" className={styles.input} placeholder="Reason (optional)" maxLength={500}
                        value={note[r.id] ?? ""} onChange={(e) => setNote({ ...note, [r.id]: e.target.value })} />
                      <button className={styles.secondary} disabled={acting === r.id} onClick={() => decide(r.id, "decline")}>Decline</button>
                    </div>
                  )}
                </article>
              ))}
              {past.length > 0 && (
                <ul className={styles.history} aria-label="Past requests">
                  {past.map((r) => (
                    <li key={r.id}>
                      <StatusPill tone={TONE[r.status]} label={LABEL[r.status]} />{" "}
                      {r.operatorName}, {r.minutes} min, {r.scope === "config_only" ? "settings only" : "limited patient view"}
                      <span className={styles.muted}> {r.windowEndsAt ? `until ${new Date(r.windowEndsAt).toLocaleString()}` : new Date(r.requestedAt).toLocaleString()}</span>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
      </section>
    </main>
  );
}

export default function CompliancePage() {
  return <AccessGate permission="compliance:view" surface="Compliance"><Inner /></AccessGate>;
}
