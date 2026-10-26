"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import type { AuthedFetch } from "./shared";
import { blobPath, defaultKind, isTerminal, KINDS, statusLabel, validateUpload, type Kind, type ScanStatus } from "../../lib/attachments";
import s from "./consultParts.module.css";

type Item = { attachmentId: string; fileId: string; kind: Kind; note: string | null; createdAt: string; contentType: string };
type Phase = { text: string; blocked?: boolean } | null;

const kindLabel = (k: string) => KINDS.find((x) => x.id === k)?.label ?? k;
const problem = async (res: Response | null, fallback: string) => (res ? ((await res.json().catch(() => null))?.detail ?? fallback) : fallback);

/** PLT-030: the visit's attachments, an upload control (PLT-029) and a viewer. Restricted controls are absent, not disabled. */
export default function AttachmentsPanel({ authedFetch, visitId, canView, canAttach }: { authedFetch: AuthedFetch; visitId: string; canView: boolean; canAttach: boolean }) {
  const [items, setItems] = useState<Item[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>(null);
  const [note, setNote] = useState("");
  const [viewer, setViewer] = useState<{ url: string; type: string; title: string } | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    setError(null);
    const res = await authedFetch(`/clinical/visits/${visitId}/attachments`);
    if (!res) return;
    if (!res.ok) { setError(await problem(res, "Attachments could not be loaded.")); return; }
    setItems(((await res.json()) as { items: Item[] }).items);
  }, [authedFetch, visitId]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- load-on-mount fetch, same as DoctorOrders
  useEffect(() => { if (canView) void load(); }, [canView, load]);

  async function upload(file: File) {
    const bad = validateUpload(file);
    if (bad) { setPhase({ text: bad, blocked: true }); return; }
    setPhase({ text: "Uploading" });
    const json = { "Content-Type": "application/json" };
    const slotRes = await authedFetch("/files/uploads", { method: "POST", headers: json, body: JSON.stringify({ purpose: "outside_report", contentType: file.type, sizeBytes: file.size }) });
    if (!slotRes?.ok) { setPhase({ text: await problem(slotRes, "Upload could not start."), blocked: true }); return; }
    const slot = (await slotRes.json()) as { uploadId: string; putUrl: string };
    const put = await authedFetch(blobPath(slot.putUrl), { method: "PUT", headers: { "Content-Type": "application/octet-stream" }, body: file });
    if (!put?.ok) { setPhase({ text: "Upload failed. Try again.", blocked: true }); return; }
    const done = await authedFetch(`/files/uploads/${slot.uploadId}/complete`, { method: "POST", headers: json, body: "{}" });
    if (!done?.ok) { setPhase({ text: await problem(done, "Upload could not be completed."), blocked: true }); return; }
    const { fileId } = (await done.json()) as { fileId: string };
    setPhase({ text: statusLabel("pending_scan") });
    for (let i = 0; i < 60; i++) {
      const st = await authedFetch(`/files/${fileId}`);
      const status = st?.ok ? (((await st.json()) as { status: ScanStatus }).status) : "pending_scan";
      if (isTerminal(status)) {
        if (status !== "available") { setPhase({ text: statusLabel(status), blocked: true }); return; }
        break;
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
    const att = await authedFetch(`/clinical/visits/${visitId}/attachments`, { method: "POST", headers: json, body: JSON.stringify({ fileId, kind: defaultKind(file.type), note: note.trim() || undefined }) });
    if (!att?.ok) { setPhase({ text: await problem(att, "The file could not be attached."), blocked: true }); return; }
    setPhase(null);
    setNote("");
    await load();
  }

  async function open(a: Item) {
    const u = await authedFetch(`/files/${a.fileId}/download-url`, { method: "POST" });
    if (!u?.ok) { setError(await problem(u, "The file could not be opened.")); return; }
    const { url } = (await u.json()) as { url: string };
    const blob = await authedFetch(blobPath(url));
    if (!blob?.ok) { setError("The file could not be opened."); return; }
    setViewer({ url: URL.createObjectURL(await blob.blob()), type: a.contentType, title: kindLabel(a.kind) });
  }
  const closeViewer = () => { if (viewer) URL.revokeObjectURL(viewer.url); setViewer(null); };

  if (!canView) return null;
  return (
    <section className={s.card} aria-label="Attachments" data-testid="attachments-panel">
      <h3 className={s.cardTitle}>Attachments</h3>
      {error && <ErrorState message={error} onRetry={load} />}
      {!error && items === null && <Skeleton rows={2} label="Loading attachments" />}
      {!error && items?.length === 0 && <EmptyState title="No attachments" hint="Outside reports and photos for this visit appear here." />}
      {!error && items && items.length > 0 && (
        <ul className={s.timeline}>
          {items.map((a) => (
            <li key={a.attachmentId}>
              <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} onClick={() => void open(a)}>{kindLabel(a.kind)}</button>
              {a.note && <span className={s.muted}> · {a.note}</span>}
              <span className={s.muted}> · {new Date(a.createdAt).toLocaleString("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" })}</span>
            </li>
          ))}
        </ul>
      )}
      {canAttach && (
        <div>
          <label className={s.muted} htmlFor="att-note">Note (optional)</label>
          <input id="att-note" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
          <input ref={input} type="file" accept="application/pdf,image/jpeg,image/png" aria-label="Attach a file"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(f); e.target.value = ""; }} />
          {phase && <p role={phase.blocked ? "alert" : "status"} className={s.muted}>{phase.text}</p>}
        </div>
      )}
      {viewer && (
        <Dialog title={viewer.title} onClose={closeViewer} size="lg">
          {viewer.type === "application/pdf"
            ? <iframe src={viewer.url} title={viewer.title} style={{ inlineSize: "100%", blockSize: "70vh", border: 0 }} />
            // eslint-disable-next-line @next/next/no-img-element
            : <img src={viewer.url} alt={viewer.title} style={{ maxInlineSize: "100%" }} />}
        </Dialog>
      )}
    </section>
  );
}
