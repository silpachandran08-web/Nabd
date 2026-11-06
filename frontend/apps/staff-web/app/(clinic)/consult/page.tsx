"use client";

// FEF-030: the consult page only composes. Queue: components/consult/queue. One open visit: components/consult/visit
// (note editor, prescription, close-out, timeline, orders in their own folders, each panel in its own error boundary).
import { useEffect, useRef, useState } from "react";
import { can } from "../../lib/permissions";
import { useDrugKbAttribution } from "../../components/consult/drugKnowledgeBase";
import PreConsultDialog from "../../components/consult/PreConsultDialog";
import QueueList from "../../components/consult/queue/QueueList";
import { useConsultQueue } from "../../components/consult/queue/useConsultQueue";
import { useSafetyDictionarySource } from "../../components/consult/safetyDictionary";
import type { Row } from "../../components/consult/shared";
import { useAuthedFetch } from "../../components/consult/visit/useAuthedFetch";
import VisitWorkspace from "../../components/consult/visit/VisitWorkspace";
import { isStep, type Step } from "../../components/consult/workflow/consultWorkflow";
import { useUnsavedGuard } from "../../lib/formValidation";
import { moveBody, moveErrorMessage } from "../../lib/queueMove";

interface Active { row: Row; step: Step; nonce: number }

export default function ConsultPage() {
  const authedFetch = useAuthedFetch();
  const rxSource = useSafetyDictionarySource(authedFetch);
  const drugKb = useDrugKbAttribution(authedFetch);
  const q = useConsultQueue();
  const { rows, setRows, loading, selfId, scope, load } = q;
  const [active, setActive] = useState<Active | null>(null);
  const [preview, setPreview] = useState<Row | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  // The URL mirrors the open visit, but only once a ?entry= deep link has been read; otherwise the
  // mirror effect would strip the params on mount, before the deep link is consumed.
  const [urlReady, setUrlReady] = useState(false);
  const [dirty, setDirty] = useState(false); // the open visit holds unsaved text in any section (FEF-035)
  useUnsavedGuard(dirty); // FEF-035: the browser asks before the tab is closed while anything is unsaved
  const deepLinkRef = useRef<{ entry: string; step: Step | null } | null>(null);
  const stepRef = useRef<Step>("reason");

  const openConsult = (row: Row, step: Step = "reason") => setActive((a) => ({ row, step, nonce: (a?.nonce ?? 0) + 1 }));

  useEffect(() => {
    void Promise.resolve().then(() => {
      const sp = new URLSearchParams(window.location.search);
      const entry = sp.get("entry");
      if (entry) deepLinkRef.current = { entry, step: isStep(sp.get("step")) ? (sp.get("step") as Step) : null };
      else setUrlReady(true);
      return load("mine");
    });
  }, [load]);

  // Closing the visit takes ?entry= and ?step= out of the URL again.
  useEffect(() => {
    if (!urlReady || active) return;
    const url = new URL(window.location.href);
    url.searchParams.delete("entry");
    url.searchParams.delete("step");
    window.history.replaceState(null, "", url);
  }, [active, urlReady]);

  // Deep link (?entry=&step=) once the queue is loaded.
  useEffect(() => {
    const link = deepLinkRef.current;
    if (!link || loading) return;
    deepLinkRef.current = null;
    setUrlReady(true);
    const row = rows.find((r) => r.id === link.entry);
    if (!row) return;
    if (row.status === "in_consult" && row.doctorId === selfId) void Promise.resolve().then(() => openConsult(row, link.step ?? undefined));
    else void Promise.resolve().then(() => setPreview(row));
  }, [rows, loading, selfId]);

  function openRow(row: Row) {
    setPreviewError(null);
    if (row.status === "in_consult" && row.doctorId === selfId) openConsult(row);
    else setPreview(row);
  }

  async function startConsult(row: Row) {
    setStarting(true);
    setPreviewError(null);
    try {
      const res = await authedFetch(`/queue/${row.id}/status`, { method: "PATCH", body: moveBody("in_consult", row.status) });
      if (!res) return;
      if (!res.ok) {
        setPreviewError(await moveErrorMessage(res, "Couldn't start the consultation."));
        return;
      }
      setRows((prev) => prev.map((r) => (r.id === row.id ? { ...r, status: "in_consult" } : r)));
      setPreview(null);
      openConsult({ ...row, status: "in_consult" });
    } catch {
      setPreviewError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setStarting(false);
    }
  }

  return (
    <>
      {active ? (
        <VisitWorkspace
          key={`${active.row.id}:${active.nonce}`} row={active.row} initialStep={active.step} rows={rows} selfId={selfId} authedFetch={authedFetch}
          rxSource={rxSource} drugKb={drugKb} canStartRow={q.canStartRow}
          onClose={() => { setActive(null); setDirty(false); void load(scope); }} onSwitch={openRow} onReload={() => openConsult(active.row, stepRef.current)}
          onStatus={(id, status) => setRows((prev) => prev.map((r) => (r.id === id ? { ...r, status } : r)))}
          onStep={(s) => { stepRef.current = s; }} onDirty={setDirty}
        />
      ) : <QueueList q={q} onOpen={openRow} />}
      {preview && (
        <PreConsultDialog
          row={preview}
          authedFetch={authedFetch}
          starting={starting}
          error={previewError}
          onClose={() => { setPreview(null); setPreviewError(null); }}
          onStart={q.canStartRow(preview) ? () => startConsult(preview) : undefined}
          canEditAllergies={can("clinical:edit")}
          onOpenVisit={(preview.status === "checkout_pending" || preview.status === "completed") && !dirty
            ? () => { const r = preview; setPreview(null); openConsult(r); }
            : undefined}
        />
      )}
    </>
  );
}
