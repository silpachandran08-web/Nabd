"use client";

import { useTranslations } from "next-intl";
import { useCallback, useEffect, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, reasonText } from "../../lib/api";
import { blobPath, defaultKind, isTerminal, KINDS, statusLabel, validateUpload, type Kind, type ScanStatus } from "../../lib/attachments";
import s from "./consultParts.module.css";

type Item = { attachmentId: string; fileId: string; kind: Kind; note: string | null; createdAt: string; contentType: string };
type Phase = { text: string; blocked?: boolean } | null;

const kindLabel = (k: string) => KINDS.find((x) => x.id === k)?.label ?? k;
/** PLT-030: the visit's attachments, an upload control (PLT-029) and a viewer. Restricted controls are absent, not disabled. */
export default function AttachmentsPanel({ visitId, canView, canAttach }: { visitId: string; canView: boolean; canAttach: boolean }) {
  const t = useTranslations("consult.attachmentsPanel");
  const [items, setItems] = useState<Item[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>(null);
  const [note, setNote] = useState("");
  const [viewer, setViewer] = useState<{ url: string; type: string; title: string } | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    setError(null);
    try { setItems((await api.get<{ items: Item[] }>(`/clinical/visits/${visitId}/attachments`)).items); }
    catch (e) { setError(reasonText(e, t("attachmentsCouldNotBeLoaded"))); }
  }, [visitId]);
  // eslint-disable-next-line react-hooks/set-state-in-effect -- load-on-mount fetch, same as DoctorOrders
  useEffect(() => { if (canView) void load(); }, [canView, load]);

  async function upload(file: File) {
    const bad = validateUpload(file);
    if (bad) { setPhase({ text: bad, blocked: true }); return; }
    setPhase({ text: t("uploading") });
    let fileId: string;
    try {
      const slot = await api.post<{ uploadId: string; putUrl: string }>("/files/uploads", { purpose: "outside_report", contentType: file.type, sizeBytes: file.size });
      try { await api.put(blobPath(slot.putUrl), undefined, { rawBody: file, headers: { "Content-Type": "application/octet-stream" } }); }
      catch { setPhase({ text: t("uploadFailedTryAgain"), blocked: true }); return; }
      ({ fileId } = await api.post<{ fileId: string }>(`/files/uploads/${slot.uploadId}/complete`, {}));
    } catch (e) { setPhase({ text: reasonText(e, t("uploadCouldNotBeCompleted")), blocked: true }); return; }
    setPhase({ text: statusLabel("pending_scan") });
    for (let i = 0; i < 60; i++) {
      const status = await api.get<{ status: ScanStatus }>(`/files/${fileId}`).then((r) => r.status, () => "pending_scan" as ScanStatus);
      if (isTerminal(status)) {
        if (status !== "available") { setPhase({ text: statusLabel(status), blocked: true }); return; }
        break;
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
    try { await api.post(`/clinical/visits/${visitId}/attachments`, { fileId, kind: defaultKind(file.type), note: note.trim() || undefined }); }
    catch (e) { setPhase({ text: reasonText(e, t("theFileCouldNotBe")), blocked: true }); return; }
    setPhase(null);
    setNote("");
    await load();
  }

  async function open(a: Item) {
    try {
      const { url } = await api.post<{ url: string }>(`/files/${a.fileId}/download-url`);
      const blob = await api.get<Blob>(blobPath(url), { as: "blob" });
      setViewer({ url: URL.createObjectURL(blob), type: a.contentType, title: kindLabel(a.kind) });
    } catch (e) { setError(reasonText(e, t("theFileCouldNotBe2"))); }
  }
  const closeViewer = () => { if (viewer) URL.revokeObjectURL(viewer.url); setViewer(null); };

  if (!canView) return null;
  return (
    <section className={s.card} aria-label={t("attachments")} data-testid="attachments-panel">
      <h3 className={s.cardTitle}>{t("attachments")}</h3>
      {error && <ErrorState message={error} onRetry={load} />}
      {!error && items === null && <Skeleton rows={2} label={t("loadingAttachments")} />}
      {!error && items?.length === 0 && <EmptyState title={t("noAttachments")} hint={t("outsideReportsAndPhotosFor")} />}
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
          <label className={s.muted} htmlFor="att-note">{t("noteOptional")}</label>
          <input id="att-note" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
          <input ref={input} type="file" accept="application/pdf,image/jpeg,image/png" aria-label={t("attachAFile")}
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(f); e.target.value = ""; }} />
          {phase && <p role={phase.blocked ? "alert" : "status"} className={s.muted}>{phase.text}</p>}
        </div>
      )}
      {viewer && (
        <Dialog title={viewer.title} onClose={closeViewer} size="lg">
          {viewer.type === "application/pdf"
            ? <iframe src={viewer.url} title={viewer.title} className={s.viewerFrame} />
            // eslint-disable-next-line @next/next/no-img-element
            : <img src={viewer.url} alt={viewer.title} className={s.viewerImg} />}
        </Dialog>
      )}
    </section>
  );
}
