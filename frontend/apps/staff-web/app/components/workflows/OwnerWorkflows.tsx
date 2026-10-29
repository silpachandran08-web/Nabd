"use client";

// ACC-008 / ACC-013 (owner.workflows): per visit type choose auto / review / hybrid, then check-in, ETA, walk-in and no-show rules,
// preview the patient journey from the draft, and publish a new version with a fresh authenticator code.
// Edit controls need setup:edit and are absent otherwise; everyone with queue:view sees the published rules read-only.
import { useCallback, useEffect, useState } from "react";
import styles from "../../(clinic)/setup/setup.module.css";
import w from "./workflows.module.css";
import AccessGate from "../ui/AccessGate";
import Can from "../ui/Can";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { api, ApiError, reasonText } from "../../lib/api";
import { intentKeys } from "@nabd/api-client";
import { usePermissions } from "../../lib/usePermissions";
import { requestStepUpToken } from "../../lib/stepUp";
import {
  CHECK_IN_MODES, VISIT_TYPES, defaultPolicy, journey, pointerLabel, policyProblem, samePolicy, setIn, showValue,
  type BookingPolicy, type VisitTypeKey,
} from "../../lib/bookingPolicy";

type Current = { published: { version: number; config: BookingPolicy; publishedAt?: string } | null; draft: { config: BookingPolicy; updatedAt?: string } | null };
type Change = { pointer: string; before?: unknown; after?: unknown };
type Version = { version: number; publishedAt?: string; publishedByName?: string | null; changeNote?: string | null; diff: Change[] };
type PresetResult = { draft: { config: BookingPolicy }; changes: Change[]; departmentWorkflowChanges: { departmentName: string; toVitalsEnabled: boolean }[] };

const intents = intentKeys();
const ROLES = ["doctor", "reception", "nurse", "owner"] as const;
const INTERLEAVE: [string, string][] = [["1:2", "1 walk-in per 2 bookings"], ["1:3", "1 per 3"], ["1:4", "1 per 4"], ["reserved_only", "Reserved slots only"], ["none", "No interleave"]];
const ETA: [string, string][] = [["exact", "Exact time"], ["range", "Time range"], ["queue_position", "Queue position"]];

export default function OwnerWorkflows() {
  return (
    <AccessGate permission="queue:view" surface="booking rules">
      <Rules />
    </AccessGate>
  );
}

function Rules() {
  const { can } = usePermissions();
  const mayEdit = can("setup:edit");
  const [cur, setCur] = useState<Current | null>(null);
  const [versions, setVersions] = useState<Version[]>([]);
  const [cfg, setCfg] = useState<BookingPolicy>(defaultPolicy());
  const [tab, setTab] = useState<VisitTypeKey>("new");
  const [error, setError] = useState<string | null>(null);
  const [fail, setFail] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [applied, setApplied] = useState<PresetResult | null>(null);
  const [code, setCode] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [c, v] = await Promise.all([api.get<Current>("/booking-policy"), api.get<{ items: Version[] }>("/booking-policy/versions?limit=20")]);
      setCur(c); setVersions(v.items); setCfg(c.draft?.config ?? c.published?.config ?? defaultPolicy()); setError(null);
    } catch (e) { setError(e instanceof ApiError ? "Could not load the booking rules." : "Could not reach the server. Check your connection."); }
  }, []);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load; state is set after the awaited fetch
  useEffect(() => { void load(); }, [load]);

  if (error && !cur) return <main className={styles.page}><ErrorState message={error} onRetry={() => void load()} /></main>;
  if (!cur) return <main className={styles.page}><Skeleton rows={6} label="Loading booking rules" /></main>;

  const base = cur.published?.config ?? defaultPolicy();
  const unpublished = !samePolicy(cfg, base);
  const problem = policyProblem(cfg);
  const set = (path: (string | number)[], value: unknown) => { setCfg((c) => setIn(c, path, value)); setNotice(null); };

  async function call<T>(work: () => Promise<T>, failText: string): Promise<T | null> {
    setBusy(true); setFail(null);
    try { return await work(); } catch (e) {
      if (e instanceof ApiError && e.status === 400 && typeof (e.problem as { pointer?: unknown }).pointer === "string") {
        const p = e.problem as { pointer: string; rule?: string };
        setFail(`Not saved: ${pointerLabel(p.pointer)} is not valid${p.rule ? ` (${p.rule.replaceAll("_", " ")})` : ""}.`);
      } else setFail(reasonText(e, failText));
      return null;
    } finally { setBusy(false); }
  }

  const saveDraft = () => call(async () => {
    const r = await api.put<{ draft: { config: BookingPolicy } }>("/booking-policy/draft", { config: cfg });
    setCfg(r.draft.config); setNotice("Draft saved. It takes effect when you publish."); await load(); return true;
  }, "Could not save the draft.");

  const preset = async () => {
    const r = await call(() => api.post<PresetResult>("/booking-policy/draft/apply-preset", { preset: "solo" }), "Could not apply the Solo preset.");
    if (r) { setCfg(r.draft.config); setApplied(r); setNotice("Solo preset applied to the draft. Review it, then publish."); }
  };

  const publish = async () => {
    const su = await requestStepUpToken(code);
    if ("error" in su) { setFail(su.error); return; }
    const ok = await call(async () => {
      if (unpublished || !cur?.draft) await api.put("/booking-policy/draft", { config: cfg });
      const r = await api.post<{ version: number }>("/booking-policy/draft/publish", note.trim() ? { changeNote: note.trim() } : {},
        { headers: { "X-Step-Up-Token": su.token }, idempotencyKey: intents.get("publish-booking-policy", { cfg, note }) });
      intents.done("publish-booking-policy"); setNotice(`Version ${r.version} published. New bookings follow it from now on.`); setCode(""); setNote(""); setApplied(null);
      await load(); return true;
    }, "Could not publish.");
    if (!ok) setCode("");
  };

  const j = journey(cfg, tab);
  const rule = cfg.visitTypes[tab];

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Booking & queue rules</h1>
          <p className={styles.subtitle}>
            {cur.published ? `Published version ${cur.published.version}.` : "Nothing published yet: every booking is accepted automatically."}{" "}
            {cfg.preset === "solo" && <strong>{cfg.presetCustomised ? "Solo (customised)" : "Solo doctor"}</strong>}
          </p>
        </div>
        <span className={unpublished ? w.pill : w.pillOk}>{unpublished ? "Unpublished changes" : "Up to date"}</span>
      </div>

      {notice && <div className={styles.card} role="status">{notice}</div>}
      {fail && <div className={styles.errorState} role="alert">{fail}</div>}

      <Can permission="setup:edit">
        <div className={styles.card}>
          <h2 className={styles.cardTitle}>Start from a preset</h2>
          <div className={w.actionsWrap}>
            <button type="button" className={`${styles.btn} ${w.tap}`} disabled={busy} onClick={() => void preset()}>Solo doctor</button>
            <span className={styles.subtitle}>One step: auto-accept, self check-in, no vitals step. You still review and publish.</span>
          </div>
          {applied && (
            <div>
              <h3 className={styles.cardTitle}>What the preset changed ({applied.changes.length})</h3>
              {applied.changes.length === 0 ? <p>Nothing: these settings were already in the draft.</p> : (
                <ul>{applied.changes.map((c) => <li key={c.pointer}>{pointerLabel(c.pointer)}: {showValue(c.before)} to {showValue(c.after)}</li>)}</ul>
              )}
              {applied.departmentWorkflowChanges.map((d) => <p key={d.departmentName}>{d.departmentName}: vitals step {d.toVitalsEnabled ? "on" : "off"} when published.</p>)}
            </div>
          )}
        </div>
      </Can>

      <div className={styles.card}>
        <div className={styles.tabs} role="tablist" aria-label="Visit type">
          {VISIT_TYPES.map((v) => (
            <button key={v.key} type="button" role="tab" aria-selected={tab === v.key} className={`${tab === v.key ? styles.tabActive : styles.tab} ${w.tap}`} onClick={() => setTab(v.key)}>{v.label}</button>
          ))}
        </div>
        {mayEdit ? (
          <div className={w.grid}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="wf-acceptance">Booking acceptance</label>
              <select id="wf-acceptance" className={`${styles.input} ${w.tap}`} value={rule.acceptance} onChange={(e) => set(["visitTypes", tab, "acceptance"], e.target.value)}>
                <option value="auto">Auto-accept</option><option value="review">Review first</option><option value="hybrid">Hybrid (auto for returning patients)</option>
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="wf-cap">Daily cap per doctor</label>
              <input id="wf-cap" type="number" min={1} max={200} className={`${styles.input} ${w.tap}`} value={rule.dailyCapPerDoctor} onChange={(e) => set(["visitTypes", tab, "dailyCapPerDoctor"], Number(e.target.value))} />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="wf-reviewer">Reviewer</label>
              {cfg.solo
                ? <p id="wf-reviewer">The doctor (solo mode)</p>
                : <select id="wf-reviewer" className={`${styles.input} ${w.tap}`} value={rule.reviewerRole} onChange={(e) => set(["visitTypes", tab, "reviewerRole"], e.target.value)}>{ROLES.map((r) => <option key={r} value={r}>{r}</option>)}</select>}
            </div>
          </div>
        ) : (
          <p>{rule.acceptance === "auto" ? "Auto-accept" : rule.acceptance === "review" ? "Review first" : "Hybrid"}, up to {rule.dailyCapPerDoctor} a day per doctor, reviewed by {cfg.solo ? "the doctor" : rule.reviewerRole}.</p>
        )}
      </div>

      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Clinic rules</h2>
        {mayEdit ? (
          <>
            <label className={w.check}><input type="checkbox" checked={cfg.solo} onChange={(e) => set(["solo"], e.target.checked)} /> Solo mode (the doctor runs the clinic without a front desk)</label>
            <fieldset className={styles.plainFieldset}>
              <legend className={styles.label}>Check-in modes</legend>
              <div className={w.checks}>
                {CHECK_IN_MODES.map((m) => (
                  <label key={m.key} className={w.check}>
                    <input type="checkbox" checked={cfg.checkIn.modes.includes(m.key)}
                      onChange={(e) => set(["checkIn", "modes"], e.target.checked ? [...cfg.checkIn.modes, m.key] : cfg.checkIn.modes.filter((x) => x !== m.key))} /> {m.label}
                  </label>
                ))}
              </div>
            </fieldset>
            <div className={w.grid}>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="wf-eta">ETA shown to patients</label>
                <select id="wf-eta" className={`${styles.input} ${w.tap}`} value={cfg.eta.displayMode} onChange={(e) => set(["eta", "displayMode"], e.target.value)}>{ETA.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="wf-margin">Leave-home margin (minutes)</label>
                <input id="wf-margin" type="number" min={0} max={120} className={`${styles.input} ${w.tap}`} value={cfg.eta.leaveHomeMarginMinutes} onChange={(e) => set(["eta", "leaveHomeMarginMinutes"], Number(e.target.value))} />
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="wf-walkin">Walk-ins</label>
                <select id="wf-walkin" className={`${styles.input} ${w.tap}`} value={cfg.walkIn.interleave} onChange={(e) => set(["walkIn", "interleave"], e.target.value)}>{INTERLEAVE.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="wf-grace">No-show grace</label>
                <select id="wf-grace" className={`${styles.input} ${w.tap}`} value={cfg.noShow.graceMinutes} onChange={(e) => set(["noShow", "graceMinutes"], Number(e.target.value))}>{[10, 15, 20].map((m) => <option key={m} value={m}>{m} minutes</option>)}</select>
              </div>
            </div>
            <div className={w.checks}>
              <label className={w.check}><input type="checkbox" checked={cfg.noShow.autoMark} onChange={(e) => set(["noShow", "autoMark"], e.target.checked)} /> Auto-mark no-show after the grace</label>
              <label className={w.check}><input type="checkbox" checked={cfg.noShow.pullForward} onChange={(e) => set(["noShow", "pullForward"], e.target.checked)} /> Pull the next patient forward</label>
              <label className={w.check}><input type="checkbox" checked={cfg.noShow.depositAfterRepeat} onChange={(e) => set(["noShow", "depositAfterRepeat"], e.target.checked)} /> Flag a deposit after repeated no-shows</label>
              <label className={w.check}><input type="checkbox" checked={cfg.workflow.vitalsEnabled} onChange={(e) => set(["workflow", "vitalsEnabled"], e.target.checked)} /> Vitals step before the consult</label>
              <label className={w.check}><input type="checkbox" checked={cfg.workflow.triageEnabled} onChange={(e) => set(["workflow", "triageEnabled"], e.target.checked)} /> Triage</label>
            </div>
          </>
        ) : (
          <dl className={w.summaryList}>
            <div>Solo mode: {showValue(cfg.solo)}</div><div>Check-in: {cfg.checkIn.modes.join(", ")}</div>
            <div>ETA: {cfg.eta.displayMode}, leave-home margin {cfg.eta.leaveHomeMarginMinutes} min</div>
            <div>Walk-ins: {cfg.walkIn.interleave}</div><div>No-show grace: {cfg.noShow.graceMinutes} min</div>
          </dl>
        )}
      </div>

      <div className={styles.card} aria-label="Patient journey preview">
        <h2 className={styles.cardTitle}>Patient journey: {VISIT_TYPES.find((v) => v.key === tab)?.label}</h2>
        <ol className={w.steps}>
          {j.steps.map((s) => (
            <li key={s.key} className={w.step}><span>{s.label}</span><span className={s.touch ? w.touch : w.noTouch}>{s.touch ? `${s.touch} touches` : "no staff"}</span></li>
          ))}
        </ol>
        <p data-testid="touches">{j.touchesBeforeConsult} staff touch{j.touchesBeforeConsult === 1 ? "" : "es"} before the consult.</p>
      </div>

      <Can permission="setup:edit">
        <div className={styles.card}>
          <h2 className={styles.cardTitle}>Publish</h2>
          {problem && <p role="alert" className={styles.errorState}>{problem}</p>}
          <label className={styles.label} htmlFor="wf-note">Note for the history (optional)</label>
          <input id="wf-note" className={`${styles.input} ${w.tap}`} maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
          <div className={w.actionsWrap}>
            <button type="button" className={`${styles.btn} ${w.tap}`} disabled={busy || !!problem} onClick={() => void saveDraft()}>Save draft</button>
            <input className={`${styles.input} ${w.tap}`} style={{ maxInlineSize: 180 }} inputMode="numeric" autoComplete="one-time-code" maxLength={6} aria-label="Authenticator code"
              placeholder="6-digit code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} />
            <button type="button" className={`${styles.btnPrimary} ${w.tap}`} disabled={busy || !!problem || code.length !== 6 || (!unpublished && !cur.draft)} onClick={() => void publish()}>Publish</button>
          </div>
        </div>
      </Can>

      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Version history</h2>
        {versions.length === 0 ? <p>No versions published yet.</p> : (
          <div className={w.history}>
            {versions.map((v) => (
              <details key={v.version}>
                <summary>Version {v.version} · {v.publishedAt ? new Date(v.publishedAt).toLocaleString() : ""} · {v.publishedByName ?? "unknown"} · {v.diff.length} change{v.diff.length === 1 ? "" : "s"}{v.changeNote ? ` · ${v.changeNote}` : ""}</summary>
                {v.diff.map((c) => <div key={c.pointer} className={w.diffRow}>{pointerLabel(c.pointer)}: {showValue(c.before)} to {showValue(c.after)}</div>)}
              </details>
            ))}
          </div>
        )}
      </div>
    </main>
  );
}
