"use client";

// IDC-003: the clinic's privacy notice per language. Locked (mandatory) clauses are shown greyed and cannot change; the
// clinic edits the text around them. Publishing needs setup:edit and a fresh authenticator code. Restricted controls are absent.
import { useCallback, useEffect, useState } from "react";
import styles from "../../setup/setup.module.css";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { apiFetch } from "../../lib/apiClient";
import { requestStepUpToken } from "../../lib/stepUp";
import { currentOf, historyOf, isStepUpCode, languageLabel, lockedClausesIntact, type NoticeVersion } from "../../lib/privacyNotice";

async function detail(res: Response, fallback: string): Promise<string> {
  if (res.status >= 500) return "The server had a problem. Try again in a moment.";
  return ((await res.json().catch(() => null)) as { detail?: string } | null)?.detail || fallback;
}

export default function PrivacyNoticePanel() {
  const [versions, setVersions] = useState<NoticeVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lang, setLang] = useState("en");
  const [draft, setDraft] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [fail, setFail] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const mayEdit = can("setup:edit");

  const load = useCallback(async () => {
    try {
      const res = await apiFetch("/settings/privacy/privacy-notice");
      if (!res.ok) { setError(await detail(res, "Could not load the privacy notices.")); return; }
      const list = ((await res.json()) as { versions: NoticeVersion[] }).versions;
      setVersions(list); setError(null);
    } catch { setError("Could not reach the server. Check your connection."); }
  }, []);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load; state is set after the awaited fetch
  useEffect(() => { void load(); }, [load]);

  const langs = versions ? [...new Set(versions.map((v) => v.language))] : [];
  const cur = versions ? currentOf(versions, langs.includes(lang) ? lang : langs[0] ?? "") : undefined;
  const activeLang = cur?.language ?? "";
  useEffect(() => { setDraft(cur?.bodyMd ?? ""); }, [cur?.language, cur?.version]); // eslint-disable-line react-hooks/set-state-in-effect, react-hooks/exhaustive-deps -- reset the editor when another version is current

  if (error && !versions) return <ErrorState message={error} onRetry={() => void load()} />;
  if (!versions) return <Skeleton rows={4} label="Loading privacy notices" />;
  if (versions.length === 0) {
    return <EmptyState icon="shield" title="No privacy notice yet" hint="Save your grievance contact on the Consent tab and your notice is created in each language." />;
  }

  const locked = cur?.lockedClauses ?? [];
  const intact = lockedClausesIntact(draft, locked);
  const changed = draft !== (cur?.bodyMd ?? "");

  async function publish() {
    if (!cur) return;
    setBusy(true); setFail(null); setOk(null);
    const su = await requestStepUpToken(code);
    if ("error" in su) { setFail(su.error); setBusy(false); return; }
    try {
      const res = await apiFetch("/settings/privacy/privacy-notice", {
        method: "PUT", headers: { "Content-Type": "application/json", "X-Step-Up-Token": su.token },
        body: JSON.stringify({ language: cur.language, bodyMd: draft, basedOnTemplate: cur.templateKey, baseVersion: cur.version }),
      });
      if (res.ok) { setOk(`Version ${((await res.json()) as { version: string }).version} published. Patients are asked to re-acknowledge at their next visit.`); setCode(""); await load(); }
      else if (res.status === 409) { setFail("The notice changed since you opened it. Reloaded; apply your edit again."); await load(); }
      else setFail(await detail(res, "Could not publish."));
    } catch { setFail("Could not reach the server. Check your connection."); }
    setBusy(false);
  }

  return (
    <div className={styles.card}>
      <h2 className={styles.cardTitle}>Privacy notice</h2>
      {!cur?.legalReviewed && (
        <div className={styles.notice} role="status">
          The mandatory clauses are placeholders until your data protection officer or counsel supplies the approved wording. Do not rely on this notice for compliance yet.
        </div>
      )}
      <div className={styles.tabs} role="tablist" aria-label="Notice language">
        {langs.map((l) => (
          <button key={l} type="button" role="tab" aria-selected={l === activeLang} className={l === activeLang ? styles.tabActive : styles.tab} onClick={() => { setLang(l); setOk(null); setFail(null); }}>
            {languageLabel(l)}
          </button>
        ))}
      </div>
      {locked.length > 0 && (
        <>
          <div className={styles.label}>Locked clauses (cannot be changed)</div>
          <ul aria-label="Locked clauses" style={{ opacity: 0.65, paddingInlineStart: "1.25rem" }}>
            {locked.map((c) => <li key={c}>{c}</li>)}
          </ul>
        </>
      )}
      {mayEdit ? (
        <>
          <label className={styles.label} htmlFor="notice-body">Notice text (version {cur?.version})</label>
          <textarea id="notice-body" className={styles.textarea} rows={12} value={draft} onChange={(e) => { setDraft(e.target.value); setOk(null); }} />
          {!intact && <div className={styles.errorState} role="alert">A locked clause was changed or removed. Restore it to publish.</div>}
          <div className={styles.row}>
            <input className={styles.input} inputMode="numeric" autoComplete="one-time-code" maxLength={6} aria-label="Authenticator code"
              placeholder="6-digit code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} style={{ minBlockSize: 44 }} />
            <button type="button" className={styles.btnPrimary} style={{ minBlockSize: 44 }} onClick={() => void publish()} disabled={busy || !changed || !intact || !isStepUpCode(code)}>
              Publish new version
            </button>
          </div>
        </>
      ) : (
        <pre style={{ whiteSpace: "pre-wrap", fontFamily: "inherit" }}>{cur?.bodyMd}</pre>
      )}
      {fail && <div className={styles.errorState} role="alert">{fail}</div>}
      {ok && <div className={styles.notice} role="status">{ok}</div>}
      <div className={styles.label}>Version history</div>
      <ul aria-label="Version history">
        {historyOf(versions, activeLang).map((v) => (
          <li key={v.version}>v{v.version} · {new Date(v.effectiveFrom).toLocaleDateString()}{v.current ? " · current" : ""}</li>
        ))}
      </ul>
    </div>
  );
}
