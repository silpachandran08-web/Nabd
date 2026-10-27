"use client";

import { useTranslations } from "next-intl";
import { useCallback, useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import StatusPill, { type PillTone } from "../ui/StatusPill";
import { ErrorState, Skeleton } from "../ui/StateViews";
import type { Allergy, AllergyStatus, AuthedFetch, Problem } from "./shared";
import s from "./consultParts.module.css";

type AllergyEvent = {
  id: string; allergyId: string; event: "recorded" | "corrected" | "removed"; substance: string | null;
  before: Record<string, unknown> | null; after: Record<string, unknown> | null; reason: string | null;
  actorName: string | null; occurredAt: string;
};

const CATEGORIES = [{ id: "drug" }, { id: "food" }, { id: "environmental" }, { id: "other" }] as const;
const SEVERITIES = ["mild", "moderate", "severe"] as const;
const SEVERITY_TONE: Record<string, PillTone> = { mild: "info", moderate: "warn", severe: "danger" };
const SOURCES = [{ id: "clinician" }, { id: "patient_reported" }, { id: "outside_record" }] as const;
const when = (iso: string | undefined | null) => (iso ? new Date(iso).toLocaleString("en-GB", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "");

type Tr = ReturnType<typeof useTranslations>;
function changeSummary(e: AllergyEvent, tx: Tr): string {
  if (e.event === "recorded") return tx("recorded");
  if (e.event === "removed") return tx("removedFromRegister");
  const parts: string[] = [];
  for (const k of ["substance", "category", "severity", "reaction", "source"]) {
    const b = e.before?.[k] ?? null;
    const a = e.after?.[k] ?? null;
    if (b !== a) parts.push(`${k}: ${b ?? tx("none")} → ${a ?? tx("none")}`);
  }
  return parts.length ? tx("correctedWith", { changes: parts.join("; ") }) : tx("corrected");
}

/** The patient's allergy register (DOC-046): recorded entries with category, reaction, date, recorded-by and
 * source; fast add (category, then substance, then severity/reaction); corrections and removals that always
 * need a reason and keep the original in the history. "None recorded" (nobody asked) is shown apart from a
 * documented "no known allergies". Editing needs clinical:edit; without it the dialog is read-only. */
export default function AllergyRegister({
  authedFetch, patientId, patientName, canEdit, onClose, onChanged,
}: { authedFetch: AuthedFetch; patientId: string; patientName: string; canEdit: boolean; onClose: () => void; onChanged: () => void }) {
  const tx = useTranslations("consult.allergyRegister");
  const g = useTranslations("glossary");
  const [allergies, setAllergies] = useState<Allergy[] | null>(null);
  const [status, setStatus] = useState<AllergyStatus | null>(null);
  const [history, setHistory] = useState<AllergyEvent[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [tick, setTick] = useState(0);
  const [category, setCategory] = useState<string>("");
  const [add, setAdd] = useState({ substance: "", severity: "", reaction: "", source: "clinician" });
  const [fixing, setFixing] = useState<{ id: string; substance: string; category: string; severity: string; reaction: string; reason: string } | null>(null);
  const [removing, setRemoving] = useState<{ id: string; reason: string } | null>(null);
  // FEF-030: busy and error travel together (one state keeps this component at 10 hooks); the setters keep their old names.
  const [op, setOp] = useState<{ busy: boolean; error: string | null }>({ busy: false, error: null });
  const { busy, error } = op;
  const setBusy = (b: boolean) => setOp((o) => ({ ...o, busy: b }));
  const setError = (e: string | null) => setOp((o) => ({ ...o, error: e }));

  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (cancelled) return;
      setLoadError(false);
      try {
        const [a, st, h] = await Promise.all([
          authedFetch(`/clinical/patients/${patientId}/allergies`),
          authedFetch(`/clinical/patients/${patientId}/allergies/status`),
          authedFetch(`/clinical/patients/${patientId}/allergies/history`),
        ]);
        if (cancelled) return;
        if (!a?.ok) { setLoadError(true); return; }
        setAllergies(await a.json());
        setStatus(st?.ok ? await st.json() : null);
        setHistory(h?.ok ? await h.json() : null);
      } catch {
        if (!cancelled) setLoadError(true);
      }
    });
    return () => { cancelled = true; };
  }, [authedFetch, patientId, tick]);

  async function send(path: string, method: string, body?: unknown, fallback = tx("couldnTSaveTheChange")) {
    setBusy(true);
    setError(null);
    try {
      const res = await authedFetch(path, { method, body: body === undefined ? undefined : JSON.stringify(body) });
      if (!res) return false;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: fallback }));
        setError(p.detail || fallback);
        return false;
      }
      reload();
      onChanged();
      return true;
    } catch {
      setError(tx("couldnTReachTheServer"));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function saveNew() {
    if (!add.substance.trim() || !add.severity) return;
    const ok = await send(`/clinical/patients/${patientId}/allergies`, "POST", {
      substance: add.substance.trim(), category: category || undefined, severity: add.severity,
      reaction: add.reaction.trim() || undefined, source: add.source,
    }, tx("couldnTRecordTheAllergy"));
    if (ok) { setCategory(""); setAdd({ substance: "", severity: "", reaction: "", source: "clinician" }); }
  }

  async function saveFix() {
    if (!fixing || !fixing.reason.trim()) return;
    const cur = allergies?.find((a) => a.id === fixing.id);
    const body: Record<string, string> = { reason: fixing.reason.trim() };
    if (cur && fixing.substance.trim() !== cur.substance) body.substance = fixing.substance.trim();
    // The API cannot clear a category (null = unchanged, "" is rejected), so an empty value is never sent.
    if (cur && fixing.category !== "" && fixing.category !== (cur.category ?? "")) body.category = fixing.category;
    if (cur && fixing.severity !== cur.severity) body.severity = fixing.severity;
    if (cur && fixing.reaction.trim() !== (cur.reaction ?? "")) body.reaction = fixing.reaction.trim();
    if (Object.keys(body).length === 1) { setError(tx("changeAtLeastOneField")); return; }
    if (await send(`/clinical/allergies/${fixing.id}`, "PATCH", body, tx("couldnTSaveTheCorrection"))) setFixing(null);
  }

  async function confirmRemove() {
    if (!removing || !removing.reason.trim()) return;
    if (await send(`/clinical/allergies/${removing.id}/deactivate`, "PATCH", { reason: removing.reason.trim() }, tx("couldnTRemoveTheAllergy"))) setRemoving(null);
  }

  const hasActive = (allergies?.length ?? 0) > 0;
  return (
    <Dialog title={tx("registerTitle", { patientName })} onClose={onClose} size="lg">
      {loadError ? <ErrorState message={tx("couldnTLoadTheAllergy")} onRetry={reload} />
        : allergies === null ? <Skeleton rows={3} label={tx("loadingAllergyRegister")} /> : (
        <div className={s.stack}>
          {!canEdit && <p className={s.muted}>{tx("theRegisterIsReadOnly")}</p>}
          {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert">{error}</div>}

          <section className={s.card} aria-label={tx("recordedAllergies")}>
            <h3 className={s.cardTitle}>{tx("recorded")}</h3>
            {!hasActive ? (
              <div className={s.stack}>
                {status?.state === "none_documented"
                  ? <p><StatusPill tone="ok" label={g("allergy.nkaDocumented")} /> <span className={s.muted}>{tx("by")}{" "}{status.documentedByName ?? tx("aClinician")} · {when(status.documentedAt)}</span></p>
                  : <p><StatusPill tone="neutral" icon="list" label={tx("noneRecorded")} /> <span className={s.muted}>{tx("nobodyHasDocumentedThePatient")}</span></p>}
                {canEdit && status?.state !== "none_documented" && (
                  <div><button type="button" className={s.btn} disabled={busy} onClick={() => void send(`/clinical/patients/${patientId}/allergies/status`, "PUT", undefined, tx("couldnTDocumentTheStatus"))}>{tx("documentNoKnownAllergies")}</button></div>
                )}
              </div>
            ) : (
              <ul className={s.list}>
                {allergies.map((a) => (
                  <li key={a.id} className={s.allergyItem}>
                    <div className={s.listRow}>
                      <strong>{a.substance}</strong>
                      <StatusPill tone={SEVERITY_TONE[a.severity] ?? "neutral"} icon="alert" label={g(`allergy.severity.${a.severity}`)} />
                      <span className={s.muted}>{a.category ? tx(`categories.${a.category}`) : tx("notSet")}</span>
                      {a.unconfirmed && <StatusPill tone="warn" icon="alert" label={tx("importedDoctorToConfirm")} />}
                    </div>
                    <div className={s.muted}>
                      {a.reaction ? `${a.reaction} · ` : ""}{when(a.recordedAt)}{a.recordedByName ? ` · ${a.recordedByName}` : ""} · {a.source ? tx(`sources.${a.source}`) : tx("notSet")}
                      {a.updatedAt ? ` · corrected ${when(a.updatedAt)}` : ""}
                    </div>
                    {canEdit && fixing?.id !== a.id && removing?.id !== a.id && (
                      <div className={s.row}>
                        <button type="button" className={s.btn} onClick={() => { setError(null); setRemoving(null); setFixing({ id: a.id, substance: a.substance, category: a.category ?? "", severity: a.severity, reaction: a.reaction ?? "", reason: "" }); }}>{tx("correct")}</button>
                        <button type="button" className={s.btn} onClick={() => { setError(null); setFixing(null); setRemoving({ id: a.id, reason: "" }); }}>{tx("remove")}</button>
                      </div>
                    )}
                    {canEdit && fixing?.id === a.id && (
                      <div className={s.stack}>
                        <div className={s.formGrid}>
                          <label className={s.field}>{tx("substance")}<input className={s.input} value={fixing.substance} onChange={(e) => setFixing({ ...fixing, substance: e.target.value })} /></label>
                          <label className={s.field}>{tx("category")}
                            <select className={s.input} value={fixing.category} onChange={(e) => setFixing({ ...fixing, category: e.target.value })}>
                              {!a.category && <option value="">{tx("notSet")}</option>}{CATEGORIES.map((c) => <option key={c.id} value={c.id}>{tx(`categories.${c.id}`)}</option>)}
                            </select>
                          </label>
                          <label className={s.field}>{tx("severity")}
                            <select className={s.input} value={fixing.severity} onChange={(e) => setFixing({ ...fixing, severity: e.target.value })}>
                              {SEVERITIES.map((v) => <option key={v} value={v}>{g(`allergy.severity.${v}`)}</option>)}
                            </select>
                          </label>
                          <label className={s.field}>{tx("reaction")}<input className={s.input} value={fixing.reaction} onChange={(e) => setFixing({ ...fixing, reaction: e.target.value })} /></label>
                        </div>
                        <label className={s.field}>{tx("reasonForTheCorrectionRequired")}
                          <input className={s.input} value={fixing.reason} maxLength={500} onChange={(e) => setFixing({ ...fixing, reason: e.target.value })} />
                        </label>
                        <div className={s.row}>
                          <button type="button" className={s.btnPrimary} disabled={busy || !fixing.reason.trim()} onClick={saveFix}>{busy ? tx("saving") : tx("saveCorrection")}</button>
                          <button type="button" className={s.btn} onClick={() => { setFixing(null); setError(null); }}>{tx("cancel")}</button>
                        </div>
                      </div>
                    )}
                    {canEdit && removing?.id === a.id && (
                      <div className={s.stack}>
                        <label className={s.field}>{tx("reasonForRemovingRequiredThe")}
                          <input className={s.input} value={removing.reason} maxLength={500} onChange={(e) => setRemoving({ ...removing, reason: e.target.value })} />
                        </label>
                        <div className={s.row}>
                          <button type="button" className={s.btnPrimary} disabled={busy || !removing.reason.trim()} onClick={confirmRemove}>{busy ? tx("removing") : tx("removeFromRegister")}</button>
                          <button type="button" className={s.btn} onClick={() => { setRemoving(null); setError(null); }}>{tx("cancel")}</button>
                        </div>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>

          {canEdit && (
            <section className={s.card} aria-label={tx("addAnAllergy")}>
              <h3 className={s.cardTitle}>{tx("fastAdd")}</h3>
              <div className={s.stack}>
                <div className={s.radioRow} role="group" aria-label={tx("category")}>
                  {CATEGORIES.map((c) => (
                    <button key={c.id} type="button" className={`${s.chip} ${category === c.id ? s.chipOn : ""}`} aria-pressed={category === c.id} onClick={() => setCategory(c.id)}>{tx(`categories.${c.id}`)}</button>
                  ))}
                </div>
                {category && (
                  <>
                    <div className={s.formGrid}>
                      <label className={s.field}>{tx("substance")}<input className={s.input} value={add.substance} maxLength={200} autoFocus onChange={(e) => setAdd({ ...add, substance: e.target.value })} /></label>
                      <label className={s.field}>{tx("reaction")}<input className={s.input} value={add.reaction} maxLength={500} onChange={(e) => setAdd({ ...add, reaction: e.target.value })} /></label>
                      <label className={s.field}>{tx("source")}
                        <select className={s.input} value={add.source} onChange={(e) => setAdd({ ...add, source: e.target.value })}>
                          {SOURCES.map((x) => <option key={x.id} value={x.id}>{tx(`sources.${x.id}`)}</option>)}
                        </select>
                      </label>
                    </div>
                    <div className={s.radioRow} role="group" aria-label={tx("severity")}>
                      {SEVERITIES.map((v) => (
                        <button key={v} type="button" className={`${s.chip} ${add.severity === v ? s.chipOn : ""}`} aria-pressed={add.severity === v} onClick={() => setAdd({ ...add, severity: v })}>{g(`allergy.severity.${v}`)}</button>
                      ))}
                    </div>
                    <div><button type="button" className={s.btnPrimary} disabled={busy || !add.substance.trim() || !add.severity} onClick={saveNew}>{busy ? tx("saving") : tx("addToThePermanentRecord")}</button></div>
                  </>
                )}
              </div>
            </section>
          )}

          <section className={s.card} aria-label={tx("allergyHistory")}>
            <h3 className={s.cardTitle}>{tx("history")}</h3>
            {history === null ? <p className={s.muted}>{tx("theHistoryCouldnTBe")}</p>
              : history.length === 0 ? <p className={s.muted}>{tx("noChangesRecordedYet")}</p> : (
              <ul className={`${s.list} ${s.history}`}>
                {history.map((e) => (
                  <li key={e.id}>
                    <Icon name={e.event === "recorded" ? "check" : "alert"} size={12} /> <strong>{e.substance ?? g("allergy.label")}</strong> · {changeSummary(e, tx)}
                    {e.reason ? ` · Reason: ${e.reason}` : ""} <span className={s.muted}>· {e.actorName ?? tx("staff")} · {when(e.occurredAt)}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
      <div className={s.dialogActions}><button type="button" className={s.btn} onClick={onClose}>{tx("close")}</button></div>
    </Dialog>
  );
}
