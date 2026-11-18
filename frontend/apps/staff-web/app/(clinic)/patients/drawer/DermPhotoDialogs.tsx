"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import Dialog from "../../../components/ui/Dialog";
import SignaturePad from "../../../components/ui/consent/SignaturePad";
import { enoughInk, strokesToSvg, type Point } from "../../../components/ui/consent/consentModel";
import { api, reasonText } from "../../../lib/api";
import { blobPath, isTerminal, type ScanStatus } from "../../../lib/attachments";
import { REGIONS, SIDES, compressWith, type ConsentScope, type DermPhoto, type Side } from "../../../lib/dermPhotos";
import s from "./dermPhotos.module.css";

// CLN-047: the photo timeline's dialogs (consent capture, camera capture, withdrawal, compare) and the signed-photo image.

const SCOPES: ConsentScope[] = ["record_only", "record_and_teaching"];

/** EXIF-oriented, re-encoded on a canvas (no EXIF/location leaves the device), <= 1.5 MB. */
async function compress(file: File): Promise<Blob> {
  const bmp = await createImageBitmap(file, { imageOrientation: "from-image" });
  try {
    return await compressWith(bmp.width, bmp.height, (w, h, q) => new Promise<Blob>((resolve, reject) => {
      const c = document.createElement("canvas");
      c.width = w; c.height = h;
      const g = c.getContext("2d");
      if (!g) { reject(new Error("canvas")); return; }
      g.drawImage(bmp, 0, 0, w, h);
      c.toBlob((b) => (b ? resolve(b) : reject(new Error("encode"))), "image/jpeg", q);
    }));
  } finally {
    bmp.close();
  }
}

/** A signed photo URL, fetched as a blob (the blob path is outside /v1) and released on unmount. */
export function PhotoImg({ photo, alt, className }: { photo: DermPhoto; alt: string; className: string }) {
  const t = useTranslations("shell.dermPhotos");
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let url: string | null = null;
    let live = true;
    api.get<Blob>(blobPath(photo.url), { as: "blob" })
      .then((b) => { url = URL.createObjectURL(b); if (live) setSrc(url); else URL.revokeObjectURL(url); })
      .catch(() => { if (live) setFailed(true); });
    return () => { live = false; if (url) URL.revokeObjectURL(url); };
  }, [photo.url]);
  if (failed) return <p className={s.muted} role="alert">{t("imageFailed")}</p>;
  if (!src) return <div className={`${className} ${s.placeholder}`} aria-busy="true" />;
  // eslint-disable-next-line @next/next/no-img-element -- a revocable blob URL, not a static asset
  return <img src={src} alt={alt} className={className} />;
}

export function ConsentDialog({ patientId, onClose, onDone }: { patientId: string; onClose: () => void; onDone: () => Promise<void> }) {
  const t = useTranslations("shell.dermPhotos");
  const [scope, setScope] = useState<ConsentScope>("record_only");
  const [signer, setSigner] = useState<"patient" | "guardian">("patient");
  const [strokes, setStrokes] = useState<Point[][]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ink = enoughInk(strokes);

  async function save() {
    setBusy(true); setError(null);
    try {
      await api.post(`/specialty/derm/patients/${patientId}/photo-consent`, { scope, signerKind: signer, signatureSvg: strokesToSvg(strokes) });
      await onDone();
    } catch (e) {
      setError(reasonText(e, t("consentFailed")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={t("consentTitle")} onClose={onClose} size="lg">
      <div className={s.form}>
        <p className={s.muted}>{t("consentIntro")}</p>
        <fieldset className={s.fieldset}>
          <legend>{t("scopeLabel")}</legend>
          {SCOPES.map((x) => (
            <label key={x} className={s.choice}><input type="radio" name="derm-scope" checked={scope === x} onChange={() => setScope(x)} />{t(`scope_${x}`)}</label>
          ))}
        </fieldset>
        <fieldset className={s.fieldset}>
          <legend>{t("signerLabel")}</legend>
          {(["patient", "guardian"] as const).map((x) => (
            <label key={x} className={s.choice}><input type="radio" name="derm-signer" checked={signer === x} onChange={() => setSigner(x)} />{t(`signer_${x}`)}</label>
          ))}
        </fieldset>
        <SignaturePad label={t("signaturePad")} strokes={strokes} onChange={setStrokes} />
        {strokes.length > 0 && !ink && <p className={s.muted} role="status">{t("fullSignature")}</p>}
        {error && <p className={s.error} role="alert">{error}</p>}
        <div className={s.row}>
          <button type="button" className={s.secondary} onClick={() => setStrokes([])}>{t("clear")}</button>
          <button type="button" className={s.primary} disabled={!ink || busy} onClick={() => void save()}>{busy ? t("savingConsent") : t("saveConsent")}</button>
        </div>
      </div>
    </Dialog>
  );
}

export function CaptureDialog({ patientId, visitId, onClose, onDone }: { patientId: string; visitId: string; onClose: () => void; onDone: () => Promise<void> }) {
  const t = useTranslations("shell.dermPhotos");
  const [region, setRegion] = useState<string>("cheek");
  const [side, setSide] = useState<Side>("left");
  const [note, setNote] = useState("");
  const [phase, setPhase] = useState<{ text: string; blocked?: boolean } | null>(null);
  const busy = phase !== null && !phase.blocked;

  async function take(file: File) {
    if (file.type !== "image/jpeg" && file.type !== "image/png") { setPhase({ text: t("imageOnly"), blocked: true }); return; }
    try {
      setPhase({ text: t("compressing") });
      const blob = await compress(file);
      setPhase({ text: t("uploading") });
      const slot = await api.post<{ uploadId: string; putUrl: string }>("/files/uploads", { purpose: "clinical_photo", contentType: "image/jpeg", sizeBytes: blob.size });
      await api.put(blobPath(slot.putUrl), undefined, { rawBody: blob, headers: { "Content-Type": "application/octet-stream" } });
      await api.post(`/files/uploads/${slot.uploadId}/complete`, {});
      setPhase({ text: t("scanning") });
      let status: ScanStatus = "pending_scan";
      for (let i = 0; i < 60 && !isTerminal(status); i++) {
        await new Promise((r) => setTimeout(r, 1000));
        status = await api.get<{ status: ScanStatus }>(`/files/${slot.uploadId}`).then((r) => r.status, () => "pending_scan" as ScanStatus);
      }
      if (status !== "available") { setPhase({ text: isTerminal(status) ? t("blocked") : t("stillScanning"), blocked: true }); return; }
      setPhase({ text: t("saving") });
      await api.post(`/specialty/derm/patients/${patientId}/photos`, { uploadId: slot.uploadId, queueEntryId: visitId, site: { region, side }, note: note.trim() || undefined });
      await onDone();
    } catch (e) {
      setPhase({ text: reasonText(e, t("uploadFailed")), blocked: true });
    }
  }

  return (
    <Dialog title={t("captureTitle")} onClose={onClose}>
      <div className={s.form}>
        <label className={s.field}>{t("region")}
          <select value={region} onChange={(e) => setRegion(e.target.value)} disabled={busy}>
            {REGIONS.map((r) => <option key={r} value={r}>{t(`region_${r}`)}</option>)}
          </select>
        </label>
        <label className={s.field}>{t("side")}
          <select value={side} onChange={(e) => setSide(e.target.value as Side)} disabled={busy}>
            {SIDES.map((x) => <option key={x} value={x}>{t(`side_${x}`)}</option>)}
          </select>
        </label>
        <label className={s.field}>{t("note")}
          <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} disabled={busy} />
        </label>
        <label className={`${s.primary} ${s.fileBtn}`}>
          {t("takePhoto")}
          {/* capture: the rear camera on a phone/tablet; a file picker on desktop */}
          <input type="file" accept="image/jpeg,image/png" capture="environment" className={s.fileInput} disabled={busy}
            onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void take(f); }} />
        </label>
        {phase && <p className={phase.blocked ? s.error : s.muted} role={phase.blocked ? "alert" : "status"}>{phase.text}</p>}
      </div>
    </Dialog>
  );
}

export function WithdrawDialog({ patientId, onClose, onDone }: { patientId: string; onClose: () => void; onDone: () => Promise<void> }) {
  const t = useTranslations("shell.dermPhotos");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function withdraw() {
    setBusy(true); setError(null);
    try {
      await api.post(`/specialty/derm/patients/${patientId}/photo-consent/withdraw`);
      await onDone();
    } catch (e) {
      setError(reasonText(e, t("withdrawFailed")));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog title={t("withdraw")} onClose={onClose}>
      <div className={s.form}>
        <p>{t("withdrawConfirm")}</p>
        {error && <p className={s.error} role="alert">{error}</p>}
        <div className={s.row}>
          <button type="button" className={s.secondary} onClick={onClose}>{t("cancel")}</button>
          <button type="button" className={s.danger} disabled={busy} onClick={() => void withdraw()}>{t("withdrawYes")}</button>
        </div>
      </div>
    </Dialog>
  );
}

export function CompareDialog({ pair, label, date, onClose }: { pair: [DermPhoto, DermPhoto]; label: string; date: (iso: string) => string; onClose: () => void }) {
  const t = useTranslations("shell.dermPhotos");
  const [mode, setMode] = useState<"side" | "overlay">("side");
  const [split, setSplit] = useState(50);
  const [before, after] = pair;
  const caption = (p: DermPhoto, which: string) => `${which}: ${date(p.takenAt)}${p.visitDate ? ` · ${t("visitOn", { date: date(p.visitDate) })}` : ""} · ${t(`badge_${p.consentBadge}`)}`;
  return (
    <Dialog title={`${t("compareTitle")}: ${label}`} onClose={onClose} size="lg">
      <div className={s.form}>
        <div className={s.row} role="group" aria-label={t("compareTitle")}>
          <button type="button" className={mode === "side" ? s.primary : s.secondary} aria-pressed={mode === "side"} onClick={() => setMode("side")}>{t("sideBySide")}</button>
          <button type="button" className={mode === "overlay" ? s.primary : s.secondary} aria-pressed={mode === "overlay"} onClick={() => setMode("overlay")}>{t("overlay")}</button>
        </div>
        {mode === "side" ? (
          <div className={s.sideBySide}>
            <figure className={s.figure}><PhotoImg photo={before} alt={caption(before, t("before"))} className={s.full} /><figcaption className={s.meta}>{caption(before, t("before"))}</figcaption></figure>
            <figure className={s.figure}><PhotoImg photo={after} alt={caption(after, t("after"))} className={s.full} /><figcaption className={s.meta}>{caption(after, t("after"))}</figcaption></figure>
          </div>
        ) : (
          <>
            <div className={s.overlay}>
              <PhotoImg photo={before} alt={caption(before, t("before"))} className={s.full} />
              <div className={s.overlayTop} style={{ clipPath: `inset(0 ${100 - split}% 0 0)` }}>
                <PhotoImg photo={after} alt={caption(after, t("after"))} className={s.full} />
              </div>
            </div>
            <input type="range" min={0} max={100} value={split} onChange={(e) => setSplit(Number(e.target.value))} aria-label={t("overlayLabel")} className={s.slider} dir="ltr" />
            <p className={s.meta}>{caption(before, t("before"))}</p>
            <p className={s.meta}>{caption(after, t("after"))}</p>
          </>
        )}
      </div>
    </Dialog>
  );
}
