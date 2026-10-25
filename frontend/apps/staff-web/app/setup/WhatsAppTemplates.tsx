"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./setup.module.css";
import { api, reasonText } from "../lib/api";

type Template = {
  id: string; name: string; language: string; category: "UTILITY" | "MARKETING" | "AUTHENTICATION"; headerText: string; body: string;
  paramCount: number; key: string; source: "catalogue" | "custom"; status: "queued" | "pending" | "approved" | "flagged" | "paused" | "disabled" | "rejected";
  rejectionReason: string | null; qualityRating: "GREEN" | "YELLOW" | "RED" | "UNKNOWN"; updatedAt: string;
};
type Draft = { name: string; language: string; category: "UTILITY" | "MARKETING"; body: string; examples: string[] };

const EMPTY: Draft = { name: "", language: "en", category: "UTILITY", body: "", examples: [] };

// Readable labels for Meta's rejection reason codes; anything else is shown as Meta sent it.
const REJECTION_HELP: Record<string, string> = {
  INCORRECT_CATEGORY: "Meta thinks the category is wrong — offers and promotions must be Marketing.",
  INVALID_FORMAT: "The wording or variables break Meta's formatting rules.",
  ABUSIVE_CONTENT: "Meta flagged the wording as against its commerce policy.",
  SCAM: "Meta flagged the wording as potentially misleading.",
  TAG_CONTENT_MISMATCH: "The wording doesn't match the chosen category.",
};

const STATUS_PILL: Record<Template["status"], string> = {
  queued: styles.pillPending, approved: styles.pillValid, flagged: styles.pillExpiring, pending: styles.pillPending,
  paused: styles.pillExpiring, disabled: styles.pillInactive, rejected: styles.pillExpired,
};

// COM-012: starter templates are named in plain words; owners only see their status (the platform team fixes rejections).
const KEY_LABEL: Record<string, string> = {
  appt_confirmed: "Appointment confirmed", appt_reminder_24h: "Reminder, 1 day before", appt_reminder_2h: "Reminder, 2 hours before",
  appt_rescheduled: "Appointment moved", appt_cancelled_by_clinic: "Cancelled by the clinic", doctor_delay: "Doctor running late",
  queue_your_turn: "Your turn is next", leave_now: "Time to leave", secure_link_ready: "Secure message ready",
  recall_due: "Next visit due", review_request: "Review request", feedback_request: "Feedback request",
  package_card: "Package card", package_expiry: "Package expiring", optout_confirm: "Stopped messages", otp_login: "Sign-in code",
};
const STATUS_LABEL: Partial<Record<Template["status"], string>> = { queued: "waiting to send" };
const LANGUAGE_LABEL: Record<string, string> = { en: "English", hi: "Hindi", kn: "Kannada", te: "Telugu", ar: "Arabic" };

/** Number of {{n}} slots in the body — drives how many example inputs are shown. */
const slotCount = (body: string) => (body.match(/\{\{\d+\}\}/g) ?? []).length;

/** NB-190: the clinic's WhatsApp template library. Errors stay inline (unlike the page-level error
 * state) so a clinic fixing a rejected template keeps its text on screen. */
export default function WhatsAppTemplates() {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try { setTemplates(await api.get<Template[]>("/setup/whatsapp-templates")); } catch { /* the list keeps what it had */ }
  }, []);

  // Deferred to a microtask, same as the setup page's loadAll (react-hooks/set-state-in-effect).
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const setBody = (body: string) => {
    const n = slotCount(body);
    setDraft((d) => ({ ...d, body, examples: Array.from({ length: n }, (_, i) => d.examples[i] ?? "") }));
  };

  const startFix = (t: Template) => {
    setEditingId(t.id);
    setError(null);
    setDraft({ name: t.name, language: t.language, category: t.category as Draft["category"], body: t.body, examples: Array(t.paramCount).fill("") });
  };

  const cancel = () => { setEditingId(null); setDraft(EMPTY); setError(null); };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api.request(editingId ? "PATCH" : "POST", editingId ? `/setup/whatsapp-templates/${editingId}` : "/setup/whatsapp-templates", { body: draft });
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
            {saving ? "Submitting…" : editingId ? "Resubmit for review" : "Submit for review"}
          </button>
        </div>
      </form>

      {templates.length === 0 ? (
        <p className={styles.empty}>No templates yet. Add your first one above — an appointment reminder is a good start.</p>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr><th>Name</th><th>Language</th><th>Message</th><th>Status</th><th>Quality</th><th></th></tr>
            </thead>
            <tbody>
              {templates.map((t) => (
                <tr key={t.id}>
                  <td>{t.source === "catalogue" ? (KEY_LABEL[t.key] ?? t.key) : t.name}<br /><span className={styles.label}>{t.source === "catalogue" ? "Starter template" : t.category === "MARKETING" ? "Marketing" : "Utility"}</span></td>
                  <td>{LANGUAGE_LABEL[t.language] ?? t.language}</td>
                  <td><strong>{t.headerText}</strong><br />{t.body}</td>
                  <td>
                    <span className={`${styles.pill} ${STATUS_PILL[t.status]}`}>{STATUS_LABEL[t.status] ?? t.status}</span>
                    {t.status === "rejected" && t.rejectionReason && (
                      <div className={styles.label}>{REJECTION_HELP[t.rejectionReason] ?? t.rejectionReason}</div>
                    )}
                  </td>
                  <td>{t.qualityRating === "UNKNOWN" ? "—" : t.qualityRating.toLowerCase()}</td>
                  <td>{t.status === "rejected" && t.source === "custom" && <button className={styles.smallBtn} onClick={() => startFix(t)}>Fix &amp; resubmit</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
