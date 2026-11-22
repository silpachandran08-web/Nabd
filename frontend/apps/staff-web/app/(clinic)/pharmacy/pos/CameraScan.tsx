"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import Dialog from "../../../components/ui/Dialog";
import styles from "./pos.module.css";

// PHL-019: camera barcode scan for counters without a USB scanner. Chrome/Android's shape detection API only (as the Rx QR
// overlay, RxScanOverlay): where it is missing the button is absent and the pharmacist types or uses a keyboard-wedge scanner.
// shortcut: no decoder library (none is on the build path); add one when a non-Chrome camera scan is needed.
type Detector = { detect(src: HTMLVideoElement): Promise<{ rawValue: string }[]> };
type DetectorCtor = new (o: { formats: string[] }) => Detector;
const FORMATS = ["ean_13", "ean_8", "upc_a", "upc_e", "itf", "code_128"];
const ctor = (): DetectorCtor | undefined => (typeof window === "undefined" ? undefined : (window as unknown as { BarcodeDetector?: DetectorCtor }).BarcodeDetector);

export const cameraScanSupported = (): boolean => !!ctor() && typeof navigator !== "undefined" && !!navigator.mediaDevices;

const REPEAT_MS = 1500; // the same pack stays in view after a read: it counts again only after this pause

/** Stays open so several packs can be scanned one after the other; every read goes to `onScan`. */
export default function CameraScan({ onScan, onClose, status }: { onScan: (code: string) => void; onClose: () => void; status: string | null }) {
  const t = useTranslations("checkout.pos");
  const video = useRef<HTMLVideoElement>(null);
  const latest = useRef(onScan);
  const [state, setState] = useState<"starting" | "on" | "blocked">("starting");
  useEffect(() => { latest.current = onScan; });

  useEffect(() => {
    const Ctor = ctor();
    if (!Ctor || !navigator.mediaDevices) return;
    const det = new Ctor({ formats: FORMATS });
    let stream: MediaStream | null = null;
    let timer: ReturnType<typeof setInterval> | undefined;
    let stopped = false;
    let last = { code: "", at: 0 };
    navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } }).then((s) => {
      if (stopped) { s.getTracks().forEach((x) => x.stop()); return; }
      stream = s;
      const el = video.current;
      if (!el) return;
      el.srcObject = s;
      void el.play();
      setState("on");
      timer = setInterval(async () => {
        if (el.readyState < 2) return;
        const code = (await det.detect(el).catch(() => []))[0]?.rawValue;
        if (!code || (code === last.code && Date.now() - last.at < REPEAT_MS)) return;
        last = { code, at: Date.now() };
        latest.current(code);
      }, 250);
    }).catch(() => setState("blocked"));
    return () => {
      stopped = true;
      clearInterval(timer);
      stream?.getTracks().forEach((x) => x.stop());
    };
  }, []);

  return (
    <Dialog title={t("cameraTitle")} onClose={onClose}>
      {state !== "blocked" && <video ref={video} className={styles.video} muted playsInline aria-label={t("cameraPreview")} />}
      {state === "starting" && <p className={styles.hint} role="status">{t("cameraStarting")}</p>}
      {state === "blocked" && <p className={styles.hint} role="alert">{t("cameraBlocked")}</p>}
      <p className={styles.hint} role="status" aria-live="polite">{status ?? t("cameraHint")}</p>
    </Dialog>
  );
}
