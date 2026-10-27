"use client";

import { useEffect, useState, type RefObject } from "react";
import { api, ApiError, errorText } from "../../lib/api";
import { looksLikeResultOrDiagnosis, type Thread } from "./inboxLogic";
import styles from "./inbox.module.css";

type Canned = { id: string; title: string; body: string; language: string };

/** Free-text composer for an open window: quick-reply chips (inserted for editing, never auto-sent), the secure-link warning, and the bot handover on the first keystroke. */
export default function Composer({ thread, inputRef, onSent, onHandover, onWindowClosed }: {
  thread: Thread; inputRef: RefObject<HTMLTextAreaElement | null>; onSent: () => void; onHandover: () => Promise<void>; onWindowClosed: () => void;
}) {
  const [text, setText] = useState("");
  const [canned, setCanned] = useState<Canned[]>([]);
  const [warn, setWarn] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const language = thread.language ?? "";

  useEffect(() => {
    let live = true;
    api.get<Canned[]>(`/inbox/canned-replies${language ? `?language=${encodeURIComponent(language)}` : ""}`).then((c) => { if (live) setCanned(c); }).catch(() => { if (live) setCanned([]); });
    return () => { live = false; };
  }, [language]);

  function change(v: string) {
    if (!text && v && thread.mode === "bot") void onHandover(); // the first keystroke takes the thread from the bot
    setText(v); setWarn(false); setError("");
  }

  async function send(override: boolean) {
    const t = text.trim();
    if (!t) return;
    if (!override && looksLikeResultOrDiagnosis(t)) { setWarn(true); return; }
    setBusy(true); setError(""); setWarn(false);
    try {
      await api.post(`/inbox/threads/${thread.id}/replies`, override ? { text: t, overrideClinical: true } : { text: t });
      setText(""); onSent();
    } catch (e) {
      if (e instanceof ApiError && e.code === "TEXT_LOOKS_CLINICAL") setWarn(true);
      else if (e instanceof ApiError && e.code === "SESSION_WINDOW_CLOSED") { setError("The 24-hour window just closed. Choose a template instead."); onWindowClosed(); }
      else if (e instanceof ApiError && e.code === "OPTED_OUT") { setError("This patient opted out of messages."); onWindowClosed(); }
      else setError(errorText(e, "Couldn't send. Your text is still here; try again."));
    } finally { setBusy(false); }
  }

  return (
    <form className={styles.composer} onSubmit={(e) => { e.preventDefault(); void send(false); }}>
      {canned.length > 0 && (
        <ul className={styles.chips} aria-label="Quick replies">
          {canned.map((c) => (
            <li key={c.id}><button type="button" className={styles.chip} onClick={() => { setText(c.body); setWarn(false); inputRef.current?.focus(); }}>{c.title}</button></li>
          ))}
        </ul>
      )}
      {warn && (
        <div className={styles.warn} role="alert">
          This looks like a result or a diagnosis. Results should go by secure link, not WhatsApp text.
          <span className={styles.warnActions}>
            <button type="button" className={styles.secondary} onClick={() => setWarn(false)}>Edit message</button>
            <button type="button" className={styles.secondary} onClick={() => void send(true)}>Send anyway</button>
          </span>
        </div>
      )}
      {error && <div className={styles.error} role="alert">{error}</div>}
      <div className={styles.composeRow}>
        <label className={styles.srOnly} htmlFor="inbox-reply">Reply</label>
        <textarea id="inbox-reply" ref={inputRef} className={styles.textarea} rows={2} maxLength={1024} value={text} placeholder="Type a reply" onChange={(e) => change(e.target.value)} />
        <button type="submit" className={styles.primary} disabled={busy || !text.trim()}>Send</button>
      </div>
    </form>
  );
}
