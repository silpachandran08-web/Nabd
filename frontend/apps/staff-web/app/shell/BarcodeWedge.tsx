"use client";
// FEF-057: the pharmacist shell's keyboard-wedge listener (logic in barcodeWedge.ts) and the hook a screen uses to receive scans.
import { useEffect, useRef } from "react";
import { createWedge, registerScanTarget } from "./barcodeWedge";

export default function BarcodeWedge() {
  useEffect(() => {
    const handle = createWedge();
    const onKey = (e: KeyboardEvent) => {
      const k = { key: e.key, timeStamp: e.timeStamp, ctrlKey: e.ctrlKey, metaKey: e.metaKey, altKey: e.altKey, target: e.target as HTMLElement | null };
      if (handle(k)) e.preventDefault();
    };
    document.addEventListener("keydown", onKey, true); // capture: before a focused control acts on the scanner's Enter
    return () => document.removeEventListener("keydown", onKey, true);
  }, []);
  return null;
}

/** The calling screen (or open dialog) receives scans made outside an input while it is mounted; the latest mounted one wins. */
export function useScanTarget(onScan: (code: string) => void) {
  const latest = useRef(onScan);
  useEffect(() => { latest.current = onScan; });
  useEffect(() => registerScanTarget((code) => latest.current(code)), []);
}
