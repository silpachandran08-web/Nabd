"use client";

import { useState } from "react";
import StatusPill, { type PillTone } from "../ui/StatusPill";
import type { IconName } from "../ui/Icon";
import type { ClinicalContext } from "./clinical";
import { pregnancyPromptRelevant } from "./pregnancyPolicy";
import { age, type AuthedFetch, type PatientDetail } from "./shared";
import s from "./consultParts.module.css";

const CHOICES: { value: "not_pregnant" | "pregnant" | "lactating" | "unknown"; label: string }[] = [
  { value: "pregnant", label: "Pregnant" },
  { value: "lactating", label: "Lactating" },
  { value: "not_pregnant", label: "Not pregnant" },
  { value: "unknown", label: "Unknown / patient unsure" },
];

const fmt = (iso: string) => new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });

/** Pregnancy / lactation status on the safety strip. "Not documented" is shown as a neutral "ask" prompt and is
 * never rendered as "not pregnant". Nothing here records a status unless the clinician picks one and saves. */
export default function PregnancyChip({ patient, ctx, canEdit, authedFetch, onSaved }: {
  patient: PatientDetail | null; ctx: ClinicalContext; canEdit: boolean; authedFetch?: AuthedFetch; onSaved: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [choice, setChoice] = useState<string>("");
  const [edd, setEdd] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  if (!patient) return null;

  const part = ctx.pregnancyStatus;
  const st = part.status === "ok" ? part.data : null;
  const documented = !!st && st.status !== "not_documented";
  const relevant = pregnancyPromptRelevant(patient.gender, age(patient.dob));
  // Hidden unless clinically relevant — but a recorded status (e.g. pregnant, any sex/age) is never hidden.
  if (!documented && !relevant) return null;
  if (part.status === "none") return null;

  let pill: { tone: PillTone; icon: IconName; label: string };
  if (part.status === "loading") pill = { tone: "neutral", icon: "clock", label: "Checking pregnancy status…" };
  else if (part.status === "error") pill = { tone: "warn", icon: "alert", label: "Couldn't load pregnancy status — ask the patient" };
  else if (!st || st.status === "not_documented") pill = { tone: "neutral", icon: "info", label: "Not documented — ask" };
  else if (st.status === "pregnant") pill = { tone: "warn", icon: "user", label: `Pregnant${st.expectedDeliveryDate ? ` · EDD ${fmt(st.expectedDeliveryDate)}` : ""}` };
  else if (st.status === "lactating") pill = { tone: "warn", icon: "user", label: "Lactating" };
  else if (st.status === "not_pregnant") pill = { tone: "ok", icon: "check", label: "Not pregnant (as recorded)" };
  else pill = { tone: "neutral", icon: "info", label: "Unknown — patient unsure" };

  const meta = documented && st?.recordedAt ? `${st.recordedByName ?? "Recorded"} · ${fmt(st.recordedAt)}` : null;

  async function save() {
    if (!choice || !authedFetch) return;
    setBusy(true); setErr(null);
    try {
      const body: { status: string; expectedDeliveryDate?: string } = { status: choice };
      if (choice === "pregnant" && edd) body.expectedDeliveryDate = edd;
      const res = await authedFetch(`/clinical/patients/${patient!.id}/pregnancy-status`, { method: "PUT", body: JSON.stringify(body) });
      if (!res || !res.ok) {
        const p = res ? await res.json().catch(() => null) : null;
        setErr(`Couldn't save${p?.detail || p?.title ? ` (${p.detail || p.title})` : ""}. Nothing was recorded.`);
        return;
      }
      setOpen(false); setChoice(""); setEdd("");
      onSaved();
    } catch {
      setErr("Couldn't save. Nothing was recorded.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={s.safetyChip}>
      <span>
        <span className={s.safetyLabel}>Pregnancy / lactation</span>
        <StatusPill tone={pill.tone} icon={pill.icon} label={pill.label} />
        {meta && <span className={s.muted}> {meta}</span>}
        {part.status === "error" && <> <button type="button" className={s.linkBtn} onClick={onSaved}>Retry</button></>}
        {canEdit && authedFetch && part.status === "ok" && !open && (
          <button style={{ marginInlineStart: "var(--nb-space-8)" }} type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} onClick={() => setOpen(true)}>{documented ? "Update" : "Record"}</button>
        )}
        {canEdit && open && (
          <fieldset className={s.pregForm}>
            <legend className={s.safetyLabel}>Record status</legend>
            {CHOICES.map((c) => (
              <label key={c.value} className={s.pregOption}>
                <input type="radio" name="pregnancy-status" value={c.value} checked={choice === c.value} onChange={() => setChoice(c.value)} /> {c.label}
              </label>
            ))}
            {choice === "pregnant" && (
              <label className={s.pregOption}>Expected delivery date (optional)
                <input type="date" value={edd} onChange={(e) => setEdd(e.target.value)} className={s.pregDate} />
              </label>
            )}
            {err && <span className={s.safetyWarn} role="alert">{err}</span>}
            <span className={s.pregActions}>
              <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} disabled={!choice || busy} onClick={() => void save()}>{busy ? "Saving…" : "Save"}</button>
              <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} disabled={busy} onClick={() => { setOpen(false); setErr(null); }}>Cancel</button>
            </span>
          </fieldset>
        )}
      </span>
    </div>
  );
}
