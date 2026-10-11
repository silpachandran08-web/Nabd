"use client";

import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import type { ClinicalContext } from "./clinical";
import { daysAgoLabel, SEVERITY_ORDER, type PatientDetail } from "./shared";
import s from "./consultParts.module.css";

type AllergyItem = { text: string; severity?: string; reaction?: string | null };

/** Merge the allergy register (severity) with the allergy names on the patient record. Anything
 * either source lists is shown; "None recorded" is only claimed when the register loaded empty
 * and the patient record lists nothing — a failed load is never rendered as "no allergies". */
export function mergeAllergies(patient: PatientDetail | null, ctx: ClinicalContext) {
  const reg = ctx.allergies.status === "ok" ? (ctx.allergies.data ?? []).filter((a) => a.active) : null;
  const items: AllergyItem[] = (reg ?? [])
    .map((a) => ({ text: a.substance, severity: a.severity, reaction: a.reaction }))
    .sort((a, b) => (SEVERITY_ORDER[a.severity ?? ""] ?? 3) - (SEVERITY_ORDER[b.severity ?? ""] ?? 3));
  for (const n of patient?.allergies ?? []) {
    if (!items.some((i) => i.text.trim().toLowerCase() === n.trim().toLowerCase())) items.push({ text: n });
  }
  let state: "list" | "none" | "loading" | "unverified" = "list";
  if (items.length === 0) {
    if (ctx.allergies.status === "loading") state = "loading";
    else if (reg !== null && patient) state = "none";
    else state = "unverified";
  }
  return { items, state, severityMissing: ctx.allergies.status === "error" || ctx.allergies.status === "none" };
}

export default function SafetyStrip({ patient, ctx, onRetry, sticky = true, onManageAllergies }: { patient: PatientDetail | null; ctx: ClinicalContext; onRetry: () => void; sticky?: boolean; onManageAllergies?: () => void }) {
  const { items, state, severityMissing } = mergeAllergies(patient, ctx);

  const conds = (ctx.conditions.status === "ok" ? (ctx.conditions.data ?? []).filter((c) => c.status !== "resolved").map((c) => c.condition) : []);
  for (const n of patient?.chronicConditions ?? []) {
    if (!conds.some((c) => c.trim().toLowerCase() === n.trim().toLowerCase())) conds.push(n);
  }
  const condsUnverified = conds.length === 0 && ctx.conditions.status === "error";

  return (
    <section className={`${s.safety} ${sticky ? s.safetySticky : ""}`} aria-label="Clinical safety">
      <div className={`${s.safetyChip} ${items.length > 0 ? s.safetyAllergy : ""}`}>
        <span className={s.safetyIcon}>
          <Icon name="alert" size={18} style={items.length > 0 ? { fill: "currentColor", fillOpacity: 0.3 } : undefined} />
        </span>
        <span>
          <span className={s.safetyLabel}>Allergy</span>
          {items.length > 0 ? (
            <>
              <ul className={s.allergyList}>
                {items.map((a) => (
                  <li key={a.text} className={s.safetyValue}>
                    {a.text}{a.severity ? ` (${a.severity})` : ""}{a.reaction ? ` · ${a.reaction}` : ""}
                  </li>
                ))}
              </ul>
              {severityMissing && <span className={s.muted}>Severity unavailable — register not loaded. <button type="button" className={s.linkBtn} onClick={onRetry}>Retry</button></span>}
            </>
          ) : state === "none" ? (
            // "None recorded" (nobody asked) is not "no known allergies" (a clinician documented it).
            ctx.allergyStatus.status === "ok" && ctx.allergyStatus.data?.state === "none_documented"
              ? <span className={s.safetyValue}>No known allergies · documented{ctx.allergyStatus.data.documentedByName ? ` by ${ctx.allergyStatus.data.documentedByName}` : ""}</span>
              : <span className={s.safetyValue}>None recorded · not yet asked</span>
          ) : state === "loading" ? (
            <span className={s.safetyValue}>Checking allergies…</span>
          ) : (
            <span className={`${s.safetyValue} ${s.safetyWarn}`}>
              Couldn&apos;t confirm allergies — ask the patient. <button type="button" className={s.linkBtn} onClick={onRetry}>Retry</button>
            </span>
          )}
          {onManageAllergies && <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} onClick={onManageAllergies}>Allergy register</button>}
        </span>
      </div>

      <div className={s.safetyChip}>
        <span className={s.safetyIcon}><Icon name="pulse" size={18} /></span>
        <span>
          <span className={s.safetyLabel}>Chronic</span>
          <span className={s.safetyValue}>
            {conds.length > 0 ? conds.join(", ") : condsUnverified ? "Couldn't load conditions" : ctx.conditions.status === "loading" ? "Checking…" : "None recorded"}
          </span>
        </span>
      </div>

      <div className={s.safetyChip}>
        <span className={s.safetyIcon}><Icon name="clock" size={18} /></span>
        <span>
          <span className={s.safetyLabel}>Last visit</span>
          <span className={s.safetyValue}>{patient ? daysAgoLabel(patient.lastVisitAt) : "—"}</span>
        </span>
      </div>

      {patient && patient.activePackages > 0 && (
        <div className={s.safetyChip}>
          <StatusPill tone="info" icon="package" label={`${patient.activePackages} active package${patient.activePackages === 1 ? "" : "s"}`} />
        </div>
      )}
    </section>
  );
}
