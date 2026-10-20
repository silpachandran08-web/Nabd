"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../components/ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { phoneFieldError, type Problem } from "./RegistryDialogs";
import styles from "./patients.module.css";

// ACC-028: GET /v1/patients/data-quality?issue=phone (patients:edit). Legacy numbers that failed validation keep their
// raw value; the desk corrects each one here with PATCH /v1/patients/{id} {phone}, which clears the flag.
type Item = { patientId: string; displayName: string; rawPhone: string | null };
type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

/** "Numbers to fix" list: paged (Load more), each row opens the phone edit form. The caller renders it only with patients:edit. */
export default function PhoneDataQuality({ authedFetch, onClose, onFixed }: { authedFetch: AuthedFetch; onClose: () => void; onFixed: () => void }) {
  const [items, setItems] = useState<Item[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [more, setMore] = useState(false);
  const [editing, setEditing] = useState<Item | null>(null);

  const load = useCallback(async (after: string | null) => {
    setFailed(false);
    setMore(after !== null);
    try {
      const res = await authedFetch(`/patients/data-quality?issue=phone&limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ""}`);
      if (!res) return;
      if (!res.ok) { setFailed(true); return; }
      const page: { items?: Item[]; nextCursor?: string | null } = await res.json();
      setItems((prev) => [...(after ? prev ?? [] : []), ...(page.items ?? [])]);
      setCursor(page.nextCursor ?? null);
    } catch { setFailed(true); } finally { setMore(false); }
  }, [authedFetch]);

  useEffect(() => { void Promise.resolve().then(() => load(null)); }, [load]);

  if (editing) {
    return <FixPhone authedFetch={authedFetch} item={editing} onClose={() => setEditing(null)}
      onSaved={() => { setItems((l) => (l ?? []).filter((x) => x.patientId !== editing.patientId)); setEditing(null); onFixed(); }} />;
  }
  return (
    <Dialog title="Numbers to fix" onClose={onClose}>
      <p className={styles.muted}>Mobile numbers that failed validation. They are kept as entered until someone corrects them.</p>
      {failed && items === null ? <ErrorState message="Couldn't load the numbers to fix." onRetry={() => void load(null)} />
        : items === null ? <Skeleton rows={3} label="Loading numbers to fix" />
        : items.length === 0 ? <EmptyState icon="check" title="No numbers to fix." />
        : (
          <ul aria-label="Patients with an invalid mobile number" style={{ listStyle: "none", margin: 0, padding: 0 }}>
            {items.map((r) => (
              <li key={r.patientId} className={styles.allergyRow}>
                <span>{r.displayName} <span className={styles.muted}>· {r.rawPhone || "no number"}</span></span>
                <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={() => setEditing(r)}
                  aria-label={`Fix mobile of ${r.displayName}`}>Fix number</button>
              </li>
            ))}
          </ul>
        )}
      {failed && items !== null && <div className={styles.errorInline} role="alert">Couldn&apos;t load more. Try again.</div>}
      {cursor && items !== null && (
        <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} disabled={more} onClick={() => void load(cursor)}>
          {more ? "Loading…" : "Load more"}
        </button>
      )}
    </Dialog>
  );
}

/** The patient's phone edit form (PATCH /v1/patients/{id}); the server's field error names the clinic country. */
function FixPhone({ authedFetch, item, onClose, onSaved }: { authedFetch: AuthedFetch; item: Item; onClose: () => void; onSaved: () => void }) {
  const [phone, setPhone] = useState(item.rawPhone ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    setSaving(true); setError(null);
    try {
      const res = await authedFetch(`/patients/${item.patientId}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ phone }) });
      if (!res) return;
      if (res.ok) { onSaved(); return; }
      const p: Problem | null = await res.json().catch(() => null);
      setError((res.status === 400 && phoneFieldError(p)) || p?.detail || "Couldn't save the number. It is unchanged.");
    } catch { setError("Couldn't reach the server. The number is unchanged."); } finally { setSaving(false); }
  }

  return (
    <Dialog title={`Mobile of ${item.displayName}`} onClose={onClose}>
      <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <label className={styles.fieldLabel} htmlFor="dq-phone">Mobile</label>
        <input id="dq-phone" className={styles.input} value={phone} inputMode="tel" autoFocus
          aria-invalid={error ? true : undefined} aria-describedby={error ? "dq-phone-error" : undefined}
          onChange={(e) => { setPhone(e.target.value); setError(null); }} />
        {error && <div id="dq-phone-error" className={styles.errorInline} role="alert">{error}</div>}
        <div style={{ display: "flex", gap: "var(--nb-space-8)", marginBlockStart: "var(--nb-space-16)" }}>
          <button type="button" className={styles.secondaryBtn} style={{ minBlockSize: 44 }} onClick={onClose}>Back</button>
          <button type="submit" className={styles.primaryBtn} style={{ minBlockSize: 44 }} disabled={saving || !phone.trim()}>{saving ? "Saving…" : "Save number"}</button>
        </div>
      </form>
    </Dialog>
  );
}
