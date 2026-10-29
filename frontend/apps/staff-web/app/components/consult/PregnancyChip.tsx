"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import StatusPill, { type PillTone } from "../ui/StatusPill";
import type { IconName } from "../ui/Icon";
import type { ClinicalContext } from "./clinical";
import { pregnancyPromptRelevant } from "@nabd/domain";
import { age, type AuthedFetch, type PatientDetail } from "./shared";
import s from "./consultParts.module.css";

const CHOICES: { value: "not_pregnant" | "pregnant" | "lactating" | "unknown"; term: string }[] = [
  { value: "pregnant", term: "pregnancy.status.pregnant" },
  { value: "lactating", term: "pregnancy.status.lactating" },
  { value: "not_pregnant", term: "pregnancy.status.notPregnant" },
  { value: "unknown", term: "pregnancy.status.unknown" },
];

const fmt = (iso: string) => new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });

/** Pregnancy / lactation status on the safety strip. "Not documented" is shown as a neutral "ask" prompt and is
 * never rendered as "not pregnant". Nothing here records a status unless the clinician picks one and saves. */
export default function PregnancyChip({ patient, ctx, canEdit, authedFetch, onSaved }: {
  patient: PatientDetail | null; ctx: ClinicalContext; canEdit: boolean; authedFetch?: AuthedFetch; onSaved: () => void;
}) {
  const tx = useTranslations("consult.pregnancyChip");
  const g = useTranslations("glossary");
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
  if (part.status === "loading") pill = { tone: "neutral", icon: "clock", label: tx("checking") };
  else if (part.status === "error") pill = { tone: "warn", icon: "alert", label: tx("loadFailed") };
  else if (!st || st.status === "not_documented") pill = { tone: "neutral", icon: "info", label: tx("notDocumented") };
  else if (st.status === "pregnant") pill = { tone: "warn", icon: "user", label: st.expectedDeliveryDate ? tx("pregnantEdd", { date: fmt(st.expectedDeliveryDate) }) : g("pregnancy.status.pregnant") };
  else if (st.status === "lactating") pill = { tone: "warn", icon: "user", label: g("pregnancy.status.lactating") };
  else if (st.status === "not_pregnant") pill = { tone: "ok", icon: "check", label: tx("notPregnantRecorded") };
  else pill = { tone: "neutral", icon: "info", label: tx("unknownUnsure") };

  const meta = documented && st?.recordedAt ? `${st.recordedByName ?? tx("recorded")} · ${fmt(st.recordedAt)}` : null;

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
      setErr(tx("saveFailed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={s.safetyChip}>
      <span>
        <span className={s.safetyLabel}>{g("pregnancy.label")}</span>
        <StatusPill tone={pill.tone} icon={pill.icon} label={pill.label} />
        {meta && <span className={s.muted}> {meta}</span>}
        {part.status === "error" && <> <button type="button" className={s.linkBtn} onClick={onSaved}>{tx("retry")}</button></>}
        {canEdit && authedFetch && part.status === "ok" && !open && (
          <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch} ${s.msS}`} onClick={() => setOpen(true)}>{documented ? tx("update") : tx("record")}</button>
        )}
        {canEdit && open && (
          <fieldset className={s.pregForm}>
            <legend className={s.safetyLabel}>{tx("recordStatus")}</legend>
            {CHOICES.map((c) => (
              <label key={c.value} className={s.pregOption}>
                <input type="radio" name="pregnancy-status" value={c.value} checked={choice === c.value} onChange={() => setChoice(c.value)} /> {g(c.term)}
              </label>
            ))}
            {choice === "pregnant" && (
              <label className={s.pregOption}>{tx("expectedDeliveryDateOptional")}
                <input type="date" value={edd} onChange={(e) => setEdd(e.target.value)} className={s.pregDate} />
              </label>
            )}
            {err && <span className={s.safetyWarn} role="alert">{err}</span>}
            <span className={s.pregActions}>
              <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} disabled={!choice || busy} onClick={() => void save()}>{busy ? tx("saving") : tx("save")}</button>
              <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} disabled={busy} onClick={() => { setOpen(false); setErr(null); }}>{tx("cancel")}</button>
            </span>
          </fieldset>
        )}
      </span>
    </div>
  );
}
