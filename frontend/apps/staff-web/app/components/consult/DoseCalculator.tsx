"use client";

import { useState } from "react";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { rateError, weightAge, weightError, type DoseResult } from "@nabd/domain";
import type { AuthedFetch, Problem } from "./shared";
import s from "./consultParts.module.css";
import d from "./dose.module.css";

type ProblemWithType = Problem & { type?: string };

const fmtDate = (dt: Date) => dt.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });

/** DOC-036 weight-based dose calculator. Decision support only: it never writes to the prescription. It shows the rule
 * source the API returns, when the weight was recorded, and blocks the suggestion when no weight exists. The rule is a
 * clinician-supplied mg/kg rate, so no per-dose or daily maximum exists in the data and none is shown as a number. */
export default function DoseCalculator({
  authedFetch, patientId, queueEntryId, canRecordWeight, onWeightRecorded,
}: {
  authedFetch: AuthedFetch; patientId: string; queueEntryId: string;
  /** clinical:edit (PATCH /clinical/vitals/{id}); the Record weight action is absent without it. */
  canRecordWeight: boolean; onWeightRecorded?: () => void;
}) {
  const [rate, setRate] = useState("");
  const [result, setResult] = useState<DoseResult | null>(null);
  const [noWeight, setNoWeight] = useState(false);
  const [adult, setAdult] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [recording, setRecording] = useState(false);
  const [weightText, setWeightText] = useState("");
  // FEF-030: the weight form's error and saving flag travel together (keeps this component at 10 hooks); the setters keep their old names.
  const [wf, setWf] = useState<{ err: string | null; saving: boolean }>({ err: null, saving: false });
  const { err: weightErr, saving } = wf;
  const setWeightErr = (err: string | null) => setWf((w) => ({ ...w, err }));
  const setSaving = (saving: boolean) => setWf((w) => ({ ...w, saving }));
  const [recorded, setRecorded] = useState(false);

  async function calculate(rateText = rate) {
    const re = rateError(rateText);
    if (re) { setError(re); return; }
    setBusy(true);
    setError(null);
    try {
      const res = await authedFetch(`/clinical/patients/${patientId}/dose-calculator?mgPerKg=${encodeURIComponent(rateText.trim())}`);
      if (!res) return;
      if (!res.ok) {
        const pr: ProblemWithType = await res.json().catch(() => ({ title: "Error", detail: "Couldn't calculate dose." }));
        setResult(null);
        if (res.status === 422 && pr.type?.endsWith("adult-patient")) {
          setAdult(true); // mg/kg dosing is out of scope for adults: the helper disappears
        } else if (res.status === 400 && (pr.type?.endsWith("no-weight-recorded") || pr.title === "No weight recorded")) {
          setNoWeight(true);
        } else {
          setNoWeight(false);
          setError(pr.detail || "Couldn't calculate dose.");
        }
        return;
      }
      setNoWeight(false);
      setRecorded(false);
      setResult(await res.json());
    } catch {
      setResult(null);
      setError("Couldn't calculate dose. Check the connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  async function saveWeight() {
    const we = weightError(weightText);
    setWeightErr(we);
    if (we) return;
    setSaving(true);
    try {
      // Weight only: other measurements are omitted so the server keeps their stored values.
      const res = await authedFetch(`/clinical/vitals/${queueEntryId}`, { method: "PATCH", body: JSON.stringify({ weightKg: Number(weightText.trim()) }) });
      if (!res) return;
      if (!res.ok) {
        const pr: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't save the weight." }));
        setWeightErr(pr.detail || "Couldn't save the weight.");
        return;
      }
      setRecording(false);
      setWeightText("");
      setNoWeight(false);
      onWeightRecorded?.();
      if (rate.trim() !== "" && !rateError(rate)) {
        await calculate();
        setRecorded(true);
      } else {
        setResult(null);
        setRecorded(true);
      }
    } catch {
      setWeightErr("Couldn't save the weight. Check the connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  const age = result ? weightAge(result.weightRecordedAt) : null;
  // The server decides staleness by age band (CLN-003); the client month check only covers an older server.
  const stale = result ? (result.weightStale ?? age?.state === "stale") : false;
  if (adult) return null;

  const recordAction = canRecordWeight && !recording && (
    <button type="button" className={s.btn} onClick={() => { setRecording(true); setWeightErr(null); }}>
      <Icon name="plus" size={14} />Record weight
    </button>
  );

  return (
    <div className={d.wrap} aria-label="Weight-based dose calculator">
      <div className={d.head}>
        <span className={d.title}>Weight-based dose calculator</span>
        <StatusPill tone="info" icon="info" label="Decision support" />
      </div>
      <p className={d.note}>Suggestion only, not a prescription. It does not change the prescription; you set the final dose, and the prescriber remains responsible for it.</p>

      <div className={s.row}>
        <input className={s.input} aria-label="mg per kg" inputMode="decimal" placeholder="mg/kg" value={rate} onChange={(e) => setRate(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") void calculate(); }} />
        <button type="button" className={s.btn} onClick={() => void calculate()} disabled={busy || rate.trim() === ""}>{busy ? "Calculating…" : "Calculate"}</button>
      </div>
      {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}

      {noWeight && (
        <div className={`${s.notice} ${s.noticeDanger}`} role="alert">
          <Icon name="alert" size={16} />
          <div>
            <strong>No weight recorded.</strong> The automatic suggestion is blocked. A weight is never estimated or assumed; record an actual measured weight to see a suggestion.
            {!canRecordWeight && <div className={d.hint}>Ask nursing to record the weight.</div>}
          </div>
        </div>
      )}

      {result && (
        <div className={d.result} aria-live="polite">
          {stale && age && age.state !== "unknown" && (
            <div className={`${s.notice} ${s.noticeWarn}`} role="alert">
              <Icon name="alert" size={16} />
              <span><strong>Re-weigh.</strong> The weight was recorded {fmtDate(age.date)} and is out of date for this patient. Record a current weight before relying on this suggestion; the old weight is not used silently.</span>
            </div>
          )}
          {age?.state === "unknown" && (
            <div className={`${s.notice} ${s.noticeWarn}`} role="alert">
              <Icon name="alert" size={16} />
              <span><strong>Weight date unknown.</strong> The system cannot say when this weight was recorded. Confirm a current weight before relying on this suggestion.</span>
            </div>
          )}
          <dl className={d.facts}>
            <div><dt>Suggested total</dt><dd className={d.big}>{result.totalDoseMg} mg</dd></div>
            <div><dt>Calculation</dt><dd>{result.weightKg} kg × {result.mgPerKg} mg/kg</dd></div>
            <div><dt>Weight recorded</dt><dd>{age?.state === "unknown" || !age ? "Date unknown" : `${fmtDate(age.date)}${stale ? " (out of date)" : ""}`}</dd></div>
            <div><dt>Rule source</dt><dd>{result.ruleSource}</dd></div>
            <div><dt>Maximum dose</dt><dd>Not defined by this rule. No per-dose or daily limit is applied or checked here; check the drug reference before prescribing.</dd></div>
          </dl>
        </div>
      )}
      {recorded && !recording && !noWeight && <div className={`${s.notice} ${s.noticeOk}`} role="status"><Icon name="check" size={16} /><span>Weight recorded. {result ? "Suggestion refreshed with the new weight." : "Enter a mg/kg rate and calculate."}</span></div>}

      {(noWeight || stale || age?.state === "unknown") && recordAction}
      {recording && (
        <form className={d.record} onSubmit={(e) => { e.preventDefault(); void saveWeight(); }}>
          <label className={d.label}>
            Measured weight (kg)
            <input className={s.input} inputMode="decimal" value={weightText} onChange={(e) => setWeightText(e.target.value)} aria-invalid={weightErr ? true : undefined} autoFocus />
          </label>
          {weightErr && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{weightErr}</span></div>}
          <div className={s.row}>
            <button type="submit" className={s.btnPrimary} disabled={saving}>{saving ? "Saving…" : "Save weight"}</button>
            <button type="button" className={s.btn} onClick={() => { setRecording(false); setWeightErr(null); }} disabled={saving}>Cancel</button>
          </div>
          <span className={d.hint}>Saved to this visit&apos;s vitals with the time it was recorded.</span>
        </form>
      )}
    </div>
  );
}
