"use client";

// CLN-022: the Rx pad header's quick actions. "Repeat last (date, doctor)" opens a preview of exactly what will be copied, then
// copies that signed prescription; set chips apply a favourite set (the server's order: own sets, then shared, then clinic
// standards, each by last use). Keys: R = repeat preview, 1-9 = that chip. Both go through the server, which re-runs every
// allergy, pregnancy and controlled-substance check on every line; no earlier decision is carried over (J7).
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import Dialog from "../../ui/Dialog";
import { repeatSource, rxShortcut, type RxSet } from "@nabd/domain";
import { api, reasonText } from "../../../lib/api";
import { formatClinicDate } from "../../../lib/clinicTime";
import { dropVisitReads } from "../../../lib/doctorQueries";
import type { Part } from "../clinical";
import { rxItemLine, type Prescription } from "../shared";
import styles from "../../ui/rxSets.module.css";

type Props = {
  entryId: string; prescriptions: Part<Prescription[]>; sets: RxSet[] | null; filledCount: number;
  onApplied: (rx: Prescription) => void; onError: (message: string | null) => void; onSetUsed: () => void;
};

// Typing in a field, or anywhere inside an open (aria-modal) dialog, never triggers a pad shortcut.
const isTyping = (t: EventTarget | null) =>
  t instanceof HTMLElement && (t.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(t.tagName) || !!t.closest("[aria-modal]"));

export default function RxQuickActions({ entryId, prescriptions, sets, filledCount, onApplied, onError, onSetUsed }: Props) {
  const tx = useTranslations("consult.rxQuickActions");
  const qc = useQueryClient();
  const [preview, setPreview] = useState(false);
  const [confirmSet, setConfirmSet] = useState<RxSet | null>(null);
  const [busy, setBusy] = useState(false);
  const source = prescriptions.status === "ok" ? repeatSource(prescriptions.data ?? [], entryId) : null;
  const chips = (sets ?? []).slice(0, 9);

  async function run(path: string, body: unknown, fallback: string): Promise<boolean> {
    setBusy(true);
    onError(null);
    try {
      // The shared client adds the Idempotency-Key (repeat-last needs one) and keeps it across its own retries.
      onApplied(await api.post<Prescription>(path, body));
      dropVisitReads(qc); // same as every other consult write: cached visit reads are stale now
      return true;
    } catch (e) {
      onError(reasonText(e, fallback));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function repeat() {
    if (!source) return;
    setPreview(false);
    await run(`/clinical/prescriptions/${entryId}/repeat-last`, { sourcePrescriptionId: source.id }, tx("couldntRepeat"));
  }

  async function apply(set: RxSet) {
    setConfirmSet(null);
    if (await run(`/clinical/prescriptions/${entryId}/apply-set/${set.id}`, undefined, tx("couldntApply"))) onSetUsed(); // lastUsedAt moved
  }

  // One tap on an empty pad; a confirm when it would replace lines or when the set has stale items to look at first.
  const pick = (set: RxSet) => (filledCount > 0 || set.staleItemCount > 0 ? setConfirmSet(set) : void apply(set));

  const keyRef = useRef<(e: KeyboardEvent) => void>(() => {});
  useEffect(() => {
    keyRef.current = (e) => {
      if (busy) return;
      const hit = rxShortcut({ key: e.key, ctrlKey: e.ctrlKey, metaKey: e.metaKey, altKey: e.altKey, typing: isTyping(e.target) }, chips.length);
      if (!hit || (hit.kind === "repeat" && !source)) return;
      e.preventDefault();
      if (hit.kind === "repeat") setPreview(true); else pick(chips[hit.index]);
    };
  });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => keyRef.current(e);
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  if (!source && chips.length === 0) return null;
  return (
    <div className={styles.quickRow} data-testid="rx-quick-actions">
      {source && (
        <button type="button" className={`${styles.chip} ${styles.chipRepeat}`} onClick={() => setPreview(true)} disabled={busy}
          aria-keyshortcuts="R" data-testid="rx-repeat-last">
          {source.signedByName
            ? tx("repeatLastBy", { date: formatClinicDate(source.signedAt ?? ""), doctor: source.signedByName })
            : tx("repeatLast", { date: formatClinicDate(source.signedAt ?? "") })}
        </button>
      )}
      {chips.map((s, n) => (
        <button key={s.id} type="button" className={styles.chip} onClick={() => pick(s)} disabled={busy} aria-keyshortcuts={String(n + 1)}
          aria-label={s.staleItemCount > 0 ? tx("applySetStale", { n: n + 1, name: s.name, count: s.staleItemCount }) : tx("applySet", { n: n + 1, name: s.name })}
          data-testid="rx-set-chip">
          <span className={styles.chipKey} aria-hidden="true">{n + 1}</span>{s.name}{s.staleItemCount > 0 && <span className={styles.chipStale} aria-hidden="true"> ⚠</span>}
        </button>
      ))}

      {preview && source && (
        <Dialog title={tx("previewTitle")} onClose={() => setPreview(false)}>
          <p className={styles.quiet}>
            {source.signedByName
              ? tx("previewFromBy", { date: formatClinicDate(source.signedAt ?? ""), doctor: source.signedByName })
              : tx("previewFrom", { date: formatClinicDate(source.signedAt ?? "") })}
          </p>
          <ul className={styles.editItems} data-testid="rx-repeat-preview">
            {source.items.map((i, n) => <li key={i.id ?? n} className={styles.editItem}>{rxItemLine(i)}{i.instructions ? ` — ${i.instructions}` : ""}</li>)}
          </ul>
          <p className={styles.staleNote} role="note">{tx("checksRunAgain")}</p>
          {filledCount > 0 && <p className={styles.quiet}>{tx("replacesPad", { count: filledCount })}</p>}
          <div className={styles.dialogActions}>
            <button type="button" className={styles.btn} onClick={() => setPreview(false)}>{tx("cancel")}</button>
            <button type="button" className={`${styles.btn} ${styles.btnPrimary}`} onClick={() => void repeat()} autoFocus>{tx("repeat")}</button>
          </div>
        </Dialog>
      )}

      {confirmSet && (
        <Dialog title={tx("applyTitle", { name: confirmSet.name })} onClose={() => setConfirmSet(null)}>
          {confirmSet.items.some((i) => i.stale) && (
            <ul className={styles.editItems} data-testid="rx-set-stale">
              {confirmSet.items.filter((i) => i.stale).map((i, n) => (
                <li key={n} className={styles.editItem}>
                  <span className={styles.editItemText}>
                    <span>{i.drugName} — {tx(`stale.${i.staleReason ?? "not_in_master"}`)}</span>
                    {i.replacement && <span className={styles.editItemSub}>{tx("replacement", { display: i.replacement.display })}</span>}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {filledCount > 0 && <p className={styles.quiet}>{tx("replacesPad", { count: filledCount })}</p>}
          <p className={styles.quiet}>{tx("checksRunAgain")}</p>
          <div className={styles.dialogActions}>
            <button type="button" className={styles.btn} onClick={() => setConfirmSet(null)}>{tx("cancel")}</button>
            <button type="button" className={`${styles.btn} ${styles.btnPrimary}`} onClick={() => void apply(confirmSet)} autoFocus>{tx("apply")}</button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
