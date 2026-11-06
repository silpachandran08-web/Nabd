"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import type { RxVerification } from "@nabd/api-client/generated";
import Dialog from "../ui/Dialog";
import { api, ApiError, errorText } from "../../lib/api";
import { usePermissions } from "../../lib/usePermissions";
import { getTenantId } from "../../lib/session";
import { cacheKeys, cachedKeys, headline, isOffline, tone, verifyOffline, type Jwks, type OfflineResult } from "./rxScan";
import styles from "./rxScan.module.css";

type Result = { kind: "server"; v: RxVerification } | { kind: "offline"; r: OfflineResult };

// Chrome/Android's shape detection API; Safari and Firefox have none, so there the pharmacist uses a keyboard-wedge
// scanner or pastes the QR text (every scanner app types it). shortcut: no decoder library (none is on the build path),
// add @zxing/browser when a non-Chrome camera scan is needed.
type Detector = { detect(src: HTMLVideoElement): Promise<{ rawValue: string }[]> };
type DetectorCtor = new (o: { formats: string[] }) => Detector;
const detector = (): Detector | null => {
  const w = window as unknown as { BarcodeDetector?: DetectorCtor };
  return w.BarcodeDetector ? new w.BarcodeDetector({ formats: ["qr_code"] }) : null;
};

const day = (iso: string) => new Date(iso).toLocaleDateString([], { dateStyle: "medium" });

/** PHL-013 (prototype overlay:scanrx): scan the Rx QR with the camera or a keyboard-wedge scanner, verify it, then open the
 * worklist entry or the patient's checkout to dispense. With no network the signature and expiry are checked in the browser
 * against the clinic key cached on the last online scan; the card then shows what the QR carries and nothing more. */
export default function RxScanOverlay({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const { can } = usePermissions();
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [camera, setCamera] = useState<"starting" | "on" | "off">(() => (detector() && navigator.mediaDevices ? "starting" : "off"));
  const video = useRef<HTMLVideoElement>(null);
  const submitRef = useRef<(raw: string) => void>(() => {});

  // Refresh the offline key cache whenever the overlay opens online (keys rotate).
  useEffect(() => {
    api.get<Jwks>("/pharmacy/rx-verifications/keys").then(cacheKeys).catch(() => {});
  }, []);

  useEffect(() => {
    const det = detector();
    if (!det || !navigator.mediaDevices) return;
    let stream: MediaStream | null = null;
    let timer: ReturnType<typeof setInterval> | undefined;
    let stopped = false;
    navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } }).then((s) => {
      if (stopped) { s.getTracks().forEach((t) => t.stop()); return; }
      stream = s;
      const el = video.current;
      if (!el) return;
      el.srcObject = s;
      void el.play();
      setCamera("on");
      timer = setInterval(async () => {
        if (el.readyState < 2) return;
        const hits = await det.detect(el).catch(() => []);
        const raw = hits[0]?.rawValue;
        if (raw) { clearInterval(timer); submitRef.current(raw); }
      }, 300);
    }).catch(() => setCamera("off"));
    return () => {
      stopped = true;
      clearInterval(timer);
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, []);

  async function submit(raw: string) {
    const qrPayload = raw.trim();
    if (!qrPayload || busy) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      if (navigator.onLine === false) throw new TypeError("offline");
      setResult({ kind: "server", v: await api.post<RxVerification>("/pharmacy/rx-verifications", { qrPayload }) });
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) setResult({ kind: "offline", r: { status: "invalid", reason: "unreadable" } });
      else if (isOffline(e)) setResult({ kind: "offline", r: await verifyOffline(qrPayload, cachedKeys(), getTenantId()) });
      else setError(errorText(e, "Couldn't verify this prescription."));
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => { submitRef.current = (raw) => void submit(raw); });

  function showOnWorklist(id: string) {
    onClose();
    document.querySelector(`[data-testid="wl-${id}"]`)?.scrollIntoView({ block: "center" });
  }

  const status = result?.kind === "server" ? result.v.status : result?.r.status;
  const canDispense = can("billing:view");

  return (
    <Dialog title="Scan prescription QR" onClose={onClose}>
      <div className={styles.scanner}>
        {camera !== "off" && <video ref={video} className={styles.video} muted playsInline aria-label="Camera preview" />}
        {camera === "starting" && <p className={styles.hint} role="status">Starting the camera…</p>}
        {camera === "off" && <p className={styles.hint}>No camera scan in this browser. Use a USB scanner or paste the QR text below.</p>}
      </div>
      <form className={styles.row} onSubmit={(e) => { e.preventDefault(); void submit(text); }}>
        <label className={styles.label} htmlFor="rx-scan-input">QR text (a USB scanner types it here)</label>
        <div className={styles.inputRow}>
          <input id="rx-scan-input" className={styles.input} value={text} onChange={(e) => setText(e.target.value)} autoFocus
                 autoComplete="off" spellCheck={false} placeholder="NRX1…" />
          <button type="submit" className={styles.primary} disabled={busy || !text.trim()}>{busy ? "Checking…" : "Verify"}</button>
        </div>
      </form>
      {busy && <p className={styles.hint} role="status">Checking the signature…</p>}
      {error && <div className={`${styles.card} ${styles.card_danger}`} role="alert">{error} <button type="button" className={styles.linkBtn} onClick={() => void submit(text)}>Retry</button></div>}

      {result && status && (
        <section className={`${styles.card} ${styles[`card_${tone(status)}`]}`} aria-live="polite" data-testid="rx-scan-result" data-status={status}>
          {result.kind === "offline" && (
            <p className={styles.offline}>Offline: checked against the clinic key saved in this browser. Showing what the QR carries only.</p>
          )}
          {result.kind === "server" ? (
            <>
              <h3 className={styles.headline}>{headline(result.v.status, result.v.reason, result.v.validUntil, result.v.dispensed?.at ?? null)}</h3>
              {result.v.status !== "invalid" && (
                <>
                  <dl className={styles.facts}>
                    <dt>Prescriber</dt><dd>{result.v.prescriber?.name ?? "—"}{result.v.prescriber?.regNo ? ` · Reg. no. ${result.v.prescriber.regNo}` : " · no registration number on file"}</dd>
                    <dt>Signed</dt><dd>{result.v.signedAt ? day(result.v.signedAt) : "—"}</dd>
                    <dt>Valid until</dt><dd>{result.v.validUntil ? day(result.v.validUntil) : "No expiry"}</dd>
                    {result.v.dispensed?.at && <><dt>Dispensed</dt><dd>{day(result.v.dispensed.at)} · {result.v.dispensed.where}</dd></>}
                  </dl>
                  {(result.v.remaining ?? []).length > 0 && (
                    <ul className={styles.lines} aria-label="Still to dispense">
                      {(result.v.remaining ?? []).map((l) => (
                        <li key={l.prescriptionItemId}>{l.drug}{l.qtyPrescribed != null ? ` × ${l.remaining} of ${l.qtyPrescribed}` : " (no quantity)"}</li>
                      ))}
                    </ul>
                  )}
                  <div className={styles.actions}>
                    {result.v.worklistEntryId && <button type="button" className={styles.secondary} onClick={() => showOnWorklist(result.v.worklistEntryId as string)}>Show on worklist</button>}
                    {canDispense && result.v.queueEntryId && (
                      <button type="button" className={styles.primary} disabled={result.v.status !== "valid"}
                              onClick={() => router.push(`/checkout/${result.v.queueEntryId}`)}>Dispense</button>
                    )}
                    {result.v.status !== "valid" && <span className={styles.hint}>{result.v.status === "expired" ? "Expired prescriptions cannot be dispensed." : "Nothing is left to dispense."}</span>}
                  </div>
                </>
              )}
            </>
          ) : (
            <>
              <h3 className={styles.headline}>{headline(result.r.status, result.r.status === "invalid" ? result.r.reason : null, result.r.status === "invalid" ? null : result.r.claims.validUntil)}</h3>
              {result.r.status !== "invalid" && (
                <dl className={styles.facts}>
                  <dt>Prescriber reg. no.</dt><dd>{result.r.claims.regNo ?? "not on the QR"}</dd>
                  <dt>Signed</dt><dd>{day(result.r.claims.signedAt)}</dd>
                  <dt>Valid until</dt><dd>{result.r.claims.validUntil ? day(result.r.claims.validUntil) : "No expiry"}</dd>
                </dl>
              )}
            </>
          )}
        </section>
      )}
    </Dialog>
  );
}
