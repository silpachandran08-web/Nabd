"use client";

import { useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, errorText } from "../../lib/api";
import { fillTemplate, variableCount } from "./inboxLogic";
import styles from "./inbox.module.css";

export type Tpl = { id: string; name: string; language: string; category: string; body: string; paramCount: number };

/** The overlay for a closed window (prototype fd-tpl): approved templates in the patient's language only, with one field per variable. */
export default function TemplatePicker({ threadId, language, onClose, onSent }: { threadId: string; language?: string | null; onClose: () => void; onSent: () => void }) {
  const [list, setList] = useState<Tpl[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [pick, setPick] = useState<Tpl | null>(null);
  const [params, setParams] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function load() {
    setFailed(false); setList(null);
    try { setList(await api.get<Tpl[]>(`/inbox/templates${language ? `?language=${encodeURIComponent(language)}` : ""}`)); } catch { setFailed(true); }
  }
  useEffect(() => { void Promise.resolve().then(load); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function choose(t: Tpl) { setPick(t); setParams(Array(Math.max(t.paramCount, variableCount(t.body))).fill("")); setError(""); }
  async function send() {
    if (!pick) return;
    if (params.some((p) => !p.trim())) { setError("Fill in every field."); return; }
    setBusy(true); setError("");
    try {
      await api.post(`/inbox/threads/${threadId}/template-sends`, { templateId: pick.id, params: params.map((p) => p.trim()) });
      onSent();
    } catch (e) { setError(errorText(e, "Couldn't send the template. Nothing was sent.")); } finally { setBusy(false); }
  }

  return (
    <Dialog title="Choose template" onClose={onClose} size="lg">
      <p className={styles.hint}>The 24-hour window has closed. Only approved templates can start the conversation again.</p>
      {failed ? <ErrorState message="Couldn't load the templates." onRetry={() => void load()} />
        : list === null ? <Skeleton rows={3} label="Loading templates" />
        : list.length === 0 ? <EmptyState icon="info" title="No approved template for this patient's language." hint="Ask your clinic admin to get a template approved." />
        : !pick ? (
          <ul className={styles.tplList} aria-label="Approved templates">
            {list.map((t) => (
              <li key={t.id}>
                <button type="button" className={styles.tplItem} onClick={() => choose(t)}>
                  <span className={styles.tplName}>{t.name} <span className={styles.badge}>{t.language}</span></span>
                  <span className={styles.tplBody}>{t.body}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <form onSubmit={(e) => { e.preventDefault(); void send(); }}>
            <h3 className={styles.tplName}>{pick.name}</h3>
            {params.map((p, i) => (
              <label key={i} className={styles.field}>Variable {i + 1}
                <input value={p} maxLength={200} onChange={(e) => setParams(params.map((x, j) => (j === i ? e.target.value : x)))} />
              </label>
            ))}
            <p className={styles.preview} aria-label="Preview">{fillTemplate(pick.body, params)}</p>
            {error && <div className={styles.error} role="alert">{error}</div>}
            <div className={styles.dialogActions}>
              <button type="button" className={styles.secondary} onClick={() => setPick(null)}>Back</button>
              <button type="submit" className={styles.primary} disabled={busy}>Send template</button>
            </div>
          </form>
        )}
    </Dialog>
  );
}
