"use client";

import { useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { api, errorText } from "../../lib/api";
import { NOTE_OUTCOMES } from "./inboxLogic";
import styles from "./inbox.module.css";

type Note = { id: string; outcome: string; note: string | null; at: string };

/** Call-outcome notes (COM-032): what happened when reception rang the patient. */
export default function NotesDialog({ threadId, onClose }: { threadId: string; onClose: () => void }) {
  const [notes, setNotes] = useState<Note[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [outcome, setOutcome] = useState("");
  const [text, setText] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function load() {
    setFailed(false);
    try { setNotes(await api.get<Note[]>(`/inbox/threads/${threadId}/notes`)); } catch { setFailed(true); }
  }
  useEffect(() => { void Promise.resolve().then(load); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function save() {
    if (!outcome) { setError("Choose what happened."); return; }
    setBusy(true); setError("");
    try {
      await api.post(`/inbox/threads/${threadId}/notes`, { outcome, ...(text.trim() ? { note: text.trim() } : {}) });
      setOutcome(""); setText("");
      await load();
    } catch (e) { setError(errorText(e, "Couldn't save the note.")); } finally { setBusy(false); }
  }
  const label = (c: string) => NOTE_OUTCOMES.find((o) => o.code === c)?.label ?? c;

  return (
    <Dialog title="Call notes" onClose={onClose} size="drawer">
      <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <label className={styles.field}>Outcome
          <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>
            <option value="">Choose…</option>
            {NOTE_OUTCOMES.map((o) => <option key={o.code} value={o.code}>{o.label}</option>)}
          </select>
        </label>
        <label className={styles.field}>Note (optional)
          <textarea value={text} maxLength={1024} rows={3} onChange={(e) => setText(e.target.value)} />
        </label>
        {error && <div className={styles.error} role="alert">{error}</div>}
        <div className={styles.dialogActions}><button type="submit" className={styles.primary} disabled={busy}>Save note</button></div>
      </form>
      {failed ? <ErrorState message="Couldn't load the notes." onRetry={() => void load()} />
        : notes === null ? <Skeleton rows={2} label="Loading notes" />
        : notes.length === 0 ? <p className={styles.hint}>No call notes yet.</p>
        : <ul className={styles.noteList} aria-label="Call notes">{notes.map((n) => <li key={n.id}><strong>{label(n.outcome)}</strong>{n.note ? ` — ${n.note}` : ""}</li>)}</ul>}
    </Dialog>
  );
}
