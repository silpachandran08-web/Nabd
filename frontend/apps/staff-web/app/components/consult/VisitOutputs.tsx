"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { getIdentity } from "../../lib/session";
import DocumentSheet, { type SheetFallback } from "./DocumentSheet";
import {
  DOC_FIELDS, DOC_LABEL, DOC_ORDER, MAX_FIELD, missingForIssue,
  type ClinicalDocument, type DocBody, type DocType, type SummaryPrefill,
} from "./documents";
import type { AuthedFetch, PatientDetail, Problem } from "./shared";
import p from "./consultParts.module.css";
import v from "./visitOutputs.module.css";

type Editor = {
  id: string | null; type: DocType; body: DocBody;
  tab: "edit" | "preview"; busy: boolean; error: string | null; confirmIssue: boolean; prefillNote: string | null;
};

async function problemText(res: Response, fallback: string): Promise<string> {
  const pr: Problem | null = await res.json().catch(() => null);
  return pr?.detail || fallback;
}

// DOC-030 - the Close step's "Visit outputs": the doctor fills labelled fields, previews, issues; an issued
// document opens a print-friendly view. Every word on the document is typed by the doctor.
export default function VisitOutputs({
  authedFetch, queueEntryId, patientName, patient,
}: { authedFetch: AuthedFetch; queueEntryId: string; patientName: string; patient: PatientDetail | null }) {
  const mayView = can("clinical:view");
  const mayEdit = can("clinical:edit");
  const [docs, setDocs] = useState<ClinicalDocument[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [viewing, setViewing] = useState<ClinicalDocument | null>(null);

  const load = useCallback(async () => {
    setLoadError(false);
    try {
      const res = await authedFetch(`/clinical/visits/${queueEntryId}/documents`);
      if (!res) return;
      if (!res.ok) { setLoadError(true); return; }
      setDocs((await res.json()) as ClinicalDocument[]);
    } catch {
      setLoadError(true);
    }
  }, [authedFetch, queueEntryId]);

  useEffect(() => {
    if (!mayView) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDocs(null);
    void load();
  }, [load, mayView]);

  if (!mayView) return null;

  const identity = getIdentity();
  const fallback: SheetFallback = {
    clinicName: identity?.tenantName ?? "", patientName: patient?.name ?? patientName, mrn: patient?.mrn,
    dob: patient?.dob, gender: patient?.gender, doctorName: identity?.staffName,
  };

  async function startNew(type: DocType) {
    setEditor({ id: null, type, body: {}, tab: "edit", busy: type === "visit_summary", error: null, confirmIssue: false, prefillNote: null });
    if (type !== "visit_summary") return;
    try {
      const res = await authedFetch(`/clinical/visits/${queueEntryId}/documents/summary-prefill`);
      if (!res) return;
      if (res.ok) {
        const pre = (await res.json()) as SummaryPrefill;
        const parts: string[] = [];
        if (!pre.noteSigned) parts.push("The note isn't signed yet, so nothing was filled in from it.");
        if (!pre.prescriptionSigned) parts.push("The prescription isn't signed yet, so no medicines were filled in.");
        const note = Object.keys(pre.body).length > 0 ? ["Filled in from the signed record. Review and edit before issuing.", ...parts].join(" ") : parts.join(" ") || null;
        setEditor((e) => (e && e.id === null && e.type === type ? { ...e, body: pre.body, busy: false, prefillNote: note } : e));
      } else {
        setEditor((e) => (e ? { ...e, busy: false, prefillNote: "Couldn't read the signed record, so nothing was filled in." } : e));
      }
    } catch {
      setEditor((e) => (e ? { ...e, busy: false, prefillNote: "Couldn't reach the server, so nothing was filled in." } : e));
    }
  }

  function openDoc(d: ClinicalDocument) {
    if (d.status === "issued" || !mayEdit) { setViewing(d); return; }
    setEditor({ id: d.id, type: d.type, body: d.body, tab: "edit", busy: false, error: null, confirmIssue: false, prefillNote: null });
  }

  /** Create or update the draft; returns the stored document, or null after showing the error. */
  async function persist(e: Editor): Promise<ClinicalDocument | null> {
    try {
      const res = e.id
        ? await authedFetch(`/clinical/documents/${e.id}`, { method: "PATCH", body: JSON.stringify({ body: e.body }) })
        : await authedFetch(`/clinical/visits/${queueEntryId}/documents`, { method: "POST", body: JSON.stringify({ type: e.type, body: e.body }) });
      if (!res) return null;
      if (!res.ok) {
        const detail = await problemText(res, "Couldn't save the document.");
        setEditor((c) => (c ? { ...c, busy: false, error: detail } : c));
        return null;
      }
      return (await res.json()) as ClinicalDocument;
    } catch {
      setEditor((c) => (c ? { ...c, busy: false, error: "Couldn't reach the server. Check your connection and try again." } : c));
      return null;
    }
  }

  async function saveDraft() {
    if (!editor) return;
    setEditor({ ...editor, busy: true, error: null });
    const doc = await persist(editor);
    if (!doc) return;
    setEditor(null);
    await load();
  }

  async function issue() {
    if (!editor) return;
    setEditor({ ...editor, busy: true, error: null });
    const doc = await persist(editor);
    if (!doc) return;
    try {
      const res = await authedFetch(`/clinical/documents/${doc.id}/issue`, { method: "POST" });
      if (!res) return;
      if (!res.ok) {
        // The draft is saved; keep the editor open on that draft so nothing is lost.
        const detail = await problemText(res, "Couldn't issue the document.");
        setEditor((c) => (c ? { ...c, id: doc.id, busy: false, confirmIssue: false, error: detail } : c));
        return;
      }
      const issued = (await res.json()) as ClinicalDocument;
      setEditor(null);
      setViewing(issued);
      await load();
    } catch {
      setEditor((c) => (c ? { ...c, id: doc.id, busy: false, confirmIssue: false, error: "Couldn't reach the server. The draft is saved; try issuing again." } : c));
    }
  }

  function setField(key: string, value: string) {
    setEditor((c) => (c ? { ...c, body: { ...c.body, [key]: value }, confirmIssue: false } : c));
  }

  const missing = editor ? missingForIssue(editor.type, editor.body) : [];

  return (
    <section className={p.stack} aria-label="Visit outputs">
      <div className={`${p.cardTitle} ${p.mb0}`}>Visit outputs</div>
      {docs === null && !loadError && <Skeleton rows={2} label="Loading visit outputs" />}
      {loadError && <ErrorState message="Couldn't load the documents for this visit." onRetry={() => { setDocs(null); void load(); }} />}
      {docs !== null && DOC_ORDER.map((type) => {
        const list = docs.filter((d) => d.type === type);
        return (
          <div key={type} className={v.group}>
            <div className={v.groupHead}>
              <span className={v.groupTitle}>{DOC_LABEL[type]}</span>
              {mayEdit && (
                <button type="button" className={p.btn} onClick={() => void startNew(type)}>
                  <Icon name="plus" size={14} /> New {DOC_LABEL[type].toLowerCase()}
                </button>
              )}
            </div>
            {list.length === 0 && <span className={p.muted}>None for this visit.</span>}
            {list.map((d) => (
              <div key={d.id} className={v.docRow}>
                <span className={v.grow}>
                  {DOC_LABEL[d.type]} · {new Date(d.issuedAt ?? d.createdAt).toLocaleString("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" })}
                </span>
                {d.status === "issued" ? <StatusPill tone="ok" label="Issued" /> : <StatusPill tone="warn" label="Draft" />}
                <button type="button" className={p.btn} onClick={() => openDoc(d)}>
                  {d.status === "draft" && mayEdit ? "Edit" : "Open"}
                </button>
              </div>
            ))}
          </div>
        );
      })}
      <p className={v.share}><Icon name="info" size={16} /><span>Sharing by WhatsApp or a secure link is not available yet. Print the issued document instead.</span></p>
      <p className={p.muted}>Diagnostic requests aren&apos;t available yet.</p>

      {editor && (
        <Dialog title={`${editor.id ? "Edit" : "New"} ${DOC_LABEL[editor.type].toLowerCase()}`} size="lg" onClose={() => { if (!editor.busy) setEditor(null); }}>
          <div className={p.stack}>
            {editor.prefillNote && <div className={`${p.notice} ${p.noticeInfo}`} role="status"><Icon name="info" size={16} /><span>{editor.prefillNote}</span></div>}
            <div className={v.tabs} role="group" aria-label="View">
              <button type="button" className={`${p.chip} ${editor.tab === "edit" ? p.chipOn : ""}`} aria-pressed={editor.tab === "edit"} onClick={() => setEditor({ ...editor, tab: "edit" })}>Fields</button>
              <button type="button" className={`${p.chip} ${editor.tab === "preview" ? p.chipOn : ""}`} aria-pressed={editor.tab === "preview"} onClick={() => setEditor({ ...editor, tab: "preview" })}>Preview</button>
            </div>
            {editor.tab === "edit" ? (
              <div className={p.stack}>
                {DOC_FIELDS[editor.type].map((f) => (
                  <label key={f.key} className={v.fieldLabel}>
                    <span>{f.label}{f.required && <span className={v.req}> (required)</span>}</span>
                    {f.kind === "textarea" ? (
                      <textarea className={p.textarea} rows={3} maxLength={MAX_FIELD} value={editor.body[f.key] ?? ""} onChange={(e) => setField(f.key, e.target.value)} />
                    ) : (
                      <input className={p.input} type={f.kind === "date" ? "date" : "text"} maxLength={MAX_FIELD} value={editor.body[f.key] ?? ""} onChange={(e) => setField(f.key, e.target.value)} />
                    )}
                  </label>
                ))}
              </div>
            ) : (
              <DocumentSheet type={editor.type} body={editor.body} header={null} fallback={fallback} />
            )}
            {editor.error && <div className={`${p.notice} ${p.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{editor.error}</span></div>}
            {editor.confirmIssue ? (
              <div className={v.confirm} role="group" aria-label="Confirm issue">
                <div className={`${p.notice} ${p.noticeWarn}`}><Icon name="alert" size={16} /><span>Once issued, this document can&apos;t be edited. The clinic, doctor and patient details are recorded as they are now.</span></div>
                <div className={p.dialogActions}>
                  <button type="button" className={p.btn} disabled={editor.busy} onClick={() => setEditor({ ...editor, confirmIssue: false })}>Keep editing</button>
                  <button type="button" className={p.btnPrimary} disabled={editor.busy} onClick={() => void issue()}>{editor.busy ? "Issuing…" : "Issue document"}</button>
                </div>
              </div>
            ) : (
              <>
                {missing.length > 0 && <span className={p.muted}>To issue, fill in: {missing.join(", ")}.</span>}
                <div className={p.dialogActions}>
                  <button type="button" className={p.btn} disabled={editor.busy} onClick={() => setEditor(null)}>Close</button>
                  <button type="button" className={p.btn} disabled={editor.busy} onClick={() => void saveDraft()}>{editor.busy ? "Saving…" : "Save draft"}</button>
                  <button type="button" className={p.btnPrimary} disabled={editor.busy || missing.length > 0} onClick={() => setEditor({ ...editor, confirmIssue: true })}>Issue…</button>
                </div>
              </>
            )}
          </div>
        </Dialog>
      )}

      {viewing && (
        <Dialog title={DOC_LABEL[viewing.type]} size="lg" onClose={() => setViewing(null)}>
          <div className={p.stack}>
            <DocumentSheet type={viewing.type} body={viewing.body} header={viewing.header} issuedAt={viewing.issuedAt} fallback={fallback} />
            <p className={v.share}><Icon name="info" size={16} /><span>Sharing by WhatsApp or a secure link is not available yet.</span></p>
            <div className={p.dialogActions}>
              <button type="button" className={p.btn} onClick={() => setViewing(null)}>Close</button>
              {viewing.status === "issued" && (
                <a className={`${p.btnPrimary} ${p.noDeco}`} href={`/documents/${viewing.id}/print`} target="_blank" rel="noopener noreferrer">
                  <Icon name="list" size={14} /> Open print view
                </a>
              )}
            </div>
          </div>
        </Dialog>
      )}
    </section>
  );
}
