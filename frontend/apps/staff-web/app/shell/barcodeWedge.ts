// FEF-057: keyboard-wedge barcode scanners "type" the code a few ms per key and end with Enter. Keys typed into a field stay the
// field's (a scan into a focused input lands there as usual); fast keys outside a field are buffered and, on Enter, routed to the
// active scan target: the most recently mounted screen or dialog that registered one. Pure (unit-tested); BarcodeWedge.tsx binds it.
import { isTyping } from "./shortcuts.ts";

/** Longest gap between two scanner keys. People type 100+ ms apart; calibrate on the counter's scanner if it is slower. */
export const WEDGE_MAX_GAP_MS = 50;
export const WEDGE_MIN_LENGTH = 4; // shorter bursts are not a barcode

export type WedgeKey = { key: string; timeStamp: number; ctrlKey?: boolean; metaKey?: boolean; altKey?: boolean; target: { tagName?: string; isContentEditable?: boolean } | null };
export type ScanTarget = (code: string) => void;

const targets: ScanTarget[] = [];
/** Registers a scan target; returns its unregister. The latest registered one is active. */
export function registerScanTarget(t: ScanTarget): () => void {
  targets.push(t);
  return () => { const i = targets.lastIndexOf(t); if (i >= 0) targets.splice(i, 1); };
}
export const activeScanTarget = (): ScanTarget | undefined => targets[targets.length - 1];

function toActive(code: string): boolean {
  const t = activeScanTarget();
  if (!t) return false;
  t(code);
  return true;
}

/** A keydown handler. Returns true when the key completed a scan that was routed: the caller then prevents the default (a focused
 * button must not turn the scanner's Enter into a click). */
export function createWedge(route: (code: string) => boolean = toActive): (e: WedgeKey) => boolean {
  let buf = "";
  let last = -Infinity;
  return (e) => {
    if (e.key === "Shift") return false; // scanners hold Shift for capitals
    if (e.ctrlKey || e.metaKey || e.altKey || isTyping(e.target)) { buf = ""; return false; }
    const fast = e.timeStamp - last <= WEDGE_MAX_GAP_MS;
    last = e.timeStamp;
    if (e.key === "Enter") {
      const code = fast ? buf : "";
      buf = "";
      return code.length >= WEDGE_MIN_LENGTH && route(code);
    }
    buf = e.key.length === 1 ? (fast ? buf : "") + e.key : "";
    return false;
  };
}
