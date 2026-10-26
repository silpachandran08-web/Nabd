"use client";

import { useCallback, useEffect, useState } from "react";
import Icon from "../ui/Icon";
import { Skeleton } from "../ui/StateViews";
import type { NoteFields } from "./draftStore";
import {
  addTag, hasSectionText, MAX_NAME_LEN, MAX_TAG_LEN, MAX_TAGS, mergeSections, SECTION_KEYS, sortTemplates,
  type NoteTemplate, type Sections,
} from "./noteTemplates";
import type { AuthedFetch, Problem } from "./shared";
import p from "./consultParts.module.css";
import s from "./noteTemplates.module.css";

type Form = { id: string | null; name: string; tags: string[]; tagInput: string; replaceText: boolean };

async function problemText(res: Response | null, fallback: string): Promise<string> {
  if (!res) return fallback;
  const pr: Problem | null = await res.json().catch(() => null);
  return pr?.detail || fallback;
}

const sectionsOf = (n: NoteFields): Sections => ({ subjective: n.subjective, objective: n.objective, assessment: n.assessment, plan: n.plan });

/**
 * DOC-027. Rendered only for holders of clinical:edit (it writes into the note). The doctor's own note templates: apply, favourite, tag, save the current note as a template,
 * and "Repeat previous" (this patient's most recent completed note). Nothing is pre-loaded: the doctor
 * writes every template. There is no ICD or template library here and no voice dictation.
 */
export default function NoteTemplates({
  authedFetch, patientId, entryId, note, onSetField,
}: {
  authedFetch: AuthedFetch; patientId: string; entryId: string; note: NoteFields;
  onSetField: (field: keyof NoteFields, value: string) => void;
}) {
  const [templates, setTemplates] = useState<NoteTemplate[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [tick, setTick] = useState(0);
  const [tagFilter, setTagFilter] = useState<string | null>(null);
  const [form, setForm] = useState<Form | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "ok" | "warn"; text: string } | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [repeating, setRepeating] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (cancelled) return;
      setLoadError(false);
      try {
        const res = await authedFetch("/clinical/note-templates");
        if (cancelled) return;
        if (!res?.ok) { setLoadError(true); return; }
        setTemplates(sortTemplates((await res.json()) as NoteTemplate[]));
      } catch {
        if (!cancelled) setLoadError(true);
      }
    });
    return () => { cancelled = true; };
  }, [authedFetch, tick]);

  const reload = useCallback(() => { setTemplates(null); setTick((t) => t + 1); }, []);

  function apply(t: NoteTemplate) {
    const merged = mergeSections(note, t);
    for (const k of SECTION_KEYS) if (merged[k] !== note[k]) onSetField(k, merged[k]);
    setMessage({ kind: "ok", text: `Template "${t.name}" added to the note. Text you had already written was kept.` });
  }

  async function repeatPrevious() {
    setRepeating(true);
    setMessage(null);
    try {
      const tl = await authedFetch(`/clinical/patients/${patientId}/timeline?limit=5`);
      if (!tl?.ok) { setMessage({ kind: "warn", text: "Couldn't load this patient's previous visits. Nothing was changed." }); return; }
      const encounters = ((await tl.json()) as { data?: { queueEntryId: string; occurredAt: string }[] }).data ?? [];
      const prev = encounters.find((e) => e.queueEntryId !== entryId);
      if (!prev) { setMessage({ kind: "warn", text: "This patient has no previous completed visit to repeat." }); return; }
      const nr = await authedFetch(`/clinical/notes/${prev.queueEntryId}`);
      if (!nr?.ok) { setMessage({ kind: "warn", text: nr?.status === 404 ? "The previous visit has no written note to repeat." : "Couldn't load the previous note. Nothing was changed." }); return; }
      const n = (await nr.json()) as Record<string, string | null>;
      const prevSections: Sections = { subjective: n.subjective ?? "", objective: n.objective ?? "", assessment: n.assessment ?? "", plan: n.plan ?? "" };
      if (!hasSectionText(prevSections)) { setMessage({ kind: "warn", text: "The previous note is empty, so there is nothing to repeat." }); return; }
      const merged = mergeSections(note, prevSections);
      for (const k of SECTION_KEYS) if (merged[k] !== note[k]) onSetField(k, merged[k]);
      const when = new Date(prev.occurredAt).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
      setMessage({ kind: "ok", text: `Added the four sections from this patient's note of ${when}. Review and edit them; the diagnosis is not copied.` });
    } catch {
      setMessage({ kind: "warn", text: "Couldn't reach the server. Nothing was changed." });
    } finally {
      setRepeating(false);
    }
  }

  async function submitForm() {
    if (!form) return;
    const name = form.name.trim();
    if (!name) { setMessage({ kind: "warn", text: "Give the template a name." }); return; }
    const current = sectionsOf(note);
    if (form.id === null && !hasSectionText(current)) { setMessage({ kind: "warn", text: "The note is empty, so there is nothing to save as a template yet." }); return; }
    setBusy(true);
    setMessage(null);
    try {
      const body: Record<string, unknown> = { name, tags: addTag(form.tags, form.tagInput) };
      if (form.id === null || form.replaceText) Object.assign(body, current);
      const res = await authedFetch(form.id === null ? "/clinical/note-templates" : `/clinical/note-templates/${form.id}`, { method: form.id === null ? "POST" : "PATCH", body: JSON.stringify(body) });
      if (!res?.ok) { setMessage({ kind: "warn", text: await problemText(res, "Couldn't save the template. Try again.") }); return; }
      setForm(null);
      setMessage({ kind: "ok", text: form.id === null ? `Saved "${name}" as a template.` : `Updated "${name}".` });
      reload();
    } catch {
      setMessage({ kind: "warn", text: "Couldn't reach the server. The template was not saved." });
    } finally {
      setBusy(false);
    }
  }

  async function toggleFavourite(t: NoteTemplate) {
    setBusy(true);
    try {
      const res = await authedFetch(`/clinical/note-templates/${t.id}`, { method: "PATCH", body: JSON.stringify({ favourite: !t.favourite }) });
      if (!res?.ok) { setMessage({ kind: "warn", text: await problemText(res, "Couldn't update the favourite. Try again.") }); return; }
      reload();
    } catch {
      setMessage({ kind: "warn", text: "Couldn't reach the server." });
    } finally {
      setBusy(false);
    }
  }

  async function remove(t: NoteTemplate) {
    setBusy(true);
    try {
      const res = await authedFetch(`/clinical/note-templates/${t.id}`, { method: "DELETE" });
      if (!res?.ok) { setMessage({ kind: "warn", text: await problemText(res, "Couldn't delete the template. Try again.") }); return; }
      setConfirmDelete(null);
      setMessage({ kind: "ok", text: `Deleted "${t.name}". Notes already written from it are unchanged.` });
      reload();
    } catch {
      setMessage({ kind: "warn", text: "Couldn't reach the server." });
    } finally {
      setBusy(false);
    }
  }

  const allTags = Array.from(new Set((templates ?? []).flatMap((t) => t.tags))).sort((a, b) => a.localeCompare(b));
  const favourites = (templates ?? []).filter((t) => t.favourite);
  const shown = (templates ?? []).filter((t) => tagFilter === null || t.tags.includes(tagFilter));

  return (
    <section className={`${p.card} ${s.panel}`} aria-label="Note templates">
      <div className={s.head}>
        <h3 className={s.title}>Note templates</h3>
        <div className={s.actions}>
          <button type="button" className={p.btn} onClick={() => void repeatPrevious()} disabled={repeating}>
            <Icon name="refresh" size={14} /> {repeating ? "Looking…" : "Repeat previous"}
          </button>
          {!form && (
            <button type="button" className={p.btn} onClick={() => setForm({ id: null, name: "", tags: [], tagInput: "", replaceText: false })}>
              <Icon name="plus" size={14} /> Save note as template
            </button>
          )}
        </div>
      </div>
      <p className={s.scope}>Templates are your own: only you can see them, and you write them. Voice dictation is not available.</p>

      {message && (
        <div className={`${p.notice} ${message.kind === "ok" ? p.noticeOk : p.noticeWarn}`} role="status">
          <Icon name={message.kind === "ok" ? "check" : "alert"} size={16} />
          <span>{message.text}</span>
        </div>
      )}

      {form && (
        <div className={s.form} role="group" aria-label={form.id === null ? "Save note as template" : "Edit template"}>
          <label className={s.fieldLabel}>
            Template name
            <input className={p.input} value={form.name} maxLength={MAX_NAME_LEN} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </label>
          <div className={s.fieldLabel}>
            <span>Tags (your own labels, up to {MAX_TAGS})</span>
            <div className={s.chips}>
              {form.tags.map((t) => (
                <span key={t} className={s.tagChip}>
                  {t}
                  <button type="button" className={p.linkBtn} aria-label={`Remove tag ${t}`} onClick={() => setForm({ ...form, tags: form.tags.filter((x) => x !== t) })}><Icon name="x" size={12} /></button>
                </span>
              ))}
            </div>
            <div className={s.formRow}>
              <input
                className={`${p.input} ${s.grow}`} aria-label="New tag" value={form.tagInput} maxLength={MAX_TAG_LEN} placeholder="Add a tag"
                onChange={(e) => setForm({ ...form, tagInput: e.target.value })}
                onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); setForm({ ...form, tags: addTag(form.tags, form.tagInput), tagInput: "" }); } }}
              />
              <button type="button" className={p.btn} disabled={form.tagInput.trim() === "" || form.tags.length >= MAX_TAGS} onClick={() => setForm({ ...form, tags: addTag(form.tags, form.tagInput), tagInput: "" })}>Add tag</button>
            </div>
          </div>
          {form.id === null ? (
            <span className={p.muted}>Saves the four sections exactly as they are written in the note now (the diagnosis is not part of a template).</span>
          ) : (
            <label className={p.check}>
              <input type="checkbox" checked={form.replaceText} onChange={(e) => setForm({ ...form, replaceText: e.target.checked })} />
              <span>Replace this template&apos;s text with the note as written now</span>
            </label>
          )}
          <div className={s.actions}>
            <button type="button" className={p.btnPrimary} disabled={busy} onClick={() => void submitForm()}>{busy ? "Saving…" : form.id === null ? "Save template" : "Save changes"}</button>
            <button type="button" className={p.btn} disabled={busy} onClick={() => setForm(null)}>Cancel</button>
          </div>
        </div>
      )}

      {templates === null && !loadError && <Skeleton rows={2} label="Loading your templates" />}
      {loadError && (
        <div className={`${p.notice} ${p.noticeWarn}`} role="alert">
          <Icon name="alert" size={16} />
          <span>Couldn&apos;t load your templates. <button type="button" className={p.linkBtn} onClick={reload}>Retry</button></span>
        </div>
      )}
      {templates !== null && templates.length === 0 && (
        <p className={p.muted}>No templates yet. Write a note, then use &ldquo;Save note as template&rdquo; to keep it for next time.</p>
      )}

      {favourites.length > 0 && (
        <div>
          <div className={s.title}>Favourites</div>
          <div className={`${s.chips} ${p.mtS}`}>
            {favourites.map((t) => (
              <button key={t.id} type="button" className={`${p.chip} ${p.chipOn}`} onClick={() => apply(t)}>
                <Icon name="check" size={12} /> {t.name}
              </button>
            ))}
          </div>
        </div>
      )}

      {allTags.length > 0 && (
        <div className={s.chips} role="group" aria-label="Filter by tag">
          <button type="button" aria-pressed={tagFilter === null} className={`${s.tagChip} ${tagFilter === null ? s.tagChipOn : ""}`} onClick={() => setTagFilter(null)}>All</button>
          {allTags.map((t) => (
            <button key={t} type="button" aria-pressed={tagFilter === t} className={`${s.tagChip} ${tagFilter === t ? s.tagChipOn : ""}`} onClick={() => setTagFilter(tagFilter === t ? null : t)}>{t}</button>
          ))}
        </div>
      )}

      {shown.length > 0 && (
        <ul className={s.tplList}>
          {shown.map((t) => (
            <li key={t.id} className={s.tpl}>
              <div className={s.tplTop}>
                <span className={s.tplName}>{t.name}</span>
                <div className={s.actions}>
                  <button type="button" className={p.btnPrimary} onClick={() => apply(t)}>Use template</button>
                  {(
                    <>
                      <button type="button" className={p.btn} aria-pressed={t.favourite} disabled={busy} onClick={() => void toggleFavourite(t)}>
                        {t.favourite ? <><Icon name="check" size={14} /> Favourite</> : "Add to favourites"}
                      </button>
                      <button type="button" className={p.btn} onClick={() => setForm({ id: t.id, name: t.name, tags: t.tags, tagInput: "", replaceText: false })}>Edit</button>
                      {confirmDelete === t.id ? (
                        <>
                          <button type="button" className={p.btn} disabled={busy} onClick={() => void remove(t)}>Confirm delete</button>
                          <button type="button" className={p.btn} onClick={() => setConfirmDelete(null)}>Keep</button>
                        </>
                      ) : (
                        <button type="button" className={p.btn} onClick={() => setConfirmDelete(t.id)}>Delete</button>
                      )}
                    </>
                  )}
                </div>
              </div>
              {t.tags.length > 0 && <div className={s.chips}>{t.tags.map((x) => <span key={x} className={s.tagChip}>{x}</span>)}</div>}
              <p className={s.tplPreview}>{SECTION_KEYS.map((k) => t[k].trim()).filter(Boolean).join(" · ") || "No text saved in this template."}</p>
            </li>
          ))}
        </ul>
      )}
      {templates !== null && templates.length > 0 && shown.length === 0 && <p className={p.muted}>No templates carry the tag &ldquo;{tagFilter}&rdquo;.</p>}
    </section>
  );
}
