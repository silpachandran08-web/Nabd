"use client";

import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { can } from "../../lib/permissions";
import { api, reasonText } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { blobPath, isTerminal, validateUpload, type ScanStatus } from "../../lib/attachments";
import { FDI_TEETH, toothLabel, type DentalPreset, type Numbering } from "../../lib/dental";
import Dialog from "../ui/Dialog";
import StatusPill, { type PillTone } from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import DentalPresetEditor, { type DentalSettings } from "./DentalPresetEditor";
import s from "./consultParts.module.css";

type Tooth = { id: string; toothNumber: number; status: string; note: string | null; isSupernumerary: boolean };
type Img = { imageId: string; fileId: string; kind: string; teeth: number[]; createdAt: string };
type Note = { noteId: string; tooth: number; text: string; createdAt: string };
type Lot = { drugId: string; drugName: string; lot: string; expiryDate: string | null };
type Kind = "iopa" | "bitewing" | "opg" | "intraoral_photo";

const STATUSES = ["healthy", "decayed", "filled", "missing", "crown", "root_canal"] as const;
const KINDS: Kind[] = ["iopa", "bitewing", "opg", "intraoral_photo"];
const TONE: Record<string, PillTone> = { healthy: "ok", decayed: "danger", filled: "info", missing: "neutral", crown: "sync", root_canal: "warn" };
/** A failure whose message is already the line to show (not a server or network error). */
class Shown extends Error {}
const when = (at: string) => new Date(at).toLocaleString("en-GB", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });

/** Dental chart in a drawer, opened from the Examine step (DOC-051). CLN-050: the clinic's numbering (display only, teeth stay
 * FDI), X-rays and photos per tooth or for the whole case, and chair-side preset notes in two taps at most. */
export default function DentalDrawer({ patientId, patientName, visitId, onClose }: { patientId: string; patientName: string; visitId: string | null; onClose: () => void }) {
  const tx = useTranslations("consult.dentalDrawer");
  const qc = useQueryClient();
  const base = `/specialty/dental/patients/${patientId}`;
  const chartKey = key("patient-chart", { id: patientId });
  const settingsKey = key("dental-settings", {});
  const imagesKey = key("dental-images", { id: patientId });
  const notesKey = key("dental-notes", { id: patientId });
  const chartQ = useQuery({ queryKey: chartKey, queryFn: getter<Tooth[]>(`${base}/chart`) });
  const settingsQ = useQuery({ queryKey: settingsKey, queryFn: getter<DentalSettings>("/specialty/dental/settings") });
  const imagesQ = useQuery({ queryKey: imagesKey, queryFn: getter<{ items: Img[] }>(`${base}/images`) });
  const notesQ = useQuery({ queryKey: notesKey, queryFn: getter<{ items: Note[] }>(`${base}/chair-notes`) });

  const canEdit = can("specialty_dental:edit");
  const canChair = canEdit && !!visitId;
  const canUpload = canChair && can("clinical:create");
  const canSetup = can("setup:edit");
  const [selected, setSelected] = useState<number | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ text: string; ok?: boolean } | null>(null);
  const [laFor, setLaFor] = useState<DentalPreset | null>(null);
  const [lots, setLots] = useState<Lot[] | "error" | null>(null);
  const [editor, setEditor] = useState<"mine" | "clinic" | null>(null);

  const numbering: Numbering = settingsQ.data?.numbering ?? "FDI";
  const label = (n: number, sup = false) => toothLabel(n, numbering, sup);
  const chart = chartQ.data;
  const images = imagesQ.data?.items ?? [];
  const caseImages = images.filter((i) => i.teeth.length === 0);
  const toothImages = selected === null ? [] : images.filter((i) => i.teeth.includes(selected));
  const toothNotes = selected === null ? [] : (notesQ.data?.items ?? []).filter((n) => n.tooth === selected);

  async function run(tag: string, work: () => Promise<void>, failText: string) {
    setBusy(tag);
    setNotice(null);
    try { await work(); } catch (e) { setNotice({ text: e instanceof Shown ? e.message : reasonText(e, failText) }); } finally { setBusy(null); }
  }

  const setStatus = (n: number, status: string) => run(`tooth-${n}`, async () => {
    const updated = await api.patch<Tooth>(`${base}/chart/${n}`, { status });
    qc.setQueryData(chartKey, (prev: Tooth[] | undefined) => [...(prev ?? []).filter((t) => !(t.toothNumber === n && !t.isSupernumerary)), updated]);
  }, tx("couldnTUpdateTheTooth"));

  const setNumbering = (n: Numbering) => run("numbering", async () => {
    qc.setQueryData(settingsKey, await api.put<DentalSettings>("/specialty/dental/settings", { numbering: n }));
  }, tx("numberingNotSaved"));

  const saveNote = (preset: DentalPreset, lot?: Lot) => run(`preset-${preset.code}`, async () => {
    if (selected === null || !visitId) return;
    await api.post(`${base}/chair-notes`, { queueEntryId: visitId, tooth: selected, presetCode: preset.code, laBatch: lot ? { drugId: lot.drugId, lot: lot.lot } : undefined });
    setLaFor(null);
    setLots(null);
    setNotice({ text: tx("noteSaved", { label: preset.label, tooth: label(selected) }), ok: true });
    await qc.invalidateQueries({ queryKey: notesKey });
  }, tx("noteNotSaved"));

  async function tapPreset(p: DentalPreset) {
    if (!p.la) { void saveNote(p); return; }
    setLaFor(p);
    setLots(null);
    try { setLots(await api.get<Lot[]>(`/specialty/dental/la-lots?q=${encodeURIComponent(p.drugQuery || p.label)}`)); } catch { setLots("error"); }
  }

  const upload = (file: File, kind: Kind, teeth: number[]) => run("upload", async () => {
    const bad = validateUpload(file) ?? (file.type.startsWith("image/") ? null : tx("imagesOnly"));
    if (bad) throw new Shown(bad);
    setNotice({ text: tx("uploading"), ok: true });
    const slot = await api.post<{ uploadId: string; putUrl: string }>("/files/uploads", { purpose: kind === "intraoral_photo" ? "clinical_photo" : "xray", contentType: file.type, sizeBytes: file.size });
    await api.put(blobPath(slot.putUrl), undefined, { rawBody: file, headers: { "Content-Type": "application/octet-stream" } });
    const { fileId } = await api.post<{ fileId: string }>(`/files/uploads/${slot.uploadId}/complete`, {});
    let status: ScanStatus = "pending_scan";
    for (let i = 0; i < 60 && !isTerminal(status); i++) {
      await new Promise((r) => setTimeout(r, 1000));
      status = await api.get<{ status: ScanStatus }>(`/files/${fileId}`).then((r) => r.status, () => "pending_scan" as ScanStatus);
    }
    if (status !== "available") throw new Shown(tx("uploadBlocked"));
    await api.post(`${base}/images`, { uploadId: fileId, queueEntryId: visitId, kind, teeth });
    setNotice({ text: tx("imageAttached"), ok: true });
    await qc.invalidateQueries({ queryKey: imagesKey });
  }, tx("uploadFailed"));

  const loading = chart === undefined && !chartQ.isError;
  return (
    <Dialog title={tx("chartTitle", { patientName })} onClose={onClose} size="drawer">
      {chartQ.isError ? <ErrorState message={tx("couldnTLoadTheDental")} onRetry={() => void chartQ.refetch()} /> : loading || !chart ? <Skeleton rows={4} label={tx("loadingDentalChart")} /> : (
        <div className={s.stack}>
          {!canEdit && <p className={s.muted}>{tx("theChartIsReadOnly")}</p>}
          {notice && <div className={`${s.notice} ${notice.ok ? s.noticeOk : s.noticeDanger}`} role={notice.ok ? "status" : "alert"}>{notice.text}</div>}
          <div className={s.row}>
            <span className={s.muted}>{tx("numbering")}</span>
            {canSetup ? (["FDI", "UNIVERSAL"] as const).map((n) => (
              <button key={n} type="button" className={`${s.chip} ${numbering === n ? s.chipOn : ""}`} aria-pressed={numbering === n}
                disabled={busy === "numbering"} onClick={() => numbering !== n && void setNumbering(n)}>{n === "FDI" ? tx("fdi") : tx("universal")}</button>
            )) : <strong>{numbering === "FDI" ? tx("fdi") : tx("universal")}</strong>}
          </div>
          <div className={s.dentalLayout}>
            <div className={s.teeth}>
              {FDI_TEETH.map((n) => {
                const t = chart.find((x) => x.toothNumber === n && !x.isSupernumerary);
                const status = t?.status ?? "healthy";
                const count = images.filter((i) => i.teeth.includes(n)).length;
                return (
                  <div key={n} className={`${s.tooth} ${selected === n ? s.toothOn : ""}`}>
                    <button type="button" className={`${s.linkBtn} ${s.linkBtnTouch}`} aria-pressed={selected === n}
                      aria-label={tx("selectTooth", { n: label(n) })} onClick={() => setSelected(selected === n ? null : n)}>
                      <strong>{label(n)}</strong>{count > 0 && <span className={s.muted}> · {tx("imageCount", { count })}</span>}
                    </button>
                    <StatusPill tone={TONE[status] ?? "neutral"} label={status.replace(/_/g, " ")} />
                    {canEdit && <select aria-label={tx("toothStatus", { n: label(n) })} value={status} disabled={busy === `tooth-${n}`} onChange={(e) => void setStatus(n, e.target.value)}>
                      {STATUSES.map((st) => <option key={st} value={st}>{st.replace(/_/g, " ")}</option>)}
                    </select>}
                  </div>
                );
              })}
            </div>
            <aside className={s.stack} aria-label={tx("imagesAndNotes")}>
              {selected !== null && (
                <section className={s.card} aria-label={tx("toothPanel", { n: label(selected) })}>
                  <h3 className={s.cardTitle}>{tx("toothPanel", { n: label(selected) })}</h3>
                  {imagesQ.isError ? <ErrorState message={tx("imagesNotLoaded")} onRetry={() => void imagesQ.refetch()} />
                    : <ImageStrip items={toothImages} empty={tx("noToothImages")} />}
                  {canUpload && <UploadControl defaultKind="iopa" busy={busy === "upload"} onPick={(f, k) => void upload(f, k, [selected])} />}
                  <h4 className={s.legend}>{tx("chairNotes")}</h4>
                  {notesQ.isError ? <ErrorState message={tx("notesNotLoaded")} onRetry={() => void notesQ.refetch()} />
                    : notesQ.data === undefined ? <Skeleton rows={1} label={tx("loadingNotes")} />
                    : toothNotes.length === 0 ? <p className={s.muted}>{tx("noNotes")}</p>
                    : <ul className={s.list}>{toothNotes.map((n) => <li key={n.noteId} className={s.listRow}><span>{n.text}</span><span className={s.muted}>{when(n.createdAt)}</span></li>)}</ul>}
                </section>
              )}
              <section className={s.card} aria-label={tx("caseGallery")}>
                <h3 className={s.cardTitle}>{tx("caseGallery")}</h3>
                {imagesQ.isError ? <ErrorState message={tx("imagesNotLoaded")} onRetry={() => void imagesQ.refetch()} />
                  : imagesQ.data === undefined ? <Skeleton rows={1} label={tx("loadingImages")} />
                  : <ImageStrip items={caseImages} empty={tx("noCaseImages")} />}
                {canUpload && <UploadControl defaultKind="opg" busy={busy === "upload"} onPick={(f, k) => void upload(f, k, [])} />}
              </section>
            </aside>
          </div>
          {chart.some((t) => t.isSupernumerary) && (
            <p className={s.muted}>{tx("supernumeraryTeethRecorded")}{" "}{chart.filter((t) => t.isSupernumerary).map((t) => label(t.toothNumber, true)).join(", ")}{tx("manageThemFromThePatient")}</p>
          )}
          {editor && settingsQ.data && (
            <DentalPresetEditor scope={editor} settings={settingsQ.data} onClose={() => setEditor(null)}
              onSaved={(next) => { qc.setQueryData(settingsKey, next); setEditor(null); }} />
          )}
          {(canEdit || canSetup) && !editor && (
            <div className={s.row}>
              {canEdit && <button type="button" className={s.btn} onClick={() => setEditor("mine")}>{tx("editMyPhrases")}</button>}
              {canSetup && <button type="button" className={s.btn} onClick={() => setEditor("clinic")}>{tx("editClinicPhrases")}</button>}
            </div>
          )}
          <Link href="/patients" className={s.linkBtn}>{tx("openFullPatientRecordHistory")}</Link>
          {canChair && (
            <div className={s.chairBar} role="group" aria-label={tx("chairSide")}>
              {settingsQ.isError ? <ErrorState message={tx("phrasesNotLoaded")} onRetry={() => void settingsQ.refetch()} />
                : selected === null ? <p className={s.muted}>{tx("selectAToothFirst")}</p>
                : laFor ? (
                  <div className={s.slots}>
                    <span className={s.muted}>{tx("pickLot", { label: laFor.label })}</span>
                    {lots === null ? <Skeleton rows={1} label={tx("loadingLots")} />
                      : lots === "error" ? <span className={s.muted}>{tx("lotsNotLoaded")}</span>
                      : lots.length === 0 ? <span className={s.muted}>{tx("noUsableLot")}</span>
                      : lots.map((l) => (
                        <button key={`${l.drugId}-${l.lot}`} type="button" className={`${s.chip} ${s.chipTouch}`} disabled={busy !== null} onClick={() => void saveNote(laFor, l)}>
                          {tx("lotChip", { drug: l.drugName, lot: l.lot, expiry: l.expiryDate ?? "-" })}
                        </button>
                      ))}
                    <button type="button" className={`${s.chip} ${s.chipTouch}`} disabled={busy !== null} onClick={() => void saveNote(laFor)}>{tx("saveWithoutLot")}</button>
                    <button type="button" className={`${s.btn} ${s.chipTouch}`} onClick={() => { setLaFor(null); setLots(null); }}>{tx("cancel")}</button>
                  </div>
                ) : (
                  <div className={s.slots}>
                    {(settingsQ.data?.presets ?? []).map((p) => (
                      <button key={p.code} type="button" className={`${s.chip} ${s.chipTouch}`} disabled={busy !== null} onClick={() => void tapPreset(p)}>{p.label}</button>
                    ))}
                  </div>
                )}
            </div>
          )}
        </div>
      )}
    </Dialog>
  );
}

function UploadControl({ defaultKind, busy, onPick }: { defaultKind: Kind; busy: boolean; onPick: (f: File, k: Kind) => void }) {
  const tx = useTranslations("consult.dentalDrawer");
  const [kind, setKind] = useState<Kind>(defaultKind);
  return (
    <div className={s.row}>
      <select className={s.input} aria-label={tx("imageKind")} value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
        {KINDS.map((k) => <option key={k} value={k}>{tx(`kind_${k}`)}</option>)}
      </select>
      <input type="file" accept="image/jpeg,image/png" aria-label={tx("attachImage")} disabled={busy}
        onChange={(e) => { const f = e.target.files?.[0]; if (f) onPick(f, kind); e.target.value = ""; }} />
    </div>
  );
}

function ImageStrip({ items, empty }: { items: Img[]; empty: string }) {
  if (items.length === 0) return <EmptyState title={empty} />;
  return <div className={s.imageStrip}>{items.map((i) => <Thumb key={i.imageId} img={i} />)}</div>;
}

/** Fetched through a signed URL (at most five minutes, PLT-029); the object URL is revoked on unmount. */
function Thumb({ img }: { img: Img }) {
  const tx = useTranslations("consult.dentalDrawer");
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let url: string | null = null;
    let cancelled = false;
    void (async () => {
      try {
        const { url: signed } = await api.post<{ url: string }>(`/files/${img.fileId}/download-url`);
        const blob = await api.get<Blob>(blobPath(signed), { as: "blob" });
        url = URL.createObjectURL(blob);
        if (cancelled) URL.revokeObjectURL(url); else setSrc(url);
      } catch { if (!cancelled) setFailed(true); }
    })();
    return () => { cancelled = true; if (url) URL.revokeObjectURL(url); };
  }, [img.fileId]);
  const title = `${tx(`kind_${img.kind}`)} · ${when(img.createdAt)}`;
  return (
    <>
      <button type="button" className={s.thumb} onClick={() => src && setOpen(true)} aria-label={title} title={title}>
        {/* eslint-disable-next-line @next/next/no-img-element -- object URL of a signed download */}
        {src ? <img src={src} alt={title} /> : <span className={s.muted}>{failed ? tx("imageNotLoaded") : tx(`kind_${img.kind}`)}</span>}
      </button>
      {open && src && (
        <Dialog title={title} onClose={() => setOpen(false)} size="lg">
          {/* eslint-disable-next-line @next/next/no-img-element -- object URL of a signed download */}
          <img src={src} alt={title} className={s.viewerImg} />
        </Dialog>
      )}
    </>
  );
}
