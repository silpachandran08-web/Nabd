"use client";

import { useState } from "react";
import Dialog from "./Dialog";
import StatusPill from "./StatusPill";
import { can } from "../../lib/permissions";
import styles from "./rxSets.module.css";
import {
  itemLine, lastUsedLabel, medCountLabel, SCOPE_LABEL, toWriteItem,
  type RxSet, type RxSetResult, type RxSetScope, type RxSetWriteItem,
} from "@nabd/domain";

// DOC-035: one saved set, shared by the prescription pad panel and the Prescriptions & Templates screen.
export function RxSetCard({ set, onApply, onEdit, onDelete, compact = false }: {
  set: RxSet; onApply?: () => void; onEdit?: () => void; onDelete?: () => void; compact?: boolean;
}) {
  const staleNames = set.items.filter((i) => i.stale === true).map(itemLine);
  const scopeLabel = set.scope === "shared" && set.ownerName ? `${SCOPE_LABEL.shared} · ${set.ownerName}` : SCOPE_LABEL[set.scope];
  return (
    <li className={`${styles.card} ${compact ? styles.cardCompact : ""}`} data-testid="rx-set">
      <div className={styles.cardHead}>
        <h3 className={styles.name}>{set.name}</h3>
        <StatusPill tone={set.scope === "standard" ? "info" : set.scope === "mine" ? "neutral" : "sync"} icon={set.scope === "standard" ? "shield" : set.scope === "mine" ? "user" : "users"}
          label={set.scope === "mine" && set.shared ? "Mine · shared with clinic" : scopeLabel} />
        {staleNames.length > 0 && <StatusPill tone="warn" icon="alert" label={`${staleNames.length} not in active list`} />}
      </div>
      <div className={styles.meta}>
        <span>{medCountLabel(set.items.length)}</span>
        <span>{lastUsedLabel(set.lastUsedAt)}</span>
      </div>
      <p className={styles.meds}>{set.items.map(itemLine).join(", ")}</p>
      {set.items.some((i) => i.codeSuggestion) && (
        <p className={styles.staleNote} data-testid="code-this">
          Code this: {set.items.filter((i) => i.codeSuggestion).map((i) => `${i.drugName} → ${i.codeSuggestion?.display}`).join("; ")}.
        </p>
      )}
      {staleNames.length > 0 && (
        <p className={styles.staleNote}>
          Not in the current active pharmacy list: {staleNames.join(", ")}. Check before applying.
        </p>
      )}
      {(onApply || onEdit || onDelete) && (
        <div className={styles.actions}>
          {onApply && <button type="button" className={`${styles.btn} ${styles.btnPrimary}`} onClick={onApply} aria-label={`Apply set ${set.name}`}>Apply</button>}
          {set.canEdit && onEdit && <button type="button" className={styles.btn} onClick={onEdit} aria-label={`Edit set ${set.name}`}>Edit</button>}
          {set.canEdit && onDelete && <button type="button" className={`${styles.btn} ${styles.btnDanger}`} onClick={onDelete} aria-label={`Delete set ${set.name}`}>Delete</button>}
        </div>
      )}
    </li>
  );
}

/** Rename, change who can see it, remove medicines, or (from the pad) replace the medicines with what is on the pad. */
export function RxSetEditDialog({ set, padItems, onSave, onClose }: {
  set: RxSet; padItems?: RxSetWriteItem[];
  onSave: (body: { name?: string; scope?: RxSetScope; items?: RxSetWriteItem[] }) => Promise<RxSetResult>;
  onClose: () => void;
}) {
  const [name, setName] = useState(set.name);
  const [scope, setScope] = useState<RxSetScope>(set.scope === "mine" && set.shared ? "shared" : set.scope);
  const [items, setItems] = useState<RxSetWriteItem[] | null>(null); // null = medicines untouched
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const current = items ?? set.items.map((i) => toWriteItem(i));
  const staleOf = (drug: string) => set.items.some((i) => i.drugName === drug && i.stale === true);
  // "Clinic standard" is offered only to a clinical:approve holder (or when the set already is one, which only they can open).
  const canStandard = can("clinical:approve") || set.scope === "standard";
  const originalScope: RxSetScope = set.scope === "mine" && set.shared ? "shared" : set.scope;

  async function save() {
    if (!name.trim() || current.length === 0) return;
    setBusy(true);
    setErr(null);
    const body: { name?: string; scope?: RxSetScope; items?: RxSetWriteItem[] } = {};
    if (name.trim() !== set.name) body.name = name.trim();
    if (scope !== originalScope) body.scope = scope;
    if (items) body.items = items;
    if (Object.keys(body).length === 0) { onClose(); return; }
    const r = await onSave(body);
    setBusy(false);
    if (r.ok) onClose(); else setErr(r.message);
  }

  return (
    <Dialog title={`Edit set: ${set.name}`} onClose={onClose}>
      <div className={styles.formRow}>
        <label className={styles.label} htmlFor="rxset-name">Name</label>
        <input id="rxset-name" className={styles.field} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className={styles.formRow}>
        <label className={styles.label} htmlFor="rxset-scope">Who can use it</label>
        <select id="rxset-scope" className={styles.field} value={scope} onChange={(e) => setScope(e.target.value as RxSetScope)}>
          <option value="mine">{SCOPE_LABEL.mine} (only me)</option>
          <option value="shared">{SCOPE_LABEL.shared}</option>
          {canStandard && <option value="standard">{SCOPE_LABEL.standard}</option>}
        </select>
      </div>
      <div className={styles.label}>Medicines ({current.length})</div>
      <ul className={styles.editItems}>
        {current.map((i, idx) => (
          <li key={`${i.drugName}-${idx}`} className={styles.editItem}>
            <span className={styles.editItemText}>
              <span>{i.brandName ? `${i.drugName} (${i.brandName})` : i.drugName}</span>
              <span className={styles.editItemSub}>{[i.dosage, i.sigPattern ?? i.frequency, i.duration].filter(Boolean).join(" · ") || "No dose details"}</span>
              {items === null && staleOf(i.drugName) && <StatusPill tone="warn" icon="alert" label="Not in active list" />}
            </span>
            {current.length > 1 && (
              <button type="button" className={styles.btn} aria-label={`Remove ${i.drugName} from this set`}
                onClick={() => setItems(current.filter((_, k) => k !== idx))}>Remove</button>
            )}
          </li>
        ))}
      </ul>
      {padItems && padItems.length > 0 && (
        <button type="button" className={styles.btn} onClick={() => setItems(padItems)}>Replace medicines with the {padItems.length} on the pad</button>
      )}
      {err && <p className={styles.error} role="alert">{err}</p>}
      <div className={styles.dialogActions}>
        <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
        <button type="button" className={`${styles.btn} ${styles.btnPrimary}`} onClick={() => void save()} disabled={busy || !name.trim()}>
          {busy ? "Saving…" : "Save changes"}
        </button>
      </div>
    </Dialog>
  );
}

export function RxSetDeleteDialog({ set, onDelete, onClose }: {
  set: RxSet; onDelete: () => Promise<RxSetResult>; onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function go() {
    setBusy(true);
    setErr(null);
    const r = await onDelete();
    setBusy(false);
    if (r.ok) onClose(); else setErr(r.message);
  }
  return (
    <Dialog title="Delete this set?" onClose={onClose}>
      <p>
        <strong>{set.name}</strong> ({medCountLabel(set.items.length)}) will be removed
        {set.scope !== "mine" || set.shared ? " for everyone who can see it" : ""}. Prescriptions already written from it are not changed.
      </p>
      {err && <p className={styles.error} role="alert">{err}</p>}
      <div className={styles.dialogActions}>
        <button type="button" className={styles.btn} onClick={onClose}>Keep set</button>
        <button type="button" className={`${styles.btn} ${styles.btnDanger}`} onClick={() => void go()} disabled={busy}>{busy ? "Deleting…" : "Delete set"}</button>
      </div>
    </Dialog>
  );
}
