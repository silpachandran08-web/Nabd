"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { formatMoney } from "@nabd/i18n/format";
import base from "../../support/support.module.css";
import styles from "./activation.module.css";
import { EmptyState, ErrorState, Skeleton } from "../../../../components/ui/StateViews";
import RoleDenied from "../../../../components/platform/RoleDenied";
import { isForbidden, platformApi, platformError, useOperatorAccess } from "../../platformApi";
import { barPct, COVERABLE, isOpen, MARKETS, NEXT_ACTION, STEP_LABELS, type Cohort, type QueueItem, type StuckPage } from "./activation";

// ADM-005 (platform.onboard): where self-serve clinics stop in the wizard (funnel per sign-up week, median hours to go-live) and
// the clinics stuck on a step longer than N hours. ADM-006: the white-glove queue. Reads need onboarding:view; the queue actions
// are absent without onboarding:edit. No PHI: ids, step names, counts, times and the clinic name only.
const iso = (d: Date) => d.toISOString().slice(0, 10);
const fmt = (s: string | null) => (s ? new Date(s).toLocaleString() : "—");
const money = (minor: number, currency: string) => formatMoney(minor, currency, { minor: true });

export default function ActivationPage() {
  const access = useOperatorAccess();
  const canEdit = access.ready && !!access.profile?.permissions.includes("onboarding:edit");
  const [forbidden, setForbidden] = useState(false);

  // ---- funnel
  const [from, setFrom] = useState(() => iso(new Date(Date.now() - 84 * 86400000)));
  const [to, setTo] = useState(() => iso(new Date()));
  const [market, setMarket] = useState("");
  const [specialty, setSpecialty] = useState("");
  const [cohorts, setCohorts] = useState<Cohort[] | null>(null);
  const [funnelError, setFunnelError] = useState<string | null>(null);
  const loadFunnel = useCallback(async () => {
    setFunnelError(null);
    setCohorts(null);
    const q = new URLSearchParams({ from, to, ...(market ? { market } : {}), ...(specialty.trim() ? { specialty: specialty.trim() } : {}) });
    try {
      setCohorts((await platformApi.get<{ cohorts: Cohort[] }>(`/platform/activation/funnel?${q}`)).cohorts);
    } catch (e) {
      if (isForbidden(e)) setForbidden(true);
      else setFunnelError(platformError(e, "Couldn't load the funnel. Try again."));
    }
  }, [from, to, market, specialty]);

  // ---- stuck list
  const [hours, setHours] = useState(48);
  const [stuck, setStuck] = useState<StuckPage | null>(null);
  const [stuckError, setStuckError] = useState<string | null>(null);
  const loadStuck = useCallback(async (cursor?: string) => {
    setStuckError(null);
    if (!cursor) setStuck(null);
    try {
      const page = await platformApi.get<StuckPage>(`/platform/activation/stuck?hours=${hours}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
      setStuck((prev) => (cursor && prev ? { items: [...prev.items, ...page.items], nextCursor: page.nextCursor } : page));
    } catch (e) {
      if (isForbidden(e)) setForbidden(true);
      else setStuckError(platformError(e, "Couldn't load the stuck clinics. Try again."));
    }
  }, [hours]);

  // ---- white-glove queue
  const [queue, setQueue] = useState<QueueItem[] | null>(null);
  const [queueError, setQueueError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [acting, setActing] = useState<string | null>(null);
  const [slotPick, setSlotPick] = useState<Record<string, string>>({});
  const [covered, setCovered] = useState<Record<string, string[]>>({});
  const loadQueue = useCallback(async () => {
    setQueueError(null);
    try {
      setQueue(await platformApi.get<QueueItem[]>("/platform/white-glove"));
    } catch (e) {
      if (isForbidden(e)) setForbidden(true);
      else setQueueError(platformError(e, "Couldn't load the white-glove queue. Try again."));
    }
  }, []);

  useEffect(() => {
    // Deferred to a microtask so the effect body never calls setState synchronously (react-hooks/set-state-in-effect).
    void Promise.resolve().then(() => { void loadFunnel(); void loadStuck(); void loadQueue(); });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps -- first load only; filters reload on "Apply"

  async function move(item: QueueItem, toStatus: string) {
    setActing(item.id);
    setActionError(null);
    const body: Record<string, unknown> = { toStatus };
    if (toStatus === "scheduled") body.slotAt = slotPick[item.id] ?? item.preferredSlots[0];
    if (toStatus === "done") body.coveredSteps = covered[item.id] ?? [];
    try {
      await platformApi.post(`/platform/white-glove/${item.id}/transitions`, body);
      await loadQueue();
    } catch (e) {
      setActionError(platformError(e, "Couldn't update the engagement. Try again."));
    } finally {
      setActing(null);
    }
  }

  if (forbidden) return <main className={base.page}><RoleDenied surface="Activation & onboarding" /></main>;

  return (
    <main className={base.page}>
      <div className={base.header}>
        <div>
          <h1 className={base.title}>Activation &amp; white-glove</h1>
          <p className={base.subtitle}>Where self-serve clinics stop in the setup wizard, who is stuck, and the assisted-setup queue.</p>
        </div>
        <span className={base.count}>SCS-03 · SCS-04</span>
      </div>

      <section className={styles.section} aria-labelledby="funnel-h">
        <h2 id="funnel-h" className={styles.h2}>Funnel by sign-up week</h2>
        <form className={styles.filters} onSubmit={(e) => { e.preventDefault(); void loadFunnel(); }}>
          <label className={styles.field}>From<input className={styles.input} type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label className={styles.field}>To<input className={styles.input} type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
          <label className={styles.field}>Market
            <select className={styles.input} value={market} onChange={(e) => setMarket(e.target.value)}>
              <option value="">All</option>
              {MARKETS.map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </label>
          <label className={styles.field}>Specialty<input className={styles.input} value={specialty} onChange={(e) => setSpecialty(e.target.value)} /></label>
          <button type="submit" className={base.actionBtn}>Apply</button>
        </form>
        <div className={base.card}>
          {funnelError ? <ErrorState message={funnelError} onRetry={() => void loadFunnel()} />
            : cohorts === null ? <Skeleton rows={4} label="Loading funnel" />
            : cohorts.length === 0 ? <EmptyState icon="inbox" title="No sign-ups in this range" hint="Widen the dates or clear the filters." />
            : cohorts.map((c) => (
              <div key={c.week} className={styles.cohort} data-testid={`cohort-${c.week}`}>
                <div className={styles.cohortHead}>
                  <strong>Week of {c.week} · {c.signups} sign-ups</strong>
                  <span className={base.muted}>Median to go-live: {c.medianHoursToLive === null ? "none live yet" : `${c.medianHoursToLive} h`}</span>
                </div>
                <div className={styles.bars}>
                  {c.steps.map((s) => (
                    <div key={s.step} style={{ display: "contents" }}>
                      <span>{STEP_LABELS[s.step] ?? s.step}</span>
                      <span className={styles.bar} role="img" aria-label={`${s.reached} reached, ${s.completed} completed`}>
                        <span className={styles.reached} style={{ inlineSize: `${barPct(s.reached, c.signups)}%` }} />
                        <span className={styles.completed} style={{ inlineSize: `${barPct(s.completed, c.signups)}%` }} />
                      </span>
                      <span className={base.muted}>{s.completed} / {s.reached}</span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
        </div>
      </section>

      <section className={styles.section} aria-labelledby="stuck-h">
        <h2 id="stuck-h" className={styles.h2}>Stuck clinics</h2>
        <form className={styles.filters} onSubmit={(e) => { e.preventDefault(); void loadStuck(); }}>
          <label className={styles.field}>On one step for more than (hours)
            <input className={styles.input} type="number" min={1} max={2160} value={hours} onChange={(e) => setHours(Number(e.target.value) || 48)} />
          </label>
          <button type="submit" className={base.actionBtn}>Apply</button>
        </form>
        <div className={base.card}>
          {stuckError ? <ErrorState message={stuckError} onRetry={() => void loadStuck()} />
            : stuck === null ? <Skeleton rows={4} label="Loading stuck clinics" />
            : stuck.items.length === 0 ? <EmptyState icon="inbox" title="No clinic is stuck" hint="Clinics on one step longer than the threshold show here." />
            : (
              <div className={base.tableWrap}>
                <table className={base.table}>
                  <thead><tr><th scope="col">Clinic</th><th scope="col">Step</th><th scope="col">Since</th><th scope="col">Nudges sent</th></tr></thead>
                  <tbody>
                    {stuck.items.map((i) => (
                      <tr key={`${i.tenantId}-${i.step}`}>
                        <td><Link href={`/platform/fleet/${i.tenantId}`}>Open clinic</Link></td>
                        <td>{STEP_LABELS[i.step] ?? i.step}</td>
                        <td>{fmt(i.since)}</td>
                        <td>{i.nudgesSent}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {stuck.nextCursor && <button type="button" className={base.actionBtn} onClick={() => void loadStuck(stuck.nextCursor!)}>Load more</button>}
              </div>
            )}
        </div>
      </section>

      <section className={styles.section} aria-labelledby="wg-h">
        <h2 id="wg-h" className={styles.h2}>White-glove setup queue</h2>
        <p className={base.subtitle}>To work inside a clinic, request support access on the engagement&apos;s ticket; the owner approves it.</p>
        {actionError && <div className={base.actionError} role="alert">{actionError}</div>}
        <div className={base.card}>
          {queueError ? <ErrorState message={queueError} onRetry={() => void loadQueue()} />
            : queue === null ? <Skeleton rows={4} label="Loading queue" />
            : queue.length === 0 ? <EmptyState icon="inbox" title="No open bookings" hint="Owners book an assisted setup from the setup wizard." />
            : (
              <div className={base.tableWrap}>
                <table className={base.table}>
                  <thead><tr><th scope="col">Clinic</th><th scope="col">Status</th><th scope="col">Fee</th><th scope="col">Call</th><th scope="col">Note</th><th scope="col"></th></tr></thead>
                  <tbody>
                    {queue.map((q) => {
                      const next = NEXT_ACTION[q.status];
                      return (
                        <tr key={q.id} data-testid={`wg-${q.id}`}>
                          <td>{q.tenantName}</td>
                          <td>{q.status.replace("_", " ")}</td>
                          <td>{q.fee.status === "waived" ? `waived (${q.waiverReason})` : `${money(q.fee.amountMinor, q.fee.currency)} · ${q.fee.status}`}</td>
                          <td>
                            {q.slotAt ? fmt(q.slotAt) : canEdit && q.status === "requested" ? (
                              <select className={styles.input} aria-label="Call slot" value={slotPick[q.id] ?? q.preferredSlots[0]}
                                onChange={(e) => setSlotPick((p) => ({ ...p, [q.id]: e.target.value }))}>
                                {q.preferredSlots.map((s) => <option key={s} value={s}>{fmt(s)}</option>)}
                              </select>
                            ) : q.preferredSlots.map(fmt).join(" · ")}
                          </td>
                          <td className={base.muted}>{q.notes ?? "—"}</td>
                          <td>
                            {canEdit && isOpen(q.status) && (
                              <div className={base.actions}>
                                {q.status === "in_progress" && (
                                  <fieldset className={styles.checks} aria-label="Steps set up">
                                    {COVERABLE.map((s) => (
                                      <label key={s} className={styles.check}>
                                        <input type="checkbox" checked={(covered[q.id] ?? []).includes(s)}
                                          onChange={(e) => setCovered((c) => ({ ...c, [q.id]: e.target.checked ? [...(c[q.id] ?? []), s] : (c[q.id] ?? []).filter((x) => x !== s) }))} />
                                        {STEP_LABELS[s]}
                                      </label>
                                    ))}
                                  </fieldset>
                                )}
                                {next && (
                                  <button type="button" className={base.actionBtn} disabled={acting === q.id || (next.toStatus === "done" && !(covered[q.id] ?? []).length)}
                                    onClick={() => void move(q, next.toStatus)}>{acting === q.id ? "…" : next.label}</button>
                                )}
                                <button type="button" className={base.actionBtn} disabled={acting === q.id} onClick={() => void move(q, "cancelled")}>Cancel</button>
                              </div>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
        </div>
      </section>
    </main>
  );
}
