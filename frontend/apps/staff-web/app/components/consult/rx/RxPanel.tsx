"use client";

// FEF-030: the prescription card of the Plan step (pad, warnings, favourites, dose calculator, previous medicines)
// and its two dialogs (sign confirmation, warning decision). Mounted inside a PanelBoundary: a failure here
// leaves the note editor working.
import Link from "next/link";
import Dialog from "../../ui/Dialog";
import Icon from "../../ui/Icon";
import StatusPill from "../../ui/StatusPill";
import { can } from "../../../lib/permissions";
import { getIdentity } from "../../../lib/session";
import { age, type AuthedFetch, type PatientDetail, type Row } from "../shared";
import type { ClinicalContext } from "../clinical";
import DoseCalculator from "../DoseCalculator";
import FavouriteRxSetsPanel from "../FavouriteRxSetsPanel";
import MedicinesList from "../MedicinesList";
import RxPad from "../RxPad";
import RxWarnings, { OverrideDialog, WARNING_TITLE } from "../RxWarnings";
import { rxItemLine } from "../shared";
import { openWarningCount } from "./rxLogic";
import type { RxPad as Pad } from "./useRxPad";
import type { RxSigning } from "./useRxSigning";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

type Props = {
  entry: Row; patient: PatientDetail | null; authedFetch: AuthedFetch; ctx: ClinicalContext; reloadCtx: () => void;
  pad: Pad; signing: RxSigning; mayDecideRx: boolean; signBlocked: string | null; rxSource: string | null | undefined; drugKb: string | null | undefined;
};

export default function RxPanel({ entry, patient, authedFetch, ctx, reloadCtx, pad, signing, mayDecideRx, signBlocked, rxSource, drugKb }: Props) {
  const { rxItems, rxStatus, rxSaving, rxError, rxDirty } = pad;
  const signed = rxStatus === "signed";
  const openWarnings = openWarningCount(rxItems);
  // Advisory warnings (moderate/mild allergy) don't block but are acknowledged at signing; decisions already recorded are shown, not re-asked.
  const advisoryItems = rxItems.filter((i) => (i.warnings ?? []).some((w) => !w.requiresDecision));
  const focusDrug = (index: number) => {
    const el = document.getElementById(`rx-drug-${index}`) as HTMLInputElement | null;
    el?.focus();
    el?.select();
  };
  return (
    <>
      <div className={`${styles.card} ${styles.cardPad24}`}>
        <div className={styles.rxHeader}>
          <h3 className={styles.rxTitle}>Prescription {signed && <StatusPill tone="ok" label="Signed" />}</h3>
          {signed && can("clinical:view") && (
            <div className={styles.rxHeaderActions}>
              <Link className={`${styles.backBtn} ${styles.anchorBtn}`} href={`/consult/rx/${entry.id}/print`}>Print prescription</Link>
            </div>
          )}
          {!signed && (
            <div className={styles.rxHeaderActions}>
              <button className={styles.backBtn} onClick={() => void pad.saveRx()} disabled={rxSaving}>{rxSaving ? "Saving…" : "Save"}</button>
              {mayDecideRx && (
                <button className={styles.completeBtn} onClick={signing.reviewAndSign} disabled={rxSaving || (openWarnings > 0 && !rxDirty)}>
                  {openWarnings > 0 && !rxDirty ? "Resolve the warning first" : "Review and sign"}
                </button>
              )}
            </div>
          )}
        </div>
        {!signed && signBlocked && <p className={p.muted} role="status" data-testid="rx-sign-blocked">{signBlocked}</p>}
        {!signed && <p className={p.muted}>The allergy check matches drug names and common drug classes only. It is not a drug database, so confirm against the patient&apos;s record.</p>}
        {rxError && <div className={styles.errorState} role="alert">{rxError}</div>}
        {openWarnings > 0 && !signed && (
          <div className={`${p.notice} ${p.noticeDanger} ${p.mbM}`} role="alert">
            <Icon name="alert" size={16} />
            <span>{openWarnings} safety warning{openWarnings === 1 ? "" : "s"} need{openWarnings === 1 ? "s" : ""} a decision before this prescription can be signed.</span>
          </div>
        )}
        {rxItems.map((item, i) => (
          <div key={i}>
            <RxPad item={item} index={i} locked={signed} authedFetch={authedFetch} onChange={(patch) => pad.patchRxItem(i, patch)}>
              {!signed && rxItems.length > 1 && (
                <button type="button" className={styles.removeBtn} onClick={() => pad.removeRxItem(i)} aria-label="Remove">×</button>
              )}
            </RxPad>
            <RxWarnings
              item={item}
              locked={signed}
              onOverride={mayDecideRx ? (warning) => signing.askOverride(i, warning) : undefined}
              onChangeMedicine={() => focusDrug(i)}
              onRemove={rxItems.length > 1 ? () => pad.removeRxItem(i) : undefined}
              source={rxSource}
            />
          </div>
        ))}
        {!signed && <button type="button" className={styles.backBtn} onClick={pad.addRxItem}>+ Add drug</button>}
        {drugKb && <p data-testid="drug-kb-attribution" className={p.kbNote}>{drugKb}</p>}

        {!(patient && age(patient.dob) >= 18) && <div className={styles.previousMeds}>
          <DoseCalculator key={entry.patientId} authedFetch={authedFetch} patientId={entry.patientId} queueEntryId={entry.id} canRecordWeight={can("clinical:edit")} onWeightRecorded={reloadCtx} />
        </div>}

        <div className={styles.previousMeds}>
          <div className={`${styles.rxTitle} ${styles.rxTitleSm}`}>Favourite Rx sets</div>
          <FavouriteRxSetsPanel
            authedFetch={authedFetch} entryId={entry.id} locked={signed} items={rxItems}
            onError={pad.setRxError}
            onApplied={(rx) => { pad.applySaved(rx); pad.setRxError(null); }}
          />
        </div>

        <div className={styles.previousMeds}>
          <div className={`${styles.rxTitle} ${styles.rxTitleSmGap}`}>Previous medicines</div>
          <MedicinesList prescriptions={ctx.prescriptions} onRetry={reloadCtx} />
        </div>
      </div>

      {signing.showSignConfirm && (
        <Dialog title="Sign prescription" onClose={() => signing.setShowSignConfirm(false)}>
          <p className={p.muted}>Signing locks this prescription and can&apos;t be undone.</p>
          <ul className={`${p.list} ${p.mtM}`}>
            {rxItems.filter((i) => i.drugName.trim()).map((i, n) => <li key={n}>{rxItemLine(i)}{i.instructions ? ` — ${i.instructions}` : ""}</li>)}
          </ul>
          {rxItems.some((i) => (i.warnings ?? []).some((w) => w.decision)) && (
            <div className={`${p.notice} ${p.noticeInfo} ${p.mtM}`} role="status">
              <Icon name="info" size={16} />
              <span>
                Decisions recorded on this prescription:
                <ul className={p.noticeList}>
                  {rxItems.flatMap((i) => (i.warnings ?? []).filter((w) => w.decision).map((w) => (
                    <li key={`${i.drugName}-${w.kind}`}>{i.drugName}: {WARNING_TITLE[w.kind]} — {w.decision?.reasonLabel ?? w.decision?.reasonCode}{w.decision?.byName ? ` (${w.decision.byName})` : ""}</li>
                  )))}
                </ul>
              </span>
            </div>
          )}
          {advisoryItems.length > 0 && (
            <div className={`${p.notice} ${p.noticeWarn} ${p.mtM}`} role="alert">
              <Icon name="alert" size={16} />
              <span>
                Advisory warnings on this prescription:
                <ul className={p.noticeList}>
                  {advisoryItems.map((i, n) => (
                    <li key={n}>{i.drugName}: {(i.warnings ?? []).filter((w) => !w.requiresDecision).map((w) => `${WARNING_TITLE[w.kind].toLowerCase()} — ${w.message}`).join("; ")}</li>
                  ))}
                </ul>
                <label className={`${p.check} ${p.mtS}`}>
                  <input type="checkbox" checked={signing.signAck} onChange={(e) => signing.setSignAck(e.target.checked)} />
                  I have reviewed these warnings.
                </label>
              </span>
            </div>
          )}
          {signing.signError && <div className={`${p.notice} ${p.noticeDanger} ${p.mtM}`} role="alert">{signing.signError}</div>}
          <div className={p.dialogActions}>
            <button type="button" className={p.btn} onClick={() => signing.setShowSignConfirm(false)}>Cancel</button>
            <button type="button" className={p.btnPrimary} onClick={signing.confirmSignRx} disabled={signing.signing || (advisoryItems.length > 0 && !signing.signAck)}>
              {signing.signing ? "Signing…" : "Sign prescription"}
            </button>
          </div>
        </Dialog>
      )}

      {signing.override && rxItems[signing.override.index] && (
        <OverrideDialog item={rxItems[signing.override.index]} warning={signing.override.warning} doctorName={getIdentity()?.staffName ?? ""} busy={rxSaving} error={signing.overrideError}
          onConfirm={signing.confirmOverride} onClose={() => signing.setOverride(null)} />
      )}
    </>
  );
}
