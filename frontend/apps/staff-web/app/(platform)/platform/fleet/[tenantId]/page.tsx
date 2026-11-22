"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import fleet from "../fleet.module.css";
import styles from "../lifecycle.module.css";
import { isForbidden, platformApi, platformError, useOperatorAccess } from "../../platformApi";
import { EmptyState, ErrorState, Skeleton } from "../../../../components/ui/StateViews";
import RoleDenied, { ReadOnlyNote } from "../../../../components/platform/RoleDenied";
import { TenantStatusPill } from "../../../../components/platform/statusPills";
import {
  LIFECYCLE_STATES, MAX_REASON, MIN_REASON, TRANSITION_EFFECT, actorLabel, canMove, nextStates, reasonProblem, stateLabel, type TenantLifecycle,
} from "../lifecycle";

// ADM-020 (platform.tenants): the tenant's lifecycle. A stepper of the seven states with the moves the server allows from the
// current one, a required reason, and the full history (from, to, operator, reason, time). Reading needs tenant_detail:view;
// the move controls are absent without tenant_detail:edit (super admin). The server enforces both.
export default function TenantDetailPage() {
  const { tenantId } = useParams<{ tenantId: string }>();
  const access = useOperatorAccess();
  const canEdit = access.ready && canMove(access.profile?.permissions);
  const [data, setData] = useState<TenantLifecycle | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [target, setTarget] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // `quiet` refreshes in place after a refused move: the page is never swapped for a skeleton once it has data.
  const load = useCallback(async (quiet = false) => {
    if (!quiet) { setLoading(true); setError(null); }
    try {
      setData(await platformApi.get<TenantLifecycle>(`/platform/tenants/${tenantId}/lifecycle`));
    } catch (e) {
      if (isForbidden(e)) setForbidden(true);
      else if (!quiet) setError(platformError(e, "Couldn't load this clinic's lifecycle. Try again."));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [tenantId]);

  useEffect(() => { void Promise.resolve().then(() => load()); }, [load]);

  function pick(state: string) {
    setTarget(state);
    setReason("");
    setFormError(null);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!target) return;
    const problem = reasonProblem(reason);
    if (problem) { setFormError(problem); return; }
    setSaving(true);
    setFormError(null);
    try {
      setData(await platformApi.post<TenantLifecycle>(`/platform/tenants/${tenantId}/lifecycle/transitions`, { toStatus: target, reason: reason.trim() }));
      setTarget(null);
      setReason("");
    } catch (err) {
      setFormError(platformError(err, "Couldn't change the status."));
      void load(true); // 409 INVALID_TRANSITION: someone else moved it first, so show where it stands now
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <main className={fleet.page}><div className={fleet.card}><Skeleton rows={6} label="Loading clinic lifecycle" /></div></main>;
  if (forbidden) return <main className={fleet.page}><div className={fleet.card}><RoleDenied surface="Tenant detail" /></div></main>;
  if (error || !data) {
    return <main className={fleet.page}><div className={fleet.card}><ErrorState message={error ?? "Couldn't load this clinic's lifecycle. Try again."} onRetry={() => void load()} /></div></main>;
  }

  const allowed = nextStates(data);
  const effect = target ? TRANSITION_EFFECT[target] : null;

  return (
    <main className={fleet.page}>
      <Link className={styles.back} href="/platform/fleet">Back to clinics</Link>
      <div className={fleet.header}>
        <div>
          <h1 className={fleet.title}>{data.tenantName ?? "Clinic"}</h1>
          {data.tenantSlug && <p className={fleet.subtitle}>{data.tenantSlug}</p>}
        </div>
        <TenantStatusPill status={data.status} />
      </div>

      <section className={`${fleet.card} ${styles.section}`} aria-labelledby="lifecycle-h">
        <h2 id="lifecycle-h" className={styles.h2}>Lifecycle</h2>
        <ol className={styles.steps}>
          {LIFECYCLE_STATES.map((s) => (
            <li key={s} className={s === data.status ? styles.stepCurrent : styles.step} aria-current={s === data.status ? "step" : undefined} data-testid={`state-${s}`}>
              <span>{stateLabel(s)}</span>
              {s === data.status && <span className={styles.stepHint}>Current state</span>}
              {canEdit && allowed.includes(s) && (
                <button type="button" className={styles.moveBtn} aria-pressed={target === s} onClick={() => pick(s)}>
                  Move to {s}
                </button>
              )}
            </li>
          ))}
        </ol>

        {access.ready && !canEdit && <ReadOnlyNote />}
        {canEdit && allowed.length === 0 && <p className={styles.hint} role="note">No further move is possible from {data.status}.</p>}

        {canEdit && target && effect && (
          <form className={styles.form} onSubmit={submit} aria-label={`${effect.title}: ${data.tenantName ?? "clinic"}`}>
            <p className={effect.danger ? styles.noteDanger : styles.note}>{effect.note}</p>
            <label className={styles.label} htmlFor="lifecycleReason">Reason (required; kept in the history and the audit log)</label>
            <textarea id="lifecycleReason" className={styles.textarea} value={reason} onChange={(e) => setReason(e.target.value)}
              minLength={MIN_REASON} maxLength={MAX_REASON} required autoFocus aria-describedby="lifecycleReasonHint" />
            <span id="lifecycleReasonHint" className={styles.hint}>At least {MIN_REASON} characters. Do not write patient details here.</span>
            {formError && <div className={styles.error} role="alert">{formError}</div>}
            <div className={styles.actions}>
              <button type="button" className={styles.moveBtn} onClick={() => setTarget(null)}>Cancel</button>
              <button type="submit" className={effect.danger ? styles.confirmDanger : styles.confirm} disabled={saving}>
                {saving ? "Saving…" : effect.title}
              </button>
            </div>
          </form>
        )}
      </section>

      <section className={fleet.card} aria-labelledby="history-h">
        <div className={styles.section}><h2 id="history-h" className={styles.h2}>History</h2></div>
        {data.events.length === 0 ? (
          <EmptyState icon="clock" title="No lifecycle changes yet" hint="Every move appears here with who made it and why." />
        ) : (
          <div className={fleet.tableWrap}>
            <table className={fleet.table}>
              <thead>
                <tr><th scope="col">When</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Operator</th><th scope="col">Reason</th></tr>
              </thead>
              <tbody>
                {data.events.map((e, i) => (
                  <tr key={`${e.changedAt}-${i}`} data-testid="lifecycle-event">
                    <td><time dateTime={e.changedAt}>{new Date(e.changedAt).toLocaleString()}</time></td>
                    <td><TenantStatusPill status={e.fromStatus} /></td>
                    <td><TenantStatusPill status={e.toStatus} /></td>
                    <td>{actorLabel(e)}</td>
                    <td className={styles.reason}>{e.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
