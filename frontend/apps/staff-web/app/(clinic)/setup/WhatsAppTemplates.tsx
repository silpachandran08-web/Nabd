"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import styles from "./setup.module.css";
import { BottomSheet, ErrorState, Skeleton } from "@nabd/ui";
import { api, reasonText } from "../../lib/api";
import { can } from "../../lib/permissions";
import { useHasMounted } from "../../lib/useHasMounted";
import { attention, editLabel, pendingChange, reasonHelp, type TemplateVersion } from "./templateStatus";

type Status = "submitting" | "queued" | "pending" | "approved" | "flagged" | "paused" | "disabled" | "rejected";
type Template = {
  id: string; name: string; language: string; category: "UTILITY" | "MARKETING" | "AUTHENTICATION"; headerText: string; body: string;
  paramCount: number; key: string; source: "catalogue" | "custom"; status: Status;
  rejectionReason: string | null; qualityRating: "GREEN" | "YELLOW" | "RED" | "UNKNOWN"; updatedAt: string;
  // COM-013: versions (sends use the active one), Meta's re-categorisation and pause
  version: number; active: boolean; activeVersion: number; requestedCategory: string; categoryChangedAt: string | null;
  pausedUntil: string | null; versions: TemplateVersion[];
};
type Draft = { name: string; language: string; category: "UTILITY" | "MARKETING"; body: string; examples: string[] };

const EMPTY: Draft = { name: "", language: "en", category: "UTILITY", body: "", examples: [] };

const STATUS_PILL: Record<Status, string> = {
  submitting: styles.pillPending, queued: styles.pillPending, approved: styles.pillValid, flagged: styles.pillExpiring, pending: styles.pillPending,
  paused: styles.pillExpiring, disabled: styles.pillInactive, rejected: styles.pillExpired,
};

// COM-012: starter templates are named in plain words; owners only see their status (the platform team fixes rejections).
const KEY_LABEL: Record<string, string> = {
  appt_confirmed: "Appointment confirmed", appt_reminder_24h: "Reminder, 1 day before", appt_reminder_2h: "Reminder, 2 hours before", appt_nudge: "Please confirm (no answer yet)",
  appt_rescheduled: "Appointment moved", appt_cancelled_by_clinic: "Cancelled by the clinic", doctor_delay: "Doctor running late",
  queue_your_turn: "Your turn is next", leave_now: "Time to leave", secure_link_ready: "Secure message ready",
  recall_due: "Next visit due", review_request: "Review request", feedback_request: "Feedback request",
  package_card: "Package card", package_expiry: "Package expiring", optout_confirm: "Stopped messages", otp_login: "Sign-in code",
};
const STATUS_LABEL: Partial<Record<Status, string>> = { queued: "waiting to send", submitting: "sending to Meta", pending: "in review" };
const LANGUAGE_LABEL: Record<string, string> = { en: "English", hi: "Hindi", kn: "Kannada", te: "Telugu", ar: "Arabic" };

/** Number of {{n}} slots in the body — drives how many example inputs are shown. */
const slotCount = (body: string) => (body.match(/\{\{\d+\}\}/g) ?? []).length;

const label = (t: { source: string; key: string; name: string }) => (t.source === "catalogue" ? (KEY_LABEL[t.key] ?? t.key) : t.name);

/** COM-013: Meta's reason in plain words with what to change. */
function Reason({ code }: { code: string | null }) {
  const help = reasonHelp(code);
  if (!help) return null;
  return <div className={styles.label}>{help.text} <strong>How to fix:</strong> {help.fix}</div>;
}

/** NB-190: the clinic's WhatsApp template library. Errors stay inline (unlike the page-level error
 * state) so a clinic fixing a rejected template keeps its text on screen. COM-013: an edit is a new version; the approved
 * one keeps sending until Meta approves the new one. */
export default function WhatsAppTemplates() {
  const [templates, setTemplates] = useState<Template[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [history, setHistory] = useState<Template | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // Restricted controls are absent, not disabled: without setup:edit there is no create form and no Edit (T1-BUG-1).
  const mounted = useHasMounted();
  const canEdit = mounted && can("setup:edit");
  // COM-013 (T1-BUG-2): one Idempotency-Key per edit, so a retry of the same PATCH is answered once and makes no new version.
  const editKey = useRef<string | null>(null);

  const load = useCallback(async () => {
    try { setTemplates(await api.get<Template[]>("/setup/whatsapp-templates")); setLoadFailed(false); } catch { setLoadFailed(true); /* the list keeps what it had */ }
  }, []);

  // Deferred to a microtask, same as the setup page's loadAll (react-hooks/set-state-in-effect).
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const setBody = (body: string) => {
    const n = slotCount(body);
    setDraft((d) => ({ ...d, body, examples: Array.from({ length: n }, (_, i) => d.examples[i] ?? "") }));
  };

  const startFix = (t: Template) => {
    editKey.current = crypto.randomUUID();
    setEditingId(t.id);
    setError(null);
    setDraft({ name: t.name, language: t.language, category: t.category as Draft["category"], body: t.body, examples: Array(t.paramCount).fill("") });
  };

  const alerts = templates === null ? [] : attention(templates, label);

  const cancel = () => { setEditingId(null); setDraft(EMPTY); setError(null); editKey.current = null; };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api.request(editingId ? "PATCH" : "POST", editingId ? `/setup/whatsapp-templates/${editingId}` : "/setup/whatsapp-templates",
        editingId ? { body: { body: draft.body, examples: draft.examples }, idempotencyKey: editKey.current ?? undefined } : { body: draft });
    } catch (err) {
      setError(reasonText(err, "Couldn't submit the template."));
      return;
    } finally {
      setSaving(false);
    }
    cancel();
    load();
  };

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>WhatsApp message templates</h2>
      <p className={styles.subtitle} style={{ marginBottom: 16 }}>
        Every WhatsApp message your clinic sends uses one of these. Meta reviews each template before it can be used —
        usually within minutes, sometimes up to a day. Your clinic name is added as the heading of every message.
      </p>

      {canEdit && (
      <form onSubmit={submit}>
        <div className={styles.row}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="wa-name">Template name</label>
            <input id="wa-name" className={styles.input} value={draft.name} disabled={!!editingId} placeholder="appointment_reminder"
              onChange={(e) => setDraft({ ...draft, name: e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, "_") })} />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="wa-lang">Language</label>
            <select id="wa-lang" className={styles.select} value={draft.language} disabled={!!editingId}
              onChange={(e) => setDraft({ ...draft, language: e.target.value })}>
              <option value="en">English</option>
              <option value="ar">Arabic</option>
              <option value="hi">Hindi</option>
              <option value="ml">Malayalam</option>
              <option value="ta">Tamil</option>
            </select>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="wa-cat">Category</label>
            <select id="wa-cat" className={styles.select} value={draft.category} disabled={!!editingId}
              onChange={(e) => setDraft({ ...draft, category: e.target.value as Draft["category"] })}>
              <option value="UTILITY">Utility — reminders, updates</option>
              <option value="MARKETING">Marketing — offers, campaigns</option>
            </select>
          </div>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="wa-body">Message text</label>
          <textarea id="wa-body" className={styles.textarea} rows={4} value={draft.body}
            placeholder="Your visit with {{1}} is at {{2}} today. Reply to reschedule."
            onChange={(e) => setBody(e.target.value)} />
          <span className={styles.label}>Use {"{{1}}"}, {"{{2}}"}… for the parts that change per patient. The text can&apos;t start or end with one.</span>
        </div>
        {draft.examples.length > 0 && (
          <div className={styles.row}>
            {draft.examples.map((ex, i) => (
              <div className={styles.field} key={i}>
                <label className={styles.label} htmlFor={`wa-ex-${i}`}>Example for {`{{${i + 1}}}`}</label>
                <input id={`wa-ex-${i}`} className={styles.input} value={ex}
                  onChange={(e) => setDraft({ ...draft, examples: draft.examples.map((v, j) => (j === i ? e.target.value : v)) })} />
              </div>
            ))}
          </div>
        )}
        {error && <div className={styles.errorState} role="alert">{error}</div>}
        <div className={styles.actions}>
          {editingId && <button className={styles.btn} type="button" onClick={cancel}>Cancel</button>}
          <button className={styles.btnPrimary} type="submit" disabled={saving}>
            {saving ? "Submitting…" : editingId ? "Submit new version" : "Submit for review"}
          </button>
        </div>
        {editingId && <p className={styles.label}>The approved version keeps sending while Meta reviews this one, and is replaced as soon as Meta approves it.</p>}
      </form>
      )}

      {templates !== null && alerts.length > 0 && (
        <div role="status" className={styles.errorState} style={{ marginBlock: 16 }}>
          <strong>Needs your attention</strong>
          <ul style={{ marginBlock: 4, paddingInlineStart: 20 }}>{alerts.map((a) => <li key={a.id}>{a.text}</li>)}</ul>
        </div>
      )}

      {templates === null ? (loadFailed ? <ErrorState message="Couldn't load your WhatsApp templates." onRetry={load} /> : <Skeleton rows={4} label="Loading templates" />) : templates.length === 0 ? (
        <p className={styles.empty}>{canEdit ? "No templates yet. Add your first one above — an appointment reminder is a good start." : "No templates yet."}</p>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr><th scope="col">Name</th><th scope="col">Language</th><th scope="col">Message</th><th scope="col">Status</th><th scope="col">Quality</th><th scope="col"></th></tr>
            </thead>
            <tbody>
              {templates.map((t) => {
                const change = pendingChange(t);
                const edit = editLabel(t, canEdit);
                return (
                <tr key={t.id}>
                  <td>{label(t)}<br /><span className={styles.label}>{t.source === "catalogue" ? "Starter template" : t.category === "MARKETING" ? "Marketing" : "Utility"}</span></td>
                  <td>{LANGUAGE_LABEL[t.language] ?? t.language}</td>
                  <td><strong>{t.headerText}</strong><br />{t.body}</td>
                  <td>
                    <span className={`${styles.pill} ${STATUS_PILL[t.status] ?? styles.pillPending}`}>{STATUS_LABEL[t.status] ?? t.status}</span>
                    {t.status === "rejected" && <Reason code={t.rejectionReason} />}
                    {change && (
                      <div className={styles.label}>
                        Version {change.version}: {STATUS_LABEL[change.status as Status] ?? change.status}
                        {change.status === "rejected" && <Reason code={change.rejectionReason} />}
                      </div>
                    )}
                  </td>
                  <td>{t.qualityRating === "UNKNOWN" ? "—" : t.qualityRating.toLowerCase()}</td>
                  <td>
                    {edit && <button className={styles.smallBtn} onClick={() => startFix(t)}>{edit}</button>}
                    {t.versions.length > 1 && <button className={styles.smallBtn} onClick={() => setHistory(t)}>History ({t.versions.length})</button>}
                  </td>
                </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {history && (
        <BottomSheet title={`Version history: ${label(history)}`} onClose={() => setHistory(null)}>
          <ol style={{ paddingInlineStart: 20 }}>
            {history.versions.map((v) => (
              <li key={v.id} style={{ marginBlockEnd: 12 }}>
                <strong>Version {v.version}</strong> <span className={`${styles.pill} ${STATUS_PILL[v.status as Status] ?? styles.pillPending}`}>{STATUS_LABEL[v.status as Status] ?? v.status}</span>
                {v.active && <span className={styles.label}> sending now</span>}
                <div className={styles.label}>{new Date(v.createdAt).toLocaleString()}</div>
                <div><strong>{v.headerText}</strong><br />{v.body}</div>
                {v.status === "rejected" && <Reason code={v.rejectionReason} />}
              </li>
            ))}
          </ol>
          <button className={styles.btn} type="button" onClick={() => setHistory(null)}>Close</button>
        </BottomSheet>
      )}
    </div>
  );
}
