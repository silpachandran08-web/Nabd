"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../components/ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import type { Problem } from "@nabd/api-client/types";
import styles from "./patients.module.css";

// ACC-030: GET /v1/patients/duplicate-suggestions, POST .../{id}/dismiss, POST /patients/{id}/merge (step-up), GET /patients/merges,
// POST /patients/merges/{id}/undo. The caller renders it only with patients:delete (the merge authority).
type Brief = { patientId: string; mrn: string; displayName: string; dob: string; sex: string; phone: string; lastVisitAt?: string | null };
type Suggestion = { suggestionId: string; a: Brief; b: Brief; score: number; matchedFields: string[] };
type MergeItem = { mergeId: string; mergedAt: string; undoUntil: string; duplicate: Brief; survivor: Brief };
type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
const day = (iso?: string | null) => (iso ? new Date(iso).toLocaleDateString("en-GB") : "no visit yet");
const idemKey = () => crypto.randomUUID();

export default function DuplicateSuggestions({ authedFetch, onClose, onChanged }: { authedFetch: AuthedFetch; onClose: () => void; onChanged: () => void }) {
  const [items, setItems] = useState<Suggestion[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [merges, setMerges] = useState<MergeItem[]>([]);
  const [compare, setCompare] = useState<Suggestion | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // A page can be empty while nextCursor is set (the server scans a bounded block per call): keep going until something shows or the end.
  const load = useCallback(async (after: string | null) => {
    setFailed(false); setBusy(true);
    try {
      let next = after; const got: Suggestion[] = [];
      for (let i = 0; i < 25; i++) {
        const res = await authedFetch(`/patients/duplicate-suggestions${next ? `?cursor=${encodeURIComponent(next)}` : ""}`);
        if (!res) return;
        if (!res.ok) { setFailed(true); return; }
        const page: { items?: Suggestion[]; nextCursor?: string | null } = await res.json();
        got.push(...(page.items ?? [])); next = page.nextCursor ?? null;
        if (got.length > 0 || !next) break;
      }
      setItems((prev) => [...(after ? prev ?? [] : []), ...got]); setCursor(next);
    } catch { setFailed(true); } finally { setBusy(false); }
  }, [authedFetch]);

  const loadMerges = useCallback(async () => {
    try {
      const res = await authedFetch("/patients/merges");
      if (res?.ok) setMerges(((await res.json()) as { items?: MergeItem[] }).items ?? []);
    } catch { /* the undo list is secondary: the suggestions still work */ }
  }, [authedFetch]);

  useEffect(() => { void Promise.resolve().then(() => { void load(null); void loadMerges(); }); }, [load, loadMerges]);

  async function dismiss(s: Suggestion) {
    setNotice(null);
    const res = await authedFetch(`/patients/duplicate-suggestions/${s.suggestionId}/dismiss`, { method: "POST", headers: { "Idempotency-Key": idemKey() } });
    if (res && (res.ok || res.status === 404)) { setItems((l) => (l ?? []).filter((x) => x.suggestionId !== s.suggestionId)); setCompare(null); }
    else if (res) setNotice("Couldn't dismiss this suggestion. It is still listed.");
  }

  async function undo(m: MergeItem, code: string): Promise<string | null> {
    const token = await stepUp(code);
    if (typeof token !== "string") return token.error;
    const res = await authedFetch(`/patients/merges/${m.mergeId}/undo`, { method: "POST", headers: { "X-Step-Up-Token": token, "Idempotency-Key": idemKey() } });
    if (!res) return "Signed out.";
    if (!res.ok) return problemText(await res.json().catch(() => null), "Couldn't undo the merge.");
    setNotice(`Merge undone: ${m.duplicate.displayName} is an active record again.`);
    void loadMerges(); void load(null); onChanged();
    return null;
  }

  // Deliberately not authedFetch: a wrong code also answers 401, which authedFetch would read as an expired session.
  async function stepUp(code: string): Promise<string | { error: string }> {
    const access = localStorage.getItem("nabd_access_token");
    if (!access) return { error: "Signed out. Sign in again." };
    try {
      const r = await fetch(`${API_BASE}/auth/mfa/verify`, { method: "POST", headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" }, body: JSON.stringify({ challengeId: "", code }) });
      if (!r.ok) return { error: problemText(await r.json().catch(() => null), "That code was not accepted.") };
      return ((await r.json()) as { stepUpToken: string }).stepUpToken;
    } catch { return { error: "Couldn't reach the server. Nothing was changed." }; }
  }

  async function merge(keep: Brief, dup: Brief, code: string): Promise<string | null> {
    const token = await stepUp(code);
    if (typeof token !== "string") return token.error;
    const res = await authedFetch(`/patients/${keep.patientId}/merge`, { method: "POST", headers: { "X-Step-Up-Token": token, "Content-Type": "application/json", "Idempotency-Key": idemKey() }, body: JSON.stringify({ duplicatePatientId: dup.patientId }) });
    if (!res) return "Signed out.";
    if (!res.ok) return problemText(await res.json().catch(() => null), "Couldn't merge. Nothing was changed.");
    setNotice(`Merged ${dup.displayName} into ${keep.displayName}. You can undo for 30 days.`);
    setCompare(null); void load(null); void loadMerges(); onChanged();
    return null;
  }

  if (compare) return <Compare s={compare} onBack={() => setCompare(null)} onDismiss={() => void dismiss(compare)} onMerge={merge} />;
  return (
    <Dialog title="Possible duplicates" onClose={onClose}>
      <p className={styles.muted}>Records that look like the same person. Merging moves the clinical history and asks for your authenticator code.</p>
      {notice && <div className={styles.flash} role="status">{notice}</div>}
      {failed && items === null ? <ErrorState message="Couldn't load the suggestions." onRetry={() => void load(null)} />
        : items === null ? <Skeleton rows={3} label="Loading suggestions" />
        : items.length === 0 ? <EmptyState icon="check" title="No duplicate suggestions." />
        : (
          <ul aria-label="Duplicate suggestions" style={{ listStyle: "none", margin: 0, padding: 0 }}>
            {items.map((s) => (
              <li key={s.suggestionId} className={styles.allergyRow}>
                <span>{s.a.displayName} <span className={styles.muted}>and</span> {s.b.displayName}
                  <span className={styles.muted}> · matched: {s.matchedFields.join(", ")}</span></span>
                <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => { setNotice(null); setCompare(s); }}
                  aria-label={`Compare ${s.a.displayName} and ${s.b.displayName}`}>Compare</button>
              </li>
            ))}
          </ul>
        )}
      {failed && items !== null && <div className={styles.errorInline} role="alert">Couldn&apos;t load more. Try again.</div>}
      {cursor && items !== null && (
        <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} disabled={busy} onClick={() => void load(cursor)}>{busy ? "Loading…" : "Load more"}</button>
      )}
      {merges.length > 0 && (
        <section aria-label="Recent merges">
          <h3>Recent merges</h3>
          <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
            {merges.map((m) => <UndoRow key={m.mergeId} m={m} onUndo={undo} />)}
          </ul>
        </section>
      )}
    </Dialog>
  );
}

function problemText(p: (Problem & { code?: string }) | null, fallback: string): string {
  return p?.code === "UNDO_WINDOW_PASSED" ? "This merge is older than 30 days and can no longer be undone." : p?.detail || p?.title || fallback;
}

function CodeForm({ label, busy, error, onSubmit, onCancel }: { label: string; busy: boolean; error: string | null; onSubmit: (code: string) => void; onCancel: () => void }) {
  const [code, setCode] = useState("");
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit(code.trim()); }}>
      <label className={styles.fieldLabel} htmlFor="dup-stepup">Authenticator code</label>
      <input id="dup-stepup" className={styles.input} value={code} inputMode="numeric" autoComplete="one-time-code" autoFocus maxLength={8}
        aria-invalid={error ? true : undefined} aria-describedby={error ? "dup-stepup-error" : undefined} onChange={(e) => setCode(e.target.value)} />
      {error && <div id="dup-stepup-error" className={styles.errorInline} role="alert">{error}</div>}
      <div style={{ display: "flex", gap: "var(--nb-space-8)", marginBlockStart: "var(--nb-space-16)" }}>
        <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={onCancel}>Cancel</button>
        <button type="submit" className={styles.primaryBtn} style={{ minBlockSize: 44 }} disabled={busy || code.trim().length < 6}>{busy ? "Working…" : label}</button>
      </div>
    </form>
  );
}

function UndoRow({ m, onUndo }: { m: MergeItem; onUndo: (m: MergeItem, code: string) => Promise<string | null> }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <li className={styles.allergyRow} style={{ flexWrap: "wrap" }}>
      <span>{m.duplicate.displayName} <span className={styles.muted}>merged into</span> {m.survivor.displayName}
        <span className={styles.muted}> · undo until {day(m.undoUntil)}</span></span>
      {!open && <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => setOpen(true)} aria-label={`Undo merge of ${m.duplicate.displayName}`}>Undo</button>}
      {open && <div style={{ inlineSize: "100%" }}>
        <CodeForm label="Undo merge" busy={busy} error={error} onCancel={() => { setOpen(false); setError(null); }}
          onSubmit={(code) => { setBusy(true); setError(null); void onUndo(m, code).then((e) => { setError(e); setBusy(false); }); }} />
      </div>}
    </li>
  );
}

function Compare({ s, onBack, onDismiss, onMerge }: { s: Suggestion; onBack: () => void; onDismiss: () => void; onMerge: (keep: Brief, dup: Brief, code: string) => Promise<string | null> }) {
  const [keepId, setKeepId] = useState(s.a.patientId);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const keep = keepId === s.a.patientId ? s.a : s.b;
  const dup = keepId === s.a.patientId ? s.b : s.a;
  const rows: [string, (b: Brief) => string][] = [["Name", (b) => b.displayName], ["Patient ID", (b) => b.mrn], ["Date of birth", (b) => b.dob], ["Sex", (b) => b.sex], ["Mobile", (b) => b.phone], ["Last visit", (b) => day(b.lastVisitAt)]];
  return (
    <Dialog title="Compare records" onClose={onBack}>
      <p className={styles.muted}>Matched on {s.matchedFields.join(", ")}. Pick the record to keep; the other is merged into it.</p>
      <div role="radiogroup" aria-label="Record to keep" style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(14rem, 1fr))", gap: "var(--nb-space-16)" }}>
        {[s.a, s.b].map((b) => (
          <div key={b.patientId} data-testid={`record-${b.patientId === s.a.patientId ? "a" : "b"}`}>
            <label style={{ display: "flex", alignItems: "center", gap: "var(--nb-space-8)", minBlockSize: 44 }}>
              <input type="radio" name="keep" checked={keepId === b.patientId} onChange={() => { setKeepId(b.patientId); setError(null); }} /> Keep this record
            </label>
            <dl style={{ margin: 0 }}>
              {rows.map(([k, f]) => <div key={k}><dt className={styles.muted}>{k}</dt><dd style={{ margin: 0, overflowWrap: "anywhere" }}>{f(b)}</dd></div>)}
            </dl>
          </div>
        ))}
      </div>
      {!confirming ? (
        <div style={{ display: "flex", flexWrap: "wrap", gap: "var(--nb-space-8)", marginBlockStart: "var(--nb-space-16)" }}>
          <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={onBack}>Back</button>
          <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={onDismiss}>Not a duplicate</button>
          <button type="button" className={styles.primaryBtn} style={{ minBlockSize: 44 }} onClick={() => setConfirming(true)}>Merge into {keep.displayName}</button>
        </div>
      ) : (
        <CodeForm label="Confirm merge" busy={busy} error={error} onCancel={() => { setConfirming(false); setError(null); }}
          onSubmit={(code) => { setBusy(true); setError(null); void onMerge(keep, dup, code).then((e) => { setError(e); setBusy(false); }); }} />
      )}
    </Dialog>
  );
}
