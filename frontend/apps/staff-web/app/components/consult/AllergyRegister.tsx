"use client";

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

const CATEGORIES = [
  { id: "drug", label: "Drug / medicine" }, { id: "food", label: "Food" },
  { id: "environmental", label: "Environmental" }, { id: "other", label: "Other" },
] as const;
const SEVERITIES = ["mild", "moderate", "severe"] as const;
const SEVERITY_TONE: Record<string, PillTone> = { mild: "info", moderate: "warn", severe: "danger" };
const SOURCES = [
  { id: "clinician", label: "Clinician assessed" }, { id: "patient_reported", label: "Patient reported" }, { id: "outside_record", label: "Outside record" },
] as const;
const label = (list: readonly { id: string; label: string }[], id: string | null | undefined) => list.find((x) => x.id === id)?.label ?? "Not set";
const when = (iso: string | undefined | null) => (iso ? new Date(iso).toLocaleString("en-GB", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "");

function changeSummary(e: AllergyEvent): string {
  if (e.event === "recorded") return "Recorded";
  if (e.event === "removed") return "Removed from the register";
  const parts: string[] = [];
  for (const k of ["substance", "category", "severity", "reaction", "source"]) {
    const b = e.before?.[k] ?? null;
    const a = e.after?.[k] ?? null;
    if (b !== a) parts.push(`${k}: ${b ?? "none"} → ${a ?? "none"}`);
  }
  return `Corrected${parts.length ? ` (${parts.join("; ")})` : ""}`;
}

/** The patient's allergy register (DOC-046): recorded entries with category, reaction, date, recorded-by and
 * source; fast add (category, then substance, then severity/reaction); corrections and removals that always
 * need a reason and keep the original in the history. "None recorded" (nobody asked) is shown apart from a
 * documented "no known allergies". Editing needs clinical:edit; without it the dialog is read-only. */
export default function AllergyRegister({
  authedFetch, patientId, patientName, canEdit, onClose, onChanged,
}: { authedFetch: AuthedFetch; patientId: string; patientName: string; canEdit: boolean; onClose: () => void; onChanged: () => void }) {
  const [allergies, setAllergies] = useState<Allergy[] | null>(null);
  const [status, setStatus] = useState<AllergyStatus | null>(null);
  const [history, setHistory] = useState<AllergyEvent[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [tick, setTick] = useState(0);
  const [category, setCategory] = useState<string>("");
  const [add, setAdd] = useState({ substance: "", severity: "", reaction: "", source: "clinician" });
  const [fixing, setFixing] = useState<{ id: string; substance: string; category: string; severity: string; reaction: string; reason: string } | null>(null);
  const [removing, setRemoving] = useState<{ id: string; reason: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

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

  async function send(path: string, method: string, body?: unknown, fallback = "Couldn't save the change.") {
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
      setError("Couldn't reach the server. Nothing was changed; try again.");
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
    }, "Couldn't record the allergy.");
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
    if (Object.keys(body).length === 1) { setError("Change at least one field to record a correction."); return; }
    if (await send(`/clinical/allergies/${fixing.id}`, "PATCH", body, "Couldn't save the correction.")) setFixing(null);
  }

  async function confirmRemove() {
    if (!removing || !removing.reason.trim()) return;
    if (await send(`/clinical/allergies/${removing.id}/deactivate`, "PATCH", { reason: removing.reason.trim() }, "Couldn't remove the allergy.")) setRemoving(null);
  }

  const hasActive = (allergies?.length ?? 0) > 0;
  return (
    <Dialog title={`Allergy register · ${patientName}`} onClose={onClose} size="lg">
      {loadError ? <ErrorState message="Couldn't load the allergy register. Don't assume there are none." onRetry={reload} />
        : allergies === null ? <Skeleton rows={3} label="Loading allergy register" /> : (
        <div className={s.stack}>
          {!canEdit && <p className={s.muted}>The register is read-only for your role.</p>}
          {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert">{error}</div>}

          <section className={s.card} aria-label="Recorded allergies">
            <h3 className={s.cardTitle}>Recorded</h3>
            {!hasActive ? (
              <div className={s.stack}>
                {status?.state === "none_documented"
                  ? <p><StatusPill tone="ok" label="No known allergies (documented)" /> <span className={s.muted}>by {status.documentedByName ?? "a clinician"} · {when(status.documentedAt)}</span></p>
                  : <p><StatusPill tone="neutral" icon="list" label="None recorded" /> <span className={s.muted}>Nobody has documented the patient&apos;s allergies yet. This is not the same as &ldquo;no known allergies&rdquo;.</span></p>}
                {canEdit && status?.state !== "none_documented" && (
                  <div><button type="button" className={s.btn} disabled={busy} onClick={() => void send(`/clinical/patients/${patientId}/allergies/status`, "PUT", undefined, "Couldn't document the status.")}>Document no known allergies</button></div>
                )}
              </div>
            ) : (
              <ul className={s.list}>
                {allergies.map((a) => (
                  <li key={a.id} className={s.allergyItem}>
                    <div className={s.listRow}>
                      <strong>{a.substance}</strong>
                      <StatusPill tone={SEVERITY_TONE[a.severity] ?? "neutral"} icon="alert" label={a.severity} />
                      <span className={s.muted}>{label(CATEGORIES, a.category)}</span>
                      {a.unconfirmed && <StatusPill tone="warn" icon="alert" label="Imported, doctor to confirm" />}
                    </div>
                    <div className={s.muted}>
                      {a.reaction ? `${a.reaction} · ` : ""}{when(a.recordedAt)}{a.recordedByName ? ` · ${a.recordedByName}` : ""} · {label(SOURCES, a.source)}
                      {a.updatedAt ? ` · corrected ${when(a.updatedAt)}` : ""}
                    </div>
                    {canEdit && fixing?.id !== a.id && removing?.id !== a.id && (
                      <div className={s.row}>
                        <button type="button" className={s.btn} onClick={() => { setError(null); setRemoving(null); setFixing({ id: a.id, substance: a.substance, category: a.category ?? "", severity: a.severity, reaction: a.reaction ?? "", reason: "" }); }}>Correct</button>
                        <button type="button" className={s.btn} onClick={() => { setError(null); setFixing(null); setRemoving({ id: a.id, reason: "" }); }}>Remove</button>
                      </div>
                    )}
                    {canEdit && fixing?.id === a.id && (
                      <div className={s.stack}>
                        <div className={s.formGrid}>
                          <label className={s.field}>Substance<input className={s.input} value={fixing.substance} onChange={(e) => setFixing({ ...fixing, substance: e.target.value })} /></label>
                          <label className={s.field}>Category
                            <select className={s.input} value={fixing.category} onChange={(e) => setFixing({ ...fixing, category: e.target.value })}>
                              {!a.category && <option value="">Not set</option>}{CATEGORIES.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
                            </select>
                          </label>
                          <label className={s.field}>Severity
                            <select className={s.input} value={fixing.severity} onChange={(e) => setFixing({ ...fixing, severity: e.target.value })}>
                              {SEVERITIES.map((v) => <option key={v} value={v}>{v}</option>)}
                            </select>
                          </label>
                          <label className={s.field}>Reaction<input className={s.input} value={fixing.reaction} onChange={(e) => setFixing({ ...fixing, reaction: e.target.value })} /></label>
                        </div>
                        <label className={s.field}>Reason for the correction (required; the original is kept in the history)
                          <input className={s.input} value={fixing.reason} maxLength={500} onChange={(e) => setFixing({ ...fixing, reason: e.target.value })} />
                        </label>
                        <div className={s.row}>
                          <button type="button" className={s.btnPrimary} disabled={busy || !fixing.reason.trim()} onClick={saveFix}>{busy ? "Saving…" : "Save correction"}</button>
                          <button type="button" className={s.btn} onClick={() => { setFixing(null); setError(null); }}>Cancel</button>
                        </div>
                      </div>
                    )}
                    {canEdit && removing?.id === a.id && (
                      <div className={s.stack}>
                        <label className={s.field}>Reason for removing (required; the entry stays in the history)
                          <input className={s.input} value={removing.reason} maxLength={500} onChange={(e) => setRemoving({ ...removing, reason: e.target.value })} />
                        </label>
                        <div className={s.row}>
                          <button type="button" className={s.btnPrimary} disabled={busy || !removing.reason.trim()} onClick={confirmRemove}>{busy ? "Removing…" : "Remove from register"}</button>
                          <button type="button" className={s.btn} onClick={() => { setRemoving(null); setError(null); }}>Cancel</button>
                        </div>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>

          {canEdit && (
            <section className={s.card} aria-label="Add an allergy">
              <h3 className={s.cardTitle}>Fast add</h3>
              <div className={s.stack}>
                <div className={s.radioRow} role="group" aria-label="Category">
                  {CATEGORIES.map((c) => (
                    <button key={c.id} type="button" className={`${s.chip} ${category === c.id ? s.chipOn : ""}`} aria-pressed={category === c.id} onClick={() => setCategory(c.id)}>{c.label}</button>
                  ))}
                </div>
                {category && (
                  <>
                    <div className={s.formGrid}>
                      <label className={s.field}>Substance<input className={s.input} value={add.substance} maxLength={200} autoFocus onChange={(e) => setAdd({ ...add, substance: e.target.value })} /></label>
                      <label className={s.field}>Reaction<input className={s.input} value={add.reaction} maxLength={500} onChange={(e) => setAdd({ ...add, reaction: e.target.value })} /></label>
                      <label className={s.field}>Source
                        <select className={s.input} value={add.source} onChange={(e) => setAdd({ ...add, source: e.target.value })}>
                          {SOURCES.map((x) => <option key={x.id} value={x.id}>{x.label}</option>)}
                        </select>
                      </label>
                    </div>
                    <div className={s.radioRow} role="group" aria-label="Severity">
                      {SEVERITIES.map((v) => (
                        <button key={v} type="button" className={`${s.chip} ${add.severity === v ? s.chipOn : ""}`} aria-pressed={add.severity === v} onClick={() => setAdd({ ...add, severity: v })}>{v}</button>
                      ))}
                    </div>
                    <div><button type="button" className={s.btnPrimary} disabled={busy || !add.substance.trim() || !add.severity} onClick={saveNew}>{busy ? "Saving…" : "Add to the permanent record"}</button></div>
                  </>
                )}
              </div>
            </section>
          )}

          <section className={s.card} aria-label="Allergy history">
            <h3 className={s.cardTitle}>History</h3>
            {history === null ? <p className={s.muted}>The history couldn&apos;t be loaded.</p>
              : history.length === 0 ? <p className={s.muted}>No changes recorded yet.</p> : (
              <ul className={`${s.list} ${s.history}`}>
                {history.map((e) => (
                  <li key={e.id}>
                    <Icon name={e.event === "recorded" ? "check" : "alert"} size={12} /> <strong>{e.substance ?? "Allergy"}</strong> · {changeSummary(e)}
                    {e.reason ? ` · Reason: ${e.reason}` : ""} <span className={s.muted}>· {e.actorName ?? "Staff"} · {when(e.occurredAt)}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
      <div className={s.dialogActions}><button type="button" className={s.btn} onClick={onClose}>Close</button></div>
    </Dialog>
  );
}
