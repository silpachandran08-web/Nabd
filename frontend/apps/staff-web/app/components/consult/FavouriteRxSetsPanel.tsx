"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import Dialog from "../ui/Dialog";
import { can } from "../../lib/permissions";
import styles from "../ui/rxSets.module.css";
import { RxSetCard, RxSetDeleteDialog, RxSetEditDialog } from "../ui/RxSetParts";
import { SCOPE_LABEL, toWriteItem, type RxSet, type RxSetScope } from "@nabd/domain";
import { useRxSets } from "../../lib/useRxSets";
import type { AuthedFetch, Prescription, PrescriptionItem, Problem } from "./shared";

// DOC-035: the pad's "Favourite Rx sets" block. Applying a set goes through the server's apply-set endpoint, which keeps
// brand/sig/route/quantity and re-runs the allergy, pregnancy and controlled checks on every applied line (drug AND
// brand); the returned warnings land on the pad exactly as for typed lines and nothing is cleared here.
export default function FavouriteRxSetsPanel({ authedFetch, entryId, locked, items, onApplied, onError }: {
  authedFetch: AuthedFetch; entryId: string | null; locked: boolean; items: PrescriptionItem[];
  onApplied: (rx: Prescription) => void; onError: (message: string | null) => void;
}) {
  const tx = useTranslations("consult.favouriteRxSetsPanel");
  const { sets, error, reload, create, update, remove } = useRxSets(authedFetch);
  const [name, setName] = useState("");
  const [scope, setScope] = useState<RxSetScope>("mine");
  const [saveErr, setSaveErr] = useState<string | null>(null);
  const [editing, setEditing] = useState<RxSet | null>(null);
  const [deleting, setDeleting] = useState<RxSet | null>(null);
  const [confirmApply, setConfirmApply] = useState<RxSet | null>(null);

  const filled = items.filter((i) => i.drugName.trim() !== "");
  const padWrite = filled.map((i) => toWriteItem(i));
  const canStandard = can("clinical:approve");

  async function save() {
    if (!name.trim() || filled.length === 0) return;
    setSaveErr(null);
    const r = await create(name.trim(), scope, padWrite);
    if (r.ok) { setName(""); setScope("mine"); } else setSaveErr(r.message);
  }

  async function apply(set: RxSet) {
    if (!entryId) return;
    setConfirmApply(null);
    onError(null);
    const res = await authedFetch(`/clinical/prescriptions/${entryId}/apply-set/${set.id}`, { method: "POST" });
    if (res?.ok) {
      onApplied(await res.json());
      void reload(); // lastUsedAt moved
    } else {
      const pr: Problem | null = await res?.json().catch(() => null) ?? null;
      onError(pr?.detail || tx("couldnTApplySet"));
    }
  }

  return (
    <div data-testid="rx-sets-panel">
      {error ? (
        <div className={styles.error} role="alert">
          {error} <button type="button" className={styles.btn} onClick={() => void reload()}>{tx("retry")}</button>
        </div>
      ) : sets === null ? (
        <p className={styles.quiet} role="status">{tx("loadingSavedSets")}</p>
      ) : sets.length === 0 ? (
        <p className={styles.quiet}>{tx("noSavedSetsYet")}</p>
      ) : (
        <ul className={`${styles.list} ${styles.listScroll}`} tabIndex={0} aria-label={tx("savedPrescriptionSets")}>
          {sets.map((s) => (
            <RxSetCard key={s.id} set={s} compact
              onApply={locked ? undefined : () => (filled.length > 0 ? setConfirmApply(s) : void apply(s))}
              onEdit={() => setEditing(s)} onDelete={() => setDeleting(s)} />
          ))}
        </ul>
      )}
      {!locked && (
        <>
          <div className={styles.saveRow}>
            <input className={styles.field} aria-label={tx("newSetName")} placeholder={tx("saveCurrentItemsAsSet")} value={name} maxLength={120}
              onChange={(e) => setName(e.target.value)} />
            <select className={`${styles.field} ${styles.fieldScope}`} aria-label={tx("whoCanUseTheNew")} value={scope} onChange={(e) => setScope(e.target.value as RxSetScope)}>
              <option value="mine">{SCOPE_LABEL.mine}</option>
              <option value="shared">{SCOPE_LABEL.shared}</option>
              {canStandard && <option value="standard">{SCOPE_LABEL.standard}</option>}
            </select>
            <button type="button" className={styles.btn} onClick={() => void save()} disabled={!name.trim() || filled.length === 0}>{tx("saveSet")}</button>
          </div>
          {saveErr && <p className={styles.error} role="alert">{saveErr}</p>}
        </>
      )}
      {editing && (
        <RxSetEditDialog set={editing} padItems={locked ? undefined : padWrite} onSave={(body) => update(editing.id, body)} onClose={() => setEditing(null)} />
      )}
      {deleting && <RxSetDeleteDialog set={deleting} onDelete={() => remove(deleting.id)} onClose={() => setDeleting(null)} />}
      {confirmApply && (
        <Dialog title={tx("replaceTheMedicinesOnThe")} onClose={() => setConfirmApply(null)}>
          <p>
            {tx("applying")}{" "}<strong>{confirmApply.name}</strong>{" "}{tx("replacesThe", { length: filled.length })} {filled.length === 1 ? "medicine" : "medicines"} {" "}{tx("nowOnThePadIncluding")}
          </p>
          <div className={styles.dialogActions}>
            <button type="button" className={styles.btn} onClick={() => setConfirmApply(null)}>{tx("cancel")}</button>
            <button type="button" className={`${styles.btn} ${styles.btnPrimary}`} onClick={() => void apply(confirmApply)}>{tx("replaceAndApply")}</button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
