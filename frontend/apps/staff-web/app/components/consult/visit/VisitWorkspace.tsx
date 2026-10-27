"use client";

// FEF-030: one open visit: patient banner, note editor, step panels, side column, action bar. Mounted per visit (keyed by the queue
// entry), so everything below starts fresh when the doctor opens another one. The panels that can fail on their own (prescription,
// timeline, close-out, side column) sit in PanelBoundary; the banner and the note fields do not, so they survive a panel failure.
import { useTranslations } from "next-intl";
import { useEffect, useReducer, useRef, useState } from "react";
import { nextPatient } from "@nabd/domain";
import { can } from "../../../lib/permissions";
import Icon from "../../ui/Icon";
import AllergyRegister from "../AllergyRegister";
import { useClinicalContext } from "../clinical";
import ConflictDialog from "../ConflictDialog";
import DentalDrawer from "../DentalDrawer";
import SafetyStrip from "../SafetyStrip";
import { signBlockCode } from "../signAuthority";
import { AmendForm, AmendHistory, SignedBanner } from "../amend/AmendPanel";
import { useAmendments } from "../amend/useAmendments";
import ActionBar from "../closeout/ActionBar";
import CloseOutPanel from "../closeout/CloseOutPanel";
import { closeView } from "../closeout/closeLogic";
import { useCloseOut } from "../closeout/useCloseOut";
import NoteBanners from "../note/NoteBanners";
import { AssessStep, ExamineStep, PlanField, ReasonStep } from "../note/NoteSteps";
import { saveBadge } from "../note/SaveBadge";
import { useNoteEditor } from "../note/useNoteEditor";
import ToolsAside from "../orders/ToolsAside";
import RxPanel from "../rx/RxPanel";
import { useRxPad } from "../rx/useRxPad";
import { useRxSigning } from "../rx/useRxSigning";
import TimelinePanel from "../timeline/TimelinePanel";
import TransferDialog from "../transfer/TransferDialog";
import { useTransfer } from "../transfer/useTransfer";
import StepRail from "../workflow/StepRail";
import { initialWorkflow, workflowReducer, type Step } from "../workflow/consultWorkflow";
import type { AuthedFetch, PatientDetail, Row } from "../shared";
import PanelBoundary from "./PanelBoundary";
import TodayAside from "./TodayAside";
import { useVisitLoad } from "./useVisitLoad";
import VisitStrip from "./VisitStrip";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

export type WorkspaceProps = {
  row: Row; initialStep: Step; rows: Row[]; selfId: string; authedFetch: AuthedFetch;
  rxSource: string | null | undefined; drugKb: string | null | undefined;
  canStartRow: (r: Row) => boolean;
  onClose: () => void; onSwitch: (r: Row) => void; onReload: () => void;
  onStatus: (entryId: string, status: string) => void;
  /** Mirrors for the page: current step (deep link, reload) and whether the note holds unsaved text. */
  onStep: (s: Step) => void; onDirty: (dirty: boolean) => void;
};

export default function VisitWorkspace(w: WorkspaceProps) {
  const tx = useTranslations("consult.visitWorkspace");
  const tb = useTranslations("consult.saveBadge");
  const { authedFetch, selfId } = w;
  const [entry, setEntry] = useState<Row>(w.row);
  const [patient, setPatient] = useState<PatientDetail | null>(w.row.patient);
  const [unavailable, setUnavailable] = useState(false); // IDC-001: the server answered patient-not-found for this visit (consent withdrawn / out of scope)
  // undefined = not known (still checking, or the lookup failed): never shown as "Not set" (T1-BUG-2).
  const [followUpLabel, setFollowUpLabel] = useState<string | null | undefined>(undefined);
  const [notice, setNotice] = useState<string | null>(null);
  const [shellError, setShellError] = useState<string | null>(null);
  const [wf, dispatch] = useReducer(workflowReducer, w.initialStep, initialWorkflow);

  const { ctx, reload: reloadCtx } = useClinicalContext(authedFetch, unavailable ? null : entry.patientId, entry.id);
  const n = useNoteEditor(authedFetch, entry.id, setNotice);
  const pad = useRxPad(authedFetch, entry.id);
  const signing = useRxSigning(authedFetch, entry.id, pad, reloadCtx, setNotice);
  const amend = useAmendments(authedFetch, entry.id, () => dispatch({ type: "amend", open: false }));
  const view = closeView({ note: n.note, noteStatus: n.noteStatus, rxItems: pad.rxItems, rxStatus: pad.rxStatus, entryStatus: entry.status });
  const setEntryStatus = (status: string) => { setEntry((prev) => ({ ...prev, status })); w.onStatus(entry.id, status); };
  const closeOut = useCloseOut(authedFetch, entry, n, view.handoffPending, setShellError, setNotice, setEntryStatus);
  useVisitLoad({ row: w.row, authedFetch, n, pad, amend, setPatient, setUnavailable, setNotice, setShellError });

  // The URL mirrors the open visit and step so My Day (and a reload) can deep-link into the workspace.
  useEffect(() => {
    const url = new URL(window.location.href);
    url.searchParams.set("entry", entry.id);
    url.searchParams.set("step", wf.step);
    window.history.replaceState(null, "", url);
    w.onStep(wf.step);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entry.id, wf.step]);
  const reportDirty = useRef(w.onDirty);
  useEffect(() => { reportDirty.current = w.onDirty; });
  useEffect(() => { reportDirty.current(n.dirty); }, [n.dirty]);

  async function saveThen(failure: string): Promise<boolean> {
    if (n.ctl.dirtyNow()) {
      const r = await n.saveNote();
      if (!r?.ok) { setShellError(failure); return false; }
    }
    return true;
  }
  const close = async () => { if (await saveThen(tx("saveFailedStay"))) w.onClose(); };
  const switchTo = async (r: Row) => {
    if (r.id === entry.id) return;
    if (await saveThen(tx("saveFailedSwitch"))) w.onSwitch(r);
  };
  const transfer = useTransfer(authedFetch, entry, () => void close());

  if (unavailable) {
    return (
      <main className={`${styles.page} ${styles.pageWide}`}>
        <div className={styles.strip}>
          <div className={styles.stripIdentity}>
            <button className={styles.backBtn} onClick={() => void close()}>{tx("queue")}</button>
          </div>
        </div>
        <div className={`${p.notice} ${p.noticeInfo}`} role="alert">
          <Icon name="info" size={16} />
          <span>{tx("recordNotAvailable")}</span>
        </div>
      </main>
    );
  }

  const locked = n.noteStatus === "signed";
  const canEditNote = can("clinical:edit");
  const fieldsDisabled = locked || !canEditNote || n.noteLoad !== "ok";
  // Recording a warning decision and signing are the visit doctor's (or a clinical:approve holder's); the server enforces it (403 not-prescriber), the UI simply doesn't offer it to anyone else.
  // CLN-005: and only as a registered clinician with an in-date licence (signing-authority, same rule as the server).
  const sa = pad.signAuthority;
  const mayDecideRx = (entry.doctorId === selfId || can("clinical:approve")) && sa !== null && sa !== "error" && sa.canSign;
  // CLN-006: signing and amending are the visit doctor's (or a clinical:approve holder's); others see the note read-only.
  const mayAmendNote = canEditNote && mayDecideRx;
  const badge = saveBadge(tb, { conflict: n.conflict, online: n.online, saveState: n.saveState, dirty: n.dirty, lastSavedAt: n.lastSavedAt });
  const next = view.closedOut ? nextPatient(w.rows, entry.id, selfId, w.canStartRow) : null;
  const primaryLabel = closeOut.completing ? tx("working") : view.handoffPending ? tx("retryHandoff") : view.closedOut ? tx("sentToCheckout") : tx("signAndSend");
  const field = { note: n.note, disabled: fieldsDisabled, onChange: n.updateField };

  return (
    <main className={`${styles.page} ${styles.pageWide}`}>
      <VisitStrip entry={entry} patient={patient} badge={badge} timer={{ show: entry.status === "in_consult" || locked, ended: locked }} onBack={() => void close()} />
      <NoteBanners online={n.online} shellError={shellError} loadFailed={n.noteLoad === "error"} notice={notice} orphanText={n.orphanText} hasConflict={!!n.conflict}
        onRetryLoad={w.onReload} onDismissNotice={() => setNotice(null)} onDismissOrphan={() => n.setOrphanText(null)} onReviewConflict={() => n.setConflictOpen(true)} />

      <PanelBoundary panel="safety">
        <SafetyStrip patient={patient} ctx={ctx} onRetry={reloadCtx} onManageAllergies={can("clinical:view") ? () => dispatch({ type: "allergies", open: true }) : undefined} canEditPregnancy={can("clinical:edit")} authedFetch={authedFetch} />
      </PanelBoundary>

      <div className={styles.workspace}>
        <TodayAside rows={w.rows} activeId={entry.id} onSwitch={(r) => void switchTo(r)} />

        <div className={styles.centerCol}>
          <StepRail step={wf.step} onGoto={(step) => dispatch({ type: "goto", step })} />
          <PanelBoundary panel="timeline">
            <TimelinePanel entry={entry} ctx={ctx} lastSavedAt={n.lastSavedAt} dirty={n.dirty} saveState={n.saveState} online={n.online} signedAt={n.signedAt} noteSigned={locked} amendments={amend.amendments} />
          </PanelBoundary>
          {locked && <SignedBanner canAmend={mayAmendNote} open={wf.showAmend} onToggle={() => dispatch({ type: "amend", open: !wf.showAmend })} />}
          {wf.showAmend && mayAmendNote && <AmendForm a={amend} />}
          <AmendHistory a={amend} />

          {wf.step === "reason" && <PanelBoundary panel="reason"><ReasonStep entry={entry} ctx={ctx} onRetry={reloadCtx} onNext={() => dispatch({ type: "goto", step: "examine" })} /></PanelBoundary>}
          {wf.step === "examine" && <ExamineStep {...field} entry={entry} patient={patient} authedFetch={authedFetch} locked={locked} noteLoaded={n.noteLoad === "ok"} onOpenDental={() => dispatch({ type: "dental", open: true })} />}
          {wf.step === "assess" && <AssessStep {...field} />}
          {wf.step === "plan" && (
            <>
              <PlanField {...field} />
              <PanelBoundary panel="rx">
                <RxPanel entry={entry} patient={patient} authedFetch={authedFetch} ctx={ctx} reloadCtx={reloadCtx} pad={pad} signing={signing}
                  mayDecideRx={mayDecideRx} signBlocked={signBlockCode(pad.signAuthority)} rxSource={w.rxSource} drugKb={w.drugKb} />
              </PanelBoundary>
            </>
          )}
          {wf.step === "close" && (
            <PanelBoundary panel="closeout">
              <CloseOutPanel entry={entry} patient={patient} authedFetch={authedFetch} view={view} noteSigned={locked} diagnosis={n.note.diagnosis}
                rxItems={pad.rxItems} rxStatus={pad.rxStatus} followUpLabel={followUpLabel} closeAck={wf.closeAck}
                onAck={(value) => dispatch({ type: "closeAck", value })} onGoToRx={() => dispatch({ type: "goto", step: "plan" })} />
            </PanelBoundary>
          )}
        </div>

        <ToolsAside entry={entry} patient={patient} authedFetch={authedFetch} rxItems={pad.rxItems} rxStatus={pad.rxStatus}
          onOpenRx={() => dispatch({ type: "goto", step: "plan" })} onFollowUp={setFollowUpLabel} />
      </div>

      <ActionBar badge={badge} step={wf.step} closedOut={view.closedOut} locked={locked} canEditNote={canEditNote} noteLoaded={n.noteLoad === "ok"} mayAmend={mayAmendNote} mayDecideRx={mayDecideRx}
        entryStatus={entry.status} saveState={n.saveState} dirty={n.dirty} hasConflict={!!n.conflict} completing={closeOut.completing}
        signedBlocked={view.issues.length > 0 && !wf.closeAck} primaryLabel={primaryLabel} canTransfer={can("queue:edit")} next={next}
        onAmend={() => { dispatch({ type: "amend", open: true }); window.scrollTo({ top: 0, behavior: "smooth" }); }}
        onOutputs={() => dispatch({ type: "goto", step: "close" })} onNext={(r) => void switchTo(r)} onBack={() => void close()}
        onSaveDraft={() => void n.saveNote()} onTransfer={() => void transfer.openTransfer()} onCloseStep={() => dispatch({ type: "goto", step: "close" })} onComplete={() => void closeOut.completeConsultation()} />

      {n.conflict && n.conflictOpen && <ConflictDialog mine={n.conflict.mine} server={n.conflict.server} onResolve={n.resolveConflict} onClose={() => n.setConflictOpen(false)} />}
      <TransferDialog t={transfer} />
      {wf.showAllergies && patient && (
        <AllergyRegister authedFetch={authedFetch} patientId={patient.id} patientName={patient.name} canEdit={can("clinical:edit")}
          onClose={() => dispatch({ type: "allergies", open: false })}
          onChanged={() => { reloadCtx(); void authedFetch(`/patients/${patient.id}`).then(async (r) => { if (r?.ok) setPatient(await r.json()); }); }} />
      )}
      {wf.showDental && patient && <DentalDrawer authedFetch={authedFetch} patientId={patient.id} patientName={patient.name} onClose={() => dispatch({ type: "dental", open: false })} />}
    </main>
  );
}
